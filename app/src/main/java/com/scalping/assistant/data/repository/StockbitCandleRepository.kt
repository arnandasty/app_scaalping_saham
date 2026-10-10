package com.scalping.assistant.data.repository

import android.content.Context
import android.util.Log
import com.scalping.assistant.data.models.Candle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * Candle REAL-TIME via endpoint `chartbit` Stockbit (0 delay, bukan Yahoo).
 *
 * Endpoint dan parameternya ditemukan dari inspeksi Network tab Chrome saat chart
 * `/symbol/{TICKER}/chartbit` dibuka, lalu DIVERIFIKASI ulang lewat tes HTTP nyata:
 *
 * ```
 * GET https://exodus.stockbit.com/chartbit/{TICKER}/price/daily?from=YYYY-MM-DD&to=YYYY-MM-DD&limit=0
 *     → {"message":"Successfully Get Daily Price data","data":{"chartbit":[{"date","unixdate","open",...}]}}
 * GET https://exodus.stockbit.com/chartbit/{TICKER}/price/intraday?from=<unix>&to=<unix>&limit=N
 *     → {"message":"Successfully Get Intraday data","data":{"allow_decimal":0,"chartbit":[{"datetime","unix_timestamp","open",...}]}}
 * ```
 *
 * Catatan hasil tes yang mengikat implementasi ini:
 * - `Authorization: Bearer <JWT>` **WAJIB** (tanpa itu → 401 Unauthorized).
 * - `daily` menerima tanggal `yyyy-MM-dd`; `intraday` menerima **unix seconds**.
 * - `from` = tanggal/waktu PALING BARU, `to` = PALING LAMA (kebalikan intuisi).
 * - `intraday` hanya menyediakan granularitas **per-menit** (tidak ada 15m/1H langsung;
 *   `price/15m|1h|weekly|...` semua 404 "Unrecognized Command"). Karena itu resolusi
 *   kasar (15M & 1H) dirakit di sisi klien lewat [aggregate].
 * - `limit` BENAR-benar memotong jumlah baris (limit=300 → 300 baris, bukan seluruh rentang),
 *   dipakai untuk menahan ukuran payload saham likuid.
 * - Data dikembalikan **terbaru → terlama**, jadi selalu diurutkan naik sebelum dipakai.
 * - Batas backdate `intraday` < 1 tahun; `daily` masih jalan di 120 bulan.
 *
 * Fallback otomatis ke Yahoo Finance bila belum login / token kedaluwarsa (401) / endpoint
 * berubah / parsing gagal, supaya badge UI tetap jujur menampilkan sumber sebenarnya.
 */
