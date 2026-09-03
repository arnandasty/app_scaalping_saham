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
    val hasBreakoutSignal: Boolean = false,
    val hasBearTrap: Boolean = false,
    val cumulativeDelta: Long = 0L,
    val details: List<String> = emptyList()
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
    val estimatedProfitPercent: Double,
    val reasons: List<String>,
    val warnings: List<String>,
    val technical: TechnicalResult,
    val orderFlow: OrderFlowResult,
    val lastUpdated: Long = System.currentTimeMillis()
)
