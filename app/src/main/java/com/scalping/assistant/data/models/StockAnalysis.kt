package com.scalping.assistant.data.models

enum class Recommendation(val label: String, val colorCode: String) {
    STRONG_BUY("STRONG BUY", "#10B981"),
    BUY("BUY", "#34D399"),
    WATCH("WATCH", "#F59E0B"),
    AVOID("AVOID", "#EF4444")
}

data class OrderFlowResult(
    val ticker: String,
    val deltaVolumeScore: Int = 0, // 0 - 25
    val stabilityScore: Int = 0,   // 0 - 15
    val fakeWallPenalty: Int = 0,  // 0 or -10
    val absorptionBonus: Int = 0,  // 0 or +10
    val breakoutBonus: Int = 0,    // 0 or +10
    val totalScore: Int = 0,       // 0 - 50
    val hasFakeWall: Boolean = false,
    val hasAbsorption: Boolean = false,
    val hasAccumulation: Boolean = false, // Ritel jual tapi harga bertahan = akumulasi bandar
    val hasBreakoutSignal: Boolean = false,
    val hasBearTrap: Boolean = false,
    val cumulativeDelta: Long = 0L,
    val details: List<String> = emptyList()
)

data class TapeReadingStat(
    val ticker: String,
    var totalHakaLot: Long = 0L,
    var totalHakiLot: Long = 0L,
    var hakaFrequency: Int = 0,
    var hakiFrequency: Int = 0,
    var lastUpdated: Long = System.currentTimeMillis()
)

data class StockAnalysis(
    val ticker: String,
    val score: Int, // 0 - 100
    val recommendation: Recommendation,
    val lastPrice: Int,
    val changePercent: Double,
    val entryPrice: Int,
    val targetPrice: Int,
    val stopLoss: Int,
    val riskRewardRatio: Double,
    val style: String,
    val estimatedProfitPercent: Double,
    val reasons: List<String>,
    val warnings: List<String>,
    val technical: TechnicalResult,
    val orderFlow: OrderFlowResult,
    val tapeReading: TapeReadingStat? = null,
    val snapshotCount: Int = 0,     // Jumlah snapshot yang sudah terkumpul (confidence level)
    val lastUpdated: Long = System.currentTimeMillis()
)
