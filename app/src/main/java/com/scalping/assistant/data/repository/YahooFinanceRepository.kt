package com.scalping.assistant.data.repository

import com.scalping.assistant.data.models.Candle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class YahooFinanceRepository {

    // Cache candle per ticker untuk hemat request
    private val candleCache = mutableMapOf<String, Pair<Long, List<Candle>>>()
    private val CACHE_DURATION_MS = 60_000L // 1 menit

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
