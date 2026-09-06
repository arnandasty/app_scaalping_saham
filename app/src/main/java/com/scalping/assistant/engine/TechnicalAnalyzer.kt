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
            return TechnicalResult(ticker = ticker, lastClosePrice = currentPrice)
        }

        val closes = candles.map { it.close }
        val highs = candles.map { it.high }
        val lows = candles.map { it.low }

        val priceToUse = if (currentPrice > 0.0) currentPrice else closes.last()
        
        val vwapValue = calculateVWAP(candles)
        val mfiValue = calculateMFI(candles, 14)

        // === Filter Layer: Volume Ratio (volume hari ini vs rata-rata 5 hari) ===
        val todayVolume = candles.lastOrNull()?.volume?.toDouble() ?: 0.0
        val avg5DayVolume = if (candles.size >= 6) {
            candles.takeLast(6).dropLast(1).map { it.volume.toDouble() }.average()
        } else todayVolume
        val volumeRatio = if (avg5DayVolume > 0.0) todayVolume / avg5DayVolume else 1.0

        // === Filter Layer: Today High ===
        val todayHighVal = highs.lastOrNull() ?: priceToUse

        // 0. Simple Moving Average (MA 5, MA 9, MA 20)
        val ma5Value = if (closes.size >= 5) closes.takeLast(5).average() else closes.average()
        val ma9Value = if (closes.size >= 9) closes.takeLast(9).average() else closes.average()
        val ma20Value = if (closes.size >= 20) closes.takeLast(20).average() else closes.average()

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

        // === Filter Layer: EMA 50 (Tren Makro) ===
        val ema50Value = if (closes.size >= 50) calculateEMA(closes, 50).last() else 0.0
        val isBelowEma50 = ema50Value > 0.0 && priceToUse < ema50Value

        // 5. Fibonacci Retracement
        val recentHigh = highs.takeLast(50).maxOrNull() ?: priceToUse
        val recentLow = lows.takeLast(50).minOrNull() ?: priceToUse
        val fibLevels = calculateFibonacci(recentHigh, recentLow)

        // 6. Dynamic Support & Resistance (Stable Levels Only)
        // Kumpulkan semua potensi level support (di bawah harga saat ini)
        val supportCandidates = mutableListOf<Double>()
        supportCandidates.addAll(fibLevels.values.filter { it < priceToUse })
        // Supertrend cukup stabil sebagai support jika sedang uptrend
        if (stValue < priceToUse && isSupertrendBullish) supportCandidates.add(stValue)

        // Kumpulkan semua potensi level resistance (di atas harga saat ini)
        val resistanceCandidates = mutableListOf<Double>()
        resistanceCandidates.addAll(fibLevels.values.filter { it > priceToUse })
        // Supertrend cukup stabil sebagai resistance jika sedang downtrend
        if (stValue > priceToUse && !isSupertrendBullish) resistanceCandidates.add(stValue)

        // 7. Support & Resistance 2 (Volatile/Dynamic Levels)
        val support2Candidates = mutableListOf<Double>()
        if (vwapValue < priceToUse) support2Candidates.add(vwapValue)
        if (lastEma9 < priceToUse) support2Candidates.add(lastEma9)
        if (lastEma21 < priceToUse) support2Candidates.add(lastEma21)
        if (bbLower < priceToUse) support2Candidates.add(bbLower)

        val resistance2Candidates = mutableListOf<Double>()
        if (vwapValue > priceToUse) resistance2Candidates.add(vwapValue)
        if (lastEma9 > priceToUse) resistance2Candidates.add(lastEma9)
        if (lastEma21 > priceToUse) resistance2Candidates.add(lastEma21)
        if (bbUpper > priceToUse) resistance2Candidates.add(bbUpper)

        // Ambil support terdekat yang valid, fallback ke 97% dari harga jika tidak ada
        val rawSupport = supportCandidates.maxOrNull() ?: (priceToUse * 0.97)
        val rawSupport2 = support2Candidates.maxOrNull() ?: rawSupport
        // Ambil resistance terdekat yang valid, fallback ke 103% dari harga jika tidak ada
        val rawResistance = resistanceCandidates.minOrNull() ?: (priceToUse * 1.03)
        val rawResistance2 = resistance2Candidates.minOrNull() ?: rawResistance

        val nearestResistance = PriceFraction.roundUpToValidTick(rawResistance.toInt()).toDouble()
        val nearestSupport = PriceFraction.roundDownToValidTick(rawSupport.toInt()).toDouble()
        val nearestResistance2 = PriceFraction.roundUpToValidTick(rawResistance2.toInt()).toDouble()
        val nearestSupport2 = PriceFraction.roundDownToValidTick(rawSupport2.toInt()).toDouble()

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
            lastClosePrice = closes.lastOrNull() ?: currentPrice,
            vwap = vwapValue,
            mfi = mfiValue,
            ma5 = ma5Value,
            ma9 = ma9Value,
            ma20 = ma20Value,
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
            nearestResistance2 = nearestResistance2,
            nearestSupport2 = nearestSupport2,
            // === 5-Layer Filter Data ===
            ema50 = ema50Value,
            isBelowEma50 = isBelowEma50,
            volumeRatio = volumeRatio,
            todayHigh = todayHighVal,
            totalScore = totalScore.coerceIn(0, 30)
        )
    }

    private fun calculateVWAP(candles: List<Candle>): Double {
        if (candles.isEmpty()) return 0.0
        var cumulativeTypicalPriceVolume = 0.0
        var cumulativeVolume = 0L

        for (candle in candles) {
            val typicalPrice = (candle.high + candle.low + candle.close) / 3.0
            cumulativeTypicalPriceVolume += typicalPrice * candle.volume
            cumulativeVolume += candle.volume
        }
        
        return if (cumulativeVolume > 0) cumulativeTypicalPriceVolume / cumulativeVolume else 0.0
    }

    private fun calculateMFI(candles: List<Candle>, period: Int): Double {
        if (candles.size <= period) return 50.0 // Default neutral
        
        var positiveMoneyFlow = 0.0
        var negativeMoneyFlow = 0.0
        
        val subset = candles.takeLast(period + 1)
        for (i in 1 until subset.size) {
            val prev = subset[i - 1]
            val curr = subset[i]
            val prevTypical = (prev.high + prev.low + prev.close) / 3.0
            val currTypical = (curr.high + curr.low + curr.close) / 3.0
            val moneyFlow = currTypical * curr.volume
            
            if (currTypical > prevTypical) {
                positiveMoneyFlow += moneyFlow
            } else if (currTypical < prevTypical) {
                negativeMoneyFlow += moneyFlow
            }
        }
        
        if (negativeMoneyFlow == 0.0) return 100.0
        val moneyFlowRatio = positiveMoneyFlow / negativeMoneyFlow
        return 100.0 - (100.0 / (1.0 + moneyFlowRatio))
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
        if (closes.size <= period) return Pair(closes.lastOrNull() ?: 0.0, true)

        val trList = mutableListOf<Double>()
        for (i in 1 until closes.size) {
            val tr = max(highs[i] - lows[i], max(abs(highs[i] - closes[i - 1]), abs(lows[i] - closes[i - 1])))
            trList.add(tr)
        }

        val atrList = mutableListOf<Double>()
        var currentATR = trList.take(period).average()
        atrList.add(currentATR)
        for (i in period until trList.size) {
            currentATR = (currentATR * (period - 1) + trList[i]) / period // RMA / SMMA
            atrList.add(currentATR)
        }

        var finalUpperBand = 0.0
        var finalLowerBand = 0.0
        var isBullish = true

        val startIndex = period
        
        for (i in startIndex until closes.size) {
            val hl2 = (highs[i] + lows[i]) / 2.0
            val atr = atrList[i - startIndex]
            val basicUpperBand = hl2 + mult * atr
            val basicLowerBand = hl2 - mult * atr
            
            val prevClose = closes[i - 1]
            
            if (finalUpperBand == 0.0) {
                finalUpperBand = basicUpperBand
                finalLowerBand = basicLowerBand
                isBullish = closes[i] > finalUpperBand
            }

            val newFinalUpperBand = if (basicUpperBand < finalUpperBand || prevClose > finalUpperBand) basicUpperBand else finalUpperBand
            val newFinalLowerBand = if (basicLowerBand > finalLowerBand || prevClose < finalLowerBand) basicLowerBand else finalLowerBand

            if (isBullish && closes[i] <= newFinalLowerBand) {
                isBullish = false
            } else if (!isBullish && closes[i] >= newFinalUpperBand) {
                isBullish = true
            }

            finalUpperBand = newFinalUpperBand
            finalLowerBand = newFinalLowerBand
        }

        val stValue = if (isBullish) finalLowerBand else finalUpperBand
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
