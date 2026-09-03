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
        prevRec: Recommendation? = null
    ): StockAnalysis {
        val ticker = snapshot.ticker
        val currentPrice = if (snapshot.lastPrice > 0) snapshot.lastPrice else (snapshot.offerLevels.firstOrNull()?.price ?: 100)

        // 1. Dynamic Entry Price: Momentum vs Pullback
        val bestOffer = snapshot.offerLevels.firstOrNull()?.price ?: currentPrice
        val bestBid = snapshot.bidLevels.firstOrNull()?.price ?: currentPrice
        
        val isExtremeMomentum = orderFlow.deltaVolumeScore >= 18 || orderFlow.hasBreakoutSignal
        
        var rawEntry = if (isExtremeMomentum) {
            bestOffer.toDouble() // Hajar Kanan saat momentum meledak atau breakout
        } else {
            // Antre beli di Best Bid, atau di area support jika sangat dekat
            if (technical.nearestSupport in (bestBid * 0.98)..bestBid.toDouble()) {
                technical.nearestSupport // Antre di area pullback/support
            } else {
                bestBid.toDouble() // Standar antre di Bid
            }
        }
        val entryPrice = PriceFraction.roundToValidTick(rawEntry.roundToInt())

        // 2. Target Price: Smart Take Profit di depan Tembok Offer Tebal
        val minTargetPrice = entryPrice * 1.018
        val normalTargetPrice = entryPrice * 1.028

        // Cari apakah ada tembok offer tebal di atas entry
        val avgOfferLot = if (snapshot.offerLevels.isNotEmpty()) snapshot.offerLevels.map { it.lot }.average() else 0.0
        val thickWallLevel = snapshot.offerLevels
            .filter { it.price > entryPrice && it.lot > avgOfferLot * 2.5 && (it.lot.toLong() * 100 * it.price) > 200_000_000L }
            .minByOrNull { it.price }

        var rawTarget = if (thickWallLevel != null) {
            // Pasang target 1 tick di bawah tembok atau tepat di harga tembok agar barang pasti laku!
            val tick = PriceFraction.getTickSize(thickWallLevel.price)
            val frontRunPrice = thickWallLevel.price - tick
            if (frontRunPrice >= minTargetPrice) frontRunPrice.toDouble() else thickWallLevel.price.toDouble()
        } else if (technical.nearestResistance > minTargetPrice) {
            technical.nearestResistance
        } else if (technical.bbUpper > minTargetPrice) {
            technical.bbUpper
        } else {
            normalTargetPrice
        }

        // Batas wajar day trade (maksimal 4.0% dari entry)
        if (rawTarget > entryPrice * 1.040) {
            rawTarget = entryPrice * 1.028
        }
        val targetPrice = PriceFraction.roundUpToValidTick(rawTarget.roundToInt())

        // 3. Stop Loss: Prioritas utama adalah Bantalan Tembok Bid (Bid Wall)
        val maxStopLoss = entryPrice * 0.985
        
        // Cari tembok Bid raksasa di bawah harga masuk
        val avgBidLot = if (snapshot.bidLevels.isNotEmpty()) snapshot.bidLevels.map { it.lot }.average() else 0.0
        val thickBidWall = snapshot.bidLevels
            .filter { it.price < entryPrice && it.lot > avgBidLot * 2.5 && (it.lot.toLong() * 100 * it.price) > 200_000_000L }
            .maxByOrNull { it.price } // Ambil tembok tertinggi yang terdekat di bawah entry

        var rawSL = if (thickBidWall != null) {
            // Cut loss tepat di bawah tembok bid (jika tembok jebol, langsung buang)
            val tick = PriceFraction.getTickSize(thickBidWall.price)
            thickBidWall.price.toDouble() - tick
        } else if (technical.nearestSupport in (entryPrice * 0.96)..maxStopLoss) {
            technical.nearestSupport
        } else if (technical.bbLower in (entryPrice * 0.96)..maxStopLoss) {
            technical.bbLower
        } else {
            entryPrice * 0.985
        }
        
        // Cegah Stop Loss yang terlalu dalam akibat tembok yang terlalu jauh
        if (rawSL < entryPrice * 0.96) {
            rawSL = entryPrice * 0.985
        }
        val stopLoss = PriceFraction.roundDownToValidTick(rawSL.roundToInt())

        // 4. Hitung Risk / Reward
        val risk = max(1, entryPrice - stopLoss)
        val reward = max(1, targetPrice - entryPrice)
        val rrRatio = String.format("%.2f", reward.toDouble() / risk).replace(',', '.').toDoubleOrNull() ?: 1.5

        val profitPercent = String.format("%.2f", (reward.toDouble() / entryPrice) * 100).replace(',', '.').toDoubleOrNull() ?: 2.5

        // A. Order Flow (0 - 50)
        val ofScore = orderFlow.totalScore

        // B. Technical (0 - 30)
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

        // C. Market Context (0 - 20)
        val sessionBonus = sessionInfo.phase.scoreBonus // 0 - 8

        // Spread Quality (0 - 6)
        val spreadTicks = if (bestOffer > bestBid) (bestOffer - bestBid) / PriceFraction.getTickSize(bestOffer) else 1
        val spreadScore = when (spreadTicks) {
            1 -> 6
            2 -> 4
            3 -> 2
            else -> 0
        }

        // Volume Adequacy (0 - 6, or penalty)
        val totalLot = snapshot.totalBidLot + snapshot.totalOfferLot
        val volScore = when {
            totalLot > 50000 -> 6
            totalLot > 15000 -> 4
            totalLot > 5000 -> 2
            else -> -10 // Penalti likuiditas ketat untuk saham sepi
        }

        val marketScore = (sessionBonus + spreadScore + volScore).coerceIn(0, 20)

        // Penyesuaian RR jika < 1.2
        val rrPenalty = if (rrRatio < 1.2) -8 else 0

        // Proteksi FOMO: Penalti jika saham sudah terbang terlalu tinggi (> +15%)
        var overboughtPenalty = when {
            snapshot.changePercent >= 20.0 -> -15
            snapshot.changePercent >= 14.0 -> -8
            else -> 0
        }

        // Smart FOMO: Batalkan/kurangi penalti jika akumulasi sangat kuat (volume & teknikal mendukung)
        if (overboughtPenalty < 0 && isExtremeMomentum && technical.isSupertrendBullish) {
            overboughtPenalty = 0 // Bandar sedang ngegas, ikut!
        } else if (overboughtPenalty < 0 && orderFlow.deltaVolumeScore >= 18) {
            overboughtPenalty /= 2 // Kurangi penalti setengahnya karena akumulasi nyata
        }

        val finalScore = (ofScore + effectiveTechScore + marketScore + rrPenalty + overboughtPenalty).coerceIn(0, 100)

        // 6. Tentukan Rekomendasi (Dengan Sistem Hysteresis / Sabuk Pengaman)
        val recommendation = when {
            finalScore >= 82 && rrRatio >= 1.4 && !orderFlow.hasFakeWall -> Recommendation.STRONG_BUY
            // Jika sebelumnya STRONG BUY, tahan status sampai skor turun di bawah 78
            prevRec == Recommendation.STRONG_BUY && finalScore >= 78 && !orderFlow.hasFakeWall -> Recommendation.STRONG_BUY
            
            finalScore >= 68 && !orderFlow.hasFakeWall -> Recommendation.BUY
            // Jika sebelumnya STRONG BUY atau BUY, tahan status sampai skor murni anjlok di bawah 62
            (prevRec == Recommendation.BUY || prevRec == Recommendation.STRONG_BUY) && finalScore >= 62 && !orderFlow.hasFakeWall -> Recommendation.BUY
            
            finalScore >= 52 -> Recommendation.WATCH
            // Jika sebelumnya bukan AVOID, tahan sampai turun ke 45
            (prevRec != Recommendation.AVOID) && finalScore >= 45 -> Recommendation.WATCH
            
            else -> Recommendation.AVOID
        }

        // 7. Buat Alasan & Peringatan
        val reasons = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        reasons.addAll(orderFlow.details)

        if (snapshot.changePercent >= 12.0) {
            warnings.add("🔥 Saham sudah naik tinggi (+${String.format("%.1f", snapshot.changePercent)}%). Waspadai aksi profit taking / guyuran bandar!")
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
            warnings.add("🚨 Terdeteksi indikasi Fake Wall dari antrean bid/offer.")
        }

        if (sessionInfo.phase == MarketPhase.SESSION_2_LATE || sessionInfo.phase == MarketPhase.PRE_CLOSE) {
            warnings.add("⏱ Sesi pasar mendekati akhir. Tidak disarankan membuka posisi baru.")
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
            estimatedProfitPercent = profitPercent,
            reasons = reasons,
            warnings = warnings,
            technical = technical,
            orderFlow = orderFlow,
            lastUpdated = System.currentTimeMillis()
        )
    }
}
