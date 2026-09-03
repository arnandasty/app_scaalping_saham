package com.scalping.assistant.engine

import com.scalping.assistant.data.models.Candle
import com.scalping.assistant.data.models.TechnicalResult
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

object TechnicalAnalyzer {

    fun analyze(ticker: String, candles: List<Candle>, currentPrice: Double): TechnicalResult {
        if (candles.size < 26) {
            // Not enough candles, return basic result
            return TechnicalResult(ticker = ticker)
        }

        val closes = candles.map { it.close }
        val highs = candles.map { it.high }
        val lows = candles.map { it.low }

        val priceToUse = if (currentPrice > 0.0) currentPrice else closes.last()

        // 1. EMA 9 & EMA 21
        val ema9Values = calculateEMA(closes, 9)
        val ema21Values = calculateEMA(closes, 21)
        val lastEma9 = ema9Values.last()
        val lastEma21 = ema21Values.last()

        var emaScore = 0
        if (priceToUse > lastEma9 && lastEma9 > lastEma21) {
            emaScore = 6 // Uptrend sempurna
        } else if (priceToUse > lastEma9 && lastEma9 <= lastEma21) {
            emaScore = 4 // Baru golden cross / pembalikan
        } else if (priceToUse > lastEma21) {
            emaScore = 2
        } else {
            emaScore = 0
        }

        // 2. Bollinger Bands (20, 2)
        val (bbUpper, bbMiddle, bbLower) = calculateBollingerBands(closes, 20, 2.0)
        var bbScore = 0
        if (priceToUse <= bbLower * 1.01) {
            bbScore = 6 // Area pantulan bawah (oversold)
        } else if (priceToUse in (bbLower..bbMiddle)) {
            bbScore = 4 // Area bawah menuju tengah
        } else if (priceToUse in (bbMiddle..bbUpper * 0.98)) {
            bbScore = 3
        } else {
            bbScore = 1 // Dekat upper band (rawan koreksi)
        }

        // 3. MACD (12, 26, 9)
        val (macdLine, macdSignal, macdHist) = calculateMACD(closes, 12, 26, 9)
        var macdScore = 0
        if (macdLine > macdSignal && macdHist > 0) {
            macdScore = 6 // Bullish momentum kuat
        } else if (macdLine > macdSignal && macdHist <= 0) {
            macdScore = 4
        } else if (macdLine > 0) {
            macdScore = 2
        } else {
            macdScore = 0
        }

        // 4. Supertrend (ATR 10, Multiplier 3)
        val (stValue, isSupertrendBullish) = calculateSupertrend(highs, lows, closes, 10, 3.0)
        val supertrendScore = if (isSupertrendBullish) 6 else 0

        // 5. Fibonacci Retracement
        val recentHigh = highs.takeLast(50).maxOrNull() ?: priceToUse
        val recentLow = lows.takeLast(50).minOrNull() ?: priceToUse
        val fibLevels = calculateFibonacci(recentHigh, recentLow)

        val nearestResistance = fibLevels.values.filter { it > priceToUse }.minOrNull() ?: (priceToUse * 1.03)
        val nearestSupport = fibLevels.values.filter { it < priceToUse }.maxOrNull() ?: (priceToUse * 0.97)

        var fibScore = 0
        val fib618 = fibLevels["61.8%"] ?: 0.0
        val fib50 = fibLevels["50.0%"] ?: 0.0
        val fib382 = fibLevels["38.2%"] ?: 0.0

        if (abs(priceToUse - fib618) / priceToUse < 0.015) {
            fibScore = 6 // Golden ratio support
        } else if (abs(priceToUse - fib50) / priceToUse < 0.015) {
            fibScore = 5
        } else if (abs(priceToUse - fib382) / priceToUse < 0.015) {
            fibScore = 4
        } else {
            fibScore = 2
        }

        val totalScore = emaScore + bbScore + macdScore + supertrendScore + fibScore

        return TechnicalResult(
            ticker = ticker,
            ema9 = lastEma9,
            ema21 = lastEma21,
            emaScore = emaScore,
            bbUpper = bbUpper,
            bbMiddle = bbMiddle,
            bbLower = bbLower,
            bbScore = bbScore,
            macdLine = macdLine,
            macdSignal = macdSignal,
            macdHistogram = macdHist,
            macdScore = macdScore,
            supertrendValue = stValue,
            isSupertrendBullish = isSupertrendBullish,
            supertrendScore = supertrendScore,
            fibLevels = fibLevels,
            fibScore = fibScore,
            nearestResistance = nearestResistance,
            nearestSupport = nearestSupport,
            totalScore = totalScore.coerceIn(0, 30)
        )
    }

