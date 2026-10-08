package com.scalping.assistant.engine

import com.scalping.assistant.data.models.Candle
import com.scalping.assistant.data.repository.CandleTimeframe
import com.scalping.assistant.data.repository.ScreenerStock
import kotlin.math.roundToInt

// ============================================================
// SCREENER SCORING ENGINE (Khusus Tab Screener)
// ============================================================
//
// MENGGANTIKAN penilaian preset-based (lolos/tidak lolos) menjadi SKOR 0-100.
//
// PENILAIAN INI SENGAJA DIBEDAKAN dari ScoringEngine.kt milik aplikasi:
//   - ScoringEngine.kt  : berbasis ORDERBOOK (bid/offer, delta volume, tembok)
//                         + konteks sesi bursa + bandar detector.
//   - ScreenerScoringEngine : murni berbasis INDIKATOR & STRUKTUR PASAR.
//
// BOBOTNYA TIDAK TETAP — mengikuti gaya trading yang dipilih:
//   - Daytrade      : Volume & Bandarmology dominan (likuiditas menentukan),
//                     Moving Average kecil (tren panjang tidak relevan untuk
//                     posisi beberapa jam).
//   - Swing 3-10 hr : Moving Average & Fibonacci dominan (tren yang menopang
//                     harga selama beberapa hari), volume pemanis saja.
//
// Setiap komponen dihitung sebagai RASIO (0..1) lebih dulu, baru dikalikan
// bobot gayanya. Dengan cara ini angka 0-100 tetap bisa dibandingkan antar
// emiten meski bobotnya berbeda antar gaya.
//
// Hasil diurutkan dari skor tertinggi.
// ============================================================

/** Rincian kontribusi tiap komponen, agar alasan skor bisa ditampilkan transparan. */
data class ScoreComponent(
    val name: String,
    val earned: Int,
    val max: Int,
    val note: String
) {
    val ratio: Double get() = if (max == 0) 0.0 else earned.toDouble() / max
}

/** Hasil skor lengkap untuk satu emiten. */
data class ScreenerScore(
    val total: Int,
    val components: List<ScoreComponent>,
    val grade: String,
    val gradeEmoji: String,
    val structure: MarketStructure?,
    val fibonacci: FibLevels?,
    val hasBullishOrderBlock: Boolean,
    val hasBearishOrderBlock: Boolean,
    val summary: String,
    /** Timeframe candle yang dipakai untuk komponen Fibonacci & SMC. */
    val timeframe: CandleTimeframe = CandleTimeframe.BULAN3,
    /** Gaya trading yang dipakai saat skor ini dihitung (menentukan bobot). */
    val style: TradingStyle = TradingStyle.SWING
)

object ScreenerScoringEngine {

