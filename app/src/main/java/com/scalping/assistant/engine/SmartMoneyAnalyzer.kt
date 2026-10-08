package com.scalping.assistant.engine

import com.scalping.assistant.data.models.Candle
import kotlin.math.abs

// ============================================================
// SMART MONEY ANALYZER
// ============================================================
//
// Mendeteksi struktur pasar dan konsep Smart Money (SMC) dari DERET CANDLE nyata.
//
// Berbeda dari data snapshot scanner, analisis di sini butuh urutan candle karena
// pola yang dideteksi memang bersifat berurutan:
//   - Order Block : candle terakhir yang berlawanan arah SEBELUM pergerakan impulsif
//   - BOS         : harga menembus swing terakhir searah tren (kelanjutan)
//   - CHoCH       : harga menembus swing terakhir BERLAWANAN tren (pembalikan)
//   - HH/HL/LH/LL : klasifikasi titik swing puncak & lembah secara berurutan
//
// Semua data candle berasal dari YahooFinanceRepository (delay ~15 menit),
// jadi hasilnya tetap untuk PENYARINGAN, bukan pemicu entry.
// ============================================================

/** Titik swing (puncak atau lembah) hasil deteksi. */
data class SwingPoint(val index: Int, val price: Double, val isHigh: Boolean)

/** Arah struktur pasar berdasarkan rangkaian swing. */
enum class StructureTrend(val label: String) {
    BULLISH("Bullish"),
    BEARISH("Bearish"),
    RANGING("Ranging")
}

/** Hasil analisis struktur pasar. */
data class MarketStructure(
    val trend: StructureTrend,
    val labels: List<String>,
    val lastSwingHigh: Double,
    val lastSwingLow: Double,
    val bosBullish: Boolean,
    val bosBearish: Boolean,
    val chochBullish: Boolean,
    val chochBearish: Boolean
)

/** Zona Order Block yang terdeteksi. */
data class OrderBlock(
    val bullish: Boolean,
    val high: Double,
    val low: Double,
    val index: Int,
    val mitigated: Boolean
) {
    val mid: Double get() = (high + low) / 2.0
}

/** Peran sebuah tarikan Fibonacci. */
enum class FibKind(val label: String) {
    /** Tarikan 1: LOW PREMIER -> HIGH PREMIER. Menentukan ZONA BELI. */
    PREMIER("Premier (zona beli)"),

    /** Tarikan 2: HIGH SEKUNDER -> LOW SEKUNDER. Menentukan ZONA JUAL. */
    SECONDARY("Sekunder (zona jual)")
}

/** Status kesiapan setup Fibonacci dua tarikan. */
enum class FibSetupState(val label: String) {
    /** Pattern premier belum terbentuk (belum ada kenaikan signifikan setelah swing low). */
    NONE("Pola premier belum terbentuk"),

    /** Premier sudah ada, harga belum turun sampai garis 0,5. */
    WAITING("Menunggu koreksi ke 0,5"),

    /** Konfirmasi beli sah: sudah tembus 0,5 dan tidak lebih rendah dari 0,618. */
    VALID("Konfirmasi beli (0,5 - 0,618)"),

    /** Koreksi terlalu dalam, melewati 0,618 -> setup gugur. */
    OVERSHOOT("Gagal - menembus 0,618"),

    /** Harga sudah naik ke zona jual 0,5 - 0,618 dari leg sekunder. */
    IN_SELL("Harga di zona jual 0,5 - 0,618")
}

/** Satu tarikan Fibonacci (dari 0% ke 100%) beserta level yang dipakai. */
data class FibLeg(
    val kind: FibKind,
    /** Harga awal tarikan (level 0%). */
    val startPrice: Double,
    /** Harga akhir tarikan (level 100%). */
    val endPrice: Double,
    /** true bila leg naik (premier), false bila leg turun (sekunder). */
    val isUp: Boolean,
    val level05: Double,
    val level0618: Double,
    val level085: Double,
    val extension1618: Double
) {
    val range: Double get() = abs(endPrice - startPrice)

    /** Batas bawah zona emas (0,5 - 0,618). */
    val zoneLow: Double get() = minOf(level05, level0618)

    /** Batas atas zona emas (0,5 - 0,618). */
    val zoneHigh: Double get() = maxOf(level05, level0618)
}

