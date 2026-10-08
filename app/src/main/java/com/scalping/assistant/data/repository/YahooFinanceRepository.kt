package com.scalping.assistant.data.repository

import com.scalping.assistant.data.models.Candle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

data class DailyTechnicalSummary(
    val lastClose: Double,
    val sma5: Double,
    val sma9: Double,
    val sma20: Double,
    val sma50: Double,
    val rsi14: Double,
    val high52w: Double,
    val low52w: Double,
    val volumeAvg5d: Long,
    val lastVolume: Long,
    val trend: String,
    val maAlignment: String = ""
)

/**
 * Timeframe candle untuk analisis Fibonacci & struktur pasar (SMC).
 *
 * [interval] dan [range] mengikuti parameter API chart Yahoo Finance.
 * [analysisWindow] adalah jumlah candle terakhir yang dipakai sebagai jendela analisis
 * Fibonacci & struktur pasar. Diseragamkan 100 candle untuk SEMUA timeframe: cukup dalam
 * agar swing dan dua tarikan Fibonacci terbentuk, tetapi tetap ringan dihitung.
 *
 * CATATAN PENTING soal jumlah candle (terukur pada emiten IDX):
 * IDX hanya buka ~20-21 hari/bulan, jadi 1 bulan ≈ 21 candle harian. Deteksi swing butuh
 * 3 candle di kiri + 3 di kanan, sehingga 6 candle pertama & terakhir tidak bisa jadi titik
 * swing. Akibatnya rentang 1-2 bulan (23-43 candle) hanya menyisakan 2-3 swing dan setup
 * Fibonacci-nya tipis (sering OVERSHOOT). Minimal ~60 candle (≈3 bulan) agar struktur & dua
 * tarikan Fibonacci bisa diandalkan.
 *
 * Jumlah candle terukur (BBCA/GOTO, 8 Okt 2026): 15m/5d = 138, 15m/1mo = 640, 1d/3mo = 67,
 * 1d/6mo = 132. Artinya jendela 100 candle terisi penuh di semua pilihan, dan 640 candle dari
 * 15m/1mo dipangkas otomatis oleh jendela tersebut sehingga tidak membebani perhitungan.
 */
enum class CandleTimeframe(
    val label: String,
    val interval: String,
    val range: String,
    val analysisWindow: Int,
    /** Perkiraan jangka waktu yang tercakup, untuk keterangan di UI. */
    val horizon: String
) {
    /**
     * 15 menit x 5 hari (~138 candle terukur).
     *
     * DIPERTAHANKAN untuk pipeline Scalping (tab Manual/Movers) yang memang butuh data per
     * menit. Ini BUKAN pilihan TF di tab Screener — yang dipakai screener adalah
     * [INTRADAY_BULAN1] supaya riwayatnya cukup panjang untuk jendela 100 candle.
     */
    INTRADAY("15M", "15m", "5d", 120, "~5 hari"),

    /**
     * 15 menit x 1 bulan (~640 candle terukur). Pilihan TF intraday di tab Screener.
     *
     * Dipakai bila gerak intraday justru lebih akurat menggambarkan tarikan Fibonacci
     * (mis. emiten yang berbalik arah dalam beberapa jam). Level 15M ini bisa dibandingkan
     * langsung dengan level harian karena keduanya memakai jendela 100 candle terakhir.
     */
    INTRADAY_BULAN1("15M", "15m", "1mo", 100, "~1 bulan"),

    /** 1 jam x 1 bulan. Swing pendek dalam hari. */
    BULAN1("1H", "60m", "1mo", 100, "~1 bulan"),

    /** 1 hari x 3 bulan (~67 candle terukur). Candle HARIAN — default gaya Daytrade & Swing. */
    BULAN3("1D", "1d", "3mo", 100, "~3 bulan"),

    /** 1 hari x 6 bulan (~132 candle terukur). Konteks tren besar. */
    BULAN6("1D", "1d", "6mo", 100, "~6 bulan");

    /** Kunci cache agar tiap timeframe punya cache sendiri. */
    val cacheKey: String get() = "$interval|$range"

    /**
     * true bila satu candle = satu HARI.
     *
     * Tab Screener kini boleh dibaca dari candle intraday (15M) ATAU harian (1D): pengujian
     * menunjukkan sebagian emiten lebih akurat dinilai dari gerak intraday. Candle harian
     * tetap jadi DEFAULT karena paling stabil untuk struktur pasar & dua tarikan Fibonacci.
     * Pipeline Scalping (tab Manual/Movers) tetap memakai [INTRADAY] dan tidak terpengaruh
     * oleh pilihan TF di tab Screener.
     */
    val isDaily: Boolean get() = interval == "1d"
}