    /**
     * Menghitung skor emiten sesuai gaya trading.
     *
     * @param stock data snapshot dari TradingView Scanner
     * @param candles candle nyata (boleh kosong; komponen SMC/Fib/Struktur akan dilewati)
     * @param moneyFlow nilai MoneyFlow (0-100) dari TradingView; 0 bila tidak tersedia
     * @param timeframe timeframe candle yang dipakai untuk Fibonacci & struktur
     * @param style gaya trading yang menentukan bobot tiap komponen
     */
    fun score(
        stock: ScreenerStock,
        candles: List<Candle> = emptyList(),
        moneyFlow: Double = 0.0,
        timeframe: CandleTimeframe = CandleTimeframe.BULAN3,
        style: TradingStyle = TradingStyle.SWING
    ): ScreenerScore {
        val components = mutableListOf<ScoreComponent>()
        val reasons = mutableListOf<String>()

        // ---------- 1. VOLUME / RVOL ----------
        components += scoreVolume(stock, style.wVolume, style, reasons)

        // ---------- 2. BANDARMOLOGY ----------
        components += scoreBandarmology(stock, moneyFlow, style.wBandar, reasons)

        // ---------- 3. MACD ----------
        components += scoreMacd(stock, style.wMacd, reasons)

        // ---------- 4. RSI 14 ----------
        components += scoreRsi(stock, style.wRsi, style, reasons)

        // ---------- 5. MOVING AVERAGE ----------
        components += scoreMovingAverage(stock, style.wMa, reasons)

        // ---------- 6-8. BERBASIS CANDLE ----------
        val structure = if (candles.size >= 15) SmartMoneyAnalyzer.analyzeStructure(candles) else null
        val fibonacci = if (candles.size >= 10) {
            SmartMoneyAnalyzer.computeFibonacci(candles, timeframe.analysisWindow)
        } else null
        val orderBlocks = if (candles.size >= 10) SmartMoneyAnalyzer.findOrderBlocks(candles) else emptyList()

        val lastClose = candles.lastOrNull()?.close ?: stock.close
        val bullishOb = orderBlocks.lastOrNull { it.bullish }
        val bearishOb = orderBlocks.lastOrNull { !it.bullish }

        components += scoreFibonacci(fibonacci, style.wFib, reasons)
        components += scoreSmc(structure, bullishOb, bearishOb, lastClose, style.wSmc, reasons)
        components += scoreStructure(structure, style.wStructure, reasons)

        val total = components.sumOf { it.earned }.coerceIn(0, 100)

        return ScreenerScore(
            total = total,
            components = components,
            grade = gradeFor(total),
            gradeEmoji = emojiFor(total),
            structure = structure,
            fibonacci = fibonacci,
            hasBullishOrderBlock = bullishOb != null,
            hasBearishOrderBlock = bearishOb != null,
            summary = reasons.take(3).joinToString(" · ").ifEmpty { "Skor dari indikator snapshot." },
            timeframe = timeframe,
            style = style
        )
    }

    /**
     * Mengubah rasio 0..1 menjadi poin sesuai bobot komponen.
     *
     * Semua komponen memakai jalur ini supaya perbandingan antar gaya trading
     * konsisten: yang berubah hanya bobotnya, bukan cara menilai.
     */
    private fun points(ratio: Double, weight: Int): Int =
        (ratio.coerceIn(0.0, 1.0) * weight).roundToInt().coerceIn(0, weight)

    // ============================================================
    // 1. VOLUME / RVOL
    // ============================================================
    private fun scoreVolume(
        s: ScreenerStock,
        weight: Int,
        style: TradingStyle,
        reasons: MutableList<String>
    ): ScoreComponent {
        val rvol = s.relativeVolume
        val ratio = when {
            rvol >= 5.0 -> 1.0
            rvol >= 3.0 -> 0.8
            rvol >= 2.0 -> 0.6
            rvol >= 1.5 -> 0.4
            rvol >= 1.0 -> 0.2
            else -> 0.0
        }

        // Catatan disesuaikan gaya: daytrade menilai volume sebagai syarat likuiditas,
        // swing menilainya sebagai konfirmasi minat pasar.
        val prefix = if (style == TradingStyle.DAYTRADE) "Likuiditas" else "Minat pasar"
        val note = when {
            rvol >= 5.0 -> "$prefix: RVOL ${fmt(rvol)}x (sangat ramai)"
            rvol >= 3.0 -> "$prefix: RVOL ${fmt(rvol)}x (ramai)"
            rvol >= 2.0 -> "$prefix: RVOL ${fmt(rvol)}x (di atas rata-rata)"
            rvol >= 1.5 -> "$prefix: RVOL ${fmt(rvol)}x (sedikit di atas rata-rata)"
            rvol >= 1.0 -> "$prefix: RVOL ${fmt(rvol)}x (normal)"
            else -> "$prefix: RVOL ${fmt(rvol)}x (sepi)"
        }
        if (ratio >= 0.6) reasons += "Volume melonjak ${fmt(rvol)}x"
        return ScoreComponent("Volume / RVOL", points(ratio, weight), weight, note)
    }