/**
 * Hasil analisis Fibonacci DUA TARIKAN.
 *
 * Tarikan 1 (premier) untuk ZONA BELI, tarikan 2 (sekunder) untuk ZONA JUAL.
 * Field ringkasan di atas dipertahankan agar pemanggil lama tetap kompatibel.
 */
data class FibLevels(
    val legHigh: Double,
    val legLow: Double,
    val retrace05: Double,
    val retrace0618: Double,
    val retrace085: Double,
    val extension1618: Double,
    val nearestLabel: String,
    val nearestDistancePercent: Double,
    val inGoldenZone: Boolean,
    val inDeepZone: Boolean,
    // --- Dua tarikan (ketentuan pemakaian) ---
    val premierLeg: FibLeg? = null,
    val secondaryLeg: FibLeg? = null,
    val state: FibSetupState = FibSetupState.NONE,
    val inBuyZone: Boolean = false,
    val inSellZone: Boolean = false,
    /** Batas bawah zona beli (0,618 leg premier). */
    val entryLow: Double = 0.0,
    /** Batas atas zona beli (0,5 leg premier). */
    val entryHigh: Double = 0.0,
    /** Batas bawah zona jual (0,5 leg sekunder). */
    val sellLow: Double = 0.0,
    /** Batas atas zona jual (0,618 leg sekunder). */
    val sellHigh: Double = 0.0,
    val rationale: String = ""
)

object SmartMoneyAnalyzer {

    /** Jumlah swing minimum agar struktur/Fibonacci masih bisa dinilai. */
    private const val MIN_SWINGS = 4

    // ============================================================
    // DETEKSI SWING
    // ============================================================

    /**
     * Mencari titik swing dengan syarat: candle ke-i punya high (atau low)
     * yang lebih ekstrem dibanding [lookback] candle di kiri dan kanannya.
     */
    fun findSwings(candles: List<Candle>, lookback: Int = 2): List<SwingPoint> {
        val out = mutableListOf<SwingPoint>()
        if (candles.size < lookback * 2 + 1) return out

        for (i in lookback until candles.size - lookback) {
            var isHigh = true
            var isLow = true
            for (j in 1..lookback) {
                if (candles[i].high <= candles[i - j].high || candles[i].high <= candles[i + j].high) isHigh = false
                if (candles[i].low >= candles[i - j].low || candles[i].low >= candles[i + j].low) isLow = false
            }
            if (isHigh) out.add(SwingPoint(i, candles[i].high, true))
            if (isLow) out.add(SwingPoint(i, candles[i].low, false))
        }
        return out.sortedBy { it.index }
    }

    /**
     * [findSwings] dengan lookback yang diturunkan otomatis bila swing terlalu sedikit.
     *
     * Saham bertick kasar (harga puluhan rupiah dengan satuan 1 rupiah) punya banyak
     * high/low kembar sehingga syarat "lebih ekstrem dari [lookback] candle kiri-kanan"
     * nyaris tidak pernah terpenuhi. Tanpa penyesuaian ini, struktur dan Fibonacci
     * gagal terbentuk pada saham seperti itu.
     */
    private fun findSwingsAdaptive(candles: List<Candle>, preferred: Int): List<SwingPoint> {
        var lookback = preferred.coerceAtLeast(1)
        var swings = findSwings(candles, lookback)
        while (swings.size < MIN_SWINGS && lookback > 1) {
            lookback--
            swings = findSwings(candles, lookback)
        }
        return swings
    }

    // ============================================================
    // STRUKTUR PASAR + BOS / CHoCH
    // ============================================================

