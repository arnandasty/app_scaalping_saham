package com.scalping.assistant.data.auth

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Alasan login ditolak / dipaksa logout, dipakai UI untuk menampilkan pesan yang tepat. */
enum class LoginFailReason {
    /** Kode tidak ada di database (salah ketik / tidak pernah diterbitkan). */
    NOT_FOUND,

    /** Kode sudah melewati tanggal kadaluarsa. */
    EXPIRED,

    /** Kode dinonaktifkan manual oleh developer (revoke) sebelum kadaluarsa. */
    REVOKED,

    /** Kode sudah diaktifkan di perangkat lain (1 kode = 1 perangkat). */
    DEVICE_MISMATCH,

    /** Kode sudah dipakai / slot aktivasi penuh. */
    USED,

    /** Jam perangkat mundur dari waktu server (indikasi manipulasi waktu). */
    CLOCK_ROLLBACK,

    /** Tidak bisa terhubung ke server. */
    NETWORK,

    /** Firebase belum dikonfigurasi oleh developer. */
    NOT_CONFIGURED,

    /** Kegagalan tak terduga. */
    UNKNOWN
}

/** Hasil percobaan login / verifikasi. */
sealed class LoginResult {
    data class Success(
        val code: String,
        val expiresAtMillis: Long,
        /** true bila keabsahan dipastikan server; false bila memakai status tersimpan (offline). */
        val verifiedOnline: Boolean,
        /** true bila masa berlaku sangat dekat sehingga perlu diberi peringatan. */
        val expiringSoon: Boolean
    ) : LoginResult()

    data class Failure(
        val reason: LoginFailReason,
        val message: String
    ) : LoginResult()
}

/** Ringkasan sesi login yang tersimpan di perangkat. */
data class LoginSession(
    val code: String,
    val deviceId: String,
    val expiresAtMillis: Long,
    val activatedAtMillis: Long,
    /** Waktu terakhir server memverifikasi keabsahan sesi ini. */
    val lastVerifiedMillis: Long
) {
    /** true bila tanggal kadaluarsa sudah terlewat. */
    val isExpired: Boolean get() = System.currentTimeMillis() >= expiresAtMillis
}

/** Utilitas format tanggal/kode yang dipakai bersama UI dan generator. */
object LoginFormat {

    private val dateFmt = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.forLanguageTag("id-ID"))

    /** Format tanggal-kadaluarsa yang enak dibaca, zona waktu perangkat. */
    fun formatExpiry(millis: Long): String = dateFmt.format(Date(millis))

    /** Sisa hari menuju kadaluarsa (bisa 0 bila tinggal beberapa jam). */
    fun daysUntil(millis: Long): Long {
        val diff = millis - System.currentTimeMillis()
        return if (diff <= 0) 0 else diff / (24L * 60 * 60 * 1000)
    }

    /** Menormalkan masukan kode: huruf besar, buang spasi/strip, dan tambahkan prefix bila perlu. */
    fun normalizeCode(raw: String): String {
        var s = raw.trim().uppercase(Locale.US).replace(" ", "").replace("-", "")
        // Tolak karakter aneh agar tidak pernah menulis ID dokumen yang tidak wajar.
        s = s.filter { it.isLetterOrDigit() }
        if (s.isNotEmpty() && !s.startsWith(LoginConfig.CODE_PREFIX)) {
            // Pengguna biasanya hanya mengetik 8 karakter acak tanpa prefix.
            s = LoginConfig.CODE_PREFIX + s
        }
        return s
    }

    /** Validasi bentuk kode: prefix benar dan panjang wajar (>= prefix + 6 karakter). */
    fun isWellFormed(code: String): Boolean =
        code.length >= LoginConfig.CODE_PREFIX.length + 6 &&
            code.startsWith(LoginConfig.CODE_PREFIX)
}
