package com.scalping.assistant.engine

import com.scalping.assistant.data.models.OrderBookSnapshot
import com.scalping.assistant.data.models.OrderFlowResult
import com.scalping.assistant.data.models.Recommendation
import com.scalping.assistant.data.models.StockAnalysis
import com.scalping.assistant.data.models.TechnicalResult
import kotlin.math.max
import kotlin.math.roundToInt

object ScoringEngine {

    fun generateAnalysis(
        snapshot: OrderBookSnapshot,
        orderFlow: OrderFlowResult,
        technical: TechnicalResult,
        sessionInfo: SessionInfo,
        prevRec: Recommendation? = null,
        snapshotCount: Int = 0,
        tapeReading: com.scalping.assistant.data.models.TapeReadingStat? = null
    ): StockAnalysis {
        val ticker = snapshot.ticker
        val currentPrice = if (snapshot.lastPrice > 0) snapshot.lastPrice else (snapshot.offerLevels.firstOrNull()?.price ?: 100)

        // ============================================================
        // 1. DYNAMIC ENTRY PRICE — Strategi Pullback diprioritaskan
        //    Hajar Kanan HANYA jika breakout terkonfirmasi KUAT
        //    (tidak cukup hanya delta score ≥18, harus ada breakout nyata)
        // ============================================================
        val bestOffer = snapshot.offerLevels.firstOrNull()?.price ?: currentPrice
        val bestBid = snapshot.bidLevels.firstOrNull()?.price ?: currentPrice

        // Hajar kanan HANYA jika breakout sinyal NYATA (offer tebal sedang dibobol)
        // bukan hanya dari delta score yang masih bisa noise
        val isConfirmedBreakout = orderFlow.hasBreakoutSignal && orderFlow.deltaVolumeScore >= 22

        var rawEntry: Double
        var entryNote = ""

        if (isConfirmedBreakout) {
            // Breakout dikonfirmasi → Entry di offer (hajar kanan)
            rawEntry = bestOffer.toDouble()
            entryNote = "Buy on Breakout (Hajar Offer)"
        } else {
            // === Strategi Pullback: Antre di bawah harga saat ini ===
            // Tujuan: hindari beli di puncak sesaat, tunggu sedikit turun ke area bid/support

            val pullbackTarget = currentPrice * 0.992 // Antre ~0.8% di bawah harga last

            rawEntry = when {
                // Jika ada support kuat di dekat pullback zone, antre di sana
                technical.nearestSupport in (currentPrice * 0.97)..pullbackTarget -> {
                    entryNote = "Buy on Support"
                    technical.nearestSupport
                }
                // Jika best bid lebih baik dari harga saat ini (banyak antrean beli)
                bestBid < currentPrice && bestBid >= currentPrice * 0.99 -> {
                    entryNote = "Buy on Queue (Best Bid)"
                    bestBid.toDouble()
                }
                // Default: antre sedikit di bawah harga terkini (tidak hajar langsung)
                else -> {
                    entryNote = "Momentum (Beli Bertahap)"
                    // Ambil posisi di antara bid dan offer, condong ke bid
                    bestBid.toDouble()
                }
            }
        }

        val entryPrice = PriceFraction.roundToValidTick(rawEntry.roundToInt())

        // ============================================================
        // 2. TARGET PRICE — Minimum 2% RR adalah PRIORITAS UTAMA
        //    Tembok offer hanya relevan jika masih di atas target minimum
        // ============================================================
        // Minimum profit target: 2.0% dari entry (tidak bisa kurang dari ini!)
        val minProfitTarget = entryPrice * 1.020
        // Target normal jika tidak ada hambatan: 2.8%
        val normalProfitTarget = entryPrice * 1.028

        // Cari tembok offer tebal di atas entry
        // Threshold diperbaiki: tembok = lot > 1.5x rata-rata HANYA JIKA dia adalah lot terbesar di layar,
        // ATAU lot > 2.5x rata-rata (tembok dominan)
        val avgOfferLot = if (snapshot.offerLevels.isNotEmpty()) snapshot.offerLevels.map { it.lot }.average() else 0.0
        val maxOfferLot = if (snapshot.offerLevels.isNotEmpty()) snapshot.offerLevels.maxOf { it.lot } else 0L

        val thickWallLevel = snapshot.offerLevels
            .filter {
                it.price > entryPrice &&
                ((it.lot > avgOfferLot * 1.5 && it.lot == maxOfferLot) || it.lot > avgOfferLot * 2.5) &&
                (it.lot.toLong() * 100 * it.price) > 300_000_000L
            }
            .minByOrNull { it.price }

        var rawTarget: Double

        if (thickWallLevel != null) {
            val tick = PriceFraction.getTickSize(thickWallLevel.price)
            val frontRunPrice = (thickWallLevel.price - tick).toDouble()

            // Tembok boleh jadi acuan HANYA JIKA lokasinya di atas target minimum 2%
            // Jika tembok ada di bawah 2%, ABAIKAN tembok dan pakai target minimum
            rawTarget = if (frontRunPrice >= minProfitTarget) {
                // Target 1 tick di bawah tembok (front-running tembok)
                frontRunPrice
            } else {
                // Tembok terlalu dekat / di bawah 2% → pakai target normal 2.8%
                // Lebih baik pasang target di atas tembok dan berharap tembok tertembus
                normalProfitTarget
            }
        } else {
            // User request: Target jual dihitung/dipertimbangkan berdasarkan bid offer saja
            // Jika tidak ada antrean offer tebal (bid offer), kita gunakan default
            rawTarget = normalProfitTarget
        }

        // Batas atas: maksimal 4.0% dari entry untuk day trade (jangan pasang target terlalu jauh)
        if (rawTarget > entryPrice * 1.040) {
            rawTarget = entryPrice * 1.028
        }

        // Pastikan target MINIMUM 2.0% dari entry (TIDAK BOLEH KURANG)
        if (rawTarget < minProfitTarget) {
            rawTarget = normalProfitTarget
        }

        val targetPrice = PriceFraction.roundUpToValidTick(rawTarget.roundToInt())

        // ============================================================
        // 3. STOP LOSS — Prioritas: Support dari Analisis Teknikal
        // ============================================================
        // User request: "untuk support dan resistance itu dihitung berdasarkan analisis teknikal nya aja"
        val maxStopLoss = entryPrice * 0.985

        var rawSL = if (technical.nearestSupport in (entryPrice * 0.96)..maxStopLoss) {
            technical.nearestSupport
        } else if (technical.bbLower in (entryPrice * 0.96)..maxStopLoss) {
            technical.bbLower
        } else {
            entryPrice * 0.985
        }

        if (rawSL < entryPrice * 0.96) {
            rawSL = entryPrice * 0.985
        }
        val stopLoss = PriceFraction.roundDownToValidTick(rawSL.roundToInt())

        // ============================================================
        // 4. Risk / Reward Calculation
        // ============================================================
        val risk = max(1, entryPrice - stopLoss)
        val reward = max(1, targetPrice - entryPrice)
        val rrRatio = String.format("%.2f", reward.toDouble() / risk).replace(',', '.').toDoubleOrNull() ?: 1.5
        val profitPercent = String.format("%.2f", (reward.toDouble() / entryPrice) * 100).replace(',', '.').toDoubleOrNull() ?: 2.5

        // ============================================================
        // 5. SCORING
        // ============================================================

        // A. Order Flow Score (0 - 50)
        val ofScore = orderFlow.totalScore

        // B. Technical Score (0 - 30) dengan modifikasi
        val isNewIpo = (technical.totalScore == 0)
        var effectiveTechScore = if (isNewIpo) 15 else technical.totalScore

        // VWAP Modifier
        if (technical.vwap > 0) {
            if (snapshot.lastPrice < technical.vwap * 0.99) {
                effectiveTechScore -= 5 // Harga di bawah VWAP (Bearish intraday)
            } else if (snapshot.lastPrice > technical.vwap) {
                effectiveTechScore += 2 // Di atas VWAP (Bullish intraday)
            }
        }

        // MFI Divergence (Harga naik tajam tapi MFI rendah = Fake Pump)
        if (technical.mfi > 0) {
            if (snapshot.changePercent > 5.0 && technical.mfi < 40) {
                effectiveTechScore -= 15 // Divergence bahaya!
            }
        }

        // C. Market Context Score (0 - 20)
        val sessionBonus = sessionInfo.phase.scoreBonus

        val spreadTicks = if (bestOffer > bestBid) (bestOffer - bestBid) / PriceFraction.getTickSize(bestOffer) else 1
        val spreadScore = when (spreadTicks) {
            1 -> 6
            2 -> 4
            3 -> 2
            else -> 0
        }

        val totalLot = snapshot.totalBidLot + snapshot.totalOfferLot
        val volScore = when {
            totalLot > 50000 -> 6
            totalLot > 15000 -> 4
            totalLot > 5000 -> 2
            else -> -10
        }

        val marketScore = (sessionBonus + spreadScore + volScore).coerceIn(0, 20)

        // D. Penalty Calculations
        val rrPenalty = if (rrRatio < 1.2) -8 else 0

        var overboughtPenalty = when {
            snapshot.changePercent >= 20.0 -> -15
            snapshot.changePercent >= 14.0 -> -8
            else -> 0
        }

        if (overboughtPenalty < 0 && isConfirmedBreakout && technical.isSupertrendBullish) {
            overboughtPenalty = 0
        } else if (overboughtPenalty < 0 && orderFlow.deltaVolumeScore >= 22) {
            overboughtPenalty /= 2
        }

        // E. Tape Reading Score (0 - 15)
        var tapeReadingScore = 0
        var isHakiMasif = false
        var isHakaMasif = false
        if (tapeReading != null) {
            val haka = tapeReading.totalHakaLot
            val haki = tapeReading.totalHakiLot
            if (haka > 0 || haki > 0) {
                if (haka > haki * 2) {
                    tapeReadingScore = 15 // HAKA dominan
                    isHakaMasif = true
                } else if (haka > haki * 1.3) {
                    tapeReadingScore = 8
                } else if (haki > haka * 2) {
                    tapeReadingScore = -20 // HAKI dominan masif
                    isHakiMasif = true
                } else if (haki > haka * 1.3) {
                    tapeReadingScore = -10
                } else {
                    tapeReadingScore = 0
                }
            }
        }

        // F. Confidence penalty jika snapshot masih sedikit
        // Dengan snapshot <5, belum cukup data untuk sinyal kuat → turunkan skor
        val confidencePenalty = when {
            snapshotCount < 3  -> -20  // Data masih sangat sedikit
            snapshotCount < 5  -> -12  // Data belum cukup
            snapshotCount < 8  -> -5   // Data sedang terkumpul
            else -> 0                   // Data sudah cukup
        }

        val finalScore = (ofScore + effectiveTechScore + marketScore + tapeReadingScore + rrPenalty + overboughtPenalty + confidencePenalty).coerceIn(0, 100)

        // ============================================================
        // 6. REKOMENDASI — Hysteresis yang diperkuat
        //    hasFakeWall SAJA tidak cukup untuk flip ke AVOID
        //    Butuh score BENAR-BENAR anjlok + konfirmasi teknikal
        // ============================================================

        // hasFakeWall hanya flip rekomendasi jika ada JUGA bukti teknikal bearish ATAU ada HAKI masif di Tape Reading
        val isTrulyBearish = (orderFlow.hasFakeWall && (!technical.isSupertrendBullish || effectiveTechScore < 10)) || isHakiMasif
        val isAra = snapshot.araPrice > 0 && currentPrice >= snapshot.araPrice
        val isTooExpensive = currentPrice >= 2000

        var recommendation = when {
            // FILTER: Saham ARA atau harga >= 2000 langsung AVOID
            isAra || isTooExpensive -> Recommendation.AVOID

            // STRONG BUY: Butuh score sangat tinggi + RR baik + tidak ada fake wall terkonfirmasi (ATAU HAKA Masif)
            finalScore >= 82 && rrRatio >= 1.5 && !isTrulyBearish && snapshotCount >= 5 -> Recommendation.STRONG_BUY
            // Jika ada HAKA masif dari Tape Reading, kita bisa overrule syarat snapshot! (Flash Signal Copet)
            isHakaMasif && finalScore >= 75 && rrRatio >= 1.3 && !isTrulyBearish -> Recommendation.STRONG_BUY
            // Hysteresis STRONG BUY: Sangat lengket, pertahankan sampai score benar-benar turun ke 65
            prevRec == Recommendation.STRONG_BUY && finalScore >= 65 && !isTrulyBearish -> Recommendation.STRONG_BUY

            // BUY
            finalScore >= 68 && rrRatio >= 1.3 && !isTrulyBearish && snapshotCount >= 5 -> Recommendation.BUY
            // Hysteresis BUY: Sangat lengket, bertahan sampai score jatuh di bawah 45
            (prevRec == Recommendation.BUY || prevRec == Recommendation.STRONG_BUY) && finalScore >= 45 && !isTrulyBearish -> Recommendation.BUY

            // WATCH
            finalScore >= 52 -> Recommendation.WATCH
            // Hysteresis WATCH: Bertahan sampai 35 sebelum ke AVOID
            (prevRec != Recommendation.AVOID) && finalScore >= 35 -> Recommendation.WATCH

            else -> Recommendation.AVOID
        }

        // ============================================================
        // 7. ALASAN & PERINGATAN
        // ============================================================
        val reasons = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // Tambahkan confidence info di awal
        val confidenceLabel = when {
            snapshotCount < 3 -> "⏳ Data sangat sedikit ($snapshotCount snapshot) — jangan beli dulu, tunggu konfirmasi!"
            snapshotCount < 5 -> "⚠️ Sinyal awal ($snapshotCount snapshot) — belum terkonfirmasi penuh."
            snapshotCount < 8 -> "🔍 Sinyal sedang terkumpul ($snapshotCount snapshot)."
            snapshotCount >= 20 -> "✅ Sinyal sangat kuat ($snapshotCount snapshot terkonfirmasi)."
            else -> null
        }
        if (confidenceLabel != null) {
            if (snapshotCount < 5) warnings.add(confidenceLabel) else reasons.add(confidenceLabel)
        }

        reasons.addAll(orderFlow.details)

        // Peringatan jika user mencoba beli saham yang sudah naik tinggi
        if (isAra) {
            warnings.add("🚨 SAHAM ARA! Tidak bisa dibeli lagi karena sudah limit auto reject atas.")
        } else if (snapshot.changePercent >= 12.0) {
            warnings.add("🔥 Saham sudah naik tinggi (+${String.format("%.1f", snapshot.changePercent)}%). Waspadai aksi profit taking / guyuran bandar!")
        }

        if (isTooExpensive) {
            warnings.add("⛔ Harga Saham > Rp 2.000. Filter aktif: Hanya merekomendasikan harga di bawah 2000.")
        }

        // Peringatan jika entry jauh dari bid (artinya harus hajar offer)
        if (entryPrice >= bestOffer) {
            warnings.add("⚡ Entry harga offer (hajar kanan) — risiko slippage. Pertimbangkan antre di ${formatPrice(bestBid)} jika mau aman.")
        }

        if (technical.isSupertrendBullish) {
            reasons.add("🚀 Supertrend Bullish (arah tren utama mendukung).")
        } else if (technical.totalScore > 0) {
            warnings.add("⚠️ Supertrend Bearish. Waspadai pembalikan arah tiba-tiba.")
        }

        if (technical.emaScore >= 4) {
            reasons.add("📈 Indikator EMA 9 & EMA 21 membentuk pola golden cross uptrend.")
        }

        if (technical.macdScore >= 4) {
            reasons.add("📊 MACD Histogram positif mengonfirmasi momentum kenaikan.")
        }

        if (technical.bbScore >= 4) {
            reasons.add("〰️ Harga berada di area pantulan Bollinger Band.")
        }

        if (spreadTicks <= 1) {
            reasons.add("💧 Spread tipis (1 tick), sangat likuid dan mudah keluar masuk.")
        } else if (spreadTicks >= 4) {
            warnings.add("⚠️ Spread cukup lebar ($spreadTicks tick), risiko slippage saat jual cepat.")
        }

        if (orderFlow.hasFakeWall) {
            if (isTrulyBearish) {
                warnings.add("🚨 KONFIRMASI Fake Wall + Bearish! Hindari masuk posisi baru.")
            } else {
                warnings.add("⚠️ Terdeteksi indikasi Fake Wall — bisa juga akumulasi bandar, pantau lebih lanjut.")
            }
        }

        if (orderFlow.hasAccumulation) {
            reasons.add("🏦 Terdeteksi pola AKUMULASI: Ritel jual, bandar tampung. Harga kemungkinan dijaga.")
        }

        if (sessionInfo.phase == MarketPhase.SESSION_2_LATE || sessionInfo.phase == MarketPhase.PRE_CLOSE) {
            warnings.add("⏱ Sesi pasar mendekati akhir. Tidak disarankan membuka posisi baru.")
        }

        // Info target
        if (thickWallLevel != null && targetPrice < thickWallLevel.price) {
            reasons.add("🧱 Target dipasang di depan tembok tebal (Rp ${formatPrice(thickWallLevel.price)}). Lebih aman pastikan terjual sebelum tembus.")
        }

        val style = when {
            isConfirmedBreakout -> "Buy on Breakout (Tembus Offer Terverifikasi)"
            orderFlow.hasAccumulation -> "Buy on Weakness (Bandar Akumulasi — Tunggu Pantulan)"
            orderFlow.hasAbsorption -> "Buy on Dip (Penjualan Terserap Kuat)"
            currentPrice <= technical.nearestSupport * 1.015 -> "Buy on Support (Pantulan Bawah)"
            else -> entryNote.ifEmpty { "Momentum / Follow Trend" }
        }

        if (tapeReading != null) {
            val haka = tapeReading.totalHakaLot
            val haki = tapeReading.totalHakiLot
            if (haka > haki * 1.5) {
                reasons.add("🏃‍♂️ Tape Reading: HAKA masif (${haka}L vs ${haki}L)!")
            } else if (haki > haka * 1.5) {
                warnings.add("🏃‍♂️ Tape Reading: HAKI masif (${haki}L vs ${haka}L)!")
            } else if (haka > 0 || haki > 0) {
                reasons.add("Tape Reading: HAKA ${haka}L | HAKI ${haki}L")
            }
        }

        if (confidencePenalty < 0) {
            warnings.add("Sedang mengumpulkan data awal (menunggu ${8 - snapshotCount} iterasi lagi). Sinyal belum valid.")
        }

        return StockAnalysis(
            ticker = ticker,
            score = finalScore,
            recommendation = recommendation,
            lastPrice = currentPrice,
            changePercent = snapshot.changePercent,
            entryPrice = entryPrice,
            targetPrice = targetPrice,
            stopLoss = stopLoss,
            riskRewardRatio = rrRatio,
            style = style,
            estimatedProfitPercent = profitPercent,
            reasons = reasons,
            warnings = warnings,
            technical = technical,
            orderFlow = orderFlow,
            tapeReading = tapeReading,
            snapshotCount = snapshotCount,
            lastUpdated = System.currentTimeMillis()
        )
    }

    private fun formatPrice(price: Int): String = String.format("%,d", price).replace(',', '.')
}