    /**
     * Mengklasifikasi struktur menjadi HH/HL/LH/LL lalu menentukan tren, BOS, dan CHoCH.
     */
    fun analyzeStructure(candles: List<Candle>, lookback: Int = 3): MarketStructure {
        val swings = findSwingsAdaptive(candles, lookback)
        val highs = swings.filter { it.isHigh }
        val lows = swings.filter { !it.isHigh }

        val labels = mutableListOf<String>()
        var prevHigh: Double? = null
        var prevLow: Double? = null

        for (sp in swings) {
            if (sp.isHigh) {
                labels.add(
                    when {
                        prevHigh == null -> "H"
                        sp.price > prevHigh -> "HH"
                        else -> "LH"
                    }
                )
                prevHigh = sp.price
            } else {
                labels.add(
                    when {
                        prevLow == null -> "L"
                        sp.price > prevLow -> "HL"
                        else -> "LL"
                    }
                )
                prevLow = sp.price
            }
        }

        val recent = labels.takeLast(4)
        val bulls = recent.count { it == "HH" || it == "HL" }
        val bears = recent.count { it == "LH" || it == "LL" }

        val trend = when {
            bulls > 0 && bears == 0 -> StructureTrend.BULLISH
            bears > 0 && bulls == 0 -> StructureTrend.BEARISH
            bulls > bears -> StructureTrend.BULLISH
            bears > bulls -> StructureTrend.BEARISH
            else -> StructureTrend.RANGING
        }

        val lastSwingHigh = highs.lastOrNull()?.price ?: (candles.maxOfOrNull { it.high } ?: 0.0)
        val lastSwingLow = lows.lastOrNull()?.price ?: (candles.minOfOrNull { it.low } ?: 0.0)
        val lastClose = candles.lastOrNull()?.close ?: 0.0

        var bosBullish = false
        var bosBearish = false
        var chochBullish = false
        var chochBearish = false

        when (trend) {
            StructureTrend.BULLISH -> {
                // Tren naik: tembus swing high = BOS; jatuh di bawah swing low = CHoCH
                bosBullish = lastClose > lastSwingHigh
                chochBearish = lastClose < lastSwingLow
            }
            StructureTrend.BEARISH -> {
                bosBearish = lastClose < lastSwingLow
                chochBullish = lastClose > lastSwingHigh
            }
            StructureTrend.RANGING -> {
                // Belum ada tren jelas, tembusan dianggap awal karakter baru
                chochBullish = lastClose > lastSwingHigh
                chochBearish = lastClose < lastSwingLow
            }
        }

        return MarketStructure(
            trend = trend,
            labels = labels,
            lastSwingHigh = lastSwingHigh,
            lastSwingLow = lastSwingLow,
            bosBullish = bosBullish,
            bosBearish = bosBearish,
            chochBullish = chochBullish,
            chochBearish = chochBearish
        )
    }

    // ============================================================
    // ORDER BLOCK
    // ============================================================

    /**
     * Mencari Order Block: candle berlawanan arah terakhir sebelum gerakan impulsif.
     *
     * Bullish OB = candle bearish yang diikuti lonjakan naik melebihi ambang volatilitas
     * Bearish OB = candle bullish yang diikuti penurunan tajam melebihi ambang volatilitas
     *
     * Ambang sengaja ADAPTIF, bukan angka tetap. Kalibrasi pada candle 15m IDX menunjukkan
     * rata-rata gerak antar saham berbeda jauh (BBRI ~0,8% vs GOTO ~3,7%),
     * sehingga satu angka global membuat saham tenang tidak pernah terdeteksi OB,
     * sementara saham volatil justru kebanjiran sinyal palsu.
     *
     * [mitigated] menandai OB yang sudah pernah disentuh lagi setelah impuls selesai.
     */
    fun findOrderBlocks(
        candles: List<Candle>,
        lookahead: Int = 5,
        impulsePercent: Double = -1.0
    ): List<OrderBlock> {
        val out = mutableListOf<OrderBlock>()
        if (candles.size < lookahead + 2) return out

        val threshold = if (impulsePercent > 0.0) impulsePercent else adaptiveImpulseThreshold(candles, lookahead)

        for (i in 0 until candles.size - lookahead - 1) {
            val c = candles[i]
            if (c.low <= 0.0) continue

            val isBearish = c.close < c.open
            val isBullish = c.close > c.open

            var futureHigh = -Double.MAX_VALUE
            var futureLow = Double.MAX_VALUE
            for (j in i + 1..minOf(i + lookahead, candles.size - 1)) {
                if (candles[j].high > futureHigh) futureHigh = candles[j].high
                if (candles[j].low < futureLow) futureLow = candles[j].low
            }

            if (isBearish && c.high > 0.0) {
                val movePct = (futureHigh - c.high) / c.high * 100.0
                if (movePct >= threshold) {
                    out.add(OrderBlock(true, c.high, c.low, i, isMitigated(candles, i + lookahead, c.high, c.low)))
                }
            }
            if (isBullish) {
                val movePct = (c.low - futureLow) / c.low * 100.0
                if (movePct >= threshold) {
                    out.add(OrderBlock(false, c.high, c.low, i, isMitigated(candles, i + lookahead, c.high, c.low)))
                }
            }
        }
        return out
    }

