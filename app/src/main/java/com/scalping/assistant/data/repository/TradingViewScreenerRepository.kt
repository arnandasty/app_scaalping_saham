package com.scalping.assistant.data.repository

import com.scalping.assistant.data.models.Candle
import com.scalping.assistant.engine.ScreenerScore
import com.scalping.assistant.engine.ScreenerScoringEngine
import com.scalping.assistant.engine.TradingStyle
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
    /** Kode/nama pendek dari scanner, mis. "BBCA". */
    val name: String,
    /** Nama perusahaan lengkap, mis. "PT Bank Central Asia Tbk" — dipakai untuk pencarian. */
    val companyName: String,
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

/** Sumber candle yang dipakai untuk analisis mendalam satu emiten. */
enum class CandleSource(val badge: String) {
    /** Candle Stockbit 0-delay (login WebView aktif). */
    STOCKBIT("🟢 LIVE"),
    /** Candle Yahoo Finance (delay ±10 menit). */
    YAHOO("🟡 DLY"),
    /** Belum dianalisis candle (snapshot saja). */
    NONE("⚪ SNAP")
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
    val deepAnalyzed: Boolean = false,
    /** Dari mana candle deep-analysis berasal (untuk badge 🟢/🟡/⚪ di UI). */
    val candleSource: CandleSource = CandleSource.NONE
) {
    val ticker: String get() = stock.ticker
}

/**
 * Preset penyaring awal (mempersempit universe sebelum diskor).
 *
 * Dipisah per gaya trading supaya kandidat yang disaring sejak awal memang cocok
 * dengan gaya yang dipilih, bukan satu daftar generik untuk semua gaya.
 */
enum class ScreenerPreset(val label: String, val style: TradingStyle) {

    // ---------- GAYA DAYTRADE ----------
    /** Saham paling likuid — syarat utama daytrade: mudah masuk & keluar. */
    DAY_LIKUID("💧 Likuid", TradingStyle.DAYTRADE),
    /** Momentum awal: naik wajar tapi volume melonjak. */
    DAY_MOMENTUM("🚀 Momentum", TradingStyle.DAYTRADE),
    /** Pembalikan: merah tapi volume masuk, kandidat rebound intraday. */
    DAY_REVERSAL("🔄 Reversal", TradingStyle.DAYTRADE),
    /** Seluruh emiten likuid tanpa filter tambahan. */
    DAY_SEMUA("Semua Aktif", TradingStyle.DAYTRADE),

    // ---------- GAYA SWING ----------
    /** Tren naik sehat untuk ditahan 3-10 hari. */
    SWING_TREN("✅ Tren Naik", TradingStyle.SWING),
    /** Pullback di tengah tren naik — beli saat diskon. */
    SWING_PULLBACK("🎯 Pullback", TradingStyle.SWING),
    /** Proxy bandarmology: MoneyFlow tinggi = sedang diakumulasi. */
    SWING_AKUMULASI("🧠 Akumulasi", TradingStyle.SWING),
    /** Seluruh emiten tanpa filter tambahan. */
    SWING_SEMUA("Semua Aktif", TradingStyle.SWING);

    companion object {
        /** Preset untuk satu gaya trading, urut sesuai deklarasi. */
        fun forStyle(style: TradingStyle): List<ScreenerPreset> =
            entries.filter { it.style == style }
    }
}