    private fun calculateEMA(data: List<Double>, period: Int): List<Double> {
        if (data.isEmpty()) return emptyList()
        val k = 2.0 / (period + 1)
        val emaList = mutableListOf<Double>()
        var ema = data.take(period).average()
        emaList.add(ema)

        for (i in period until data.size) {
            ema = data[i] * k + ema * (1 - k)
            emaList.add(ema)
        }
        return emaList
    }

    private fun calculateBollingerBands(data: List<Double>, period: Int, mult: Double): Triple<Double, Double, Double> {
        val subset = data.takeLast(period)
        val sma = subset.average()
        val variance = subset.map { (it - sma).pow(2) }.average()
        val stdDev = sqrt(variance)
        return Triple(sma + mult * stdDev, sma, sma - mult * stdDev)
    }

    private fun calculateMACD(data: List<Double>, fast: Int, slow: Int, signal: Int): Triple<Double, Double, Double> {
        val fastEMA = calculateEMA(data, fast)
        val slowEMA = calculateEMA(data, slow)
        val minSize = minOf(fastEMA.size, slowEMA.size)

        val macdLineValues = mutableListOf<Double>()
        for (i in 0 until minSize) {
            macdLineValues.add(fastEMA[fastEMA.size - minSize + i] - slowEMA[slowEMA.size - minSize + i])
        }

        val signalEMA = calculateEMA(macdLineValues, signal)
        val lastMacd = macdLineValues.lastOrNull() ?: 0.0
        val lastSignal = signalEMA.lastOrNull() ?: 0.0
        val hist = lastMacd - lastSignal

        return Triple(lastMacd, lastSignal, hist)
    }

    private fun calculateSupertrend(highs: List<Double>, lows: List<Double>, closes: List<Double>, period: Int, mult: Double): Pair<Double, Boolean> {
        if (closes.size < period + 1) return Pair(closes.lastOrNull() ?: 0.0, true)

        val trList = mutableListOf<Double>()
        for (i in 1 until closes.size) {
            val tr = max(highs[i] - lows[i], max(abs(highs[i] - closes[i - 1]), abs(lows[i] - closes[i - 1])))
            trList.add(tr)
        }
        val atr = trList.takeLast(period).average()

        val lastHigh = highs.last()
        val lastLow = lows.last()
        val lastClose = closes.last()
        val hl2 = (lastHigh + lastLow) / 2.0

        val upperBand = hl2 + mult * atr
        val lowerBand = hl2 - mult * atr

        val isBullish = lastClose > lowerBand
        val stValue = if (isBullish) lowerBand else upperBand
        return Pair(stValue, isBullish)
    }

    private fun calculateFibonacci(high: Double, low: Double): Map<String, Double> {
        val diff = high - low
        return mapOf(
            "0.0% (High)" to high,
            "23.6%" to high - diff * 0.236,
            "38.2%" to high - diff * 0.382,
            "50.0%" to high - diff * 0.500,
            "61.8%" to high - diff * 0.618,
            "78.6%" to high - diff * 0.786,
            "100% (Low)" to low
        )
    }
}