    /**
     * Ambang = 0,8x rata-rata gerakan terbesar selama [lookahead] candle, dibatasi 0,5%-3,0%
     * supaya tetap wajar baik untuk saham tenang maupun saham sangat volatil.
     */
    private fun adaptiveImpulseThreshold(candles: List<Candle>, lookahead: Int): Double {
        if (candles.size < lookahead + 1) return 0.8

        var sum = 0.0
        var count = 0
        for (i in 0 until candles.size - lookahead) {
            val base = candles[i].low
            if (base <= 0.0) continue
            var hi = -Double.MAX_VALUE
            for (j in i + 1..i + lookahead) {
                if (candles[j].high > hi) hi = candles[j].high
            }
            if (hi > -Double.MAX_VALUE) {
                sum += (hi - base) / base * 100.0
                count++
            }
        }
        if (count == 0) return 0.8
        return ((sum / count) * 0.8).coerceIn(0.5, 3.0)
    }

    /**
     * OB dianggap termitigasi bila setelah periode impulsif berlalu, harga pernah
     * kembali menyentuh zonanya. Periode impulsif sengaja dilewati agar pergerakan
     * yang MENCIPTAKAN OB tidak dihitung sebagai pengambilan kembali zona.
     */
    private fun isMitigated(candles: List<Candle>, fromIndex: Int, high: Double, low: Double): Boolean {
        for (j in fromIndex until candles.size) {
            if (candles[j].low <= high && candles[j].high >= low) return true
        }
        return false
    }

    // ============================================================
    // PIVOT ZIGZAG (acuan HIGHT tarikan Fibonacci)
    // ============================================================

    /**
     * Mencari titik balik (pivot) bergaya **ZigZag**: puncak/lembah yang sudah TERKONFIRMASI
     * berbalik minimal sejauh [thresholdPercent], DITAMBAH satu titik ekstrem yang masih
     * berjalan di ujung kanan.
     *
     * Kenapa tidak cukup [findSwings]? Fungsi itu menuntut candle puncak lebih tinggi dari
     * candle di KANANNYA, sehingga puncak TERBARU (mis. high hari ini) selalu belum terdeteksi
     * selama harga belum turun. Akibatnya Fibonacci sempat mengunci high lama (mis. 136)
     * padahal harga sudah mencetak high baru (mis. 150), sehingga ZONA BELI (tarikan 1) dan
     * ZONA JUAL (tarikan 2) sama-sama tertinggal. ZigZag di sini selalu memasukkan ekstrem
     * yang sedang berjalan hingga candle terakhir, jadi kedua tarikan otomatis terangkat
     * begitu harga membuat high/lembah baru.
     */
    fun findZigZagPivots(candles: List<Candle>, thresholdPercent: Double): List<SwingPoint> {
        if (candles.size < 2) return emptyList()
        val th = thresholdPercent.coerceAtLeast(0.01)
        val out = mutableListOf<SwingPoint>()

        var dir = 0            // 0 = belum ada arah, 1 = leg naik, -1 = leg turun
        var maxIdx = 0
        var maxPrice = candles[0].high
        var minIdx = 0
        var minPrice = candles[0].low

        for (k in 1 until candles.size) {
            val h = candles[k].high
            val l = candles[k].low
            if (h > maxPrice) { maxPrice = h; maxIdx = k }
            if (l < minPrice) { minPrice = l; minIdx = k }

            val dropFromMax = if (maxPrice > 0.0) (maxPrice - l) / maxPrice * 100.0 else 0.0
            val riseFromMin = if (minPrice > 0.0) (h - minPrice) / minPrice * 100.0 else 0.0

            when (dir) {
                0 -> {
                    // Belum ada arah: tentukan dari gerakan pertama yang melewati ambang.
                    if (riseFromMin >= th && minIdx < maxIdx) {
                        out.add(SwingPoint(minIdx, minPrice, false))
                        dir = 1
                        maxIdx = k; maxPrice = h
                    } else if (dropFromMax >= th && maxIdx < minIdx) {
                        out.add(SwingPoint(maxIdx, maxPrice, true))
                        dir = -1
                        minIdx = k; minPrice = l
                    }
                }
                1 -> {
                    if (dropFromMax >= th) {
                        out.add(SwingPoint(maxIdx, maxPrice, true))
                        dir = -1
                        minIdx = k; minPrice = l
                    }
                }
                else -> {
                    if (riseFromMin >= th) {
                        out.add(SwingPoint(minIdx, minPrice, false))
                        dir = 1
                        maxIdx = k; maxPrice = h
                    }
                }
            }
        }

        // Titik ekstrem yang MASIH BERJALAN di ujung kanan (mis. high hari ini) — inilah
        // kunci agar tarikan tidak tertinggal dari harga terbaru.
        val lastIdx = out.lastOrNull()?.index
        when (dir) {
            1 -> if (maxIdx != lastIdx) out.add(SwingPoint(maxIdx, maxPrice, true))
            -1 -> if (minIdx != lastIdx) out.add(SwingPoint(minIdx, minPrice, false))
            else -> if (out.isEmpty()) {
                if (maxIdx >= minIdx) out.add(SwingPoint(maxIdx, maxPrice, true))
                else out.add(SwingPoint(minIdx, minPrice, false))
            }
        }
        return out.sortedBy { it.index }
    }