    // ============================================================
    // 2. BANDARMOLOGY
    // ============================================================
    private fun scoreBandarmology(
        s: ScreenerStock,
        moneyFlow: Double,
        weight: Int,
        reasons: MutableList<String>
    ): ScoreComponent {
        // MoneyFlow 0-100: di atas 50 = aliran masuk, di bawah 50 = keluar.
        val hasMf = moneyFlow > 0.0
        val mfRatio = when {
            !hasMf -> 0.0
            moneyFlow >= 80 -> 0.80
            moneyFlow >= 65 -> 0.65
            moneyFlow >= 55 -> 0.45
            moneyFlow >= 45 -> 0.20
            else -> 0.0
        }

        // Rating teknikal sebagai konfirmasi arah aliran dana (porsi kecil).
        val ratingRatio = when {
            s.recommendAll >= 0.5 -> 0.20
            s.recommendAll >= 0.3 -> 0.13
            s.recommendAll >= 0.0 -> 0.07
            else -> 0.0
        }

        val ratio = mfRatio + ratingRatio
        val note = if (hasMf) {
            when {
                moneyFlow >= 65 -> "MoneyFlow ${moneyFlow.toInt()} (akumulasi)"
                moneyFlow >= 50 -> "MoneyFlow ${moneyFlow.toInt()} (aliran masuk)"
                moneyFlow >= 35 -> "MoneyFlow ${moneyFlow.toInt()} (netral)"
                else -> "MoneyFlow ${moneyFlow.toInt()} (distribusi)"
            }
        } else {
            "MoneyFlow tidak tersedia"
        }
        if (mfRatio >= 0.65) reasons += "Bandar akumulasi (MF ${moneyFlow.toInt()})"
        return ScoreComponent("Bandarmology", points(ratio, weight), weight, note)
    }

    // ============================================================
    // 3. MACD
    // ============================================================
    private fun scoreMacd(s: ScreenerStock, weight: Int, reasons: MutableList<String>): ScoreComponent {
        // Histogram dihitung sendiri karena kolom MACD.histogram mengembalikan null.
        val histogram = s.macdMacd - s.macdSignal
        val ratio = when {
            histogram > 0 && s.macdMacd > 0 -> 1.0
            histogram > 0 -> 0.7
            histogram > -0.5 -> 0.2
            else -> 0.0
        }
        val note = when {
            histogram > 0 && s.macdMacd > 0 -> "MACD di atas signal & di atas nol (bullish kuat)"
            histogram > 0 -> "MACD memotong ke atas signal"
            histogram > -0.5 -> "MACD hampir berpotongan"
            else -> "MACD di bawah signal (bearish)"
        }
        if (ratio >= 1.0) reasons += "MACD bullish"
        return ScoreComponent("MACD", points(ratio, weight), weight, note)
    }

    // ============================================================
    // 4. RSI 14
    // ============================================================
    //
    // Penilaian RSI SENGAJA BERBEDA antar gaya:
    //   - Daytrade : RSI sangat rendah dianggap peluang pantulan cepat (bagus),
    //                karena posisi hanya sebentar.
    //   - Swing    : RSI sangat rendah berarti tren turun masih berlangsung
    //                (kurang bagus), karena harga perlu naik selama 3-10 hari.
    private fun scoreRsi(
        s: ScreenerStock,
        weight: Int,
        style: TradingStyle,
        reasons: MutableList<String>
    ): ScoreComponent {
        val rsi = s.rsi

        val (ratio, note) = if (style == TradingStyle.DAYTRADE) {
            when {
                rsi >= 78 -> 0.0 to "RSI $rsi (terlalu panas untuk intraday)"
                rsi >= 60 -> 1.0 to "RSI $rsi (momentum kuat, belum jenuh)"
                rsi >= 50 -> 0.8 to "RSI $rsi (condong naik)"
                rsi >= 45 -> 0.5 to "RSI $rsi (netral)"
                rsi >= 30 -> 0.6 to "RSI $rsi (lemah, kandidat pantulan)"
                else -> 0.8 to "RSI $rsi (oversold, kandidat pantulan kuat)"
            }
        } else {
            when {
                rsi >= 78 -> 0.0 to "RSI $rsi (terlalu panas, rawan koreksi)"
                rsi >= 60 -> 1.0 to "RSI $rsi (momentum sehat, masih ada ruang)"
                rsi >= 50 -> 0.85 to "RSI $rsi (condong naik)"
                rsi >= 45 -> 0.7 to "RSI $rsi (netral, cocok untuk entry)"
                rsi >= 35 -> 0.6 to "RSI $rsi (pullback dalam tren)"
                rsi >= 30 -> 0.4 to "RSI $rsi (lemah)"
                else -> 0.2 to "RSI $rsi (tren turun, belum layak swing)"
            }
        }

        if (ratio >= 0.8 && rsi < 78) reasons += "RSI $rsi sehat"
        return ScoreComponent("RSI 14", points(ratio, weight), weight, note)
    }

