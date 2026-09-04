package com.scalping.assistant.data.models

data class Candle(
    val timestamp: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long
)

data class TechnicalResult(
    val ticker: String,
    val lastClosePrice: Double = 0.0,
    val vwap: Double = 0.0,
    val mfi: Double = 0.0,

    val ema9: Double = 0.0,
    val ema21: Double = 0.0,
    val emaScore: Int = 0, // 0 - 6

    val bbUpper: Double = 0.0,
    val bbMiddle: Double = 0.0,
    val bbLower: Double = 0.0,
    val bbScore: Int = 0, // 0 - 6

    val macdLine: Double = 0.0,
    val macdSignal: Double = 0.0,
    val macdHistogram: Double = 0.0,
    val macdScore: Int = 0, // 0 - 6

    val supertrendValue: Double = 0.0,
    val isSupertrendBullish: Boolean = false,
    val supertrendScore: Int = 0, // 0 - 6

    val fibLevels: Map<String, Double> = emptyMap(),
    val fibScore: Int = 0, // 0 - 6
    val nearestResistance: Double = 0.0,
    val nearestSupport: Double = 0.0,
    
    val nearestResistance2: Double = 0.0,
    val nearestSupport2: Double = 0.0,

    // === Filter Penguatan Sinyal (5-Layer) ===
    val ema50: Double = 0.0,            // EMA 50 hari untuk filter tren makro
    val isBelowEma50: Boolean = false,  // true jika harga di bawah EMA 50 (tren mayor bearish)
    val volumeRatio: Double = 1.0,      // Volume hari ini vs rata-rata 5 hari (< 1 = sepi)
    val todayHigh: Double = 0.0,        // High harga hari ini (untuk filter cooldown drop)
    val prevHighScore: Int = 0,         // Skor snapshot sebelumnya (untuk double confirm)

    val totalScore: Int = 0 // 0 - 30
)
