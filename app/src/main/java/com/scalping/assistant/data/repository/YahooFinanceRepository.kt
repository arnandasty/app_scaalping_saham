package com.scalping.assistant.data.repository

import com.scalping.assistant.data.models.Candle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

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

class YahooFinanceRepository {

    // Cache candle per ticker untuk hemat request
    private val candleCache = mutableMapOf<String, Pair<Long, List<Candle>>>()
    private val CACHE_DURATION_MS = 60_000L // 1 menit

    private val dailyCache = mutableMapOf<String, Pair<Long, DailyTechnicalSummary>>()
    private val DAILY_CACHE_DURATION_MS = 300_000L // 5 menit

    suspend fun fetchIntradayCandles(ticker: String): List<Candle> = withContext(Dispatchers.IO) {
        val cleanTicker = ticker.trim().uppercase()
        val cached = candleCache[cleanTicker]
        val now = System.currentTimeMillis()

        if (cached != null && (now - cached.first) < CACHE_DURATION_MS && cached.second.isNotEmpty()) {
            return@withContext cached.second
        }

        try {
            val symbol = if (cleanTicker.endsWith(".JK")) cleanTicker else "$cleanTicker.JK"
            val urlString = "https://query1.finance.yahoo.com/v8/finance/chart/$symbol?interval=15m&range=5d"
            val url = URL(urlString)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 3000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            }

            if (conn.responseCode != 200) {
                return@withContext cached?.second ?: emptyList()
            }

            val response = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            val candles = parseCandles(response)

            if (candles.isNotEmpty()) {
                candleCache[cleanTicker] = Pair(now, candles)
            }

            candles
        } catch (e: Exception) {
            cached?.second ?: emptyList()
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