    /**
     * Ambang pembalikan ZigZag = 1,5x rata-rata rentang candle (high-low), dibatasi 0,6%-3,0%.
     * Rentang candle (bukan gerak close-ke-close) dipakai supaya noise intrabar kecil tidak
     * memecah satu ayunan menjadi banyak pivot palsu.
     */
    private fun zigZagReversalThreshold(window: List<Candle>): Double {
        var sum = 0.0
        var count = 0
        for (c in window) {
            if (c.low <= 0.0) continue
            sum += (c.high - c.low) / c.low * 100.0
            count++
        }
        if (count == 0) return 1.0
        return ((sum / count) * 1.5).coerceIn(0.6, 3.0)
    }

    // ============================================================
    // FIBONACCI
    // ============================================================

    // ============================================================
    // FIBONACCI (DUA TARIKAN)
    // ============================================================
    //
    // Ketentuan pemakaian yang diikuti:
    //   1. Terbentuk POLA PREMIER: kenaikan signifikan setelah swing low.
    //   2. Terbentuk POLA SEKUNDER: koreksi setelah pola premier terbentuk.
    //   3. Tarikan 1 = LOW PREMIER -> HIGH PREMIER  => ZONA BELI.
    //   4. Entry sah bila harga sudah tembus KE BAWAH garis 0,5 DAN
    //      TIDAK LEBIH RENDAH dari garis 0,618.
    //   5. Tarikan 2 = HIGH SEKUNDER -> LOW SEKUNDER => ZONA JUAL
    //      pada rentang 0,5 sampai 0,618.

