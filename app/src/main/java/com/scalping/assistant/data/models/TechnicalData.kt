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

    val totalScore: Int = 0 // 0 - 30
)
