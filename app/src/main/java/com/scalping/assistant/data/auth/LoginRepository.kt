package com.scalping.assistant.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Locale

/**
 * Inti fitur Login Kode Redeem.
 *
 * ## Model keamanan
 * Verifikasi dilakukan ke **Cloud Firestore** (backend yang tidak bisa dimanipulasi pengguna),
 * bukan ke data lokal. Yang disimpan di perangkat hanyalah salinan sesi untuk pemakaian offline
 * berdurasi pendek ([LoginConfig.VERIFY_TIMEOUT_MS]); setiap kali aplikasi dibuka, sesi
 * diverifikasi ulang ke server. Bila server menyatakan kode kadaluarsa/dicabut, pengguna
 * langsung dipaksa logout.
 *
 * ## Struktur data Firestore (diatur dari Firebase Console)
 *
 * Koleksi `redeem_codes` — **ID dokumen = kode redeem** (mis. `SCLP7K2M9QX4`):
 * ```json
 * {
 *   "active": true,                 // set false untuk mencabut kode
 *   "expiresAt": 1767225600000,     // epoch millis kadaluarsa (wajib)
 *   "note": "Budi - paket 1 bulan", // opsional, catatan Anda
 *   "createdAt": 1764556800000,     // opsional
 *   "deviceId": "a1b2c3d4e5f6",     // DIISI OTOMATIS saat aktivasi pertama
 *   "activatedAt": 1764556900000,   // DIISI OTOMATIS
 *   "activationCount": 1            // DIISI OTOMATIS
 * }
 * ```
 *
 * Koleksi `activations` — **ID dokumen = device ID** (sidik perangkat anonim):
 * ```json
 * { "code": "SCLP7K2M9QX4", "activatedAt": 1764556900000 }
 * ```
 * Berguna untuk melihat daftar perangkat aktif dan memeriksa "1 perangkat = 1 kode".
 *
 * ## Catatan keamanan Firebase (WAJIB dibaca)
 * `LoginConfig.API_KEY` pada aplikasi Android bukan rahasia (memang begitu desain Firebase).
 * Yang melindungi data adalah **Security Rules**. Gunakan aturan berikut di Firebase Console →
 * Firestore → Rules supaya pengguna hanya boleh MEMBACA kode miliknya dan tidak bisa
 * mengubah basis data:
 * ```
 * rules_version = '2';
 * service cloud.firestore {
 *   match /databases/{database}/documents {
 *     match /redeem_codes/{code} {
 *       allow read: if true;      // verifikasi kode saat login
 *       allow write: if false;    // hanya Console / generator (admin) yang boleh menulis
 *     }
 *     match /activations/{deviceId} {
 *       allow read, write: if false; // diisi lewat jalur admin saja
 *     }
 *   }
 * }
 * ```
 * Bila Anda ingin aktivasi perangkat dicatat otomatis oleh aplikasi, buka izin tulis khusus
 * untuk koleksi `activations` sesuai kebutuhan.
 */
class LoginRepository(private val ctx: Context) {

    private val prefs: SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val prefsClock: SharedPreferences = ctx.getSharedPreferences(PREFS_CLOCK, Context.MODE_PRIVATE)

    private val deviceId: String by lazy { buildDeviceId(ctx) }

    // ---------------------------------------------------------------- Firestore