    /**
     * Menghitung Fibonacci DUA TARIKAN dari candle.
     *
     * Tarikan pertama dipakai untuk mencari area beli, tarikan kedua untuk area jual.
     *
     * Penting: high premier diambil dari pivot **ZigZag** ([findZigZagPivots]) agar high
     * TERBARU — termasuk yang masih berjalan di candle terakhir — langsung dipakai. Dengan
     * begitu kedua tarikan tidak lagi tertinggal di high lama saat harga mencetak rekor baru
     * hari ini. Titik low tetap memakai deteksi swing karena sudah terbukti akurat.
     *
     * Bila pola premier belum terbentuk, hasilnya berstatus [FibSetupState.NONE]
     * dan skor Fibonacci tidak diberikan.
     */
    fun computeFibonacci(candles: List<Candle>, windowSize: Int = 120): FibLevels {
        val window = candles.takeLast(windowSize)
        if (window.size < 10) return emptyFib(window)

        val close = window.last().close
        if (close <= 0.0) return emptyFib(window)

        // HIGH dari pivot ZigZag supaya high TERBARU (mis. high hari ini) ikut terbaca,
        // bukan hanya high lama yang sudah "terkonfirmasi" turun. LOW tetap dari deteksi
        // swing seperti semula karena titik bawah sudah terbukti benar.
        val swingLows = findSwingsAdaptive(window, 3).filter { !it.isHigh }
        val zigzagPivots = findZigZagPivots(window, zigZagReversalThreshold(window))
        val zigzagHighs = zigzagPivots.filter { it.isHigh }
        val zigzagLows = zigzagPivots.filter { !it.isHigh }
        if (zigzagHighs.isEmpty() || swingLows.isEmpty()) return emptyFib(window)

        // --- 1 & 3: POLA PREMIER -> tarikan 1 (zona beli) ---
        val premierPair = findPremierLeg(window, zigzagHighs, swingLows) ?: return emptyFib(window)
        val premierLeg = makeLeg(
            FibKind.PREMIER,
            window[premierPair.first].low,
            window[premierPair.second].high,
            isUp = true
        )

        // --- 2 & 5: POLA SEKUNDER -> tarikan 2 (zona jual) ---
        // Low sekunder = titik koreksi setelah high premier. Bila koreksinya SUDAH terjadi,
        // dipakai titik terendah sesudah high (perilaku lama). Bila high premier masih
        // BERJALAN (mis. high hari ini, belum ada koreksi sesudahnya), zona jual diukur dari
        // DASAR leg berjalan — pivot low terakhir SEBELUM high — supaya target profit ikut
        // terangkat bersama high baru, bukan tertinggal di high lama.
        val afterHigh = window.drop(premierPair.second + 1)
        val hasCorrectionAfterHigh = zigzagLows.any { it.index > premierPair.second }
        val sekunderLow: Double? = if (hasCorrectionAfterHigh) {
            afterHigh.minOfOrNull { it.low }?.takeIf { it > 0.0 }
        } else {
            zigzagLows.lastOrNull { it.index < premierPair.second }?.price
                ?: afterHigh.minOfOrNull { it.low }?.takeIf { it > 0.0 }
        }
        val secondaryLeg = sekunderLow
            ?.let { makeLeg(FibKind.SECONDARY, premierLeg.endPrice, it, isUp = false) }

        // --- 4: konfirmasi area beli ---
        val entryHigh = premierLeg.level05    // batas atas zona beli
        val entryLow = premierLeg.level0618   // batas bawah zona beli
        val lowestAfterHigh = afterHigh.minOfOrNull { it.low } ?: close

        val dippedBelow05 = lowestAfterHigh <= entryHigh   // sudah tembus ke bawah 0,5
        val heldAbove0618 = lowestAfterHigh >= entryLow    // tidak lebih rendah dari 0,618

        val inBuyZone = close in entryLow..entryHigh
        val inSellZone = secondaryLeg != null && close in secondaryLeg.zoneLow..secondaryLeg.zoneHigh

        val state = when {
            inSellZone -> FibSetupState.IN_SELL
            !dippedBelow05 -> FibSetupState.WAITING
            !heldAbove0618 -> FibSetupState.OVERSHOOT
            else -> FibSetupState.VALID
        }

        // Zona dalam = antara 0,618 dan 0,85 (retracement makin dalam).
        val deepLow = minOf(premierLeg.level0618, premierLeg.level085)
        val deepHigh = maxOf(premierLeg.level0618, premierLeg.level085)
        val inDeep = close in deepLow..deepHigh

        // Level terdekat pada tarikan premier (dipakai untuk label ringkas di kartu).
        val levels = listOf(
            "0.5" to premierLeg.level05,
            "0.618" to premierLeg.level0618,
            "0.85" to premierLeg.level085,
            "1.618" to premierLeg.extension1618
        )
        val nearest = levels.minByOrNull { abs(it.second - close) } ?: ("-" to close)
        val distPct = abs(nearest.second - close) / close * 100.0

        val rationale = buildString {
            append(state.label)
            if (secondaryLeg != null) {
                append(" · jual ${fmtPrice(secondaryLeg.zoneLow)}-${fmtPrice(secondaryLeg.zoneHigh)}")
            }
        }

        return FibLevels(
            legHigh = premierLeg.endPrice,
            legLow = premierLeg.startPrice,
            retrace05 = premierLeg.level05,
            retrace0618 = premierLeg.level0618,
            retrace085 = premierLeg.level085,
            extension1618 = premierLeg.extension1618,
            nearestLabel = nearest.first,
            nearestDistancePercent = distPct,
            inGoldenZone = inBuyZone,
            inDeepZone = inDeep,
            premierLeg = premierLeg,
            secondaryLeg = secondaryLeg,
            state = state,
            inBuyZone = inBuyZone,
            inSellZone = inSellZone,
            entryLow = entryLow,
            entryHigh = entryHigh,
            sellLow = secondaryLeg?.zoneLow ?: 0.0,
            sellHigh = secondaryLeg?.zoneHigh ?: 0.0,
            rationale = rationale
        )
    }

