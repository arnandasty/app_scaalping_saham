package com.scalping.assistant.data.repository

import com.scalping.assistant.data.models.Candle
import com.scalping.assistant.engine.ScreenerScore
import com.scalping.assistant.engine.ScreenerScoringEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

// ============================================================
// TRADINGVIEW SCREENER (Universe IDX / IHSG) + SKORING
// ============================================================
//
// Alur HYBRID dua tahap:
//   TAHAP 1 (cepat)  : Snapshot 1 request untuk seluruh universe IDX.
//                      Semua kandidat diskor dari indikator snapshot.
//   TAHAP 2 (akurat) : N kandidat teratas diambil CANDLE NYATA-nya, lalu
//                      dianalisis ulang dengan Smart Money Concept
//                      (Order Block, BOS, CHoCH, Fibonacci, struktur HH/HL).
//
// Hasil akhir SELALU diurutkan dari skor tertinggi.
//
// ⚠️ KETERBATASAN DATA:
// Data IDX dari TradingView DELAYED 10-15 MENIT (terverifikasi: `delayed_streaming_600`,
// feed `IDX_DLY`). Candle dari Yahoo Finance juga memiliki jeda. Karena itu skor ini
// untuk MENYARING KANDIDAT — bukan pemicu entry. Harga & orderbook tetap dari Stockbit.
//
// Kolom penyedia data snapshot (terverifikasi valid):
//   MoneyFlow (0-100) = proxy bandarmology  |  MACD.histogram tidak tersedia -> dihitung
//   MFI, ADL, Williams.R tidak tersedia (null) -> tidak dipakai.
// ============================================================

/**
 * Satu emiten hasil screening, lengkap dengan indikator untuk skoring.
 *
 * Semua angka DELAYED. Gunakan untuk penyaringan, bukan keputusan entry.
 */
data class ScreenerStock(
    val ticker: String,
    val name: String,
    val close: Double,
    val changePercent: Double,
    val volume: Long,
    /** Rasio volume vs rata-rata 10 hari. 25.0 berarti 25x lipat, BUKAN persen. */
    val relativeVolume: Double,
    val rsi: Double,
    val atr: Double,
    /** Rating TradingView, -1 (jual kuat) s/d +1 (beli kuat). */
    val recommendAll: Double,
    val sector: String,
    val supertrendDir: Double,
    val ema9: Double,
    val ema21: Double,
    val ema20: Double,
    val ema50: Double,
    val ema200: Double,
    val macdMacd: Double,
    val macdSignal: Double,
    /** Proxy bandarmology dari TradingView, rentang 0-100. */
    val moneyFlow: Double,
    val high: Double,
    val low: Double,
    val vwap: Double
) {
    /** Susunan EMA menanjak jangka pendek (EMA9 di atas EMA21). */
    val isBullishEmaStack: Boolean get() = ema9 > 0.0 && ema21 > 0.0 && ema9 > ema21

    val isSupertrendBullish: Boolean get() = supertrendDir > 0.0

    val isRecommendBuy: Boolean get() = recommendAll >= 0.3

    /** Histogram MACD dihitung sendiri karena kolom MACD.histogram mengembalikan null. */
    val macdHistogram: Double get() = macdMacd - macdSignal
}

/** Emiten + skornya. Skor inilah yang dipakai untuk mengurutkan. */
data class ScoredScreenerStock(
    val stock: ScreenerStock,
    val score: ScreenerScore,
    /**
     * true bila skor dihitung dengan candle nyata (SMC, Order Block, BOS/CHoCH,
     * Fibonacci, struktur HH/HL lengkap). false = baru snapshot, sehingga komponen
     * berbasis candle bernilai 0 dan skor maksimalnya hanya 70, bukan 100.
     */
    val deepAnalyzed: Boolean = false
) {
    val ticker: String get() = stock.ticker
}

/** Preset penyaring awal (mempersempit universe sebelum diskor). */
enum class ScreenerPreset(val label: String) {
    AKTIF("Semua Aktif"),
    EARLY_MOMENTUM("🚀 Early Momentum"),
    VOLUME_SPIKE("🔥 Volume Spike"),
    TOP_GAINERS("📈 Top Gainers"),
    TREN_NAIK("✅ Tren Naik"),
    OVERSOLD("💎 Oversold")
}

