package com.scalping.assistant.data.repository

import android.content.Context

/**
 * Konfigurasi endpoint AI yang dipakai seluruh fitur opini AI
 * (Opini Scalper, Analisis Swing, dan Dokter Portfolio).
 *
 * Endpoint-nya OpenAI-compatible, jadi 9Router bisa dipakai apa adanya tanpa
 * perubahan format request. 9Router dijalankan di PC/Laptop (`npm install -g 9router`
 * lalu `9router`) dan membuka dashboard di `http://localhost:20128`.
 *
 * PENTING soal alamat untuk HP:
 * - `localhost` di HP menunjuk ke HP itu sendiri, BUKAN ke PC. Karena itu alamat
 *   default di bawah memakai IP LAN PC. IP ini bisa berubah bila DHCP memberi alamat
 *   baru, jadi selalu bisa dikoreksi lewat dialog ⚙️ API Key tanpa build ulang.
 * - Emulator Android di PC yang sama harus memakai [EMULATOR_BASE_URL] (`10.0.2.2`),
 *   karena `10.0.2.2` adalah alias khusus emulator untuk mesin host.
 * - Bila 9Router dijalankan dengan Tunnel/Cloud Sync, ganti ke URL https yang
 *   diberikan 9Router (mis. `https://xxxx.trycloudflare.com/v1`).
 *
 * Server 9Router hanya mau menerima koneksi dari luar bila dijalankan dengan
 * `HOSTNAME=0.0.0.0`; kalau tidak, hanya bisa diakses dari PC itu sendiri.
 */
object AiConfig {

    /** Base URL default: 9Router di PC, diakses dari HP lewat Wi-Fi yang sama. */
    const val DEFAULT_BASE_URL = "http://192.168.18.70:20128/v1"

    /** Alias khusus emulator Android untuk mesin host (PC yang menjalankan 9Router). */
    const val EMULATOR_BASE_URL = "http://10.0.2.2:20128/v1"

    /** Base URL lama (Groq Cloud), disimpan sebagai jalan pintas bila masih dipakai. */
    const val GROQ_BASE_URL = "https://api.groq.com/openai/v1"

    /**
     * Model default. Prefix `kr/` = provider Kiro AI di 9Router (Claude 4.5 + GLM-5 +
     * MiniMax, tier gratis). Nama model ditulis persis seperti yang terlihat di
     * dashboard 9Router; ganti bila ingin model lain (mis. `or/...`, `glm/...`).
     */
    const val DEFAULT_MODEL = "kr/claude-sonnet-4.5"

    /** Model cadangan yang dicoba berurutan bila model utama ditolak server. */
    val FALLBACK_MODELS = listOf(
        "kr/claude-sonnet-4.5",
        "kr/claude-haiku-4.5",
        "oc/gpt-5",
        "glm/glm-4.6"
    )

    private const val PREFS = "ScalpingPrefs"
    private const val KEY_BASE_URL = "ai_base_url"
    private const val KEY_API_KEY = "ai_api_key"
    private const val KEY_MODEL = "ai_model"

    // ---- Key lama, hanya dibaca sebagai nilai awal agar setelan pengguna lama tidak hilang ----
    private const val LEGACY_KEY_API_KEY = "groq_api_key"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Base URL aktif. Bila belum pernah diatur, memakai [DEFAULT_BASE_URL]. */
    fun baseUrl(ctx: Context): String =
        prefs(ctx).getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL

    /**
     * API Key aktif.
     *
     * Urutan pembacaan: kunci baru -> kunci lama (`groq_api_key`) -> kosong. Cara ini
     * membuat pengguna yang sudah menyimpan API Key Groq tetap bisa langsung memakai
     * aplikasi tanpa harus mengisi ulang.
     */
    fun apiKey(ctx: Context): String {
        val p = prefs(ctx)
        return p.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }
            ?: p.getString(LEGACY_KEY_API_KEY, null)?.takeIf { it.isNotBlank() }
            ?: ""
    }

    /** Model aktif. Bila belum pernah diatur, memakai [DEFAULT_MODEL]. */
    fun model(ctx: Context): String =
        prefs(ctx).getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL

    /** Daftar model yang dicoba berurutan: model aktif lebih dulu, lalu cadangan unik. */
    fun modelChain(ctx: Context): List<String> {
        val aktif = model(ctx)
        return (listOf(aktif) + FALLBACK_MODELS).distinct()
    }

    /** Menyimpan seluruh konfigurasi sekaligus (dipanggil dari dialog ⚙️ API Key). */
    fun save(ctx: Context, baseUrl: String, apiKey: String, model: String) {
        prefs(ctx).edit()
            .putString(KEY_BASE_URL, baseUrl.trim().ifBlank { DEFAULT_BASE_URL })
            .putString(KEY_API_KEY, apiKey.trim())
            .putString(KEY_MODEL, model.trim().ifBlank { DEFAULT_MODEL })
            .apply()
    }

    /**
     * Mengubah base URL menjadi endpoint chat completions.
     *
     * Pengguna boleh menulis dalam beberapa bentuk dan semuanya dinormalkan:
     * `http://ip:20128`, `http://ip:20128/v1`, atau URL lengkap
     * `http://ip:20128/v1/chat/completions`.
     */
    fun chatEndpoint(ctx: Context): String = chatEndpoint(baseUrl(ctx))

    fun chatEndpoint(baseUrl: String): String {
        var base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) base = DEFAULT_BASE_URL
        return when {
            base.endsWith("/chat/completions") -> base
            base.endsWith("/v1") -> "$base/chat/completions"
            else -> "$base/v1/chat/completions"
        }
    }
}