class YahooFinanceRepository {

    // Cache candle per ticker+timeframe untuk hemat request.
    private val candleCache = mutableMapOf<String, Pair<Long, List<Candle>>>()
    private val CACHE_DURATION_MS = 60_000L // 1 menit

    /**
     * Request yang SEDANG berjalan, supaya pemanggil kedua tidak mengirim request
     * yang sama. Terukur: 12 emiten paralel ≈ 100 ms, jadi tabrakan request jarang,
     * TAPI saat tab Screener baru dibuka, satu emiten bisa diminta oleh pipeline
     * Scalping dan Screener sekaligus. Dengan peta ini keduanya berbagi 1 request.
     *
     * Memakai ConcurrentHashMap + CompletableDeferred agar pemanggil kedua benar-benar
     * MENUNGGU (suspend), bukan sibuk menunggu (busy wait) atau mengirim request ulang.
     */
    private val candleInFlight = ConcurrentHashMap<String, CompletableDeferred<List<Candle>>>()

    private val dailyCache = mutableMapOf<String, Pair<Long, DailyTechnicalSummary>>()
    private val DAILY_CACHE_DURATION_MS = 300_000L // 5 menit

    /**
     * Candle 15m (perilaku lama, dipertahankan agar pemanggil lama tidak berubah).
     * Setara dengan [fetchCandles] memakai [CandleTimeframe.INTRADAY].
     */
    suspend fun fetchIntradayCandles(ticker: String): List<Candle> =
        fetchCandles(ticker, CandleTimeframe.INTRADAY)

    /**
     * Candle untuk ticker pada timeframe tertentu.
     *
     * Cache dipisah per ticker + timeframe, jadi berpindah timeframe tidak
     * saling menimpa hasil yang sudah diunduh.
     */
    suspend fun fetchCandles(
        ticker: String,
        timeframe: CandleTimeframe = CandleTimeframe.BULAN3
    ): List<Candle> = withContext(Dispatchers.IO) {
        val cleanTicker = ticker.trim().uppercase()
        val key = "$cleanTicker|${timeframe.cacheKey}"
        val cached = candleCache[key]
        val now = System.currentTimeMillis()

        if (cached != null && (now - cached.first) < CACHE_DURATION_MS && cached.second.isNotEmpty()) {
            return@withContext cached.second
        }

        // Kalau ada request yang sedang berjalan untuk ticker+timeframe ini, tunggu
        // hasilnya saja (suspend) — jangan kirim request kedua yang isinya sama.
        val slot = CompletableDeferred<List<Candle>>()
        val sudahAda = candleInFlight.putIfAbsent(key, slot)
        if (sudahAda != null) {
            return@withContext sudahAda.await()
        }

        val result: List<Candle> = try {
            val data = fetchCandlesFromNetwork(cleanTicker, timeframe, cached?.second)
            if (data.isNotEmpty()) candleCache[key] = Pair(System.currentTimeMillis(), data)
            data
        } catch (e: Exception) {
            // Pengecualian tetap diteruskan ke penunggu, tetapi slot WAJIB diselesaikan
            // supaya penunggu tidak menggantung selamanya.
            candleInFlight.remove(key)
            slot.completeExceptionally(e)
            throw e
        }

        candleInFlight.remove(key)
        slot.complete(result)
        result
    }

