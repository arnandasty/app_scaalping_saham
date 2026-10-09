package com.scalping.assistant.data.auth

/**
 * Konfigurasi Firebase untuk fitur Login Kode Redeem.
 *
 * Nilai-nilai ini HANYA perlu diisi SEKALI oleh developer, diambil dari
 * **Firebase Console → Project settings → General → Your apps → (app Android) → SDK setup**.
 * Tidak perlu menaruh `google-services.json`: inisialisasi dilakukan secara programatik di
 * [LoginRepository] supaya seluruh konfigurasi terkumpul di satu tempat yang mudah ditinjau.
 *
 * Cara mengaktifkan:
 * 1. Buat project Firebase (gratis), lalu tambahkan aplikasi Android dengan package
 *    `com.scalping.assistant`.
 * 2. Buat database **Cloud Firestore** (mode produksi).
 * 3. Salin `projectId`, `applicationId` (App ID, format `1:123...:android:abc...`), dan
 *    `apiKey` dari SDK setup ke konstanta di bawah.
 * 4. Terbitkan kode redeem (lihat format koleksi di [LoginRepository]).
 *
 * Selama [PROJECT_ID] masih kosong, aplikasi berjalan dalam MODE PENGEMBANGAN: login
 * dilewati (tidak ada gerbang kode redeem) supaya build & pengujian fitur lain tetap lancar.
 */
object LoginConfig {

    /** Project ID Firebase, mis. `scalping-assistant-a1b2c`. */
    const val PROJECT_ID = "scalping-assistant"

    /** App ID Firebase, mis. `1:1234567890:android:1a2b3c4d5e6f7g8h`. */
    const val APPLICATION_ID = "1:122081106113:android:ad3812dcb92e4c581fc40"

    /** Web API Key dari SDK setup Firebase. */
    const val API_KEY = "AIzaSyBU7LXc9m_s4fjKg97tEt1Fd_D0KaNIK6Y"

    /** Nama koleksi Firestore yang menyimpan dokumen kode redeem (ID dokumen = kodenya). */
    const val COLLECTION_REDEEM_CODES = "redeem_codes"

    /** Nama koleksi Firestore yang menyimpan aktivasi per perangkat (ID dokumen = device ID). */
    const val COLLECTION_ACTIVATIONS = "activations"

    /**
     * Prefix kode redeem. Dipakai aplikasi untuk memvalidasi FORMAT kode sebelum menanyakan
     * ke server, dan dipakai alat generator di `tools/redeem/` saat menerbitkan kode baru.
     */
    const val CODE_PREFIX = "SCLP"

    /** true bila konfigurasi Firebase sudah diisi sehingga login bisa dijalankan. */
    val isConfigured: Boolean
        get() = PROJECT_ID.isNotBlank() && APPLICATION_ID.isNotBlank()

    /**
     * Timeout verifikasi ulang saat aplikasi dibuka. Bila server tidak terjangkau dalam
     * rentang ini, status login terakhir tetap dipakai (mode offline) supaya pengguna tidak
     * terkunci hanya karena jaringan bermasalah — kecuali server secara jelas menyatakan
     * kode sudah kadaluarsa/dicabut.
     */
    const val VERIFY_TIMEOUT_MS = 8000L
}
