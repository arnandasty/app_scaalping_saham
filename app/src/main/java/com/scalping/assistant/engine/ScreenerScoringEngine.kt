package com.scalping.assistant.engine

import com.scalping.assistant.data.models.Candle
import com.scalping.assistant.data.repository.CandleTimeframe
import com.scalping.assistant.data.repository.ScreenerStock
import kotlin.math.abs

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
// Bobot (total 100):
//   1. Volume / RVOL ................ 20
//   2. Bandarmology (money flow) .... 15
//   3. MACD ......................... 10
//   4. RSI 14 ....................... 10
//   5. Moving Average (EMA stack) ... 15
//   6. Fibonacci (0.5/0.618/0.85/1.618)  10
//   7. Smart Money Concept (OB/BOS/CHoCH) 15
//   8. Struktur Pasar (HH/HL/LH/LL) .. 5
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
    val timeframe: CandleTimeframe = CandleTimeframe.M15
)

object ScreenerScoringEngine {

    // --- Bobot maksimum tiap komponen ---
    private const val W_VOLUME = 20
    private const val W_BANDAR = 15
    private const val W_MACD = 10
    private const val W_RSI = 10
    private const val W_MA = 15
    private const val W_FIB = 10
    private const val W_SMC = 15
    private const val W_STRUCTURE = 5