    // ============================================================
    // 5. MOVING AVERAGE
    // ============================================================
    private fun scoreMovingAverage(s: ScreenerStock, weight: Int, reasons: MutableList<String>): ScoreComponent {
        val close = s.close
        // Rasio dari 4 pemeriksaan berbobot (total 1.0 sebelum dibulatkan).
        var ratio = 0.0

        // Susunan EMA menanjak = konfirmasi tren jangka pendek.
        if (s.isBullishEmaStack) ratio += 0.33

        // Harga di atas EMA20 = tren menengah naik.
        if (s.ema20 > 0.0 && close > s.ema20) ratio += 0.27

        // Harga di atas EMA50 = tren lebih kuat.
        if (s.ema50 > 0.0 && close > s.ema50) ratio += 0.20

        // Harga di atas EMA200 = tren mayor naik.
        if (s.ema200 > 0.0 && close > s.ema200) ratio += 0.20

        val note = when {
            ratio >= 0.8 -> "Harga di atas EMA20/50/200, susunan menanjak (tren kuat)"
            ratio >= 0.5 -> "Harga di atas mayoritas EMA"
            ratio >= 0.25 -> "Sebagian EMA terlewati"
            else -> "Harga tertahan di bawah EMA"
        }
        if (ratio >= 0.7) reasons += "Tren EMA menanjak"
        return ScoreComponent("Moving Average", points(ratio, weight), weight, note)
    }

    // ============================================================
    // 6. FIBONACCI — DUA TARIKAN
    // ============================================================
    //
    // Tarikan 1 (low premier -> high premier) = area BELI.
    // Tarikan 2 (high sekunder -> low sekunder) = area JUAL.
    // Poin penuh hanya bila konfirmasi beli sah: sudah tembus 0,5 dan
    // tidak lebih rendah dari 0,618 pada tarikan premier.
    private fun scoreFibonacci(fib: FibLevels?, weight: Int, reasons: MutableList<String>): ScoreComponent {
        if (fib == null) return ScoreComponent("Fibonacci", 0, weight, "Data candle tidak cukup")

        val entry = "beli ${fmt(fib.entryHigh)}-${fmt(fib.entryLow)}"
        val sell = "jual ${fmt(fib.sellHigh)}-${fmt(fib.sellLow)}"

        val (ratio, note) = when (fib.state) {
            FibSetupState.VALID -> 1.0 to "Konfirmasi beli di zona 0,5-0,618 ($entry)"
            FibSetupState.WAITING -> 0.5 to "Pola premier ada, menunggu koreksi ke 0,5 ($entry)"
            FibSetupState.IN_SELL -> 0.2 to (if (fib.secondaryLegProvisional)
                "Harga sudah mendekati proyeksi target $sell (pola sekunder belum terbentuk — zona ini proyeksi, bukan zona jual sekunder yang sah)"
            else
                "Harga sudah di zona jual $sell (terlambat untuk entry)")
            FibSetupState.OVERSHOOT -> 0.1 to "Koreksi menembus 0,618 — setup beli gugur"
            FibSetupState.NONE -> 0.0 to "Pola premier belum terbentuk"
        }

        if (ratio >= 0.5) reasons += fib.rationale
        return ScoreComponent("Fibonacci", points(ratio, weight), weight, note)
    }

