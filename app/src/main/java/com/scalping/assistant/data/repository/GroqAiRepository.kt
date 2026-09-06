package com.scalping.assistant.data.repository

import com.scalping.assistant.data.models.StockAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

enum class GroqAnalysisMode {
    SCALPING,
    SWING
}

class GroqAiRepository {

    // Model candidate list dengan auto-fallback jika salah satu model dideprecate oleh Groq
    private val candidateModels = listOf(
        "groq/compound-mini",
        "groq/compound",
        "openai/gpt-oss-20b",
        "qwen/qwen3.6-27b"
    )

    suspend fun getScalperOpinion(item: StockAnalysis, apiKey: String): Result<String> {
        return getAiAnalysis(item, apiKey, GroqAnalysisMode.SCALPING, null)
    }

    suspend fun getAiAnalysis(
        item: StockAnalysis,
        apiKey: String,
        mode: GroqAnalysisMode,
        dailyTech: DailyTechnicalSummary? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(Exception("API Key belum disetel. Silakan masukkan Groq API Key Anda."))
        }

        val systemPrompt = if (mode == GroqAnalysisMode.SCALPING) {
            """
                Kamu adalah Chief Scalper & Spesialis Bandarmologi di Bursa Efek Indonesia (IDX).
                Fokus: Intraday Scalping & Tape Reading saat market aktif.
                Berikan opini trading scalping yang sangat singkat, padat, dan langsung to-the-point (maksimal 2-3 kalimat):
                1. Aksi bandar & letak modal rata-rata bandar (apakah harga saat ini masih murah/di bawah modal bandar, atau rawan guyuran).
                2. Saran tindakan scalping konkret (apakah boleh HAKA, tunggu pullback ke support, atau AVOID).
                Gunakan gaya bahasa trader profesional Indonesia yang lugas tanpa basa-basi formal.
            """.trimIndent()
        } else {
            """
                Kamu adalah Senior Swing Trader & Pakar Bandarmologi di Bursa Efek Indonesia (IDX).
                Fokus: Analisis Pasca Penutupan Pasar (End-of-Day) untuk Strategi Swing Pendek / Hold 2-7 Hari.
                Berikan analisis yang menyeluruh, komprehensif, namun tetap padat & ringkas (dalam 3 poin terstruktur dengan bullet):
                • 🕵️ Bandarmologi & Broksum: Evaluasi aksi akumulasi/distribusi bandar harian, perbandingan harga closing vs modal bandar (Average Price), dan potensi akumulasi diam-diam.
                • 📊 Struktur Teknikal Harian: Posisi harga terhadap MA20 & MA50 (fase uptrend/rebound/downtrend), kondisi RSI 14 (oversold/akumulasi/overbought), dan pergerakan volume.
                • 💡 Keputusan & Plan Swing:
                  - Rekomendasi: [BUY SWING] / [WAIT ON DIP] / [AVOID]
                  - Area Beli Aman (Buy Range)
                  - Target Profit Swing (+4% s/d +12%)
                  - Stop Loss / Invalidation jika breakdown.
                Gunakan gaya bahasa trader profesional Indonesia yang objektif, tajam, dan realistis tanpa salam pembuka atau penutup formal.
            """.trimIndent()
        }

        val bandarInfo = if (item.bandarDetector != null) {
            "Bandar Detector: ${item.bandarDetector.accdistStatus} @ Rp ${item.bandarDetector.averagePrice.toInt()} (Total Nilai: ${formatCurrencyShort(item.bandarDetector.amountRupiah)})"
        } else {
            "Bandar Detector: Menunggu data bursa"
        }

