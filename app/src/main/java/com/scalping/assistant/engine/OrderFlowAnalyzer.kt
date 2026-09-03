package com.scalping.assistant.engine

import com.scalping.assistant.data.models.OrderBookSnapshot
import com.scalping.assistant.data.models.OrderFlowResult
import kotlin.math.max

object OrderFlowAnalyzer {

    fun analyze(ticker: String, history: List<OrderBookSnapshot>): OrderFlowResult {
        if (history.isEmpty()) {
            return OrderFlowResult(ticker = ticker)
        }

        if (history.size < 2) {
            val latest = history.last()
            val simpleDelta = latest.totalBidLot - latest.totalOfferLot
            return OrderFlowResult(
                ticker = ticker,
                totalScore = 20,
                details = listOf("Mengumpulkan data snapshot awal...")
            )
        }

        val details = mutableListOf<String>()

        // 1. Delta Volume Analysis (Perubahan antar snapshot)
        var cumulativeDelta = 0L
        var aggressiveBuyCount = 0
        var aggressiveSellCount = 0

        for (i in 1 until history.size) {
            val prev = history[i - 1]
            val curr = history[i]

            // Offer berkurang = ada yang beli agresif (hajar kanan)
            val offerConsumed = max(0L, prev.totalOfferLot - curr.totalOfferLot)
            // Bid berkurang = ada yang jual agresif (hajar kiri)
            val bidConsumed = max(0L, prev.totalBidLot - curr.totalBidLot)

            val delta = offerConsumed - bidConsumed
            cumulativeDelta += delta

            if (delta > 0) aggressiveBuyCount++
            if (delta < 0) aggressiveSellCount++
        }

        val totalSnapshots = history.size - 1
        var deltaScore = 12
        if (cumulativeDelta > 5000L && aggressiveBuyCount > aggressiveSellCount) {
            deltaScore = 25
            details.add("✅ Delta Volume positif (+${cumulativeDelta} lot). Tekanan hajar kanan aktif.")
        } else if (cumulativeDelta > 0) {
            deltaScore = 18
            details.add("✅ Pembelian agresif moderat (+${cumulativeDelta} lot).")
        } else if (cumulativeDelta < -5000L) {
            deltaScore = 2
            details.add("❌ Tekanan jual agresif dominan (${cumulativeDelta} lot).")
        } else {
            deltaScore = 8
            details.add("⚖️ Tekanan beli dan jual relatif seimbang.")
        }

        // 2. Stability Analysis
        var bidCollapseCount = 0
        var offerThinningCount = 0

        for (i in 1 until history.size) {
            val prev = history[i - 1]
            val curr = history[i]

            if (prev.totalBidLot > 0 && (prev.totalBidLot - curr.totalBidLot).toDouble() / prev.totalBidLot > 0.25) {
                bidCollapseCount++
            }
            if (curr.totalOfferLot < prev.totalOfferLot) {
                offerThinningCount++
            }
        }

        var stabilityScore = 10
        if (bidCollapseCount == 0 && offerThinningCount > totalSnapshots / 2) {
            stabilityScore = 15
            details.add("✅ Antrean Bid stabil dan antrean Offer menipis teratur.")
        } else if (bidCollapseCount > 1) {
            stabilityScore = 2
            details.add("⚠️ Antrean Bid sempat ambruk tiba-tiba ($bidCollapseCount kali). Waspada!")
        }

        // 3. Fake Wall Detection
        // Cari order yang > 3x rata-rata, lalu hilang dalam < 5 snapshot tanpa match
        var hasFakeWall = false
        val latestSnapshots = history.takeLast(10)
        for (snap in latestSnapshots) {
            val avgBidLot = if (snap.bidLevels.isNotEmpty()) snap.bidLevels.map { it.lot }.average() else 0.0
            val suspiciousBid = snap.bidLevels.any { it.lot > avgBidLot * 3.5 && it.lot > 2000 }

            if (suspiciousBid) {
                // Cek apakah di snapshot terbaru order ini hilang padahal harga tidak tembus ke bawah
                val currentHasIt = history.last().bidLevels.any { it.lot > avgBidLot * 2.5 }
                if (!currentHasIt && history.last().lastPrice >= snap.lastPrice) {
                    hasFakeWall = true
                    break
                }
            }
        }

        val fakeWallPenalty = if (hasFakeWall) {
            details.add("🚨 PERINGATAN: Terdeteksi Fake Bid Wall (antrean tebal dicabut).")
            -10
        } else {
            0
        }

        // 4. Absorption Detection
        // Bid terus diserang tapi tidak tembus (harga bertahan di support)
        var hasAbsorption = false
        if (aggressiveSellCount >= 3 && history.last().lastPrice >= history.first().lastPrice) {
            hasAbsorption = true
            details.add("🔥 Terdeteksi Absorption: Penjualan tertampung, harga tertahan kuat.")
        }
        val absorptionBonus = if (hasAbsorption) 10 else 0

        // 5. Breakout Detection (Tembok Offer tebal yang dihajar)
        var hasBreakoutSignal = false
        val latestSnap = history.last()
        val avgOfferLot = if (latestSnap.offerLevels.isNotEmpty()) latestSnap.offerLevels.map { it.lot }.average() else 0.0
        val thickOffer = latestSnap.offerLevels.any { it.lot > avgOfferLot * 3.5 && it.lot > 2000 }
        
        if (thickOffer && cumulativeDelta > 3000L && aggressiveBuyCount > aggressiveSellCount) {
            hasBreakoutSignal = true
            details.add("🚀 BREAKOUT ALERT: Tembok Offer tebal sedang dihajar pembeli agresif!")
        }
        val breakoutBonus = if (hasBreakoutSignal) 10 else 0

        val totalScore = (deltaScore + stabilityScore + fakeWallPenalty + absorptionBonus + breakoutBonus).coerceIn(0, 50)

        return OrderFlowResult(
            ticker = ticker,
            deltaVolumeScore = deltaScore,
            stabilityScore = stabilityScore,
            fakeWallPenalty = fakeWallPenalty,
            absorptionBonus = absorptionBonus,
            breakoutBonus = breakoutBonus,
            totalScore = totalScore,
            hasFakeWall = hasFakeWall,
            hasAbsorption = hasAbsorption,
            hasBreakoutSignal = hasBreakoutSignal,
            cumulativeDelta = cumulativeDelta,
            details = details
        )
    }
}
