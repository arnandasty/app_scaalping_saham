package com.scalping.assistant.engine

import java.util.Calendar
import java.util.TimeZone

enum class MarketPhase(val label: String, val scoreBonus: Int, val isTradeable: Boolean, val colorHex: String) {
    PRE_OPEN("Pre-Opening (08:45-09:00)", 2, false, "#F59E0B"),
    SESSION_1_OPEN("Sesi 1: Open Volatile (09:00-09:30)", 5, true, "#38BDF8"),
    SESSION_1_PRIME("Sesi 1: Prime Trade (09:30-11:15)", 8, true, "#10B981"),
    SESSION_1_LATE("Sesi 1: Menjelang Istirahat", 3, true, "#F59E0B"),
    BREAK("Istirahat Siang", 0, false, "#64748B"),
    SESSION_2_PRIME("Sesi 2: Momentum (13:30-14:45)", 6, true, "#10B981"),
    SESSION_2_LATE("Sesi 2: Menjelang Tutup (14:45-15:45)", 1, false, "#EF4444"),
    PRE_CLOSE("Pre-Closing Auction", -10, false, "#EF4444"),
    CLOSED("Market Tutup", 0, false, "#64748B")
}

data class SessionInfo(
    val phase: MarketPhase,
    val timeDisplay: String,
    val note: String
)

object MarketSession {
    fun getCurrentSession(): SessionInfo {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Jakarta"))
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)

        // Weekend: Sabtu (7), Minggu (1)
        if (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) {
            return SessionInfo(MarketPhase.CLOSED, "Sabtu/Minggu - Libur", "Pasar saham tutup di akhir pekan.")
        }

        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val timeInMinutes = hour * 60 + minute

        val isFriday = (dayOfWeek == Calendar.FRIDAY)

        val session1Start = 9 * 60
        val session1End = if (isFriday) (11 * 60 + 30) else (12 * 60)
        val session2Start = if (isFriday) (14 * 60) else (13 * 60 + 30)
        val session2End = 15 * 60 + 49
        val preCloseEnd = 16 * 60

        val timeStr = String.format("%02d:%02d WIB", hour, minute)

        val phase = when {
            timeInMinutes < 8 * 60 + 45 -> MarketPhase.CLOSED
            timeInMinutes in (8 * 60 + 45) until session1Start -> MarketPhase.PRE_OPEN
            timeInMinutes in session1Start until (session1Start + 30) -> MarketPhase.SESSION_1_OPEN
            timeInMinutes in (session1Start + 30) until (session1End - 30) -> MarketPhase.SESSION_1_PRIME
            timeInMinutes in (session1End - 30) until session1End -> MarketPhase.SESSION_1_LATE
            timeInMinutes in session1End until session2Start -> MarketPhase.BREAK
            timeInMinutes in session2Start until (session2End - 30) -> MarketPhase.SESSION_2_PRIME
            timeInMinutes in (session2End - 30) until session2End -> MarketPhase.SESSION_2_LATE
            timeInMinutes in session2End until preCloseEnd -> MarketPhase.PRE_CLOSE
            else -> MarketPhase.CLOSED
        }

        val note = when (phase) {
            MarketPhase.SESSION_1_PRIME -> "Waktu terbaik untuk day trade! Likuiditas & tren terbentuk."
            MarketPhase.SESSION_1_OPEN -> "Volatilitas tinggi open market. Hati-hati bid/offer loncat."
            MarketPhase.SESSION_2_PRIME -> "Momentum sesi siang berjalan. Perhatikan hajar kanan."
            MarketPhase.SESSION_1_LATE -> "Menjelang jeda. Waspadai aksi profit taking."
            MarketPhase.SESSION_2_LATE -> "Sistem BSJP Aktif 🌙 Cek Top Picks untuk kandidat overnight!"
            MarketPhase.PRE_CLOSE -> "Pre-Closing Auction 🌙 Eksekusi BSJP sekarang jika ada kandidat kuat!"
            MarketPhase.BREAK -> "Market istirahat. Analisis sinyal untuk persiapan sesi 2."
            MarketPhase.CLOSED -> "Market tutup. Gunakan mode review & simulasi."
            MarketPhase.PRE_OPEN -> "Pra-pembukaan bursa. Orderbook mulai terisi."
        }

        return SessionInfo(phase, timeStr, note)
    }
}
