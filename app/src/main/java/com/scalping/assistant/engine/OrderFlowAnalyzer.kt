package com.scalping.assistant.engine

import com.scalping.assistant.data.models.OrderBookSnapshot
import com.scalping.assistant.data.models.OrderFlowResult
import kotlin.math.abs
import kotlin.math.max

object OrderFlowAnalyzer {

    fun analyze(ticker: String, history: List<OrderBookSnapshot>): OrderFlowResult {
        if (history.isEmpty()) {
            return OrderFlowResult(ticker = ticker)
        }

        if (history.size < 2) {
            return OrderFlowResult(
                ticker = ticker,
                totalScore = 10,
                details = listOf("⏳ Mengumpulkan data snapshot pertama...")
            )
        }

        val details = mutableListOf<String>()
        val snapshotCount = history.size

        // ============================================================
        // 1. DELTA VOLUME ANALYSIS (Perubahan Agresif antar snapshot)
        //    Mengukur: siapa yang lebih agresif — pembeli atau penjual
        // ============================================================
        var cumulativeDelta = 0L
        var aggressiveBuyCount = 0
        var aggressiveSellCount = 0
        var totalTradeActivity = 0L

        for (i in 1 until history.size) {
            val prev = history[i - 1]
            val curr = history[i]

            // Offer berkurang = ada yang beli agresif (hajar offer/kanan)
            val offerConsumed = max(0L, prev.totalOfferLot - curr.totalOfferLot)
            // Bid berkurang = ada yang jual agresif (hajar bid/kiri)
            val bidConsumed = max(0L, prev.totalBidLot - curr.totalBidLot)

            val delta = offerConsumed - bidConsumed
            cumulativeDelta += delta
            totalTradeActivity += offerConsumed + bidConsumed

            if (delta > 0) aggressiveBuyCount++
            if (delta < 0) aggressiveSellCount++
        }

        val totalSnapshots = history.size - 1
        var deltaScore = 12

        when {
            cumulativeDelta > 5000L && aggressiveBuyCount > aggressiveSellCount -> {
                deltaScore = 25
                details.add("✅ Delta Volume positif kuat (+${cumulativeDelta} lot). Tekanan hajar kanan aktif.")
            }
            cumulativeDelta > 1000L -> {
                deltaScore = 18
                details.add("✅ Pembelian agresif moderat (+${cumulativeDelta} lot).")
            }
            cumulativeDelta < -5000L && aggressiveSellCount > aggressiveBuyCount * 1.5 -> {
                // Delta negatif besar DAN penjual jauh lebih dominan = kemungkinan tekanan jual nyata
                deltaScore = 2
                details.add("❌ Tekanan jual agresif dominan (${cumulativeDelta} lot).")
            }
            cumulativeDelta < 0 -> {
                // Delta negatif RINGAN — bisa saja akumulasi, jangan langsung judge
                deltaScore = 8
                // Akan diperjelas oleh Accumulation Detector di bawah
            }
            else -> {
                deltaScore = 10
                details.add("⚖️ Tekanan beli dan jual relatif seimbang.")
            }
        }

        // ============================================================
        // 2. ACCUMULATION DETECTOR (NEW — Kunci Fix Bug Utama!)
        //    Akumulasi = Ritel jual (delta negatif), TAPI harga BERTAHAN
        //    atau bahkan NAIK. Ini tanda bandar sedang beli dari ritel panik.
        //    BUKAN guyuran bandar!
        // ============================================================
        var hasAccumulation = false
        val firstPrice = history.first().lastPrice
        val lastPrice = history.last().lastPrice

        // Kondisi akumulasi:
        // 1. Banyak penjualan agresif (bid dikonsumsi)
        // 2. TAPI harga tidak turun signifikan (bertahan atau naik)
        // 3. Bid wall masih ada atau bid lot tidak collapse parah
        val priceChange = lastPrice - firstPrice
        val totalBidChange = history.last().totalBidLot - history.first().totalBidLot

        if (aggressiveSellCount >= 3 && cumulativeDelta < 0 && priceChange >= -1) {
            // Banyak yang jual tapi harga tidak turun → Bandar tampung!
            hasAccumulation = true
            details.add("🏦 AKUMULASI TERDETEKSI: Ritel jual masif, harga tetap/naik → Bandar sedang tampung!")
            // Naikkan delta score karena ini bullish, bukan bearish
            deltaScore = max(deltaScore, 15)
        } else if (aggressiveSellCount >= 2 && cumulativeDelta < -2000L && priceChange >= 0) {
            // Versi lebih lemah — masih mungkin akumulasi
            hasAccumulation = true
            details.add("🏦 Indikasi Akumulasi: Tekanan jual ada tapi harga bertahan kuat.")
            deltaScore = max(deltaScore, 12)
        }

        // ============================================================
        // 3. STABILITY ANALYSIS
        //    Mengukur stabilitas bid/offer queue
        // ============================================================
        var bidCollapseCount = 0
        var offerThinningCount = 0

        for (i in 1 until history.size) {
            val prev = history[i - 1]
            val curr = history[i]

            // Bid collapse = bid lot turun > 25% dalam satu snapshot
            if (prev.totalBidLot > 0 && (prev.totalBidLot - curr.totalBidLot).toDouble() / prev.totalBidLot > 0.25) {
                bidCollapseCount++
            }
            if (curr.totalOfferLot < prev.totalOfferLot) {
                offerThinningCount++
            }
        }

        var stabilityScore = 10
        when {
            bidCollapseCount == 0 && offerThinningCount > totalSnapshots / 2 -> {
                stabilityScore = 15
                details.add("✅ Antrean Bid stabil dan antrean Offer menipis teratur.")
            }
            bidCollapseCount >= 2 && !hasAccumulation -> {
                // Jika ada akumulasi, bid collapse bisa terjadi karena bandar juga "let it go" sementara
                stabilityScore = 3
                details.add("⚠️ Antrean Bid sempat ambruk tiba-tiba ($bidCollapseCount kali). Waspada!")
            }
            bidCollapseCount >= 2 && hasAccumulation -> {
                stabilityScore = 8 // Tidak terlalu dipunish jika ada akumulasi
            }
        }

        // ============================================================
        // 4. FAKE WALL DETECTION (Diperbaiki — lebih presisi)
        //    Fake wall = Order tebal yang DICABUT tanpa match harga
        //    BUKAN: Order yang tereksekusi (harga tembus / menyentuh level itu)
        // ============================================================
        var hasFakeWall = false
        var fakeWallConfirmCount = 0 // Harus terkonfirmasi lebih dari 1 snapshot

        // Butuh minimal 6 snapshot agar deteksi fake wall tidak jadi noise
        if (snapshotCount >= 6) {
            val checkWindow = history.takeLast(minOf(12, snapshotCount))

            for (i in 0 until checkWindow.size - 2) {
                val snapWithWall = checkWindow[i]
                val avgBidLot = if (snapWithWall.bidLevels.isNotEmpty()) snapWithWall.bidLevels.map { it.lot }.average() else 0.0

                // Temukan order yang sangat besar (>4x rata-rata, bukan hanya 3.5x)
                val bigWall = snapWithWall.bidLevels.find { it.lot > avgBidLot * 4.0 && (it.lot.toLong() * 100 * it.price) > 300_000_000L }

                if (bigWall != null) {
                    val wallPrice = bigWall.price
                    val laterSnap = checkWindow.last()

                    // Cek apakah order besar itu hilang di snapshot terbaru
                    val stillExists = laterSnap.bidLevels.any { it.price == wallPrice && it.lot > avgBidLot * 2.0 }

                    if (!stillExists) {
                        // Order hilang — tapi apakah harga TURUN ke level itu (artinya tereksekusi)?
                        val priceHitWall = laterSnap.lastPrice <= wallPrice + PriceFraction.getTickSize(wallPrice)

                        if (!priceHitWall) {
                            // Order hilang TANPA harga menyentuhnya → FAKE WALL (dicabut, bukan match)
                            fakeWallConfirmCount++
                        }
                        // Jika priceHitWall = true → ini order yang TEREKSEKUSI, bukan fake wall
                    }
                }
            }

            // Fake wall hanya ditetapkan jika dikonfirmasi LEBIH DARI SATU kali
            if (fakeWallConfirmCount >= 2) {
                hasFakeWall = true
                details.add("🚨 PERINGATAN: Fake Bid Wall Terkonfirmasi ($fakeWallConfirmCount kali). Bandar pura-pura support!")
            } else if (fakeWallConfirmCount == 1) {
                // Satu kali saja tidak cukup → catat sebagai indikasi, bukan konfirmasi
                details.add("⚠️ Indikasi Fake Wall (1 kejadian) — pantau lebih lanjut, belum terkonfirmasi.")
            }
        }

        val fakeWallPenalty = when {
            hasFakeWall && !hasAccumulation -> -10  // Fake wall nyata
            hasFakeWall && hasAccumulation -> -3    // Fake wall + akumulasi = ambigu, penalti kecil
            else -> 0
        }

        // ============================================================
        // 5. ABSORPTION DETECTION
        //    Bid diserang terus tapi harga TIDAK TURUN = Bandar tampung
        // ============================================================
        var hasAbsorption = false
        if (aggressiveSellCount >= 3 && lastPrice >= firstPrice) {
            hasAbsorption = true
            if (!hasAccumulation) { // Hindari duplikasi pesan
                details.add("🔥 Terdeteksi Absorption: Penjualan tertampung, harga tertahan kuat.")
            }
        }
        val absorptionBonus = if (hasAbsorption || hasAccumulation) 10 else 0

        // ============================================================
        // 6. BREAKOUT DETECTION (Tembok Offer tebal yang dihajar)
        // ============================================================
        var hasBreakoutSignal = false
        val latestSnap = history.last()
        val avgOfferLot = if (latestSnap.offerLevels.isNotEmpty()) latestSnap.offerLevels.map { it.lot }.average() else 0.0
        val thickOffer = latestSnap.offerLevels.any {
            it.lot > avgOfferLot * 4.0 && (it.lot.toLong() * 100 * it.price) > 300_000_000L
        }

        val cumulativeDeltaValue = cumulativeDelta * 100 * latestSnap.lastPrice.toLong()
        if (thickOffer && cumulativeDeltaValue > 500_000_000L && aggressiveBuyCount > aggressiveSellCount * 1.5) {
            hasBreakoutSignal = true
            details.add("🚀 BREAKOUT ALERT: Tembok Offer tebal sedang dihajar pembeli agresif!")
        }
        val breakoutBonus = if (hasBreakoutSignal) 10 else 0

        val totalScore = (deltaScore + stabilityScore + fakeWallPenalty + absorptionBonus + breakoutBonus).coerceIn(0, 50)

        // ============================================================
        // 7. BEAR TRAP DETECTION (False Breakdown)
        //    Harga turun tapi volume guyuran kecil = Jebakan bawah
        // ============================================================
        var hasBearTrap = false
        if (lastPrice < firstPrice && abs(cumulativeDeltaValue) < 50_000_000L && aggressiveSellCount <= aggressiveBuyCount) {
            hasBearTrap = true
            details.add("⚠️ POTENSI BEAR TRAP: Harga turun (support jebol semu) tapi volume jual KECIL. Waspada teknik 'buat takut ritel'.")
        }

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
            hasAccumulation = hasAccumulation,
            hasBreakoutSignal = hasBreakoutSignal,
            hasBearTrap = hasBearTrap,
            cumulativeDelta = cumulativeDelta,
            details = details
        )
    }
}
