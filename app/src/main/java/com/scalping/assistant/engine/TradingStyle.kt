package com.scalping.assistant.engine

import com.scalping.assistant.data.repository.CandleTimeframe

/**
 * Gaya trading yang menentukan CARA skor dihitung.
 *
 * Bobot tiap komponen berbeda karena yang penting untuk daytrade tidak sama dengan
 * yang penting untuk swing. Total bobot selalu 100 di kedua gaya, jadi angkanya
 * tetap bisa dibandingkan antar emiten.
 *
 * Contoh perbedaan nyata:
 *   - Daytrade butuh likuiditas & aliran dana detik ini -> Volume & Bandarmology besar,
 *     Moving Average jangka panjang hampir tidak relevan.
 *   - Swing 3-10 hari butuh TREN yang sehat -> Moving Average jadi komponen terbesar,
 *     sedangkan lonjakan volume sehari tidak terlalu berarti.
 */
enum class TradingStyle(
    val label: String,
    val tagline: String,
    /** Timeframe candle default untuk gaya ini. */
    val defaultTimeframe: CandleTimeframe,

    // ---- Bobot komponen (total harus 100) ----
    val wVolume: Int,
    val wBandar: Int,
    val wMacd: Int,
    val wRsi: Int,
    val wMa: Int,
    val wFib: Int,
    val wSmc: Int,
    val wStructure: Int
) {
    /**
     * Gaya daytrade: masuk & keluar di hari yang sama.
     *
     * Tetap memakai candle HARIAN (1D) bersama gaya swing, karena seluruh analisis
     * di tab Screener dibaca dari kerangka harian. Yang membedakan daytrade adalah
     * BOBOT skor dan preset penyaringnya (likuiditas & momentum), bukan timeframe.
     * Volume 20 + Bandar 18 + MACD 12 + RSI 10 + MA 8 + Fib 12 + SMC 15 + Struktur 5 = 100
     */
    DAYTRADE(
        label = "Daytrade",
        tagline = "Masuk & keluar di hari yang sama",
        defaultTimeframe = CandleTimeframe.BULAN3,
        wVolume = 20,
        wBandar = 18,
        wMacd = 12,
        wRsi = 10,
        wMa = 8,
        wFib = 12,
        wSmc = 15,
        wStructure = 5
    ),

    /**
     * Ditahan 3-10 hari.
     * Volume 15 + Bandar 15 + MACD 10 + RSI 10 + MA 18 + Fib 15 + SMC 12 + Struktur 5 = 100
     */
    SWING(
        label = "Swing 3-10 hari",
        tagline = "Ditahan 3-10 hari",
        defaultTimeframe = CandleTimeframe.BULAN3,
        wVolume = 15,
        wBandar = 15,
        wMacd = 10,
        wRsi = 10,
        wMa = 18,
        wFib = 15,
        wSmc = 12,
        wStructure = 5
    );

    /** Total bobot, untuk verifikasi cepat bahwa nilainya 100. */
    val totalWeight: Int
        get() = wVolume + wBandar + wMacd + wRsi + wMa + wFib + wSmc + wStructure
}
