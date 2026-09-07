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
        tapeReading: com.scalping.assistant.data.models.TapeReadingStat? = null,
        bandarDetector: com.scalping.assistant.data.models.BandarDetectorStat? = null
    ): StockAnalysis {
        val ticker = snapshot.ticker
        val currentPrice = if (snapshot.lastPrice > 0) snapshot.lastPrice 
                           else if (snapshot.offerLevels.isNotEmpty()) snapshot.offerLevels.first().price 
                           else if (snapshot.bidLevels.isNotEmpty()) snapshot.bidLevels.first().price
                           else technical.lastClosePrice.toInt()

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

        // Cari tembok bid tebal di bawah atau di harga saat ini (Benteng Order Book)
        val avgBidLot = if (snapshot.bidLevels.isNotEmpty()) snapshot.bidLevels.map { it.lot }.average() else 0.0
        val maxBidLot = if (snapshot.bidLevels.isNotEmpty()) snapshot.bidLevels.maxOf { it.lot } else 0L

        val thickBidWall = snapshot.bidLevels
            .filter {
                val value = it.lot.toLong() * 100 * it.price
                val isProminent = it.lot >= maxBidLot * 0.8 // Minimal 80% dari tembok terbesar
                val isTwiceAverage = it.lot > avgBidLot * 1.5 // Menonjol dari rata-rata
                
                it.price <= currentPrice &&
                it.price >= currentPrice * 0.96 && // Maksimal 4% di bawah harga last
                (isProminent || isTwiceAverage) && 
                value > 500_000_000L // Tetap harus punya nilai yang signifikan (minimal 500 Juta)
            }
            .maxByOrNull { it.price } // Tembok bid terdekat di bawah/di harga saat ini

        var rawEntry: Double
        var entryNote = ""

        if (isConfirmedBreakout) {
            // Breakout dikonfirmasi → Entry di offer (hajar kanan)
            rawEntry = bestOffer.toDouble()
            entryNote = "Buy on Breakout (Hajar Offer)"
        } else if (thickBidWall != null) {
            // Prioritas 1: Ada Tembok Bid Tebal (Benteng Pertahanan Order Book)
            val bidTick = PriceFraction.getTickSize(thickBidWall.price)
            val frontRunBid = thickBidWall.price + bidTick

            if (frontRunBid < currentPrice) {
                // Antre 1 tick di atas tembok tebal agar order cepat match sebelum tembok tertembus
                rawEntry = frontRunBid.toDouble()
                entryNote = "Buy on Bid Support (+1 Tick di Rp $frontRunBid)"
            } else {
                // Antre tepat di tembok tebal
                rawEntry = thickBidWall.price.toDouble()
                entryNote = "Buy on Bid Wall (Rp ${thickBidWall.price})"
            }
        } else {
            // Prioritas 2 (Fallback): Tidak ada tembok bid tebal → gunakan strategi pullback teknikal
            val pullbackTarget = currentPrice * 0.992 // Antre ~0.8% di bawah harga last

            rawEntry = when {
                // Jika ada support kuat di dekat pullback zone, antre di sana
                technical.nearestSupport in (currentPrice * 0.97)..pullbackTarget -> {
                    entryNote = "Buy on Support Teknikal"
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
                val value = it.lot.toLong() * 100 * it.price
                val isProminent = it.lot >= maxOfferLot * 0.8
                val isTwiceAverage = it.lot > avgOfferLot * 1.5
                
                it.price > entryPrice &&
                (isProminent || isTwiceAverage) && 
                value > 500_000_000L
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
        // 3. STOP LOSS — Prioritas: Tembok Bid atau Support Teknikal
        // ============================================================
        val maxStopLoss = entryPrice * 0.985

        var rawSL = if (thickBidWall != null) {
            val bidTick = PriceFraction.getTickSize(thickBidWall.price)
            val wallSL = (thickBidWall.price - bidTick).toDouble()
            if (wallSL in (entryPrice * 0.95)..maxStopLoss) {
                wallSL
            } else if (technical.nearestSupport in (entryPrice * 0.96)..maxStopLoss) {
                technical.nearestSupport
            } else {
                entryPrice * 0.985
            }
        } else if (technical.nearestSupport in (entryPrice * 0.96)..maxStopLoss) {
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

        // F. Bandar Detector Score (-30 s/d +20)
        var bandarScore = 0
        var isBandarBigAcc = false
        var isBandarAcc = false
        var isBandarDist = false
        var isBandarBigDist = false
        var isRetailTrap = false
        var isRetailMomentum = false
        var isPureBandarAcc = false
        var isScalperBandar = false

        if (bandarDetector != null) {
            when (bandarDetector.accdistStatus) {
                "Big Acc" -> {
                    bandarScore = 5
                    isBandarBigAcc = true
                }
                "Acc" -> {
                    bandarScore = 3
                    isBandarAcc = true
                }
                "Dist" -> {
                    bandarScore = -4
                    isBandarDist = true
                }
                "Big Dist" -> {
                    bandarScore = -8
                    isBandarBigDist = true
                }
            }

            // Klasifikasi Ritel vs Bandar Nyata (Konteks Pendukung, Bukan Penentu Mutlak Scalping)
            when (bandarDetector.bandarProfile) {
                "RETAIL_TRAP" -> {
                    // Perangkap ritel: Hanya diberi penalti ringan, keputusan scalping tetap di Tape Reading
                    bandarScore -= 4
                    isRetailTrap = true
                }
                "RETAIL_MOMENTUM" -> {
                    // Momentum ritel ramai: Likuiditas tinggi untuk scalping cepat
                    bandarScore += 2
                    isRetailMomentum = true
                }
                "PURE_ACCUMULATION" -> {
                    bandarScore += 4
                    isPureBandarAcc = true
                }
                "SCALPER_ACTIVE" -> {
                    bandarScore += 3
                    isScalperBandar = true
                }
                "DISTRIBUTION" -> {
                    bandarScore -= 5
                    isBandarDist = true
                }
            }

            // Faktor Arus Broker Asing & Smart Money Flow (Multi-day)
            if (bandarDetector.smartMoneySummary.contains("SMART MONEY ACCUMULATION") ||
                bandarDetector.foreignFlow.contains("Big Acc") ||
                bandarDetector.foreignFlowMultiDay.contains("Big Acc")) {
                bandarScore += 3
            } else if (bandarDetector.smartMoneySummary.contains("SMART MONEY DISTRIBUTION") ||
                bandarDetector.foreignFlow.contains("Big Dist") ||
                bandarDetector.foreignFlowMultiDay.contains("Big Dist")) {
                bandarScore -= 4
            }
        }

        // H. Moving Average Alignment Bonus/Penalty (MA 5, MA 9, MA 20)
        var maBonus = 0
        if (technical.ma5 > 0 && technical.ma9 > 0 && technical.ma20 > 0) {
            if (currentPrice > technical.ma5 && technical.ma5 > technical.ma9 && technical.ma9 > technical.ma20) {
                maBonus = 6 // Bullish Stack
            } else if (currentPrice < technical.ma5 && technical.ma5 < technical.ma9 && technical.ma9 < technical.ma20) {
                maBonus = -8 // Bearish Stack
            }
        }

        // G. Confidence penalty jika snapshot masih sedikit
        // Dengan snapshot <5, belum cukup data untuk sinyal kuat → turunkan skor
        val confidencePenalty = when {
            snapshotCount < 3  -> -20  // Data masih sangat sedikit
            snapshotCount < 5  -> -12  // Data belum cukup
            snapshotCount < 8  -> -5   // Data sedang terkumpul
            else -> 0                   // Data sudah cukup
        }

        val finalScore = (ofScore + effectiveTechScore + marketScore + tapeReadingScore + bandarScore + maBonus + rrPenalty + overboughtPenalty + confidencePenalty).coerceIn(0, 100)

        // ============================================================
        // 5B. FILTER LAYER 1: Volume Ratio (Time-Aware)
        // ============================================================
        val isMarketNotTrading = sessionInfo.phase == MarketPhase.CLOSED ||
                sessionInfo.phase == MarketPhase.PRE_OPEN ||
                sessionInfo.phase == MarketPhase.BREAK
        val isOpeningSession = sessionInfo.phase == MarketPhase.SESSION_1_OPEN

        val volumeRatio = technical.volumeRatio
        val volumePenalty = if (isMarketNotTrading || isOpeningSession) {
            0
        } else {
            when {
                volumeRatio < 0.4 -> -15
                volumeRatio < 0.7 -> -8
                else -> 0
            }
        }
        val lowVolumeBlock = !isMarketNotTrading && !isOpeningSession && volumeRatio < 0.5

        // ============================================================
        // 5C. FILTER LAYER 2: EMA 50 Tren Makro
        // ============================================================
        val isBelowEma50 = technical.isBelowEma50
        val ema50Penalty = if (isBelowEma50) -12 else 0

        // ============================================================
        // 5D. FILTER LAYER 3: Cooldown Drop dari High Hari Ini
        // ============================================================
        val todayHigh = technical.todayHigh
        val dropFromHigh = if (todayHigh > 0) (todayHigh - currentPrice) / todayHigh else 0.0
        val dropPenalty = when {
            dropFromHigh >= 0.03 -> -15  // Drop 3%+ dari high
            dropFromHigh >= 0.02 -> -8   // Drop 2%+ dari high
            else -> 0
        }
        val hardDropBlock = dropFromHigh >= 0.03 // Drop 3%+ = blokir Strong Buy keras

        // ============================================================
        // 5E. ATURAN 1: Saham Yang Akan Naik (Early Momentum)
        //     Kenaikan masih awal (+0.5% .. +5.0%), volume meledak, akumulasi bandar
        // ============================================================
        val isEarlyMomentum = snapshot.changePercent in 0.5..5.0 &&
                (isMarketNotTrading || isOpeningSession || volumeRatio >= 0.7) &&
                (isBandarBigAcc || isBandarAcc || isHakaMasif || orderFlow.deltaVolumeScore >= 18) &&
                !isBelowEma50
        val earlyMomentumBonus = if (isEarlyMomentum) 8 else 0

        // ============================================================
        // 5F. ATURAN 2: Anti-Pucuk vs Super Momentum Exception
        // ============================================================
        val isHighPriceZone = snapshot.changePercent >= 7.0 && dropFromHigh < 0.02
        val isTrulyBearish = (orderFlow.hasFakeWall && (!technical.isSupertrendBullish || effectiveTechScore < 10)) || isHakiMasif || (dropFromHigh >= 0.035)
        val isAra = snapshot.araPrice > 0 && currentPrice >= snapshot.araPrice
        val isTooExpensive = currentPrice >= 2000

        // Syarat Mutlak Super Momentum (Ride the Wave):
        // 1. Bukan ARA & bukan harga > 2000
        // 2. Dominasi HAKA mutlak (HAKA masif atau rasio HAKA/HAKI >= 2.0 atau delta volume >= 20)
        // 3. Struktur orderbook kuat (ada bantalan tembok bid tebal atau stabilitas antrean >= 10)
        // 4. Tren bukan bearish & bukan di bawah EMA 50
        // 5. Skor AI memadai (>= 75)
        val isSuperMomentum = isHighPriceZone && !isAra && !isTooExpensive &&
                (isHakaMasif || (tapeReading != null && tapeReading.totalHakaLot > tapeReading.totalHakiLot * 2.0) || orderFlow.deltaVolumeScore >= 20) &&
                (thickBidWall != null || orderFlow.stabilityScore >= 10) &&
                !isTrulyBearish && !isBelowEma50

        // Anti-Pucuk HANYA mengunci jika di High DAN BUKAN Super Momentum!
        val isAtPucuk = isHighPriceZone && !isSuperMomentum

        // ============================================================
        // 5G. ATURAN 3: Analisis Pullback Sehat vs Guyuran
        //     Saham sempat naik tinggi, lalu terkoreksi (drop 1.5% .. 5.5% ke support)
        // ============================================================
        val isPulledBackFromHigh = (snapshot.changePercent >= 3.5 || todayHigh >= currentPrice * 1.04) && dropFromHigh in 0.015..0.055
        var isValidPullback = false
        var isTrapPullback = false

        if (isPulledBackFromHigh) {
            if (isHakiMasif || dropFromHigh >= 0.04) {
                // Guyuran live buang barang!
                isTrapPullback = true
            } else if ((isHakaMasif || orderFlow.deltaVolumeScore >= 10) && (thickBidWall != null || technical.nearestSupport > 0)) {
                // Pullback sehat teruji di bantalan support
                isValidPullback = true
            }
        }

        // Skor final setelah semua filter & momentum bonus
        val superMomentumBonus = if (isSuperMomentum) 10 else 0
        val filteredScore = (finalScore + volumePenalty + ema50Penalty + dropPenalty + earlyMomentumBonus + superMomentumBonus).coerceIn(0, 100)

        // ============================================================
        // 6. REKOMENDASI — Berdasarkan Prinsip Scalping Konsisten (Orderbook & Tape Reading Realtime)
        // ============================================================
        val prevWasHighScore = prevRec == Recommendation.STRONG_BUY || prevRec == Recommendation.BUY

        var recommendation = when {
            // FILTER: Saham ARA, harga >= 2000, atau jebakan guyuran tajam
            isAra || isTooExpensive || isTrapPullback -> Recommendation.AVOID

            // REALTIME GUYURAN: HAKI masif mendominasi mutlak
            isHakiMasif -> Recommendation.AVOID

            // FILTER LAYER 2: Di bawah EMA 50 → maksimal WATCH (tidak bisa BUY/STRONG_BUY)
            isBelowEma50 && !isConfirmedBreakout -> Recommendation.WATCH

            // SUPER MOMENTUM: Saham laju terbang tinggi dengan HAKA masif menuju ARA
            isSuperMomentum && isHakaMasif && filteredScore >= 80 -> Recommendation.STRONG_BUY
            isSuperMomentum -> Recommendation.BUY

            // ANTI-PUCUK: Jika belum pernah BUY, saham nangkring di High >= 7% dilarang FOMO di pucuk
            isAtPucuk && !prevWasHighScore -> Recommendation.WATCH

            // PULLBACK SEHAT: Koreksi teruji di bantalan support + bandar akumulasi → Rekomendasi BUY
            isValidPullback && filteredScore >= 65 && rrRatio >= 1.3 -> Recommendation.BUY

            // STRONG BUY: Threshold >= 88 + Volume cukup + Double confirm
            filteredScore >= 88 && rrRatio >= 1.5 && !isTrulyBearish && snapshotCount >= 5
                    && !lowVolumeBlock && !hardDropBlock && prevWasHighScore && !isAtPucuk -> Recommendation.STRONG_BUY

            // HAKA masif / Big Acc bandar dorong Strong Buy jika bukan di pucuk
            (isHakaMasif || isBandarBigAcc) && filteredScore >= 80 && rrRatio >= 1.3 && !isTrulyBearish
                    && !hardDropBlock && !isAtPucuk -> Recommendation.STRONG_BUY

            // Hysteresis STRONG BUY: Pertahankan Strong Buy jika skor masih >= 70
            prevRec == Recommendation.STRONG_BUY && filteredScore >= 70 && !isTrulyBearish
                    && !hardDropBlock && !isAtPucuk -> Recommendation.STRONG_BUY

            // BUY (Scalping Kilat Ritel Ramai): Jika momentum ritel ramai dengan HAKA masif & skor >= 72
            isRetailMomentum && filteredScore >= 72 && (isHakaMasif || orderFlow.deltaVolumeScore >= 18) && !isAtPucuk -> Recommendation.BUY

            // BUY: Saham Akan Naik / threshold >= 75
            filteredScore >= 75 && rrRatio >= 1.3 && !isTrulyBearish && snapshotCount >= 5 && !isAtPucuk -> Recommendation.BUY

            // Early Momentum (Akan Naik) dengan skor >= 68 bisa BUY
            isEarlyMomentum && filteredScore >= 68 && rrRatio >= 1.3 && !isTrulyBearish && !isAtPucuk -> Recommendation.BUY

            // Hysteresis BUY: Jika sebelumnya BUY, pertahankan BUY sampai skor drop < 50 (selama bukan guyuran/distribusi)
            prevWasHighScore && filteredScore >= 50 && !isTrulyBearish && !hardDropBlock -> Recommendation.BUY

            // WATCH: Threshold 48, dengan Hysteresis bertahan sampai 38 sebelum ke AVOID
            filteredScore >= 48 -> Recommendation.WATCH
            prevRec == Recommendation.WATCH && filteredScore >= 38 && !isTrulyBearish -> Recommendation.WATCH

            else -> Recommendation.AVOID
        }

        // ============================================================
        // 7. GAYA TRADING & ALASAN REKOMENDASI AI (TERPRIORITAS & AKURAT)
        // ============================================================
        val style = when {
            isRetailMomentum && (isHakaMasif || orderFlow.deltaVolumeScore >= 18) -> "Scalping Kilat (Ritel Ramai)"
            isSuperMomentum -> "Super Momentum (Ride the Wave)"
            isAtPucuk && prevWasHighScore -> "Trailing Profit (Ride the Gain)"
            isAtPucuk -> "Anti-Pucuk (Tunggu Pullback)"
            isValidPullback -> "Buy on Pullback (Bantalan Support)"
            isEarlyMomentum -> "Early Momentum (Akan Naik)"
            isConfirmedBreakout -> "Buy on Breakout (Tembus Offer Terverifikasi)"
            thickBidWall != null -> entryNote.ifEmpty { "Buy on Bid Support (Tembok Tebal)" }
            orderFlow.hasAccumulation && !isBandarBigDist && !isBandarDist && !isHakiMasif -> "Buy on Weakness (Bandar Akumulasi — Tunggu Pantulan)"
            orderFlow.hasAbsorption && !isBandarBigDist && !isBandarDist -> "Buy on Dip (Penjualan Terserap Kuat)"
            technical.nearestSupport > 0 && currentPrice <= technical.nearestSupport * 1.015 -> "Buy on Support (Pantulan Bawah)"
            else -> entryNote.ifEmpty { "Momentum / Follow Trend" }
        }

        // Super Momentum Stop Loss: Sangat ketat (1-2 tik di bawah entry / max -1.5%)
        val effectiveStopLoss = if (isSuperMomentum) {
            val tightTick = PriceFraction.getTickSize(entryPrice) * 2
            PriceFraction.roundDownToValidTick((entryPrice - tightTick).coerceAtLeast((entryPrice * 0.985).toInt()))
        } else {
            stopLoss
        }

        val reasons = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // 1. ALASAN UTAMA (PRIMARY REASON) — Menjadi alasan nomor 1 di kartu & detail
        val primaryReason = when (recommendation) {
            Recommendation.AVOID -> when {
                isRetailTrap -> "🚨 AVOID: Terdeteksi PERANGKAP RITEL! Pembeli teratas didominasi broker ritel, rawan diguyur bandar!"
                isBandarBigDist || isBandarDist -> "⛔ AVOID: Terdeteksi Distribusi Bandar (${bandarDetector?.accdistStatus ?: "Big Dist"}). Rawan guyuran tajam!"
                isTrapPullback -> "🚨 AVOID: Jebakan Pullback! Penurunan harga disertai tekanan jual/distribusi."
                isAra -> "⛔ AVOID: Saham sudah mentok batas ARA (Auto Reject Atas)."
                isTooExpensive -> "⛔ AVOID: Harga saham > Rp 2.000 (di luar preferensi scalping)."
                isTrulyBearish -> "⛔ AVOID: Tekanan jual (HAKI) mendominasi dan struktur orderbook bearish."
                else -> "⛔ AVOID: Skor momentum & orderflow tidak memenuhi kriteria scalping ($filteredScore/100)."
            }
            Recommendation.WATCH -> when {
                isRetailTrap -> "⚠️ WATCH: Pembelian didominasi broker ritel. Tahan entri sampai ada akumulasi bandar nyata!"
                isRetailMomentum -> "🔍 WATCH: Saham ramai ditransaksikan broker ritel. Tunggu volume HAKA meledak untuk konfirmasi scalping kilat."
                isAtPucuk -> "🛑 WATCH: Saham sudah naik tinggi (+${String.format("%.1f", snapshot.changePercent)}%) di area pucuk! Dilarang FOMO — tunggu pullback."
                isBelowEma50 && technical.ema50 > 0 -> "📉 WATCH: Harga di bawah EMA 50 (${formatPrice(technical.ema50.toInt())}) tren mayor bearish. Menunggu konfirmasi breakout."
                hardDropBlock -> "🔻 WATCH: Harga terkoreksi ${String.format("%.1f", dropFromHigh * 100)}% dari high. Menunggu stabilisasi."
                lowVolumeBlock -> "⏳ WATCH: Likuiditas/volume transaksi belum mengonfirmasi kenaikan."
                snapshotCount < 3 -> "⏳ WATCH: Mengumpulkan data awal ($snapshotCount data) — jangan buru-buru masuk."
                else -> "🔍 WATCH: Pantau antrean bid/offer untuk konfirmasi arah momentum."
            }
            Recommendation.STRONG_BUY, Recommendation.BUY -> when {
                isSuperMomentum -> "🚀 ${recommendation.label}: Super Momentum (Ride the Wave) +${String.format("%.1f", snapshot.changePercent)}%! HAKA masif & bandar akumulasi menuju ARA!"
                isAtPucuk && prevWasHighScore -> "🚀 ${recommendation.label}: Trailing Profit (Ride the Gain) +${String.format("%.1f", snapshot.changePercent)}%! Pasang trailing stop dekat support."
                isEarlyMomentum -> "🚀 ${recommendation.label}: Early Momentum (Akan Naik) +${String.format("%.1f", snapshot.changePercent)}% didukung akumulasi aktif!"
                isRetailMomentum -> "⚡ ${recommendation.label}: Scalping Kilat! Saham ramai didorong broker ritel (+${String.format("%.1f", snapshot.changePercent)}%). Amankan profit 1-3 tick!"
                isValidPullback -> {
                    val modalStr = if ((bandarDetector?.averagePrice ?: 0.0) > 0) " (Modal Bandar: Rp ${formatPrice(bandarDetector!!.averagePrice.toInt())})" else ""
                    "🎯 ${recommendation.label}: Pullback Sehat tertahan di bantalan Support$modalStr! Potensi pantulan kembali."
                }
                isConfirmedBreakout -> "💥 ${recommendation.label}: Breakout Terkonfirmasi! Tembus tembok offer dengan volume masif!"
                isBandarBigAcc || isBandarAcc -> "💎 ${recommendation.label}: Akumulasi masif bandar (${bandarDetector?.accdistStatus}) terdeteksi!"
                thickBidWall != null -> "🛡️ ${recommendation.label}: Tembok bid tebal di Rp ${formatPrice(thickBidWall.price)} menjaga bantalan harga."
                else -> "✅ ${recommendation.label}: Sinyal momentum & teknikal selaras positif ($filteredScore/100)."
            }
        }

        reasons.add(primaryReason)
        if (isRetailMomentum) {
            warnings.add("⚡ Catatan Scalper: Top buyer didominasi broker ritel. Disiplin TP cepat 1-3 tick & pasang trailing stop!")
        }

        // 2. FAKTOR PENDUKUNG ORDERFLOW (Bebas dari kontradiksi Akumulasi vs Distribusi)
        val filteredOfDetails = orderFlow.details.filter { d ->
            val isContradictory = (isBandarBigDist || isBandarDist || isHakiMasif || recommendation == Recommendation.AVOID) &&
                    (d.contains("AKUMULASI", ignoreCase = true) || d.contains("tampung", ignoreCase = true) || d.contains("hajar kanan aktif", ignoreCase = true))
            val isRedundant = d.contains("Mengumpulkan data snapshot pertama", ignoreCase = true) || d.contains("Tekanan beli dan jual relatif seimbang", ignoreCase = true)
            !isContradictory && !isRedundant
        }
        reasons.addAll(filteredOfDetails)

        if (orderFlow.hasAccumulation && !isBandarBigDist && !isBandarDist && !isHakiMasif && recommendation != Recommendation.AVOID) {
            reasons.add("🏦 Terdeteksi pola AKUMULASI: Ritel jual, bandar tampung. Harga kemungkinan dijaga.")
        }

        // 3. FAKTOR TEKNIKAL (Hanya ditampilkan sebagai alasan jika bukan AVOID)
        if (recommendation != Recommendation.AVOID) {
            if (technical.isSupertrendBullish) {
                reasons.add("🚀 Supertrend Bullish (arah tren utama mendukung).")
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
            if (technical.ma5 > 0 && technical.ma9 > 0 && technical.ma20 > 0) {
                if (currentPrice > technical.ma5 && technical.ma5 > technical.ma9 && technical.ma9 > technical.ma20) {
                    reasons.add("📈 Bullish MA Alignment: Harga > MA5 > MA9 > MA20 (Super Momentum).")
                } else if (currentPrice < technical.ma5 && technical.ma5 < technical.ma9 && technical.ma9 < technical.ma20) {
                    warnings.add("📉 Bearish MA Alignment: Harga < MA5 < MA9 < MA20 (Tekanan Jual Kuat).")
                }
            }
            if (spreadTicks <= 1) {
                reasons.add("💧 Spread tipis (1 tick), sangat likuid dan mudah keluar masuk.")
            }
        } else {
            if (!technical.isSupertrendBullish && technical.totalScore > 0) {
                warnings.add("⚠️ Supertrend Bearish. Tren utama dalam tekanan jual.")
            }
            if (technical.ma5 > 0 && technical.ma9 > 0 && technical.ma20 > 0 &&
                currentPrice < technical.ma5 && technical.ma5 < technical.ma9 && technical.ma9 < technical.ma20) {
                warnings.add("📉 Bearish MA Alignment: Harga < MA5 < MA9 < MA20 (Downtrend Terkonfirmasi).")
            }
        }

        // 4. BANDAR DETECTOR & TAPE READING
        if (bandarDetector != null) {
            val avgPriceFormatted = if (bandarDetector.averagePrice > 0) " @ Rp ${formatPrice(bandarDetector.averagePrice.toInt())}" else ""
            when {
                isBandarBigAcc -> reasons.add("💎 Bandar Detector: BIG ACCUMULATION$avgPriceFormatted!")
                isBandarAcc -> reasons.add("📈 Bandar Detector: Akumulasi Positif (${bandarDetector.accdistStatus})$avgPriceFormatted.")
                isBandarBigDist -> warnings.add("🚨 Bandar Detector: BIG DISTRIBUTION! Bandar buang barang masif!")
                isBandarDist -> warnings.add("⚠️ Bandar Detector: Distribusi (${bandarDetector.accdistStatus}). Waspada guyuran!")
            }

            if (bandarDetector.foreignFlow.contains("Big Acc") || bandarDetector.foreignFlowMultiDay.contains("Big Acc")) {
                reasons.add("🌐 Broker Asing Akumulasi Masif (${bandarDetector.foreignFlow.ifEmpty { bandarDetector.foreignFlowMultiDay }})")
            } else if (bandarDetector.foreignFlow.contains("Big Dist") || bandarDetector.foreignFlowMultiDay.contains("Big Dist")) {
                warnings.add("⚠️ Broker Asing Distribusi Besar (${bandarDetector.foreignFlow.ifEmpty { bandarDetector.foreignFlowMultiDay }})")
            }

            if (bandarDetector.smartMoneySummary.contains("SMART MONEY ACCUMULATION")) {
                reasons.add("⚡ Smart Money Flow: Broker Asing serok barang dari ritel!")
            } else if (bandarDetector.smartMoneySummary.contains("SMART MONEY DISTRIBUTION")) {
                warnings.add("🚨 Smart Money Warning: Broker Asing guyur barang ke ritel!")
            }
        }

        if (tapeReading != null) {
            val haka = tapeReading.totalHakaLot
            val haki = tapeReading.totalHakiLot
            if (haka > haki * 1.5) {
                reasons.add("🏃‍♂️ Tape Reading: HAKA masif (${haka}L vs ${haki}L)!")
            } else if (haki > haka * 1.5) {
                warnings.add("🏃‍♂️ Tape Reading: HAKI masif (${haki}L vs ${haka}L)!")
            }
        }

        // 5. TEMBOK BID & OFFER
        if (thickBidWall != null && recommendation != Recommendation.AVOID) {
            reasons.add("🛡️ Tembok bid tebal di Rp ${formatPrice(thickBidWall.price)} (${String.format("%,d", thickBidWall.lot).replace(',', '.')} lot) sebagai penahan harga/support.")
        }
        if (thickWallLevel != null && targetPrice < thickWallLevel.price) {
            reasons.add("🧱 Terdapat tembok offer tebal di Rp ${formatPrice(thickWallLevel.price)}. Waspada area ini.")
        }

        // 6. PERINGATAN RISIKO (WARNINGS) & PROFIL BROKER BANDAR
        if (isRetailTrap) {
            warnings.add("⚠️ PERANGKAP RITEL (FAKE ACC): Pembeli teratas didominasi broker ritel. Rawan guyuran dari bandar!")
        }
        if (isPureBandarAcc) {
            reasons.add("🔥 AKUMULASI BANDAR MURNI: Broker bandar/asing agresif serok barang dari ritel yang panik!")
        }
        if (isScalperBandar) {
            reasons.add("⚡ BANDAR SCALPER AKTIF: Broker scalper (MG/CP/AZ) terdeteksi di top buyer. Gerakan sangat cepat!")
            warnings.add("⚡ Peringatan Scalper Bandar: Volatilitas tinggi, jangan hold lama — disiplin Take Profit kilat!")
        }

        if (isSuperMomentum) {
            warnings.add("⚡ PERINGATAN SUPER MOMENTUM: Kereta cepat menuju ARA. Wajib disiplin Stop Loss ketat di Rp ${formatPrice(effectiveStopLoss)} (1-2 tik)!")
        }
        if (isAtPucuk) {
            warnings.add("🛑 ANTI-PUCUK: Saham sudah naik tinggi (+${String.format("%.1f", snapshot.changePercent)}%) di area High! Dilarang beli di pucuk — tunggu pullback ke support.")
        }
        if (isTrapPullback) {
            warnings.add("🚨 JEBAKAN PULLBACK: Terdeteksi Distribusi Bandar (${bandarDetector?.accdistStatus ?: "HAKI Guyuran"}). Jangan tangkap pisau jatuh!")
        }
        if (isAra) {
            warnings.add("🚨 SAHAM ARA! Tidak bisa dibeli lagi karena sudah limit auto reject atas.")
        } else if (snapshot.changePercent >= 12.0 && !isAtPucuk && !isSuperMomentum) {
            warnings.add("🔥 Saham sudah naik tinggi (+${String.format("%.1f", snapshot.changePercent)}%). Waspadai aksi profit taking / guyuran bandar!")
        }
        if (isBelowEma50 && technical.ema50 > 0) {
            warnings.add("📉 Harga di bawah EMA 50 (${formatPrice(technical.ema50.toInt())}) — tren mayor BEARISH. Sinyal BUY dikunci, max WATCH.")
        }
        if (hardDropBlock) {
            warnings.add("🔻 Harga sudah drop ${String.format("%.1f", dropFromHigh * 100)}% dari high hari ini (${formatPrice(todayHigh.toInt())}). Strong Buy dikunci!")
        } else if (dropFromHigh >= 0.02 && !isValidPullback) {
            warnings.add("⬇️ Harga turun ${String.format("%.1f", dropFromHigh * 100)}% dari high hari ini. Pertimbangkan tunggu stabilisasi.")
        }
        if (!isMarketNotTrading && !isOpeningSession && volumeRatio < 0.7 && technical.volumeRatio > 0) {
            warnings.add("🔇 Volume di bawah rata-rata (${String.format("%.0f", volumeRatio * 100)}% normal). Waspada sinyal palsu.")
        }
        if (orderFlow.hasFakeWall) {
            warnings.add("⚠️ Terdeteksi indikasi Fake Wall pada antrean orderbook.")
        }
        if (entryPrice >= bestOffer) {
            warnings.add("⚡ Entry harga offer (hajar kanan) — risiko slippage. Pertimbangkan antre di ${formatPrice(bestBid)} jika mau aman.")
        }
        if (confidencePenalty < 0) {
            warnings.add("⏳ Sedang mengumpulkan data orderbook ($snapshotCount snapshot). Sinyal belum terkonfirmasi penuh.")
        }

        return StockAnalysis(
            ticker = ticker,
            score = filteredScore,
            recommendation = recommendation,
            lastPrice = currentPrice,
            changePercent = snapshot.changePercent,
            entryPrice = entryPrice,
            targetPrice = targetPrice,
            stopLoss = effectiveStopLoss,
            riskRewardRatio = rrRatio,
            style = style,
            estimatedProfitPercent = profitPercent,
            reasons = reasons,
            warnings = warnings,
            technical = technical,
            orderFlow = orderFlow,
            tapeReading = tapeReading,
            bandarDetector = bandarDetector,
            snapshotCount = snapshotCount,
            lastUpdated = System.currentTimeMillis()
        )
    }

    private fun formatPrice(price: Int): String = String.format("%,d", price).replace(',', '.')
}