class TradingViewScreenerRepository(
    private val yahooRepo: YahooFinanceRepository? = null
) {

    private val cache = mutableMapOf<String, Pair<Long, List<ScoredScreenerStock>>>()
    private val cacheDurationMs = 5 * 60 * 1000L

    /** Universe IDX/IHSG. Terverifikasi: 886 instrumen, 844 di antaranya `stock`. */
    private val market = "indonesia"

    private val columns = listOf(
        "name", "close", "change", "volume", "relative_volume_10d_calc",
        "RSI", "ATR", "Recommend.All", "sector",
        "Supertrend.Direction",
        "EMA9", "EMA21", "EMA20", "EMA50", "EMA200",
        "MACD.macd", "MACD.signal",
        "MoneyFlow",
        "high", "low", "VWAP"
    )

    /**
     * Menjalankan screening + skoring.
     *
     * @param preset penyaring awal untuk mempersempit universe
     * @param limit jumlah baris yang diambil dari scanner
     * @param deepAnalysisCount berapa kandidat teratas yang dianalisis dengan candle nyata
     * @param forceRefresh abaikan cache
     * @return daftar emiten TERURUT DARI SKOR TERTINGGI
     */
    suspend fun scan(
        preset: ScreenerPreset,
        limit: Int = 60,
        deepAnalysisCount: Int = 12,
        forceRefresh: Boolean = false,
        timeframe: CandleTimeframe = CandleTimeframe.M15
    ): List<ScoredScreenerStock> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cacheKey = "${preset.name}|${timeframe.cacheKey}"
        val cached = cache[cacheKey]
        if (!forceRefresh && cached != null && now - cached.first < cacheDurationMs) {
            return@withContext cached.second
        }

        val hasil = try {
            val snapshot = fetch(preset, limit)
            if (snapshot.isEmpty()) {
                emptyList()
            } else {
                // TAHAP 1: skor cepat dari snapshot (nanti diurutkan dulu).
                val tahap1 = snapshot
                    .map {
                        ScoredScreenerStock(
                            it,
                            ScreenerScoringEngine.score(it, emptyList(), it.moneyFlow, timeframe)
                        )
                    }
                    .sortedByDescending { it.score.total }

                // TAHAP 2: analisis candle nyata untuk kandidat teratas saja.
                val deep = tahap1.take(deepAnalysisCount)
                val sisa = tahap1.drop(deepAnalysisCount)

                val diperkaya = enrichWithCandles(deep, timeframe)
                (diperkaya + sisa).sortedByDescending { it.score.total }
            }
        } catch (e: Exception) {
            cached?.second ?: emptyList()
        }

        if (hasil.isNotEmpty()) cache[cacheKey] = Pair(now, hasil)
        hasil
    }

    fun clearCache() = cache.clear()

    // ============================================================
    // TAHAP 2: PERKAYA DENGAN CANDLE NYATA
    // ============================================================

    private suspend fun enrichWithCandles(
        items: List<ScoredScreenerStock>,
        timeframe: CandleTimeframe
    ): List<ScoredScreenerStock> {
        val repo = yahooRepo ?: return items
        return coroutineScope {
            items.map { item ->
                async {
                    try {
                        val candles: List<Candle> = repo.fetchCandles(item.ticker, timeframe)
                        if (candles.size < 10) {
                            item
                        } else {
                            ScoredScreenerStock(
                                item.stock,
                                ScreenerScoringEngine.score(
                                    item.stock,
                                    candles,
                                    item.stock.moneyFlow,
                                    timeframe
                                ),
                                deepAnalyzed = true
                            )
                        }
                    } catch (e: Exception) {
                        item
                    }
                }
            }.awaitAll().sortedByDescending { it.score.total }
        }
    }

    // ============================================================
    // FILTER PER PRESET
    // ============================================================

    private fun filtersFor(preset: ScreenerPreset): List<JSONObject> {
        val list = mutableListOf<JSONObject>()

        // Semua preset hanya mengambil saham (bukan index/ETF/warrant).
        list += filter("type", "equal", "stock")

        when (preset) {
            ScreenerPreset.AKTIF -> {
                list += filter("volume", "greater", 1_000_000)
            }

            // Selaras strategi "Early Momentum (Akan Naik)": naik awal +0.5%..+5%
            // disertai lonjakan volume, tapi bukan saham baru listing.
            ScreenerPreset.EARLY_MOMENTUM -> {
                list += filter("change", "greater", 0.5)
                list += filter("change", "less", 5.0)
                list += filter("relative_volume_10d_calc", "greater", 2.0)
                list += filter("relative_volume_10d_calc", "less", 50.0)
                list += filter("volume", "greater", 1_000_000)
            }

            // Batas atas 50x membuang saham baru listing yang rasionya tidak wajar
            // (terverifikasi ada nilai 122x di IDX:ENAK).
            ScreenerPreset.VOLUME_SPIKE -> {
                list += filter("relative_volume_10d_calc", "in_range", JSONArray(listOf(3.0, 50.0)))
                list += filter("volume", "greater", 1_000_000)
            }

            ScreenerPreset.TOP_GAINERS -> {
                list += filter("change", "greater", 3.0)
                list += filter("volume", "greater", 500_000)
            }

            ScreenerPreset.TREN_NAIK -> {
                list += filter("Recommend.All", "greater", 0.3)
                list += filter("RSI", "greater", 45.0)
                list += filter("RSI", "less", 75.0)
                list += filter("volume", "greater", 500_000)
            }

            ScreenerPreset.OVERSOLD -> {
                list += filter("RSI", "less", 35.0)
                list += filter("volume", "greater", 500_000)
            }
        }
        return list
    }

    private fun filter(left: String, operation: String, right: Any): JSONObject =
        JSONObject().put("left", left).put("operation", operation).put("right", right)

    // ============================================================
    // HTTP
    // ============================================================

    private fun fetch(preset: ScreenerPreset, limit: Int): List<ScreenerStock> {
        val body = JSONObject().apply {
            put("columns", JSONArray(columns))
            put("range", JSONArray(listOf(0, limit)))
            put("filter", JSONArray(filtersFor(preset)))
            // Urutan awal dari server hanya untuk memilih sampel; urutan akhir dihitung dari skor.
            put("sort", JSONObject().put("sortBy", "relative_volume_10d_calc").put("sortOrder", "desc"))
        }

        val conn = (URL("https://scanner.tradingview.com/$market/scan").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 8000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Origin", "https://www.tradingview.com")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
        }

        return try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) {
                emptyList()
            } else {
                val text = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                parse(text)
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(jsonText: String): List<ScreenerStock> {
        val root = JSONObject(jsonText)
        val data = root.optJSONArray("data") ?: return emptyList()

        val out = mutableListOf<ScreenerStock>()
        for (i in 0 until data.length()) {
            val row = data.optJSONObject(i) ?: continue
            val symbol = row.optString("s")
            val values = row.optJSONArray("d") ?: continue

            // Server mengembalikan nilai sesuai urutan `columns`.
            fun num(index: Int): Double =
                if (index < values.length() && !values.isNull(index)) values.optDouble(index, 0.0) else 0.0
            fun text(index: Int): String =
                if (index < values.length() && !values.isNull(index)) values.optString(index, "") else ""

            // symbol berformat "IDX:BBCA" -> simpan kodenya saja agar cocok dengan pipeline Stockbit.
            val ticker = symbol.substringAfterLast(':').trim().uppercase()
            if (ticker.isEmpty()) continue

            out += ScreenerStock(
                ticker = ticker,
                name = text(0),
                close = num(1),
                changePercent = num(2),
                volume = num(3).toLong(),
                relativeVolume = num(4),
                rsi = num(5),
                atr = num(6),
                recommendAll = num(7),
                sector = text(8),
                supertrendDir = num(9),
                ema9 = num(10),
                ema21 = num(11),
                ema20 = num(12),
                ema50 = num(13),
                ema200 = num(14),
                macdMacd = num(15),
                macdSignal = num(16),
                moneyFlow = num(17),
                high = num(18),
                low = num(19),
                vwap = num(20)
            )
        }
        return out
    }
}
