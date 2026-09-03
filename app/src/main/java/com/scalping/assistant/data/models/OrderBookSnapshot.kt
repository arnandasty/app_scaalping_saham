package com.scalping.assistant.data.models

data class PriceLevel(
    val price: Int,
    val lot: Long,
    val frequency: Int = 0
)

data class OrderBookSnapshot(
    val ticker: String,
    val lastPrice: Int,
    val changePercent: Double,
    val timestamp: Long,
    val bidLevels: List<PriceLevel>,
    val offerLevels: List<PriceLevel>,
    val totalBidLot: Long,
    val totalOfferLot: Long
)
