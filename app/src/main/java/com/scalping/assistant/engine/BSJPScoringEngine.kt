package com.scalping.assistant.engine

import com.scalping.assistant.data.models.BandarDetectorStat
import com.scalping.assistant.data.models.OrderBookSnapshot
import com.scalping.assistant.data.models.Recommendation
import com.scalping.assistant.data.models.StockAnalysis
import com.scalping.assistant.data.models.TechnicalResult
import com.scalping.assistant.data.repository.DailyTechnicalSummary
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 🌙 BSJPScoringEngine — Beli Sore Jual Pagi (Overnight Gap Play)
 *
 * Engine scoring terpisah khusus BSJP. Logikanya berbeda fundamental dari scalping:
 * - Scalping: intraday momentum, entry-exit dalam jam yang sama
 * - BSJP: overnight gap play, cari saham yang akan gap up esok pagi
 *
 * Kriteria profesional berdasarkan riset dari Stockbit, Ajaib, MNC Sekuritas:
 * 1. Closing Strong (candle kuat, buyer dominan hingga akhir)
 * 2. Volume Breakout (minimal 2x rata-rata, ada smart money masuk)
 * 3. Kenaikan Harga Signifikan (+3% minimum hari ini)
 * 4. Di atas VWAP (buyer mendominasi sepanjang hari)
 * 5. Akumulasi Bandar/Asing (net buy, bukan distribusi)
 * 6. MA Alignment Bullish (MA5 > MA9 > MA20)
 * 7. Anti-Trap Filter (penalti berat jika ada tanda distribusi/marking the close)
 */
object BSJPScoringEngine {

    data class BSJPCandidate(
        val ticker: String,
        val score: Int,                  // 0-95
        val recommendation: BSJPRecommendation,
        val lastPrice: Int,
        val changePercent: Double,
        val closingStrength: Double,     // 0.0 - 1.0 (0 = close di low, 1 = close di high)
        val volumeRatio: Double,         // Volume hari ini / avg 5 hari
        val isAboveVwap: Boolean,
        val bandarStatus: String,        // "Big Acc", "Acc", "Neutral", "Dist", "Big Dist"
        val maAlignmentLabel: String,
        val entryPrice: Int,             // Harga entry saat closing
        val targetPrice: Int,            // Target jual pagi esok hari
        val stopLoss: Int,               // Cut loss jika gap turun
        val estimatedProfitPercent: Double,
        val reasons: List<String>,
        val warnings: List<String>
    )

    enum class BSJPRecommendation(val label: String, val colorCode: String) {
        STRONG_BUY("🌙 STRONG BUY BSJP", "#8B5CF6"),   // Ungu terang — malam/overnight
        BUY("🌙 BUY BSJP", "#A78BFA"),                  // Ungu medium
        WATCH("⏳ WATCH BSJP", "#F59E0B")               // Kuning — hati-hati
    }