    /**
     * Mencari pola premier: pasangan (indeks titik terendah, indeks high) terakhir dengan
     * titik terendah SEBELUM high dan kenaikan yang cukup signifikan.
     *
     * [highs] sebaiknya berasal dari pivot ZigZag ([findZigZagPivots]) agar high terbaru/hari
     * ini ikut dipertimbangkan; [lows] tetap dari deteksi swing.
     *
     * Ambang "signifikan" memakai ambang adaptif yang sama seperti Order Block,
     * supaya definisi kenaikan penting tetap menyesuaikan volatilitas saham.
     */
    private fun findPremierLeg(
        window: List<Candle>,
        highs: List<SwingPoint>,
        lows: List<SwingPoint>
    ): Pair<Int, Int>? {
        val minImpulse = adaptiveImpulseThreshold(window, 5)
        for (high in highs.asReversed()) {
            if (high.index <= 0) continue

            // Titik low premier: utamakan swing low, dan bila tidak ada swing low
            // sebelum swing high, pakai low candle terendah pada rentang tersebut.
            // Cadangan ini penting untuk saham bertick kasar yang swing low-nya
            // jarang terbentuk (mis. GOTO di harga puluhan rupiah).
            val priorLows = lows.filter { it.index < high.index }
            val lowIndex: Int
            val lowPrice: Double
            if (priorLows.isNotEmpty()) {
                val lowest = priorLows.minByOrNull { it.price } ?: continue
                lowIndex = lowest.index
                lowPrice = lowest.price
            } else {
                val sub = window.subList(0, high.index)
                val idx = sub.indices.minByOrNull { sub[it].low } ?: continue
                lowIndex = idx
                lowPrice = sub[idx].low
            }

            if (lowPrice <= 0.0) continue
            val impulse = (high.price - lowPrice) / lowPrice * 100.0
            if (impulse >= minImpulse) return lowIndex to high.index
        }
        return null
    }

    /**
     * Membuat satu tarikan Fibonacci.
     *
     * Rumus level disusun relatif terhadap [endPrice] sehingga berlaku untuk
     * leg naik (start = low premier) maupun leg turun (start = high sekunder).
     */
    private fun makeLeg(kind: FibKind, start: Double, end: Double, isUp: Boolean): FibLeg {
        val range = end - start
        return FibLeg(
            kind = kind,
            startPrice = start,
            endPrice = end,
            isUp = isUp,
            level05 = end - 0.5 * range,
            level0618 = end - 0.618 * range,
            level085 = end - 0.85 * range,
            extension1618 = if (isUp) start + 1.618 * range else start - 1.618 * range
        )
    }

    /** Hasil kosong saat pola premier belum bisa dibentuk dari data yang ada. */
    private fun emptyFib(window: List<Candle> = emptyList()): FibLevels {
        val hi = window.maxOfOrNull { it.high } ?: 0.0
        val lo = window.minOfOrNull { it.low } ?: 0.0
        return FibLevels(
            legHigh = hi,
            legLow = lo,
            retrace05 = hi,
            retrace0618 = hi,
            retrace085 = hi,
            extension1618 = hi,
            nearestLabel = "-",
            nearestDistancePercent = 100.0,
            inGoldenZone = false,
            inDeepZone = false,
            state = FibSetupState.NONE,
            rationale = "Pola premier belum terbentuk (belum ada kenaikan signifikan setelah swing low)."
        )
    }

    private fun fmtPrice(value: Double): String =
        if (value >= 100) String.format("%,.0f", value).replace(',', '.')
        else String.format("%.1f", value)
}