    /**
     * Menghitung skor emiten.
     *
     * @param stock data snapshot dari TradingView Scanner
     * @param candles candle nyata (boleh kosong; komponen SMC/Fib/Struktur akan dilewati)
     * @param moneyFlow nilai MoneyFlow (0-100) dari TradingView; 0 bila tidak tersedia
     */
    fun score(
        stock: ScreenerStock,
        candles: List<Candle> = emptyList(),
        moneyFlow: Double = 0.0,
        timeframe: CandleTimeframe = CandleTimeframe.M15
    ): ScreenerScore {
        val components = mutableListOf<ScoreComponent>()
        val reasons = mutableListOf<String>()

        // ---------- 1. VOLUME / RVOL ----------
        components += scoreVolume(stock, reasons)

        // ---------- 2. BANDARMOLOGY ----------
        components += scoreBandarmology(stock, moneyFlow, reasons)

        // ---------- 3. MACD ----------
        components += scoreMacd(stock, reasons)

        // ---------- 4. RSI 14 ----------
        components += scoreRsi(stock, reasons)

        // ---------- 5. MOVING AVERAGE ----------
        components += scoreMovingAverage(stock, reasons)

        // ---------- 6-8. CANDLE-BASED ----------
        val structure = if (candles.size >= 15) SmartMoneyAnalyzer.analyzeStructure(candles) else null
        val fibonacci = if (candles.size >= 10) {
            SmartMoneyAnalyzer.computeFibonacci(candles, timeframe.analysisWindow)
        } else null
        val orderBlocks = if (candles.size >= 10) SmartMoneyAnalyzer.findOrderBlocks(candles) else emptyList()

        val lastClose = candles.lastOrNull()?.close ?: stock.close
        val bullishOb = orderBlocks.lastOrNull { it.bullish }
        val bearishOb = orderBlocks.lastOrNull { !it.bullish }

        components += scoreFibonacci(fibonacci, reasons)
        components += scoreSmc(structure, bullishOb, bearishOb, lastClose, reasons)
        components += scoreStructure(structure, reasons)

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
            timeframe = timeframe
        )
    }

    // ============================================================
    // 1. VOLUME (20)
    // ============================================================
    private fun scoreVolume(s: ScreenerStock, reasons: MutableList<String>): ScoreComponent {
        val rvol = s.relativeVolume
        val (pts, note) = when {
            rvol >= 5.0 -> 20 to "RVOL ${fmt(rvol)}x (lonjakan sangat kuat)"
            rvol >= 3.0 -> 16 to "RVOL ${fmt(rvol)}x (lonjakan kuat)"
            rvol >= 2.0 -> 12 to "RVOL ${fmt(rvol)}x (di atas rata-rata)"
            rvol >= 1.5 -> 8 to "RVOL ${fmt(rvol)}x (sedikit di atas rata-rata)"
            rvol >= 1.0 -> 4 to "RVOL ${fmt(rvol)}x (normal)"
            else -> 0 to "RVOL ${fmt(rvol)}x (sepi)"
        }
        if (pts >= 12) reasons += "Volume melonjak ${fmt(rvol)}x"
        return ScoreComponent("Volume / RVOL", pts, W_VOLUME, note)
    }

    // ============================================================
    // 2. BANDARMOLOGY (15)
    // ============================================================
    private fun scoreBandarmology(s: ScreenerStock, moneyFlow: Double, reasons: MutableList<String>): ScoreComponent {
        // MoneyFlow 0-100: di atas 50 = aliran masuk, di bawah 50 = keluar.
        val hasMf = moneyFlow > 0.0
        val mfPoints = when {
            !hasMf -> 0
            moneyFlow >= 80 -> 15
            moneyFlow >= 65 -> 12
            moneyFlow >= 55 -> 8
            moneyFlow >= 45 -> 4
            else -> 0
        }

        // Rating teknikal sebagai konfirmasi arah aliran dana.
        val ratingPoints = when {
            s.recommendAll >= 0.5 -> 3
            s.recommendAll >= 0.3 -> 2
            s.recommendAll >= 0.0 -> 1
            else -> 0
        }

        val total = (mfPoints + ratingPoints).coerceAtMost(W_BANDAR)
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
        if (mfPoints >= 12) reasons += "Bandar akumulasi (MF ${moneyFlow.toInt()})"
        return ScoreComponent("Bandarmology", total, W_BANDAR, note)
    }

    // ============================================================
    // 3. MACD (10)
    // ============================================================
    private fun scoreMacd(s: ScreenerStock, reasons: MutableList<String>): ScoreComponent {
        // Histogram dihitung sendiri karena kolom MACD.histogram mengembalikan null.
        val histogram = s.macdMacd - s.macdSignal
        val (pts, note) = when {
            histogram > 0 && s.macdMacd > 0 -> 10 to "MACD di atas signal & di atas nol (bullish kuat)"
            histogram > 0 -> 7 to "MACD memotong ke atas signal"
            histogram > -0.5 && histogram <= 0 -> 2 to "MACD hampir berpotongan"
            else -> 0 to "MACD di bawah signal (bearish)"
        }
        if (histogram > 0 && s.macdMacd > 0) reasons += "MACD bullish"
        return ScoreComponent("MACD", pts, W_MACD, note)
    }

    // ============================================================
    // 4. RSI 14 (10)
    // ============================================================
    private fun scoreRsi(s: ScreenerStock, reasons: MutableList<String>): ScoreComponent {
        val rsi = s.rsi
        val (pts, note) = when {
            rsi >= 78 -> 0 to "RSI $rsi (terlalu panas / overbought)"
            rsi >= 60 -> 10 to "RSI $rsi (momentum kuat, belum jenuh)"
            rsi >= 50 -> 8 to "RSI $rsi (condong naik)"
            rsi >= 45 -> 5 to "RSI $rsi (netral)"
            rsi >= 30 -> 6 to "RSI $rsi (lemah, potensi rebound)"
            else -> 8 to "RSI $rsi (oversold, potensi rebound)"
        }
        if (pts >= 8 && rsi < 78) reasons += "RSI $rsi sehat"
        return ScoreComponent("RSI 14", pts, W_RSI, note)
    }

    // ============================================================
    // 5. MOVING AVERAGE (15)
    // ============================================================
    private fun scoreMovingAverage(s: ScreenerStock, reasons: MutableList<String>): ScoreComponent {
        val close = s.close
        var pts = 0

        // Susunan EMA menanjak = konfirmasi tren jangka pendek.
        if (s.isBullishEmaStack) pts += 5

        // Harga di atas EMA20 = tren menengah naik.
        if (s.ema20 > 0.0 && close > s.ema20) pts += 4

        // Harga di atas EMA50 = tren lebih kuat.
        if (s.ema50 > 0.0 && close > s.ema50) pts += 3

        // Harga di atas EMA200 = tren mayor naik.
        if (s.ema200 > 0.0 && close > s.ema200) pts += 3

        pts = pts.coerceAtMost(W_MA)

        val note = when {
            pts >= 12 -> "Harga di atas EMA20/50/200, susunan menanjak (tren kuat)"
            pts >= 8 -> "Harga di atas mayoritas EMA"
            pts >= 4 -> "Sebagian EMA terlewati"
            else -> "Harga tertahan di bawah EMA"
        }
        if (pts >= 11) reasons += "Tren EMA menanjak"
        return ScoreComponent("Moving Average", pts, W_MA, note)
    }

    // ============================================================
    // 6. FIBONACCI (10) — DUA TARIKAN
    // ============================================================
    //
    // Tarikan 1 (low premier -> high premier) = area BELI.
    // Tarikan 2 (high sekunder -> low sekunder) = area JUAL.
    // Poin penuh hanya bila konfirmasi beli sah: sudah tembus 0,5 dan
    // tidak lebih rendah dari 0,618 pada tarikan premier.
    private fun scoreFibonacci(fib: FibLevels?, reasons: MutableList<String>): ScoreComponent {
        if (fib == null) return ScoreComponent("Fibonacci", 0, W_FIB, "Data candle tidak cukup")

        val entry = "beli ${fmt(fib.entryHigh)}-${fmt(fib.entryLow)}"
        val sell = "jual ${fmt(fib.sellHigh)}-${fmt(fib.sellLow)}"

        val (pts, note) = when (fib.state) {
            FibSetupState.VALID -> 10 to "Konfirmasi beli di zona 0,5-0,618 ($entry)"
            FibSetupState.WAITING -> 5 to "Pola premier ada, menunggu koreksi ke 0,5 ($entry)"
            FibSetupState.IN_SELL -> 2 to "Harga sudah di zona jual $sell (terlambat untuk entry)"
            FibSetupState.OVERSHOOT -> 1 to "Koreksi menembus 0,618 — setup beli gugur"
            FibSetupState.NONE -> 0 to "Pola premier belum terbentuk"
        }

        if (pts >= 5) reasons += fib.rationale
        return ScoreComponent("Fibonacci", pts, W_FIB, note)
    }

    // ============================================================
    // 7. SMART MONEY CONCEPT (15)
    // ============================================================
    private fun scoreSmc(
        structure: MarketStructure?,
        bullishOb: OrderBlock?,
        bearishOb: OrderBlock?,
        lastClose: Double,
        reasons: MutableList<String>
    ): ScoreComponent {
        if (structure == null) return ScoreComponent("Smart Money (OB/BOS/CHoCH)", 0, W_SMC, "Data candle tidak cukup")

        var pts = 0
        val notes = mutableListOf<String>()

        // BOS searah tren = kelanjutan tren, sinyal paling kuat.
        if (structure.bosBullish) {
            pts += 7
            notes += "BOS bullish (tembus swing high)"
        }
        if (structure.bosBearish) {
            pts += 2
            notes += "BOS bearish"
        }

        // CHoCH = awal pembalikan arah.
        if (structure.chochBullish) {
            pts += 5
            notes += "CHoCH bullish (pembalikan naik)"
        }
        if (structure.chochBearish) {
            pts += 1
            notes += "CHoCH bearish"
        }

        // Harga berada di dalam zona Order Block bullish = area entry berdiskon.
        if (bullishOb != null && lastClose in bullishOb.low..bullishOb.high) {
            pts += 3
            notes += "Harga di Order Block bullish"
        } else if (bullishOb != null && !bullishOb.mitigated) {
            pts += 2
            notes += "Ada Order Block bullish belum tersentuh"
        }

        // Order Block bearish yang belum termitigasi = tembok resisten.
        if (bearishOb != null && !bearishOb.mitigated) {
            pts -= 1
            notes += "Ada Order Block bearish di atas"
        }

        pts = pts.coerceIn(0, W_SMC)
        if (pts >= 7) reasons += notes.firstOrNull() ?: "Struktur SMC mendukung"

        return ScoreComponent(
            "Smart Money (OB/BOS/CHoCH)",
            pts,
            W_SMC,
            notes.joinToString("; ").ifEmpty { "Tidak ada sinyal SMC" }
        )
    }

    // ============================================================
    // 8. STRUKTUR PASAR (5)
    // ============================================================
    private fun scoreStructure(structure: MarketStructure?, reasons: MutableList<String>): ScoreComponent {
        if (structure == null) return ScoreComponent("Struktur Pasar", 0, W_STRUCTURE, "Data candle tidak cukup")

        val (pts, note) = when (structure.trend) {
            StructureTrend.BULLISH -> 5 to "HH + HL terdeteksi (tren naik)"
            StructureTrend.RANGING -> 2 to "Struktur menyamping (belum jelas)"
            StructureTrend.BEARISH -> 0 to "LH + LL terdeteksi (tren turun)"
        }
        if (pts >= 5) reasons += "Struktur HH/HL naik"
        return ScoreComponent("Struktur Pasar", pts, W_STRUCTURE, note)
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
