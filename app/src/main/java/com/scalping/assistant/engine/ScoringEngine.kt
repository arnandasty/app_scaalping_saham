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
        sessionInfo: SessionInfo
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

        // 2. Target Price: Target konsisten 2% - 3%
        // Prioritaskan Resistance terdekat yang berjarak minimal +2.0% dari Entry
        val minTargetPrice = entryPrice * 1.020
        val normalTargetPrice = entryPrice * 1.028

        var rawTarget = if (technical.nearestResistance > minTargetPrice) {
            technical.nearestResistance
        } else if (technical.bbUpper > minTargetPrice) {
            technical.bbUpper
        } else {
            normalTargetPrice
        }

        // Jangan target terlalu jauh untuk day trade (maksimal 4.5%)
        if (rawTarget > entryPrice * 1.045) {
            rawTarget = entryPrice * 1.028
        }
        val targetPrice = PriceFraction.roundUpToValidTick(rawTarget.roundToInt())

        // 3. Stop Loss: Batas toleransi 1.2% - 1.8% di bawah Entry
        val maxStopLoss = entryPrice * 0.985
        var rawSL = if (technical.nearestSupport in (entryPrice * 0.97)..maxStopLoss) {
            technical.nearestSupport
        } else if (technical.bbLower in (entryPrice * 0.97)..maxStopLoss) {
            technical.bbLower
        } else {
            entryPrice * 0.985
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
        val effectiveTechScore = if (isNewIpo) 15 else technical.totalScore

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

        val finalScore = (ofScore + effectiveTechScore + marketScore + rrPenalty).coerceIn(0, 100)

        // 6. Tentukan Rekomendasi
        val recommendation = when {
            finalScore >= 82 && rrRatio >= 1.4 && !orderFlow.hasFakeWall -> Recommendation.STRONG_BUY
            finalScore >= 68 && !orderFlow.hasFakeWall -> Recommendation.BUY
            finalScore >= 52 -> Recommendation.WATCH
            else -> Recommendation.AVOID
        }

        // 7. Buat Alasan & Peringatan
        val reasons = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        reasons.addAll(orderFlow.details)

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