    /**
     * Evaluasi apakah suatu saham layak menjadi kandidat BSJP.
     * Returns null jika saham tidak memenuhi kriteria minimum (didiskualifikasi).
     */
    fun evaluate(
        ticker: String,
        snapshot: OrderBookSnapshot,
        technical: TechnicalResult,
        bandarDetector: BandarDetectorStat?,
        dailyData: DailyTechnicalSummary?,
        todayHigh: Double = 0.0,         // High hari ini dari intraday candles
        todayLow: Double = 0.0,          // Low hari ini dari intraday candles
        prevClosePrice: Double = 0.0     // Harga penutupan kemarin (referensi)
    ): BSJPCandidate? {

        val currentPrice = snapshot.lastPrice.takeIf { it > 0 }?.toDouble() ?: return null
        val reasons = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var totalScore = 0
        var isDisqualified = false

        // ============================================================
        // ANTI-TRAP: Cek Distribusi Bandar terlebih dahulu
        // ============================================================
        val bandarStatus = bandarDetector?.accdistStatus ?: "Neutral"
        val isBandarDistributing = bandarStatus == "Big Dist" || bandarStatus == "Dist"
        if (isBandarDistributing) {
            warnings.add("⛔ Bandar sedang distribusi ($bandarStatus) — BERBAHAYA untuk overnight")
            isDisqualified = true
        }

        // ============================================================
        // KRITERIA 1: Closing Strong (0-25 poin)
        // closingStrength = (close - low) / (high - low)
        // Menandakan apakah buyer masih dominan di akhir sesi
        // ============================================================
        val effectiveHigh = maxOf(
            todayHigh,
            technical.todayHigh,
            currentPrice
        )
        val effectiveLow = if (todayLow > 0) minOf(todayLow, technical.nearestSupport * 0.98)
                           else technical.nearestSupport * 0.98
        val safeHigh = maxOf(effectiveHigh, currentPrice)
        val safeLow = minOf(effectiveLow, currentPrice)

        val range = safeHigh - safeLow
        val closingStrength = if (range > 0) {
            ((currentPrice - safeLow) / range).coerceIn(0.0, 1.0)
        } else {
            0.5 // Flat day, netral
        }

        val closingScore = when {
            closingStrength >= 0.90 -> 25 // Closing di puncak, buyer super dominan
            closingStrength >= 0.80 -> 20
            closingStrength >= 0.70 -> 15
            else -> {
                warnings.add("❌ Closing lemah (${(closingStrength * 100).toInt()}%) — buyer tidak dominan di akhir sesi")
                isDisqualified = true
                0
            }
        }
        totalScore += closingScore
        if (closingScore >= 20) reasons.add("💪 Closing kuat (${(closingStrength * 100).toInt()}%) — buyer dominan")
        else if (closingScore > 0) reasons.add("📈 Closing cukup kuat (${(closingStrength * 100).toInt()}%)")

        // ============================================================
        // KRITERIA 2: Volume Breakout (0-20 poin)
        // Volume hari ini minimal 2x rata-rata 5 hari = smart money masuk
        // ============================================================
        val avgVolume5d = dailyData?.volumeAvg5d ?: 0L
        val todayVolume = dailyData?.lastVolume ?: 0L
        val volumeRatio = if (avgVolume5d > 0) todayVolume.toDouble() / avgVolume5d else 1.0

        val volumeScore = when {
            volumeRatio >= 3.0 -> 20 // Super Volume — institusi/asing masuk masif
            volumeRatio >= 2.0 -> 15
            volumeRatio >= 1.5 -> 10
            else -> {
                warnings.add("⚠️ Volume biasa (${String.format("%.1f", volumeRatio)}x avg) — tidak ada konfirmasi smart money")
                isDisqualified = true
                0
            }
        }
        totalScore += volumeScore
        if (volumeScore >= 15) reasons.add("🔥 Volume breakout ${String.format("%.1f", volumeRatio)}x — smart money konfirmasi")
        else if (volumeScore > 0) reasons.add("📊 Volume di atas rata-rata ${String.format("%.1f", volumeRatio)}x")

        // ============================================================
        // KRITERIA 3: Kenaikan Harga Hari Ini (0-15 poin)
        // Minimal +3% dari kemarin = momentum nyata
        // ============================================================
        val changePercent = if (snapshot.changePercent != 0.0) {
            snapshot.changePercent
        } else if (prevClosePrice > 0) {
            ((currentPrice - prevClosePrice) / prevClosePrice) * 100
        } else {
            technical.todayHigh / currentPrice * 2 - 2.0 // Estimasi kasar
        }

        val priceScore = when {
            changePercent >= 7.0 -> 15  // Breakout kuat
            changePercent >= 5.0 -> 12
            changePercent >= 3.0 -> 8
            else -> {
                if (changePercent < 0) {
                    warnings.add("❌ Harga turun hari ini (${String.format("%.1f", changePercent)}%) — bukan kandidat BSJP")
                    isDisqualified = true
                } else {
                    warnings.add("⚠️ Kenaikan kurang signifikan (${String.format("%.1f", changePercent)}%) — <3%")
                }
                0
            }
        }
        totalScore += priceScore
        if (priceScore > 0) reasons.add("📈 Naik +${String.format("%.1f", changePercent)}% hari ini")

        // ============================================================
        // KRITERIA 4: Posisi vs VWAP (0-10 poin)
        // Harga closing > VWAP = buyer dominan SEPANJANG HARI (bukan cuma akhir)
        // ============================================================
        val vwap = technical.vwap
        val isAboveVwap = vwap > 0 && currentPrice > vwap

        val vwapScore = when {
            vwap <= 0 -> 5 // Tidak ada data VWAP, berikan skor netral
            isAboveVwap -> 10
            else -> {
                warnings.add("⚠️ Harga di bawah VWAP — buyer tidak dominan sepanjang hari")
                0
            }
        }
        totalScore += vwapScore
        if (isAboveVwap) reasons.add("✅ Di atas VWAP — buyer mendominasi sepanjang sesi")

        // ============================================================
        // KRITERIA 5: Akumulasi Bandar/Asing (0-15 poin)
        // ============================================================
        val bandarScore = when (bandarStatus) {
            "Big Acc" -> {
                reasons.add("🟢 Big Accumulation bandar/asing — strong overnight signal")
                15
            }
            "Acc" -> {
                reasons.add("🟢 Akumulasi bandar/asing terdeteksi")
                10
            }
            "Neutral" -> {
                reasons.add("⚪ Aliran bandar netral")
                3
            }
            "Big Dist" -> {
                // Sudah di-disqualify di atas, tapi tambahkan penalty
                -30
            }
            "Dist" -> {
                -15
            }
            else -> 3
        }
        totalScore += bandarScore

        // ============================================================
        // KRITERIA 6: MA Alignment Bullish (0-10 poin)
        // ============================================================
        val ma5 = technical.ma5
        val ma9 = technical.ma9
        val ma20 = technical.ma20
        val maScore: Int
        val maAlignmentLabel: String

        when {
            currentPrice > ma5 && ma5 > ma9 && ma9 > ma20 -> {
                maScore = 10
                maAlignmentLabel = "🟢 Perfect Bullish Stack"
                reasons.add("📊 MA alignment sempurna (Harga>MA5>MA9>MA20)")
            }
            ma5 > ma9 && ma9 > ma20 -> {
                maScore = 7
                maAlignmentLabel = "🟢 Bullish Trend"
                reasons.add("📊 MA trend bullish (MA5>MA9>MA20)")
            }
            ma5 > ma20 -> {
                maScore = 4
                maAlignmentLabel = "🟡 Golden Cross"
                reasons.add("📊 MA5 di atas MA20")
            }
            else -> {
                maScore = 0
                maAlignmentLabel = "🔴 Bearish MA"
                warnings.add("⚠️ MA alignment bearish — tren jangka pendek turun")
            }
        }
        totalScore += maScore

        // ============================================================
        // ANTI-TRAP PENALTIES tambahan
        // ============================================================

        // Saham ARA — tidak ada ruang naik lagi, justru risiko gap turun
        if (changePercent >= 25.0) {
            warnings.add("⚠️ Saham sudah ARA (${String.format("%.1f", changePercent)}%) — risiko gap turun besok tinggi")
            totalScore -= 20
        }

        // Saham harga > 2000 — di luar preferensi user
        if (currentPrice > 2000) {
            warnings.add("ℹ️ Harga > Rp 2.000 — di luar preferensi scalping")
            totalScore -= 5
        }

        // Pastikan skor tidak negatif
        totalScore = maxOf(totalScore, 0)

        // ============================================================
        // FINAL DECISION
        // ============================================================
        if (isDisqualified) return null

        // Minimum skor 45 untuk WATCH, 60 untuk BUY, 75 untuk STRONG BUY
        if (totalScore < 45) return null

        val recommendation = when {
            totalScore >= 75 -> BSJPRecommendation.STRONG_BUY
            totalScore >= 60 -> BSJPRecommendation.BUY
            else -> BSJPRecommendation.WATCH
        }

        // ============================================================
        // KALKULASI ENTRY, TARGET, STOP LOSS
        // ============================================================
        val entryPrice = snapshot.offerLevels.firstOrNull()?.price ?: snapshot.lastPrice
        val targetMultiplier = when {
            closingStrength >= 0.90 && volumeRatio >= 3.0 -> 0.05  // +5%: signal super kuat
            closingStrength >= 0.80 && volumeRatio >= 2.0 -> 0.04  // +4%: signal kuat
            else -> 0.025                                            // +2.5%: signal cukup
        }
        val targetPrice = PriceFraction.roundToValidTick((entryPrice * (1 + targetMultiplier)).toInt())
        val stopLoss = PriceFraction.roundDownToValidTick((entryPrice * 0.98).toInt()) // -2% SL ketat
        val estimatedProfit = ((targetPrice - entryPrice).toDouble() / entryPrice) * 100

        return BSJPCandidate(
            ticker = ticker,
            score = totalScore,
            recommendation = recommendation,
            lastPrice = snapshot.lastPrice,
            changePercent = changePercent,
            closingStrength = closingStrength,
            volumeRatio = volumeRatio,
            isAboveVwap = isAboveVwap,
            bandarStatus = bandarStatus,
            maAlignmentLabel = maAlignmentLabel,
            entryPrice = entryPrice,
            targetPrice = targetPrice,
            stopLoss = stopLoss,
            estimatedProfitPercent = estimatedProfit,
            reasons = reasons,
            warnings = warnings
        )
    }