    // ============================================================
    // 7. SMART MONEY CONCEPT (Order Block, BOS, CHoCH)
    // ============================================================
    private fun scoreSmc(
        structure: MarketStructure?,
        bullishOb: OrderBlock?,
        bearishOb: OrderBlock?,
        lastClose: Double,
        weight: Int,
        reasons: MutableList<String>
    ): ScoreComponent {
        if (structure == null) return ScoreComponent("Smart Money (OB/BOS/CHoCH)", 0, weight, "Data candle tidak cukup")

        var ratio = 0.0
        val notes = mutableListOf<String>()

        // BOS searah tren = kelanjutan tren, sinyal paling kuat.
        if (structure.bosBullish) {
            ratio += 0.47
            notes += "BOS bullish (tembus swing high)"
        }
        if (structure.bosBearish) {
            ratio += 0.13
            notes += "BOS bearish"
        }

        // CHoCH = awal pembalikan arah.
        if (structure.chochBullish) {
            ratio += 0.33
            notes += "CHoCH bullish (pembalikan naik)"
        }
        if (structure.chochBearish) {
            ratio += 0.07
            notes += "CHoCH bearish"
        }

        // Harga berada di dalam zona Order Block bullish = area entry berdiskon.
        if (bullishOb != null && lastClose in bullishOb.low..bullishOb.high) {
            ratio += 0.20
            notes += "Harga di Order Block bullish"
        } else if (bullishOb != null && !bullishOb.mitigated) {
            ratio += 0.13
            notes += "Ada Order Block bullish belum tersentuh"
        }

        // Order Block bearish yang belum termitigasi = tembok resisten di atas.
        if (bearishOb != null && !bearishOb.mitigated) {
            ratio -= 0.07
            notes += "Ada Order Block bearish di atas"
        }

        val clamped = ratio.coerceIn(0.0, 1.0)
        if (clamped >= 0.5) reasons += notes.firstOrNull() ?: "Struktur SMC mendukung"

        return ScoreComponent(
            "Smart Money (OB/BOS/CHoCH)",
            points(clamped, weight),
            weight,
            notes.joinToString("; ").ifEmpty { "Tidak ada sinyal SMC" }
        )
    }

    // ============================================================
    // 8. STRUKTUR PASAR (HH/HL vs LH/LL)
    // ============================================================
    private fun scoreStructure(structure: MarketStructure?, weight: Int, reasons: MutableList<String>): ScoreComponent {
        if (structure == null) return ScoreComponent("Struktur Pasar", 0, weight, "Data candle tidak cukup")

        val (ratio, note) = when (structure.trend) {
            StructureTrend.BULLISH -> 1.0 to "HH + HL terdeteksi (tren naik)"
            StructureTrend.RANGING -> 0.4 to "Struktur menyamping (belum jelas)"
            StructureTrend.BEARISH -> 0.0 to "LH + LL terdeteksi (tren turun)"
        }
        if (ratio >= 1.0) reasons += "Struktur HH/HL naik"
        return ScoreComponent("Struktur Pasar", points(ratio, weight), weight, note)
    }

    // ============================================================
    // GRADE
    // ============================================================
    private fun gradeFor(total: Int): String = when {
        total >= 80 -> "SANGAT KUAT"
        total >= 65 -> "KUAT"
        total >= 50 -> "SEDANG"
        total >= 35 -> "LEMAH"
        else -> "SANGAT LEMAH"
    }

    private fun emojiFor(total: Int): String = when {
        total >= 80 -> "🔥"
        total >= 65 -> "✅"
        total >= 50 -> "🟡"
        total >= 35 -> "⚠️"
        else -> "❌"
    }

    private fun fmt(value: Double): String = String.format("%.1f", value)
}
