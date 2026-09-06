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

class GroqAiRepository {

    // Model candidate list dengan auto-fallback jika salah satu model dideprecate oleh Groq
    private val candidateModels = listOf(
        "groq/compound-mini",
        "groq/compound",
        "openai/gpt-oss-20b",
        "qwen/qwen3.6-27b"
    )

    suspend fun getScalperOpinion(item: StockAnalysis, apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(Exception("API Key belum disetel. Silakan masukkan Groq API Key Anda."))
        }

        val systemPrompt = """
            Kamu adalah Chief Scalper & Spesialis Bandarmologi di Bursa Efek Indonesia (IDX).
            Berikan opini trading scalping yang sangat singkat, padat, dan langsung to-the-point (maksimal 2-3 kalimat).
            Sorot:
            1. Aksi bandar & letak modal rata-rata bandar (apakah harga saat ini masih murah/di bawah modal bandar, atau rawan guyuran).
            2. Saran tindakan scalping konkret (apakah boleh HAKA, tunggu pullback ke support, atau AVOID).
            Gunakan gaya bahasa trader profesional Indonesia yang lugas tanpa salam pembuka atau penutup formal.
        """.trimIndent()

        val bandarInfo = if (item.bandarDetector != null) {
            "Bandar Detector: ${item.bandarDetector.accdistStatus} @ Rp ${item.bandarDetector.averagePrice.toInt()} (Total: Rp ${item.bandarDetector.amountRupiah})"
        } else {
            "Bandar Detector: Menunggu data"
        }

        val tapeInfo = if (item.tapeReading != null) {
            "Tape Reading: HAKA ${item.tapeReading.totalHakaLot} lot vs HAKI ${item.tapeReading.totalHakiLot} lot"
        } else {
            "Tape Reading: Normal"
        }

        val userContent = """
            Data Saham: ${item.ticker}
            Harga Saat Ini: Rp ${item.lastPrice} (${if (item.changePercent > 0) "+" else ""}${item.changePercent}%)
            Rekomendasi Algoritma: ${item.recommendation.label} (Skor: ${item.score}/100, Gaya: ${item.style})
            $bandarInfo
            $tapeInfo
            Orderflow Delta: ${item.orderFlow.cumulativeDelta} lot, Fake Wall: ${item.orderFlow.hasFakeWall}, Akumulasi: ${item.orderFlow.hasAccumulation}
            Support: Rp ${item.technical.nearestSupport.toInt()} | Resisten: Rp ${item.technical.nearestResistance.toInt()}
            Trading Plan: Entry Rp ${item.entryPrice}, Target Rp ${item.targetPrice}, Stop Loss Rp ${item.stopLoss}
        """.trimIndent()

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
                    put("max_tokens", 220)
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

                    // Jika error 404 (model not found), coba model kandidat berikutnya
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
}
