package com.scalping.assistant.engine

object PriceFraction {
    fun getTickSize(price: Int): Int {
        return when {
            price < 200 -> 1
            price < 500 -> 2
            price < 2000 -> 5
            price < 5000 -> 10
            else -> 25
        }
    }

    fun roundToValidTick(price: Int): Int {
        if (price <= 0) return 0
        val tick = getTickSize(price)
        return (price / tick) * tick
    }

    fun roundUpToValidTick(price: Int): Int {
        if (price <= 0) return 0
        val tick = getTickSize(price)
        val remainder = price % tick
        return if (remainder == 0) price else price + (tick - remainder)
    }

    fun roundDownToValidTick(price: Int): Int {
        return roundToValidTick(price)
    }

    fun getNextTickUp(price: Int, steps: Int = 1): Int {
        var current = if (price <= 0) 50 else price
        for (i in 0 until steps) {
            val tick = getTickSize(current)
            current += tick
            current = roundToValidTick(current)
        }
        return current
    }

    fun getNextTickDown(price: Int, steps: Int = 1): Int {
        var current = if (price <= 0) 50 else price
        for (i in 0 until steps) {
            val tick = if (current <= 200) 1 else getTickSize(current - 1)
            current = kotlin.math.max(1, current - tick)
            current = roundToValidTick(current)
        }
        return kotlin.math.max(1, current)
    }
}