        val userContent = if (mode == GroqAnalysisMode.SCALPING) {
            val tapeInfo = if (item.tapeReading != null) {
                "Tape Reading: HAKA ${item.tapeReading.totalHakaLot} lot vs HAKI ${item.tapeReading.totalHakiLot} lot"
            } else {
                "Tape Reading: Normal"
            }
            """
                Data Saham: ${item.ticker}
                Harga Saat Ini: Rp ${item.lastPrice} (${if (item.changePercent > 0) "+" else ""}${item.changePercent}%)
                Rekomendasi Algoritma: ${item.recommendation.label} (Skor: ${item.score}/100, Gaya: ${item.style})
                $bandarInfo
                $tapeInfo
                Orderflow Delta: ${item.orderFlow.cumulativeDelta} lot, Fake Wall: ${item.orderFlow.hasFakeWall}, Akumulasi: ${item.orderFlow.hasAccumulation}
                Support: Rp ${item.technical.nearestSupport.toInt()} | Resisten: Rp ${item.technical.nearestResistance.toInt()}
                Trading Plan Scalping: Entry Rp ${item.entryPrice}, Target Rp ${item.targetPrice}, Stop Loss Rp ${item.stopLoss}
            """.trimIndent()
        } else {
            val techInfo = if (dailyTech != null) {
                """
                Teknikal Daily Chart (Yahoo Finance Multi-Day):
                - Trend: ${dailyTech.trend}
                - MA20 Harian: Rp ${dailyTech.sma20.toInt()} | MA50 Harian: Rp ${dailyTech.sma50.toInt()}
                - RSI(14) Harian: ${String.format("%.1f", dailyTech.rsi14)} (${if (dailyTech.rsi14 < 35) "Oversold/Area Akumulasi Murah" else if (dailyTech.rsi14 > 70) "Overbought/Area Rawan Profit Taking" else "Netral"})
                - Volume Terakhir: ${formatCurrencyShort(dailyTech.lastVolume)} lot (Rata-rata 5 Hari: ${formatCurrencyShort(dailyTech.volumeAvg5d)} lot)
                - Range 52 Minggu: High Rp ${dailyTech.high52w.toInt()} | Low Rp ${dailyTech.low52w.toInt()}
                """.trimIndent()
            } else {
                "Teknikal Daily Chart: Menggunakan pivot dinamis (Support Rp ${item.technical.nearestSupport.toInt()} | Resisten Rp ${item.technical.nearestResistance.toInt()})"
            }

            """
                Analisis Swing Pendek Pasca Penutupan Pasar
                Saham: ${item.ticker}
                Harga Penutupan: Rp ${item.lastPrice} (${if (item.changePercent > 0) "+" else ""}${item.changePercent}%)
                $bandarInfo
                $techInfo
                Support Kuat: Rp ${item.technical.nearestSupport.toInt()} | Resisten Kuat: Rp ${item.technical.nearestResistance.toInt()}
                Trading Plan Referensi Algoritma: Entry Rp ${item.entryPrice}, Target Rp ${item.targetPrice}, Stop Loss Rp ${item.stopLoss}
            """.trimIndent()
        }

        val maxTokens = if (mode == GroqAnalysisMode.SWING) 400 else 220
        var lastException: Exception? = null

        for (model in candidateModels) {
            try {
                val url = URL("https://api.groq.com/openai/v1/chat/completions")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 10000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android; ScalpingAssistant/2.1)")
                }

                val messages = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", userContent)
                    })
                }

                val body = JSONObject().apply {
                    put("model", model)
                    put("messages", messages)
                    put("temperature", 0.3)
                    put("max_tokens", maxTokens)
                }

                OutputStreamWriter(conn.outputStream).use { writer ->
                    writer.write(body.toString())
                    writer.flush()
                }

                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    val responseText = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                    val root = JSONObject(responseText)
                    val choices = root.getJSONArray("choices")
                    if (choices.length() > 0) {
                        val messageObj = choices.getJSONObject(0).getJSONObject("message")
                        val content = messageObj.optString("content", "").trim()
                        if (content.isNotEmpty()) {
                            return@withContext Result.success(content)
                        }
                    }
                } else {
                    val errorStream = conn.errorStream
                    val errorText = if (errorStream != null) {
                        BufferedReader(InputStreamReader(errorStream)).use { it.readText() }
                    } else "HTTP $responseCode"

                    val errorMsg = try {
                        JSONObject(errorText).getJSONObject("error").getString("message")
                    } catch (e: Exception) {
                        errorText
                    }

                    if (responseCode == 404 || errorMsg.contains("model", ignoreCase = true)) {
                        lastException = Exception("Groq ($model): $errorMsg")
                        continue
                    }

                    return@withContext Result.failure(Exception("Groq Error ($responseCode): $errorMsg"))
                }
            } catch (e: Exception) {
                lastException = e
            }
        }

        Result.failure(lastException ?: Exception("Gagal menghubungi Groq AI"))
    }

    private fun formatCurrencyShort(amount: Long): String {
        val absVal = kotlin.math.abs(amount).toDouble()
        val sign = if (amount < 0) "-" else ""
        return when {
            absVal >= 1_000_000_000_000.0 -> "${sign}Rp %.1f T".format(absVal / 1_000_000_000_000.0)
            absVal >= 1_000_000_000.0 -> "${sign}Rp %.1f M".format(absVal / 1_000_000_000.0)
            absVal >= 1_000_000.0 -> "${sign}Rp %.1f Jt".format(absVal / 1_000_000.0)
            absVal > 0 -> "${sign}Rp ${amount}"
            else -> "Rp 0"
        }
    }
}