class TradingViewScreenerRepository(
    private val yahooRepo: YahooFinanceRepository? = null,
    private val stockbitRepo: StockbitCandleRepository? = null
) {

    private val cache = mutableMapOf<String, Pair<Long, List<ScoredScreenerStock>>>()
    private val cacheDurationMs = 5 * 60 * 1000L

    /** Cache seluruh universe untuk fitur pencarian (terpisah dari cache hasil scan). */
    private var universeCache: Pair<Long, List<ScreenerStock>>? = null
    private val universeCacheMs = 10 * 60 * 1000L

    /** Universe IDX/IHSG. Terverifikasi: 886 instrumen, 844 di antaranya `stock`. */
    private val market = "indonesia"

    private val columns = listOf(
        "name", "description", "close", "change", "volume", "relative_volume_10d_calc",
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
        timeframe: CandleTimeframe = preset.style.defaultTimeframe,
        style: TradingStyle = preset.style
    ): List<ScoredScreenerStock> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        // Gaya masuk ke kunci cache: bobot penilaian daytrade berbeda dari swing,
        // jadi hasilnya tidak boleh saling menimpa.
        val cacheKey = "${preset.name}|${timeframe.cacheKey}|${style.name}"
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
                            ScreenerScoringEngine.score(it, emptyList(), it.moneyFlow, timeframe, style)
                        )
                    }
                    .sortedByDescending { it.score.total }

                // TAHAP 2: analisis candle nyata untuk kandidat teratas saja.
                val deep = tahap1.take(deepAnalysisCount)
                val sisa = tahap1.drop(deepAnalysisCount)

                val diperkaya = enrichWithCandles(deep, timeframe, style)
                (diperkaya + sisa).sortedByDescending { it.score.total }
            }
        } catch (e: Exception) {
            cached?.second ?: emptyList()
        }

        if (hasil.isNotEmpty()) cache[cacheKey] = Pair(now, hasil)
        hasil
    }

    fun clearCache() {
        cache.clear()
        universeCache = null
    }

    // ============================================================
    // PENCARIAN EMITEN (CARI KODE SAHAM)
    // ============================================================

    /**
     * Mengunduh SELURUH universe IDX sekali lalu menyimpannya di memori.
     *
     * Terukur: 844 emiten dengan 22 kolom = ~268 KB dan ~763 ms. Karena itu
     * pencarian bisa dilakukan di HP tanpa request jaringan tiap ketikan,
     * sehingga hasilnya muncul instan.
     */
    private suspend fun ensureUniverse(forceRefresh: Boolean): List<ScreenerStock> {
        val cached = universeCache
        val now = System.currentTimeMillis()
        if (!forceRefresh && cached != null && now - cached.first < universeCacheMs) {
            return cached.second
        }
        val data = fetchUniverse()
        if (data.isNotEmpty()) universeCache = Pair(now, data)
        return data
    }

    /**
     * Mencari emiten dari kode ATAU nama perusahaan.
     *
     * Contoh yang cocok: "bbca", "BCA", "bank central", "telkom".
     * Kode yang sama persis selalu diletakkan paling atas.
     */
    suspend fun searchStocks(
        query: String,
        limit: Int = 30,
        forceRefresh: Boolean = false
    ): List<ScreenerStock> = withContext(Dispatchers.IO) {
        val q = query.trim().uppercase()
        if (q.isEmpty()) return@withContext emptyList()

        val universe = try {
            ensureUniverse(forceRefresh)
        } catch (e: Exception) {
            emptyList()
        }
        if (universe.isEmpty()) return@withContext emptyList()

        universe.asSequence()
            .filter { st ->
                st.ticker.contains(q) || st.companyName.uppercase().contains(q)
            }
            // Kode yang persis sama didahulukan, lalu kode yang berawalan sama,
            // baru sisanya (biasanya cocok dari nama perusahaan).
            .sortedWith(
                compareByDescending<ScreenerStock> { it.ticker == q }
                    .thenByDescending { it.ticker.startsWith(q) }
                    .thenBy { it.ticker }
            )
            .take(limit)
            .toList()
    }

    /**
     * Mencari emiten lalu LANGSUNG memberi skor, supaya hasil pencarian bisa
     * ditampilkan dengan format kartu yang sama seperti hasil screening.
     *
     * Berbeda dari [scan], di sini threshold preset sengaja dilewati — tujuannya
     * menganalisis emiten yang sudah dipilih pengguna, bukan menyaring.
     *
     * HANYA [deepLimit] kandidat teratas (menurut skor snapshot) yang dianalisis
     * dengan candle nyata. Alasannya penting untuk kecepatan: analisis candle =
     * 1 request jaringan PER EMITEN, jadi menganalisis 30 hasil sekaligus membuat
     * pencarian terasa lama. Emiten sisanya tetap tampil dengan tanda "snapshot".
     */
    suspend fun scoreStocks(
        stocks: List<ScreenerStock>,
        style: TradingStyle,
        timeframe: CandleTimeframe = style.defaultTimeframe,
        deepAnalysis: Boolean = true,
        deepLimit: Int = 8
    ): List<ScoredScreenerStock> = withContext(Dispatchers.IO) {
        val dasar = stocks.map {
            ScoredScreenerStock(
                it,
                ScreenerScoringEngine.score(it, emptyList(), it.moneyFlow, timeframe, style)
            )
        }
        if (!deepAnalysis) return@withContext dasar

        // Urutkan dulu dari skor snapshot, baru ambil yang teratas untuk analisis
        // candle — supaya yang dianalisis memang kandidat paling menjanjikan.
        val urut = dasar.sortedByDescending { it.score.total }
        val deep = urut.take(deepLimit)
        val sisa = urut.drop(deepLimit)
        (enrichWithCandles(deep, timeframe, style) + sisa).sortedByDescending { it.score.total }
    }

    // ============================================================
    // TAHAP 2: PERKAYA DENGAN CANDLE NYATA
    // ============================================================

    private suspend fun enrichWithCandles(
        items: List<ScoredScreenerStock>,
        timeframe: CandleTimeframe,
        style: TradingStyle
    ): List<ScoredScreenerStock> {
        val hasCandleSource = yahooRepo != null || stockbitRepo != null
        if (!hasCandleSource) return items
        return coroutineScope {
            items.map { item ->
                async {
                    try {
                        // Catat sumber candle per emiten untuk badge 🟢/🟡 di UI:
                        // coba Stockbit dulu (0-delay), gagal -> fallback Yahoo (delay).
                        var candles: List<Candle> = emptyList()
                        var source = CandleSource.NONE
                        if (stockbitRepo != null && stockbitRepo.isStockbitLoggedIn()) {
                            val sb = stockbitRepo.fetchCandles(item.ticker, timeframe)
                            if (sb.size >= 10) {
                                candles = sb
                                // Sumber aktual (bisa fallback Yahoo bila endpoint fail meski login).
                                source = if (stockbitRepo.getCandleSource(item.ticker, timeframe) == "STOCKBIT") CandleSource.STOCKBIT else CandleSource.YAHOO
                            }
                        }
                        if (candles.size < 10 && yahooRepo != null) {
                            val yh = yahooRepo.fetchCandles(item.ticker, timeframe)
                            if (yh.size >= 10) {
                                candles = yh
                                source = CandleSource.YAHOO
                            }
                        }
                        if (candles.size < 10) {
                            item
                        } else {
                            ScoredScreenerStock(
                                item.stock,
                                ScreenerScoringEngine.score(
                                    item.stock,
                                    candles,
                                    item.stock.moneyFlow,
                                    timeframe,
                                    style
                                ),
                                deepAnalyzed = true,
                                candleSource = source
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

    /**
     * Seluruh universe IDX yang dipakai untuk pencarian kode/nama saham.
     * Terukur: 844 emiten, ~268 KB, ~763 ms.
     */
    private suspend fun fetchUniverse(): List<ScreenerStock> {
        val body = JSONObject().apply {
            put("columns", JSONArray(columns))
            put("range", JSONArray(listOf(0, 900)))
            put("filter", JSONArray(listOf(filter("type", "equal", "stock"))))
        }
        return parse(postJson(body.toString()))
    }

    // ============================================================
    // FILTER PER PRESET
    // ============================================================

    private fun filtersFor(preset: ScreenerPreset): List<JSONObject> {
        val list = mutableListOf<JSONObject>()

        // Semua preset hanya mengambil saham (bukan index/ETF/warrant).
        list += filter("type", "equal", "stock")

        when (preset) {
            // ---------- GAYA DAYTRADE ----------
            // Daytrade butuh likuiditas: kalau sahamnya tipis, posisi sulit keluar.
            ScreenerPreset.DAY_LIKUID -> {
                list += filter("volume", "greater", 5_000_000)
                list += filter("change", "in_range", JSONArray(listOf(-3.0, 6.0)))
            }

            // Momentum awal dengan volume melonjak. Batas atas RVOL 50x membuang
            // saham baru listing (terverifikasi ada 122x di IDX:ENAK).
            ScreenerPreset.DAY_MOMENTUM -> {
                list += filter("change", "in_range", JSONArray(listOf(1.0, 7.0)))
                list += filter("relative_volume_10d_calc", "in_range", JSONArray(listOf(2.0, 50.0)))
                list += filter("volume", "greater", 3_000_000)
            }

            // Sedang merah tapi volume masuk — kandidat pantulan intraday.
            ScreenerPreset.DAY_REVERSAL -> {
                list += filter("change", "less", -1.0)
                list += filter("relative_volume_10d_calc", "greater", 1.5)
                list += filter("volume", "greater", 2_000_000)
            }

            ScreenerPreset.DAY_SEMUA -> {
                list += filter("volume", "greater", 1_000_000)
            }

            // ---------- GAYA SWING (3-10 hari) ----------
            // Tren naik sehat: rating teknikal positif tapi RSI belum jenuh,
            // supaya masih ada ruang naik selama 3-10 hari ke depan.
            ScreenerPreset.SWING_TREN -> {
                list += filter("Recommend.All", "greater", 0.2)
                list += filter("RSI", "in_range", JSONArray(listOf(45.0, 70.0)))
                list += filter("volume", "greater", 500_000)
            }

            // Pullback: tren masih positif tapi harga sedang dikoreksi,
            // jadi entry lebih murah dibanding mengejar di puncak.
            ScreenerPreset.SWING_PULLBACK -> {
                list += filter("RSI", "in_range", JSONArray(listOf(35.0, 50.0)))
                list += filter("Recommend.All", "greater", 0.0)
                list += filter("volume", "greater", 500_000)
            }

            // Proxy bandarmology: MoneyFlow tinggi = dana besar sedang masuk.
            ScreenerPreset.SWING_AKUMULASI -> {
                list += filter("MoneyFlow", "greater", 60.0)
                list += filter("RSI", "in_range", JSONArray(listOf(40.0, 65.0)))
                list += filter("volume", "greater", 500_000)
            }

            ScreenerPreset.SWING_SEMUA -> {
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
        return parse(postJson(body.toString()))
    }

    /**
     * POST ke scanner TradingView dan kembalikan teks respons.
     * Mengembalikan string kosong bila gagal, supaya pemanggil bisa menanganinya seragam.
     */
    private fun postJson(body: String): String {
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
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) {
                ""
            } else {
                BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            }
        } catch (e: Exception) {
            ""
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(jsonText: String): List<ScreenerStock> {
        if (jsonText.isBlank()) return emptyList()
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
                companyName = text(1),
                close = num(2),
                changePercent = num(3),
                volume = num(4).toLong(),
                relativeVolume = num(5),
                rsi = num(6),
                atr = num(7),
                recommendAll = num(8),
                sector = text(9),
                supertrendDir = num(10),
                ema9 = num(11),
                ema21 = num(12),
                ema20 = num(13),
                ema50 = num(14),
                ema200 = num(15),
                macdMacd = num(16),
                macdSignal = num(17),
                moneyFlow = num(18),
                high = num(19),
                low = num(20),
                vwap = num(21)
            )
        }
        return out
    }
}