    /** Unduhan jaringan sebenarnya; dipakai [fetchCandles] dan ditunggu bersama-sama. */
    private fun fetchCandlesFromNetwork(
        cleanTicker: String,
        timeframe: CandleTimeframe,
        fallback: List<Candle>?
    ): List<Candle> {
        return try {
            val symbol = if (cleanTicker.endsWith(".JK")) cleanTicker else "$cleanTicker.JK"
            val urlString = "https://query1.finance.yahoo.com/v8/finance/chart/$symbol" +
                "?interval=${timeframe.interval}&range=${timeframe.range}"
            val url = URL(urlString)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 3000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            }

            if (conn.responseCode != 200) {
                return fallback ?: emptyList()
            }

            val response = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            parseCandles(response)
        } catch (e: Exception) {
            fallback ?: emptyList()
        }
    }

    suspend fun fetchDailyTechnicals(ticker: String): DailyTechnicalSummary? = withContext(Dispatchers.IO) {
        val cleanTicker = ticker.trim().uppercase()
        val cached = dailyCache[cleanTicker]
        val now = System.currentTimeMillis()
        if (cached != null && (now - cached.first) < DAILY_CACHE_DURATION_MS) {
            return@withContext cached.second
        }

        try {
            val symbol = if (cleanTicker.endsWith(".JK")) cleanTicker else "$cleanTicker.JK"
            val urlString = "https://query1.finance.yahoo.com/v8/finance/chart/$symbol?interval=1d&range=3mo"
            val url = URL(urlString)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            }

            if (conn.responseCode != 200) {
                return@withContext cached?.second
            }

            val response = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            val root = JSONObject(response)
            val chart = root.getJSONObject("chart")
            val resultArray = chart.getJSONArray("result")
            if (resultArray.length() == 0) return@withContext cached?.second

            val resultObj = resultArray.getJSONObject(0)
            val meta = resultObj.optJSONObject("meta")
            val high52w = meta?.optDouble("fiftyTwoWeekHigh", 0.0) ?: 0.0
            val low52w = meta?.optDouble("fiftyTwoWeekLow", 0.0) ?: 0.0

            val indicators = resultObj.getJSONObject("indicators")
            val quoteArray = indicators.getJSONArray("quote")
            if (quoteArray.length() == 0) return@withContext cached?.second

            val quoteObj = quoteArray.getJSONObject(0)
            val closesArr = quoteObj.optJSONArray("close") ?: return@withContext cached?.second
            val volumesArr = quoteObj.optJSONArray("volume")

            val closes = mutableListOf<Double>()
            val volumes = mutableListOf<Long>()

            for (i in 0 until closesArr.length()) {
                if (!closesArr.isNull(i)) {
                    closes.add(closesArr.getDouble(i))
                    val v = if (volumesArr != null && !volumesArr.isNull(i)) volumesArr.getLong(i) else 0L
                    volumes.add(v)
                }
            }

            if (closes.isEmpty()) return@withContext cached?.second

            val lastClose = closes.last()
            val lastVolume = if (volumes.isNotEmpty()) volumes.last() else 0L

            // Hitung SMA 5 (Momentum Jangka Pendek)
            val sma5 = if (closes.size >= 5) {
                closes.takeLast(5).average()
            } else {
                closes.average()
            }

            // Hitung SMA 9 (Konfirmasi Momentum)
            val sma9 = if (closes.size >= 9) {
                closes.takeLast(9).average()
            } else {
                closes.average()
            }

            // Hitung SMA 20 (Garis Tren Bulanan / Swing Utama)
            val sma20 = if (closes.size >= 20) {
                closes.takeLast(20).average()
            } else {
                closes.average()
            }

            // Hitung SMA 50
            val sma50 = if (closes.size >= 50) {
                closes.takeLast(50).average()
            } else {
                closes.average()
            }

            // Evaluasi Susunan Moving Average (MA Alignment)
            val maAlignment = when {
                lastClose > sma5 && sma5 > sma9 && sma9 > sma20 -> "🟢 PERFECT BULLISH STACK (Harga > MA5 > MA9 > MA20: Super Momentum)"
                sma5 > sma9 && sma9 > sma20 -> "🟢 BULLISH TREND (MA5 > MA9 > MA20: Tren Naik Kuat)"
                sma5 > sma20 && sma9 <= sma20 -> "🟡 GOLDEN CROSS MOMENTUM (MA5 Memotong MA20 ke Atas)"
                lastClose < sma5 && sma5 < sma9 && sma9 < sma20 -> "🔴 PERFECT BEARISH STACK (Harga < MA5 < MA9 < MA20: Downtrend Kuat)"
                sma5 < sma20 -> "🔴 BEARISH PRESSURE (MA5 di Bawah MA20: Tekanan Jual)"
                else -> "⚪ KONSOLIDASI / MIXED (Harga Menguji Area MA)"
            }

            // Hitung Volume rata-rata 5 hari
            val volumeAvg5d = if (volumes.size >= 5) {
                volumes.takeLast(5).average().toLong()
            } else {
                volumes.average().toLong()
            }

            // Hitung RSI 14
            val rsi14 = calculateRsi(closes, 14)

            val trend = when {
                lastClose > sma20 && lastClose > sma50 -> "BULLISH UPTREND (Harga di atas MA20 & MA50)"
                lastClose > sma20 && lastClose <= sma50 -> "POTENSI REBOUND (Di atas MA20 menguji MA50)"
                lastClose <= sma20 && lastClose > sma50 -> "KONSOLIDASI (Di bawah MA20 di atas MA50)"
                else -> "BEARISH DOWNTREND (Di bawah MA20 & MA50)"
            }

            val summary = DailyTechnicalSummary(
                lastClose = lastClose,
                sma5 = sma5,
                sma9 = sma9,
                sma20 = sma20,
                sma50 = sma50,
                rsi14 = rsi14,
                high52w = if (high52w > 0.0) high52w else (closes.maxOrNull() ?: lastClose),
                low52w = if (low52w > 0.0) low52w else (closes.minOrNull() ?: lastClose),
                volumeAvg5d = volumeAvg5d,
                lastVolume = lastVolume,
                trend = trend,
                maAlignment = maAlignment
            )

            dailyCache[cleanTicker] = Pair(now, summary)
            summary
        } catch (e: Exception) {
            cached?.second
        }
    }

    private fun calculateRsi(closes: List<Double>, period: Int = 14): Double {
        if (closes.size <= period) return 50.0
        var gain = 0.0
        var loss = 0.0
        for (i in 1..period) {
            val diff = closes[i] - closes[i - 1]
            if (diff >= 0) gain += diff else loss -= diff
        }
        var avgGain = gain / period
        var avgLoss = loss / period

        for (i in (period + 1) until closes.size) {
            val diff = closes[i] - closes[i - 1]
            val currentGain = if (diff >= 0) diff else 0.0
            val currentLoss = if (diff < 0) -diff else 0.0
            avgGain = (avgGain * (period - 1) + currentGain) / period
            avgLoss = (avgLoss * (period - 1) + currentLoss) / period
        }

        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return (100.0 - (100.0 / (1.0 + rs))).coerceIn(0.0, 100.0)
    }

    private fun parseCandles(jsonString: String): List<Candle> {
        val resultList = mutableListOf<Candle>()
        try {
            val root = JSONObject(jsonString)
            val chart = root.getJSONObject("chart")
            val resultArray = chart.getJSONArray("result")
            if (resultArray.length() == 0) return emptyList()

            val resultObj = resultArray.getJSONObject(0)
            if (!resultObj.has("timestamp")) return emptyList()

            val timestamps = resultObj.getJSONArray("timestamp")
            val indicators = resultObj.getJSONObject("indicators")
            val quoteArray = indicators.getJSONArray("quote")
            if (quoteArray.length() == 0) return emptyList()

            val quoteObj = quoteArray.getJSONObject(0)
            val opens = quoteObj.optJSONArray("open")
            val highs = quoteObj.optJSONArray("high")
            val lows = quoteObj.optJSONArray("low")
            val closes = quoteObj.optJSONArray("close")
            val volumes = quoteObj.optJSONArray("volume")

            if (opens == null || highs == null || lows == null || closes == null) return emptyList()

            for (i in 0 until timestamps.length()) {
                if (opens.isNull(i) || highs.isNull(i) || lows.isNull(i) || closes.isNull(i)) {
                    continue
                }
                val o = opens.getDouble(i)
                val h = highs.getDouble(i)
                val l = lows.getDouble(i)
                val c = closes.getDouble(i)
                val v = if (volumes != null && !volumes.isNull(i)) volumes.getLong(i) else 0L

                resultList.add(
                    Candle(
                        timestamp = timestamps.getLong(i),
                        open = o,
                        high = h,
                        low = l,
                        close = c,
                        volume = v
                    )
                )
            }
        } catch (e: Exception) {
            // Ignore parse exception, return partial or empty list
        }
        return resultList
    }
}
