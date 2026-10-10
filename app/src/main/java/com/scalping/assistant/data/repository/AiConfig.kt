package com.scalping.assistant.data.repository

import android.content.Context

/**
 * Konfigurasi endpoint AI yang dipakai seluruh fitur opini AI
 * (Opini Scalper, Analisis Swing, dan Dokter Portfolio).
 *
 * Default: TokenHarbor cloud (OpenAI-compatible) dengan model gratisan DeepSeek,
 * jadi HP langsung tembak internet tanpa perlu 9Router di PC / satu Wi-Fi.
 * API Key TokenHarbor (thk_live_...) wajib diisi lewat dialog ⚙️ API Key.
 *
 * Alternatif tetap didukung lewat dialog yang sama tanpa build ulang:
 * - 9Router lokal di PC (`http://IP-LAN-PC:20128/v1`, tanpa key bila tanpa auth).
 * - Emulator Android di PC yang sama memakai `http://10.0.2.2:20128/v1`.
 * - Groq Cloud (`https://api.groq.com/openai/v1`, wajib API Key Groq).
 */
object AiConfig {

    /** Base URL default: TokenHarbor cloud. */
    const val DEFAULT_BASE_URL = "https://tokenharbor.ai/v1"

    /** Alias khusus emulator Android untuk mesin host (PC yang menjalankan 9Router). */
    const val EMULATOR_BASE_URL = "http://10.0.2.2:20128/v1"

    /** Base URL lama (Groq Cloud), disimpan sebagai jalan pintas bila masih dipakai. */
    const val GROQ_BASE_URL = "https://api.groq.com/openai/v1"

    /**
     * Model default: DeepSeek gratisan di TokenHarbor (`:free` = rute gratis).
     * Daftar model live ada di https://tokenharbor.ai/models.
     */
    const val DEFAULT_MODEL = "tokenharbor/deepseek-v4.1-flash:free"

    /** Model cadangan yang dicoba berurutan bila model utama ditolak server. */
    val FALLBACK_MODELS = listOf(
        "tokenharbor/deepseek-v4.1-flash:free",
        "deepseek-v4.1-flash:free",
        "deepseek-v4.1-flash",
        "kr/claude-haiku-4.5"
    )

    private const val PREFS = "ScalpingPrefs"
    private const val KEY_BASE_URL = "ai_base_url"
    private const val KEY_API_KEY = "ai_api_key"
    private const val KEY_MODEL = "ai_model"

    // ---- Key lama, hanya dibaca sebagai nilai awal agar setelan pengguna lama tidak hilang ----
    private const val LEGACY_KEY_API_KEY = "groq_api_key"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Base URL aktif. Bila belum pernah diatur, memakai [DEFAULT_BASE_URL].
     * Migrasi otomatis: pengguna lama yang masih menyimpan Base URL 9Router era
     * sebelum TokenHarbor (IP LAN / localhost / 10.0.2.2 port 20128) langsung
     * ikut pindah ke default TokenHarbor tanpa perlu hapus data aplikasi.
     */
    fun baseUrl(ctx: Context): String {
        val saved = prefs(ctx).getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() }
            ?: return DEFAULT_BASE_URL
        val low = saved.lowercase()
        val isLegacy9Router = (low.contains(":20128") &&
            (low.contains("192.168.") || low.contains("10.0.2.2") ||
                low.contains("localhost") || low.contains("127.0.0.1"))) ||
            saved == "http://192.168.18.70:20128/v1"
        return if (isLegacy9Router) DEFAULT_BASE_URL else saved
    }

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