    /** Firestore siap pakai; null bila konfigurasi belum diisi (mode pengembangan). */
    private val firestore: FirebaseFirestore? by lazy {
        if (!LoginConfig.isConfigured) return@lazy null
        try {
            val app = FirebaseApp.getApps(ctx).firstOrNull()
                ?: FirebaseApp.initializeApp(ctx, FirebaseOptions.Builder()
                    .setProjectId(LoginConfig.PROJECT_ID)
                    .setApplicationId(LoginConfig.APPLICATION_ID)
                    .setApiKey(LoginConfig.API_KEY)
                    .build())
            FirebaseFirestore.getInstance(app)
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------- API utama

    /** true bila fitur login memang diaktifkan (Firebase terkonfigurasi). */
    fun isLoginEnabled(): Boolean = LoginConfig.isConfigured

    /** Sesi tersimpan, atau null bila belum pernah login / sudah di-logout. */
    fun currentSession(): LoginSession? {
        val code = prefs.getString(KEY_CODE, null) ?: return null
        val exp = prefs.getLong(KEY_EXPIRES, 0L)
        val act = prefs.getLong(KEY_ACTIVATED, 0L)
        val ver = prefs.getLong(KEY_LAST_VERIFY, 0L)
        val dev = prefs.getString(KEY_DEVICE, "") ?: ""
        return LoginSession(code, dev.ifEmpty { deviceId }, exp, act, ver)
    }

    /** Menghapus sesi (dipakai saat logout manual maupun paksa karena kadaluarsa). */
    fun logout() {
        prefs.edit().clear().apply()
    }

    /**
     * Aktivasi kode redeem (login pertama).
     *
     * Alur: validasi bentuk -> baca dokumen kode dari Firestore -> periksa aktif & kadaluarsa
     * -> periksa pengikatan perangkat -> simpan sesi lokal.
     */
    suspend fun redeem(rawCode: String): LoginResult = withContext(Dispatchers.IO) {
        if (!isLoginEnabled()) return@withContext LoginResult.Failure(
            LoginFailReason.NOT_CONFIGURED,
            "Fitur login belum diaktifkan oleh developer (Firebase belum dikonfigurasi)."
        )

        val code = LoginFormat.normalizeCode(rawCode)
        if (!LoginFormat.isWellFormed(code)) {
            return@withContext LoginResult.Failure(
                LoginFailReason.NOT_FOUND,
                "Format kode tidak dikenal. Kode harus diawali \"${LoginConfig.CODE_PREFIX}\"."
            )
        }

        val db = firestore ?: return@withContext LoginResult.Failure(
            LoginFailReason.NOT_CONFIGURED,
            "Tidak bisa menyiapkan koneksi Firebase."
        )

        // Deteksi jam perangkat mundur sebelum apa pun (anti manipulasi waktu).
        if (detectClockRollback()) {
            return@withContext LoginResult.Failure(
                LoginFailReason.CLOCK_ROLLBACK,
                "Waktu perangkat terdeteksi mundur. Setel jam otomatis (NTP) lalu coba lagi."
            )
        }

        val doc = try {
            db.collection(LoginConfig.COLLECTION_REDEEM_CODES).document(code).get().await()
        } catch (e: Exception) {
            return@withContext LoginResult.Failure(
                LoginFailReason.NETWORK,
                "Gagal menghubungi server: ${e.message ?: "jaringan bermasalah"}"
            )
        }

        if (!doc.exists()) {
            return@withContext LoginResult.Failure(LoginFailReason.NOT_FOUND, "Kode redeem tidak ditemukan.")
        }

        val active = doc.getBoolean("active") ?: true
        val expiresAt = readExpiryMillis(doc)
        val boundDevice = doc.getString("deviceId")?.trim().orEmpty()

        if (!active) {
            return@withContext LoginResult.Failure(LoginFailReason.REVOKED, "Kode redeem ini sudah dinonaktifkan.")
        }
        if (expiresAt <= 0L) {
            return@withContext LoginResult.Failure(
                LoginFailReason.UNKNOWN,
                "Kode ini belum memiliki tanggal kadaluarsa. Hubungi developer."
            )
        }
        if (System.currentTimeMillis() >= expiresAt) {
            return@withContext LoginResult.Failure(
                LoginFailReason.EXPIRED,
                "Kode redeem sudah kadaluarsa pada ${LoginFormat.formatExpiry(expiresAt)}."
            )
        }
        if (boundDevice.isNotEmpty() && boundDevice != deviceId) {
            return@withContext LoginResult.Failure(
                LoginFailReason.DEVICE_MISMATCH,
                "Kode ini sudah diaktifkan di perangkat lain. Satu kode hanya untuk satu perangkat."
            )
        }

        // Ikat kode ke perangkat ini bila belum terikat, lalu catat aktivasi.
        try {
            val updates = mutableMapOf<String, Any>(
                "deviceId" to deviceId,
                "activatedAt" to System.currentTimeMillis(),
                "activationCount" to FieldValue.increment(1L)
            )
            db.collection(LoginConfig.COLLECTION_REDEEM_CODES).document(code)
                .set(updates, SetOptions.merge()).await()

            db.collection(LoginConfig.COLLECTION_ACTIVATIONS).document(deviceId)
                .set(mapOf(
                    "code" to code,
                    "activatedAt" to System.currentTimeMillis()
                ), SetOptions.merge()).await()
        } catch (e: Exception) {
            // Pencatatan gagal (mis. Security Rules menolak tulis) tidak boleh menghalangi
            // login: kode & kadaluarsa sudah terbukti sah. Kegagalan ini bersifat administratif.
        }

        saveSession(code, expiresAt)
        LoginResult.Success(
            code = code,
            expiresAtMillis = expiresAt,
            verifiedOnline = true,
            expiringSoon = LoginFormat.daysUntil(expiresAt) <= 7
        )
    }

    /**
     * Memastikan sesi tersimpan masih sah — dipanggil setiap aplikasi dibuka.
     *
     * Bila server tidak terjangkau, sesi terakhir tetap dipakai (mode offline) selama tanggal
     * kadaluarsa lokal belum lewat, supaya pengguna tidak terkunci hanya karena jaringan.
     * Namun bila server MENJAWAB dan menyatakan kadaluarsa/dicabut, pemakaian offline
     * dihentikan (pengguna dipaksa logout).
     */
    suspend fun verifyStoredSession(): LoginResult = withContext(Dispatchers.IO) {
        if (!isLoginEnabled()) return@withContext LoginResult.Success("", 0L, verifiedOnline = false, expiringSoon = false)

        val session = currentSession() ?: return@withContext LoginResult.Failure(
            LoginFailReason.NOT_FOUND, "Belum ada sesi login."
        )

        if (detectClockRollback()) {
            return@withContext LoginResult.Failure(
                LoginFailReason.CLOCK_ROLLBACK,
                "Waktu perangkat terdeteksi mundur. Setel jam otomatis (NTP) lalu masuk kembali."
            )
        }

        val db = firestore ?: return@withContext offlineDecision(session)

        val doc = try {
            db.collection(LoginConfig.COLLECTION_REDEEM_CODES).document(session.code).get().await()
        } catch (e: Exception) {
            return@withContext offlineDecision(session)
        }

        // Server menjawab: keputusan server yang berlaku.
        if (!doc.exists()) {
            logout()
            return@withContext LoginResult.Failure(LoginFailReason.REVOKED, "Kode redeem tidak lagi terdaftar.")
        }
        val active = doc.getBoolean("active") ?: true
        val expiresAt = readExpiryMillis(doc).takeIf { it > 0L } ?: session.expiresAtMillis
        val boundDevice = doc.getString("deviceId")?.trim().orEmpty()

        if (!active) {
            logout()
            return@withContext LoginResult.Failure(LoginFailReason.REVOKED, "Kode redeem sudah dinonaktifkan.")
        }
        if (System.currentTimeMillis() >= expiresAt) {
            logout()
            return@withContext LoginResult.Failure(
                LoginFailReason.EXPIRED,
                "Masa berlaku kode sudah berakhir pada ${LoginFormat.formatExpiry(expiresAt)}."
            )
        }
        if (boundDevice.isNotEmpty() && boundDevice != deviceId) {
            logout()
            return@withContext LoginResult.Failure(
                LoginFailReason.DEVICE_MISMATCH,
                "Kode ini dipakai di perangkat lain. Silakan masuk kembali dengan kode Anda."
            )
        }

        saveSession(session.code, expiresAt)
        LoginResult.Success(
            code = session.code,
            expiresAtMillis = expiresAt,
            verifiedOnline = true,
            expiringSoon = LoginFormat.daysUntil(expiresAt) <= 7
        )
    }

    // ---------------------------------------------------------------- Internal

    /**
     * Membaca `expiresAt` dengan toleran terhadap tipe data di Firestore Console.
     *
     * Developer bisa memasukkan field ini sebagai **number** (epoch millis, disarankan) atau
     * sebagai **timestamp** lewat pemilih tipe di Console. Keduanya diterima supaya kode tidak
     * diam-diam dianggap "tanpa tanggal kadaluarsa" hanya karena beda tipe field.
     */
    private fun readExpiryMillis(doc: com.google.firebase.firestore.DocumentSnapshot): Long {
        val asNumber = doc.getLong("expiresAt")
        if (asNumber != null && asNumber > 0L) return asNumber
        val asTimestamp = doc.getTimestamp("expiresAt")
        return asTimestamp?.toDate()?.time ?: 0L
    }

    /** Keputusan saat server tidak bisa dihubungi: pakai sesi lokal bila belum kadaluarsa. */
    private fun offlineDecision(session: LoginSession): LoginResult {
        return if (System.currentTimeMillis() < session.expiresAtMillis) {
            LoginResult.Success(
                code = session.code,
                expiresAtMillis = session.expiresAtMillis,
                verifiedOnline = false,
                expiringSoon = LoginFormat.daysUntil(session.expiresAtMillis) <= 7
            )
        } else {
            logout()
            LoginResult.Failure(
                LoginFailReason.EXPIRED,
                "Masa berlaku kode sudah berakhir. Sambungkan internet lalu masuk dengan kode baru."
            )
        }
    }

    private fun saveSession(code: String, expiresAt: Long) {
        prefs.edit()
            .putString(KEY_CODE, code)
            .putString(KEY_DEVICE, deviceId)
            .putLong(KEY_EXPIRES, expiresAt)
            .putLong(KEY_ACTIVATED, prefs.getLong(KEY_ACTIVATED, System.currentTimeMillis()))
            .putLong(KEY_LAST_VERIFY, System.currentTimeMillis())
            .apply()
        // Patokan jam disimpan seusai verifikasi yang sah, sebagai dasar deteksi jam mundur.
        prefsClock.edit()
            .putLong(KEY_MAX_WALL, maxOf(prefsClock.getLong(KEY_MAX_WALL, 0L), System.currentTimeMillis()))
            .apply()
    }

    /**
     * Mendeteksi jam perangkat mundur: waktu dinding kini lebih kecil dari patokan tertinggi
     * yang pernah tercatat. Patokan ini hanya diperbarui saat verifikasi login yang sah,
     * sehingga memundurkan jam untuk "memperpanjang" masa berlaku justru menandai kecurangan.
     */
    private fun detectClockRollback(): Boolean {
        val maxWall = prefsClock.getLong(KEY_MAX_WALL, 0L)
        if (maxWall <= 0L) return false
        // Beri toleransi 2 menit untuk penyesuaian jam kecil/NTP yang wajar.
        return System.currentTimeMillis() < maxWall - 2L * 60L * 1000L
    }

    private companion object {
        const val PREFS = "login_session"
        const val PREFS_CLOCK = "login_clock"
        const val KEY_CODE = "code"
        const val KEY_DEVICE = "device_id"
        const val KEY_EXPIRES = "expires_at"
        const val KEY_ACTIVATED = "activated_at"
        const val KEY_LAST_VERIFY = "last_verified"
        const val KEY_MAX_WALL = "max_wall_clock"

        /**
         * Device ID anonim: hash dari ANDROID_ID + model. Tidak memakai data pribadi dan stabil
         * untuk perangkat yang sama. Format pendek 16 karakter agar rapi sebagai ID dokumen.
         */
        fun buildDeviceId(ctx: Context): String {
            val androidId = try {
                Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
            } catch (e: Exception) {
                ""
            }
            val raw = "$androidId|${Build.MANUFACTURER}|${Build.MODEL}|$APP_SALT"
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            val hex = digest.joinToString("") { "%02x".format(it) }.lowercase(Locale.US)
            return hex.take(16)
        }

        /** Garam tetap: mencegah device ID mudah direplikasi dari ANDROID_ID mentah. */
        const val APP_SALT = "scalping-assistant-v1"
    }
}