class StockbitCandleRepository(
    private val context: Context,
    private val yahooRepo: YahooFinanceRepository
) {

    /** Hasil satu penarikan series: candle + sumber aktual + ringkasan diagnosa. */
    data class CandleSeries(
        val candles: List<Candle>,
        /** "STOCKBIT" bila murni dari chartbit, "YAHOO" bila fallback. */
        val source: String,
        /** Ringkasan endpoint/HTTP untuk log [CANDLE_TEST], mis. "sb:intraday 200 raw=2100→140". */
        val diag: String
    )

    private val PREF_NAME = "ScalpingPrefs"
    private val PREF_TOKEN = "stockbit_auth_token"
    private val BASE_URL = "https://exodus.stockbit.com"

    /**
     * Plafon jumlah baris mentah per-menit untuk endpoint intraday. 6000 baris ≈ 15 hari
     * perdagangan ≈ 1.4 MB untuk emiten paling likuid — kompromi antara akurasi jendela
     * analisis dan berat unduhan di HP.
     */
    private val MAX_RAW_ROWS = 6000

    /** Perkiraan menit perdagangan efektif per hari (sesi IDX + pre/post closing). */
    private val TRADING_MINUTES_PER_DAY = 390

    private val cache = mutableMapOf<String, Pair<Long, List<Candle>>>()
    private val cacheDurationMs = 60_000L // 1 menit, sama seperti Yahoo
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<CandleSeries>>()

    /** Sumber aktual candle terakhir per series ("STOCKBIT" / "YAHOO") — untuk badge di UI. */
    private val sourceCache = ConcurrentHashMap<String, String>()

    /** Ringkasan fetch terakhir per series — agar diagnosa per ticker/timeframe jujur. */
    private val diagCache = ConcurrentHashMap<String, String>()

    /** Ringkasan fetch terakhir (tanpa key) — dipertahankan untuk pemanggil lama. */
    @Volatile private var lastFetchDiag: String = "-"
    fun getLastFetchDiag(): String = lastFetchDiag

    /** Diagnosa spesifik ticker+timeframe (series harga). */
    fun getLastFetchDiag(ticker: String, timeframe: CandleTimeframe): String =
        diagCache[seriesKey(cleanTicker(ticker), timeframe, price = true)] ?: "-"

    /** Hapus cache ticker agar tombol "Tes Candle" selalu fetch fresh (tidak kena cache 60 detik). */
    fun clearCacheFor(ticker: String) {
        val clean = cleanTicker(ticker)
        synchronized(cache) {
            cache.keys.filter { it.startsWith("$clean|") }.forEach { cache.remove(it) }
        }
        sourceCache.keys.filter { it.startsWith("$clean|") }.forEach { sourceCache.remove(it) }
        diagCache.keys.filter { it.startsWith("$clean|") }.forEach { diagCache.remove(it) }
    }

    /** Bersihkan seluruh cache candle (mis. setelah login ulang). */
    fun clearAllCache() {
        synchronized(cache) { cache.clear() }
        sourceCache.clear()
        diagCache.clear()
    }

    // ================================================================
    // Public API
    // ================================================================

    /**
     * Tarik series candle untuk [ticker]+[timeframe].
     *
     * [withVolume] = true mengembalikan volume per bar (untuk analisis volume seperti
     * Teknikal Volume Breakout); false mengosongkan volume agar payload chart lebih ringan.
     */
    suspend fun fetchSeries(
        ticker: String,
        timeframe: CandleTimeframe,
        withVolume: Boolean = false
    ): CandleSeries = withContext(Dispatchers.IO) {
        val clean = cleanTicker(ticker)
        val key = seriesKey(clean, timeframe, price = !withVolume)
        val now = System.currentTimeMillis()

        val cached = synchronized(cache) { cache[key] }
        if (cached != null && (now - cached.first) < cacheDurationMs && cached.second.isNotEmpty()) {
            return@withContext CandleSeries(
                candles = cached.second,
                source = sourceCache[key] ?: "STOCKBIT",
                diag = diagCache[key] ?: "cache"
            )
        }

        val slot = CompletableDeferred<CandleSeries>()
        val existing = inFlight.putIfAbsent(key, slot)
        if (existing != null) return@withContext existing.await()

        val result: CandleSeries = try {
            val token = getToken()
            val stockbitData = if (token.isNotEmpty()) {
                tryFetchStockbit(clean, timeframe, withVolume)
            } else null

            if (stockbitData != null && stockbitData.isNotEmpty()) {
                Log.d("STOCKBIT_CANDLE", "✅ $clean ${timeframe.label} chartbit: ${stockbitData.size} candles (REALTIME 0-delay)")
                sourceCache[key] = "STOCKBIT"
                synchronized(cache) { cache[key] = Pair(System.currentTimeMillis(), stockbitData) }
                CandleSeries(stockbitData, "STOCKBIT", diagCache[key] ?: "-")
            } else {
                val reason = if (token.isEmpty()) "belum login" else "fallback"
                Log.d("STOCKBIT_CANDLE", "↪ $clean ${timeframe.label} via Yahoo ($reason)")
                sourceCache[key] = "YAHOO"
                val yahooData = yahooRepo.fetchCandles(clean, timeframe)
                if (yahooData.isNotEmpty()) {
                    synchronized(cache) { cache[key] = Pair(System.currentTimeMillis(), yahooData) }
                }
                CandleSeries(yahooData, "YAHOO", diagCache[key] ?: "-")
            }
        } catch (e: Exception) {
            inFlight.remove(key)
            slot.completeExceptionally(e)
            throw e
        }

        inFlight.remove(key)
        slot.complete(result)
        result
    }

    /** Candle untuk analisis (memakai volume bila tersedia). */
    suspend fun fetchCandles(ticker: String, timeframe: CandleTimeframe): List<Candle> =
        fetchSeries(ticker, timeframe, withVolume = false).candles

    /** Candle intraday 15m (dipakai pipeline scalping). */
    suspend fun fetchIntradayCandles(ticker: String): List<Candle> =
        fetchCandles(ticker, CandleTimeframe.INTRADAY)

    /** Series intraday mentah (level menit) untuk chart trading dengan zoom tinggi. */
    suspend fun fetchIntradaySeries(ticker: String, minutesBack: Int): CandleSeries =
        withContext(Dispatchers.IO) {
            val clean = cleanTicker(ticker)
            val key = "$clean|RAW${minutesBack}|p|sb"
            val token = getToken()
            val candles = if (token.isNotEmpty()) {
                val raw = fetchRawIntraday(clean, minutesBack, token)
                if (raw != null && raw.isNotEmpty()) {
                    sourceCache[key] = "STOCKBIT"
                    raw
                } else {
                    sourceCache[key] = "YAHOO"
                    yahooRepo.fetchCandles(clean, CandleTimeframe.INTRADAY)
                }
            } else {
                sourceCache[key] = "YAHOO"
                yahooRepo.fetchCandles(clean, CandleTimeframe.INTRADAY)
            }
            CandleSeries(candles, sourceCache[key] ?: "YAHOO", diagCache[key] ?: "-")
        }

    fun isStockbitLoggedIn(): Boolean = getToken().isNotEmpty()

    /**
     * Sumber aktual candle terakhir untuk ticker+timeframe ("STOCKBIT" / "YAHOO").
     * Default "YAHOO" bila belum pernah di-fetch — dipakai badge di kartu.
     */
    fun getCandleSource(ticker: String, timeframe: CandleTimeframe): String {
        val clean = cleanTicker(ticker)
        return sourceCache[seriesKey(clean, timeframe, price = true)] ?: "YAHOO"
    }

    // ================================================================
    // Internal: Stockbit chartbit fetch
    // ================================================================

    private fun tryFetchStockbit(
        cleanTicker: String,
        timeframe: CandleTimeframe,
        withVolume: Boolean
    ): List<Candle>? {
        val token = getToken()
        if (token.isEmpty()) return null

        val key = seriesKey(cleanTicker, timeframe, price = !withVolume)
        val bucketMinutes = barMinutes(timeframe)
        val nowSec = System.currentTimeMillis() / 1000
        val isDaily = bucketMinutes >= 1440

        val url: String
        val kinds: String
        if (isDaily) {
            // daily: dari tanggal baru ke lama (format yyyy-MM-dd). Backdate aman s/d 120 bulan.
            val days = when (timeframe) {
                CandleTimeframe.BULAN3 -> 110
                CandleTimeframe.BULAN6 -> 200
                else -> 400
            }
            val from = fmtDate(nowSec + 2 * 86400L)
            val to = fmtDate(nowSec - days * 86400L)
            url = "$BASE_URL/chartbit/$cleanTicker/price/daily?from=$from&to=$to&limit=0"
            kinds = "daily"
        } else {
            // intraday: unix seconds. limit memotong baris mentah (1 baris = 1 menit).
            val rawLimit = min(MAX_RAW_ROWS, max(300, (timeframe.analysisWindow * bucketMinutes * 1.2).toInt()))
            val calendarDays = (rawLimit / TRADING_MINUTES_PER_DAY) + 5
            val from = nowSec
            val to = nowSec - calendarDays * 86400L
            url = "$BASE_URL/chartbit/$cleanTicker/price/intraday?from=$from&to=$to&limit=$rawLimit"
            kinds = "intraday($rawLimit)"
        }

        val body = httpGet(url, token, cleanTicker, key, kinds) ?: return null

        val root = try { JSONObject(body) } catch (e: Exception) {
            recordDiag(key, "sb:$kinds 200(parse-gagal:${body.take(40)})")
            return null
        }
        val items = root.optJSONObject("data")?.optJSONArray("chartbit")
        if (items == null || items.length() == 0) {
            recordDiag(key, "sb:$kinds 200(empty)")
            return null
        }

        val parsed = parseChartbitArray(items)
        if (parsed.isEmpty()) {
            recordDiag(key, "sb:$kinds 200(parse-kosong n=${items.length()})")
            return null
        }

        // Server mengembalikan TERBARU → TERLAMA. Urutkan naik agar indikator benar.
        val asc = parsed.sortedBy { it.timestamp }
        val aggregated = aggregate(asc, bucketMinutes)

        val finalList = if (withVolume) aggregated else aggregated.map { it.copy(volume = 0L) }
        recordDiag(key, "sb:$kinds 200 raw=${parsed.size}→${finalList.size}")
        Log.d("STOCKBIT_CANDLE", "chartbit HIT $cleanTicker ${timeframe.label}: raw=${parsed.size} → ${finalList.size} bars ($kinds)")
        return finalList
    }

    /** Series intraday mentah (tanpa agregasi) untuk chart zoom menit. */
    private fun fetchRawIntraday(cleanTicker: String, minutesBack: Int, token: String): List<Candle>? {
        val key = "$cleanTicker|RAW$minutesBack|p|sb"
        val rawLimit = min(MAX_RAW_ROWS, max(120, minutesBack))
        val nowSec = System.currentTimeMillis() / 1000
        val calendarDays = (rawLimit / TRADING_MINUTES_PER_DAY) + 5
        val from = nowSec
        val to = nowSec - calendarDays * 86400L
        val url = "$BASE_URL/chartbit/$cleanTicker/price/intraday?from=$from&to=$to&limit=$rawLimit"

        val body = httpGet(url, token, cleanTicker, key, "intraday-raw") ?: return null
        val items = try { JSONObject(body).optJSONObject("data")?.optJSONArray("chartbit") } catch (_: Exception) { null }
        if (items == null || items.length() == 0) {
            recordDiag(key, "sb:intraday-raw 200(empty)")
            return null
        }
        val parsed = parseChartbitArray(items)
        if (parsed.isEmpty()) return null
        val asc = parsed.sortedBy { it.timestamp }.map { it.copy(volume = 0L) }
        recordDiag(key, "sb:intraday-raw 200 raw=${parsed.size}")
        return asc
    }

    /**
     * Ambil body JSON. Mengembalikan null bila gagal, sekaligus mencatat diagnosa.
     * 401 → token dibersihkan agar badge jujur (fallback Yahoo) dan tidak spam request.
     */
    private fun httpGet(
        urlStr: String,
        token: String,
        cleanTicker: String,
        key: String,
        kinds: String
    ): String? {
        try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 7000
                readTimeout = 12000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
                setRequestProperty("Accept", "application/json, text/plain, */*")
                setRequestProperty("Origin", "https://stockbit.com")
                setRequestProperty("Referer", "https://stockbit.com/")
                setRequestProperty("x-platform", "web")
                if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
            }
            val code = conn.responseCode
            if (code == 401) {
                Log.w("STOCKBIT_CANDLE", "401 Unauthorized untuk $cleanTicker — token kedaluwarsa, hapus agar badge jujur DLY")
                recordDiag(key, "sb:$kinds=401(expired)")
                clearStoredToken()
                return null
            }
            if (code != 200) {
                Log.w("STOCKBIT_CANDLE", "HTTP $code untuk ${urlStr.take(120)}")
                recordDiag(key, "sb:$kinds=$code")
                return null
            }
            return BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        } catch (e: Exception) {
            Log.w("STOCKBIT_CANDLE", "Fetch gagal (${urlStr.take(100)}): ${e.message}")
            recordDiag(key, "sb:$kinds=ERR")
            return null
        }
    }

    /**
     * Parser generik untuk array `data.chartbit`.
     *
     * Struktur `daily`   : {date, unixdate, open, high, low, close, volume, ...}
     * Struktur `intraday`: {datetime, unix_timestamp (string), open, high, low, close, volume (string), lot, ...}
     *
     * Nama field OHLC identik di kedua bentuk; hanya timestamp & volume yang berbeda tipe
     * (angka vs string), jadi keduanya ditangani di sini.
     */
    private fun parseChartbitArray(items: JSONArray): List<Candle> {
        val out = ArrayList<Candle>(items.length())
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue

            val ts = o.optLong("unixdate", 0L).takeIf { it > 0L }
                ?: o.optString("unix_timestamp", "").toLongOrNull()?.takeIf { it > 0L }
                ?: continue

            val open = o.optDouble("open", Double.NaN)
            val high = o.optDouble("high", Double.NaN)
            val low = o.optDouble("low", Double.NaN)
            val close = o.optDouble("close", Double.NaN)
            if (open.isNaN() || high.isNaN() || low.isNaN() || close.isNaN()) continue

            // volume bisa angka ("11631900") atau string ("500") — koersi lewat toString().
            val volRaw = o.opt("volume")?.toString() ?: "0"
            val volume = volRaw.toDoubleOrNull()?.toLong() ?: 0L

            out.add(Candle(ts, open, high, low, close, volume))
        }
        return out
    }

    /**
     * Rakit bar resampled dari candle per-menit (input harus urut naik).
     *
     * [bucketMinutes] = 1 mengembalikan input apa adanya. Sesi IDX selaras dengan kelipatan
     * 15/60 menit pada epoch (offset WIB = +7 jam, bilangan bulat jam), sehingga pembagian
     * berbasis epoch/900 dan epoch/3600 tidak memotong bar di tengah sesi.
     */
    private fun aggregate(candlesAsc: List<Candle>, bucketMinutes: Int): List<Candle> {
        if (bucketMinutes <= 1 || candlesAsc.isEmpty()) return candlesAsc

        val bucketSec = bucketMinutes * 60L
        val out = ArrayList<Candle>(candlesAsc.size / bucketMinutes + 2)

        var bucketStart = Long.MIN_VALUE
        var open = 0.0
        var high = 0.0
        var low = 0.0
        var close = 0.0
        var volume = 0L

        for (c in candlesAsc) {
            val key = (c.timestamp / bucketSec) * bucketSec
            if (key != bucketStart) {
                if (bucketStart != Long.MIN_VALUE) {
                    out.add(Candle(bucketStart, open, high, low, close, volume))
                }
                bucketStart = key
                open = c.open; high = c.high; low = c.low; close = c.close; volume = c.volume
            } else {
                high = max(high, c.high)
                low = min(low, c.low)
                close = c.close
                volume += c.volume
            }
        }
        if (bucketStart != Long.MIN_VALUE) {
            out.add(Candle(bucketStart, open, high, low, close, volume))
        }
        return out
    }

    // ================================================================
    // Helpers
    // ================================================================

    private fun seriesKey(clean: String, timeframe: CandleTimeframe, price: Boolean): String =
        "$clean|${timeframe.cacheKey}|${if (price) "p" else "v"}|sb"

    private fun cleanTicker(ticker: String): String =
        ticker.trim().uppercase().removeSuffix(".JK")

    /** Menit per bar untuk timeframe ini (1d = 1440). */
    private fun barMinutes(tf: CandleTimeframe): Int = when (tf.interval) {
        "15m" -> 15
        "60m" -> 60
        "1d" -> 1440
        else -> 1440
    }

    private fun recordDiag(key: String, diag: String) {
        diagCache[key] = diag
        lastFetchDiag = diag
    }

    /** Format tanggal `yyyy-MM-dd` pada zona WIB agar selaras dengan kalender bursa. */
    private fun fmtDate(unixSec: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("Asia/Jakarta")
        return sdf.format(Date(unixSec * 1000L))
    }

    private fun getToken(): String {
        // 1. Cookie Stockbit (paling fresh, ikut sesi WebView).
        try {
            val cm = android.webkit.CookieManager.getInstance()
            val raw = cm.getCookie("https://stockbit.com") ?: ""
            if (raw.isNotEmpty()) {
                val decoded = java.net.URLDecoder.decode(raw, "UTF-8")
                val m1 = Regex(""""token":"([^"]+)"""").find(decoded)
                if (m1 != null) {
                    val cand = m1.groupValues[1]
                    if (cand.startsWith("eyJ") && cand.length > 80 && !isExpiredJwt(cand)) return cand
                }
                val m2 = Regex("""eyJ[a-zA-Z0-9_-]{15,}\.[a-zA-Z0-9_-]{15,}\.[a-zA-Z0-9_-]{15,}""").find(decoded)
                if (m2 != null && m2.value.length > 80 && !isExpiredJwt(m2.value)) return m2.value
            }
        } catch (_: Exception) {}

        // 2. Fallback ke SharedPrefs (disimpan oleh MainActivity.getActiveStockbitToken).
        return try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val t = prefs.getString(PREF_TOKEN, "") ?: ""
            if (t.startsWith("eyJ") && t.length > 80 && !isExpiredJwt(t)) t else {
                if (t.isNotEmpty() && isExpiredJwt(t)) clearStoredToken()
                ""
            }
        } catch (_: Exception) { "" }
    }

    /** Hapus token basi dari prefs agar tidak dipakai terus dan badge jujur DLY. */
    private fun clearStoredToken() {
        try {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().remove(PREF_TOKEN).apply()
            Log.w("STOCKBIT_CANDLE", "🗑️ Token basi dihapus — user perlu refresh/login ulang di WebView")
        } catch (_: Exception) {}
    }

    /** Cek klaim exp pada JWT tanpa verifikasi signature — true bila sudah lewat. */
    private fun isExpiredJwt(jwt: String): Boolean {
        return try {
            val parts = jwt.split(".")
            if (parts.size < 2) return false
            var payload = parts[1]
            val rem = payload.length % 4
            if (rem > 0) payload += "=".repeat(4 - rem)
            val json = JSONObject(String(android.util.Base64.decode(payload, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)))
            val exp = json.optLong("exp", 0L)
            if (exp == 0L) return false
            val nowSec = System.currentTimeMillis() / 1000
            exp < nowSec - 30
        } catch (_: Exception) { false }
    }
}