    /**
     * Konversi BSJPCandidate ke StockAnalysis agar bisa langsung dirender
     * oleh UI yang sudah ada (RankingAdapter).
     * Style field berisi "BSJP" untuk deteksi di adapter.
     */
    fun toStockAnalysis(candidate: BSJPCandidate, existingAnalysis: StockAnalysis): StockAnalysis {
        return existingAnalysis.copy(
            score = candidate.score,
            recommendation = when (candidate.recommendation) {
                BSJPRecommendation.STRONG_BUY -> Recommendation.STRONG_BUY
                BSJPRecommendation.BUY -> Recommendation.BUY
                BSJPRecommendation.WATCH -> Recommendation.WATCH
            },
            lastPrice = candidate.lastPrice,
            changePercent = candidate.changePercent,
            entryPrice = candidate.entryPrice,
            targetPrice = candidate.targetPrice,
            stopLoss = candidate.stopLoss,
            estimatedProfitPercent = candidate.estimatedProfitPercent,
            riskRewardRatio = if (candidate.stopLoss > 0 && candidate.entryPrice > candidate.stopLoss) {
                val reward = (candidate.targetPrice - candidate.entryPrice).toDouble()
                val risk = (candidate.entryPrice - candidate.stopLoss).toDouble()
                if (risk > 0) (reward / risk).let { kotlin.math.round(it * 10) / 10.0 } else 1.0
            } else 1.0,
            style = "BSJP_${candidate.recommendation.name}",
            reasons = listOf("🌙 BSJP: ${candidate.recommendation.label}") + candidate.reasons,
            warnings = candidate.warnings,
            lastUpdated = System.currentTimeMillis()
        )
    }
}
