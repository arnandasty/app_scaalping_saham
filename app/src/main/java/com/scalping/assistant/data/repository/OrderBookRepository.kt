package com.scalping.assistant.data.repository

import com.scalping.assistant.data.models.OrderBookSnapshot
import com.scalping.assistant.data.models.StockAnalysis
import com.scalping.assistant.data.models.TechnicalResult
import com.scalping.assistant.engine.MarketSession
import com.scalping.assistant.engine.OrderFlowAnalyzer
import com.scalping.assistant.engine.ScoringEngine
import com.scalping.assistant.engine.TechnicalAnalyzer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.scalping.assistant.data.models.Recommendation
import org.json.JSONArray
import java.util.UUID

// ============================================================
// Data Models untuk Portfolio / Posisi Aktif
// ============================================================

data class ActiveTrade(
    val ticker: String,
    val entryPrice: Double
)

data class PortfolioTrade(
    val id: String = UUID.randomUUID().toString(),
    val ticker: String,
    val entryPrice: Int,
    val lot: Int,                                   // Jumlah lot yang dibeli
    val buyTime: Long = System.currentTimeMillis(),
    var currentPrice: Int = entryPrice,
    var pnlPercent: Double = 0.0,
    var pnlRupiah: Long = 0L,
    var currentRecommendation: Recommendation = Recommendation.WATCH,
    var currentScore: Int = 0,
    var aiAction: String = "TAHAN",                 // "TAHAN" / "TAKE PROFIT" / "CUT LOSS"
    var aiReason: String = "",
    var targetPrice: Int = 0,
    var stopLoss: Int = 0,
    var isActive: Boolean = true
)

// ============================================================
// Repository Utama
// ============================================================

class OrderBookRepository(private val yahooRepo: YahooFinanceRepository) {

    private val MAX_SNAPSHOT_HISTORY = 40 // Naikkan dari 30 ke 40 untuk sinyal lebih kuat
    private val historyMap = mutableMapOf<String, MutableList<OrderBookSnapshot>>()
    private val technicalCache = mutableMapOf<String, TechnicalResult>()
    private val previousRecommendations = mutableMapOf<String, Recommendation>()

    // ============================================================
    // State Flows untuk UI
    // ============================================================

    private val _manualFlow = MutableStateFlow<List<StockAnalysis>>(emptyList())
    val manualFlow: StateFlow<List<StockAnalysis>> = _manualFlow.asStateFlow()

    private val _moversFlow = MutableStateFlow<List<StockAnalysis>>(emptyList())
    val moversFlow: StateFlow<List<StockAnalysis>> = _moversFlow.asStateFlow()

    private val _topPicksFlow = MutableStateFlow<List<StockAnalysis>>(emptyList())
    val topPicksFlow: StateFlow<List<StockAnalysis>> = _topPicksFlow.asStateFlow()

    private val _statusFlow = MutableStateFlow("Menunggu data...")
    val statusFlow: StateFlow<String> = _statusFlow.asStateFlow()

    private val _bailoutFlow = kotlinx.coroutines.flow.MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 5)
    val bailoutFlow = _bailoutFlow.asSharedFlow() // Pair: ticker + alasan bailout

    private val _portfolioFlow = MutableStateFlow<List<PortfolioTrade>>(emptyList())
    val portfolioFlow: StateFlow<List<PortfolioTrade>> = _portfolioFlow.asStateFlow()

    // Legacy: backward compat untuk banner aktif
    private val _activeTradeFlow = MutableStateFlow<Pair<ActiveTrade, StockAnalysis>?>(null)
    val activeTradeFlow: StateFlow<Pair<ActiveTrade, StockAnalysis>?> = _activeTradeFlow.asStateFlow()

    var currentActiveTrade: ActiveTrade? = null
        set(value) {
            field = value
            if (value == null) {
                _activeTradeFlow.value = null
            }
        }

    // ============================================================
    // PORTFOLIO MANAGEMENT
    // ============================================================

    private val _portfolio = mutableListOf<PortfolioTrade>()

    fun addPortfolioTrade(ticker: String, entryPrice: Int, lot: Int): PortfolioTrade {
        val trade = PortfolioTrade(
            ticker = ticker.uppercase(),
            entryPrice = entryPrice,
            lot = lot,
            targetPrice = (entryPrice * 1.025).toInt(), // Default target 2.5%
            stopLoss = (entryPrice * 0.985).toInt()     // Default SL 1.5%
        )
        _portfolio.add(trade)
        _portfolioFlow.value = _portfolio.toList()

        // Set active trade (backward compat untuk banner)
        currentActiveTrade = ActiveTrade(ticker, entryPrice.toDouble())

        return trade
    }

    fun closePortfolioTrade(tradeId: String) {
        _portfolio.removeAll { it.id == tradeId }
        _portfolioFlow.value = _portfolio.toList()
        if (_portfolio.isEmpty()) {
            currentActiveTrade = null
        }
    }

    fun closeAllPortfolioTrades() {
        _portfolio.clear()
        _portfolioFlow.value = emptyList()
        currentActiveTrade = null
    }

    // Update portfolio dengan harga & rekomendasi terbaru
    private fun updatePortfolioPositions(analyses: List<StockAnalysis>) {
        if (_portfolio.isEmpty()) return

        var portfolioUpdated = false
        for (i in _portfolio.indices) {
            val trade = _portfolio[i]
            val analysis = analyses.find { it.ticker == trade.ticker.uppercase() } ?: continue

            val currentPrice = analysis.lastPrice
            val pnlPercent = ((currentPrice - trade.entryPrice).toDouble() / trade.entryPrice) * 100
            val pnlRupiah = ((currentPrice - trade.entryPrice).toLong() * trade.lot * 100)

            // Tentukan aksi AI berdasarkan kondisi posisi
            val aiAction = when {
                currentPrice >= analysis.targetPrice -> "🎯 TAKE PROFIT"
                currentPrice <= analysis.stopLoss -> "🛑 CUT LOSS"
                analysis.recommendation == Recommendation.AVOID && analysis.score < 40 -> "⚠️ PERTIMBANGKAN CUT"
                analysis.recommendation == Recommendation.STRONG_BUY -> "🔥 TAHAN / TAMBAH"
                analysis.recommendation == Recommendation.BUY -> "✅ TAHAN"
                else -> "👁 PANTAU"
            }

            val aiReason = analysis.reasons.firstOrNull() ?: analysis.warnings.firstOrNull() ?: "Analisis berjalan..."

            // Buat objek baru agar StateFlow mendeteksi perubahan state
            _portfolio[i] = trade.copy(
                currentPrice = currentPrice,
                currentRecommendation = analysis.recommendation,
                currentScore = analysis.score,
                pnlPercent = pnlPercent,
                pnlRupiah = pnlRupiah,
                targetPrice = analysis.targetPrice,
                stopLoss = analysis.stopLoss,
                aiAction = aiAction,
                aiReason = aiReason
            )
            portfolioUpdated = true
        }

        if (portfolioUpdated) {
            _portfolioFlow.value = _portfolio.toList()
        }
    }

    // ============================================================
    // BAILOUT LOGIC (DIPERBAIKI — Anti False Alarm)
    // ============================================================

    // Cooldown: simpan waktu terakhir sinyal BUY muncul per ticker
    private val signalBuyTime = mutableMapOf<String, Long>()
    // Konfirmasi: berapa kali guyuran terdeteksi berturut-turut
    private val bailoutConfirmCount = mutableMapOf<String, Int>()
    // Waktu minimum sejak DONE BUY sebelum bailout bisa aktif: 2 menit
    private val BAILOUT_COOLDOWN_MS = 2 * 60 * 1000L
    // Jumlah konfirmasi minimum sebelum bailout alert
    private val BAILOUT_MIN_CONFIRM = 3

    private fun checkBailout(ticker: String, analysis: StockAnalysis, history: List<OrderBookSnapshot>) {
        // Hanya cek untuk saham yang ada di portfolio
        val trade = _portfolio.find { it.ticker == ticker } ?: return

        // Cooldown: tidak trigger bailout dalam 2 menit pertama setelah beli
        val timeSinceBuy = System.currentTimeMillis() - trade.buyTime
        if (timeSinceBuy < BAILOUT_COOLDOWN_MS) return

        val latestPrice = history.lastOrNull()?.lastPrice ?: return
        val firstPriceAfterBuy = trade.entryPrice

        // ============================================================
        // Guyuran NYATA harus memenuhi SEMUA kriteria ini:
        // 1. Delta negatif masif (tekanan jual sangat besar)
        // 2. Harga sedang TURUN dari titik entry (bukan stabil/naik)
        // 3. Bukan akumulasi (ciri akumulasi: delta negatif TAPI harga stabil)
        // 4. Dikonfirmasi multiple snapshot berturut-turut
        // ============================================================
        val ofResult = analysis.orderFlow
        val cumulativeDeltaValue = ofResult.cumulativeDelta * 100L * latestPrice

        val isPriceFalling = latestPrice < firstPriceAfterBuy * 0.985 // Harga turun > 1.5% dari entry
        val isMassiveSelling = cumulativeDeltaValue < -300_000_000L
        val isNotAccumulation = !ofResult.hasAccumulation // Bukan akumulasi

        if (isMassiveSelling && isPriceFalling && isNotAccumulation) {
            val count = (bailoutConfirmCount[ticker] ?: 0) + 1
            bailoutConfirmCount[ticker] = count

            if (count >= BAILOUT_MIN_CONFIRM) {
                // Guyuran dikonfirmasi!
                val reason = "Penjualan masif (${ofResult.cumulativeDelta} lot) + harga turun ${
                    String.format("%.1f", ((latestPrice - firstPriceAfterBuy).toDouble() / firstPriceAfterBuy) * 100)
                }%"
                _bailoutFlow.tryEmit(Pair(ticker, reason))
                bailoutConfirmCount.remove(ticker) // Reset agar tidak spam
            }
        } else {
            // Kondisi tidak memenuhi semua kriteria → reset counter konfirmasi
            if (bailoutConfirmCount.containsKey(ticker)) {
                bailoutConfirmCount.remove(ticker)
            }
        }

        // Cek apakah fake wall sudah terkonfirmasi DAN harga juga turun (bukan akumulasi)
        if (ofResult.hasFakeWall && isPriceFalling && isNotAccumulation) {
            val count = (bailoutConfirmCount["fw_$ticker"] ?: 0) + 1
            bailoutConfirmCount["fw_$ticker"] = count
            if (count >= 2) {
                _bailoutFlow.tryEmit(Pair(ticker, "Fake Wall terkonfirmasi + harga melemah!"))
                bailoutConfirmCount.remove("fw_$ticker")
            }
        }
    }

    // ============================================================
    // DATA PROCESSING: WebView Utama (Tab Manual)
    // ============================================================

    suspend fun processJsonData(jsonString: String) {
        try {
            val jsonArray = JSONArray(jsonString)
            if (jsonArray.length() == 0) return

            val currentSnapshots = mutableListOf<OrderBookSnapshot>()

            for (i in 0 until jsonArray.length()) {
                val snapshot = parseSnapshot(jsonArray.getJSONObject(i)) ?: continue
                currentSnapshots.add(snapshot)

                val sessionInfo = MarketSession.getCurrentSession()
                val isQuietMarket = isQuietMarket(sessionInfo)

                val history = historyMap.getOrPut(snapshot.ticker) { mutableListOf() }
                if (shouldAddSnapshot(history.lastOrNull(), snapshot, isQuietMarket)) {
                    history.add(snapshot)
                    if (history.size > MAX_SNAPSHOT_HISTORY) history.removeAt(0)
                }
            }

            val analyses = analyzeSnapshots(currentSnapshots, isMovers = false)
            val sorted = analyses.sortedByDescending { it.score }
            _manualFlow.value = sorted

            refreshTopPicks()
            updatePortfolioPositions(sorted)

            val totalCount = (_manualFlow.value.size + _moversFlow.value.size)
            val sessionInfo = MarketSession.getCurrentSession()
            _statusFlow.value = "Terbaca $totalCount saham • Update ${sessionInfo.timeDisplay}"

        } catch (e: Exception) {
            _statusFlow.value = "Error memproses data: ${e.localizedMessage}"
        }
    }

    // ============================================================
    // DATA PROCESSING: WebView Movers (Tab Movers)
    // ============================================================

    suspend fun processMoversJsonData(jsonString: String) {
        try {
            val jsonArray = JSONArray(jsonString)
            if (jsonArray.length() == 0) return

            val currentSnapshots = mutableListOf<OrderBookSnapshot>()

            for (i in 0 until jsonArray.length()) {
                val snapshot = parseSnapshot(jsonArray.getJSONObject(i)) ?: continue
                currentSnapshots.add(snapshot)

                val sessionInfo = MarketSession.getCurrentSession()
                val isQuietMarket = isQuietMarket(sessionInfo)

                val history = historyMap.getOrPut("movers_${snapshot.ticker}") { mutableListOf() }
                if (shouldAddSnapshot(history.lastOrNull(), snapshot, isQuietMarket)) {
                    history.add(snapshot)
                    if (history.size > MAX_SNAPSHOT_HISTORY) history.removeAt(0)
                }
            }

            val analyses = analyzeSnapshots(currentSnapshots, isMovers = true)
            val sorted = analyses.sortedByDescending { it.score }
            _moversFlow.value = sorted
            refreshTopPicks()
            updatePortfolioPositions(sorted)

        } catch (e: Exception) {
            _statusFlow.value = "Error movers: ${e.localizedMessage}"
        }
    }

    // ============================================================
    // DATA PROCESSING: Ticker-only Movers (Fallback Teknikal Saja)
    // ============================================================

    suspend fun processMoversTickerData(jsonString: String) {
        try {
            val jsonArray = JSONArray(jsonString)
            if (jsonArray.length() == 0) return

            val sessionInfo = MarketSession.getCurrentSession()
            val analyses = mutableListOf<StockAnalysis>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val ticker = obj.getString("ticker").trim().uppercase()
                val lastPrice = obj.optInt("lastPrice", 0)
                val changePercent = obj.optDouble("changePercent", 0.0)
                val priceToUse = if (lastPrice > 0) lastPrice else 100

                val snapshot = OrderBookSnapshot(
                    ticker = ticker,
                    lastPrice = priceToUse,
                    changePercent = changePercent,
                    timestamp = System.currentTimeMillis(),
                    bidLevels = emptyList(),
                    offerLevels = emptyList(),
                    totalBidLot = 0L,
                    totalOfferLot = 0L
                )

                val candles = yahooRepo.fetchIntradayCandles(ticker)
                val techResult = TechnicalAnalyzer.analyze(ticker, candles, priceToUse.toDouble())

                val ofResult = com.scalping.assistant.data.models.OrderFlowResult(
                    ticker = ticker,
                    totalScore = 12,
                    details = listOf("📊 Data Movers (teknikal saja). Orderbook aktif saat market buka.")
                )

                val prevRec = previousRecommendations["movers_$ticker"]
                val snapshotCount = historyMap["movers_$ticker"]?.size ?: 0
                val analysis = ScoringEngine.generateAnalysis(snapshot, ofResult, techResult, sessionInfo, prevRec, snapshotCount)
                previousRecommendations["movers_$ticker"] = analysis.recommendation
                analyses.add(analysis)
            }

            if (analyses.isNotEmpty()) {
                val sorted = analyses.sortedByDescending { it.score }
                _moversFlow.value = sorted
                refreshTopPicks()
                updatePortfolioPositions(sorted)
            }

        } catch (e: Exception) {
            _statusFlow.value = "Error movers: ${e.localizedMessage}"
        }
    }

    // ============================================================
    // HELPER METHODS
    // ============================================================

    fun updateStatus(msg: String) {
        _statusFlow.value = msg
    }

    private fun refreshTopPicks() {
        val all = (_manualFlow.value + _moversFlow.value)
            .sortedByDescending { it.score }
        // Top Picks: score ≥ 72, RR ≥ 1.4, snapshot sudah cukup (≥5), dan BUKAN AVOID (misal ARA)
        _topPicksFlow.value = all.filter {
            it.score >= 72 && it.riskRewardRatio >= 1.4 && it.snapshotCount >= 5 && it.recommendation != com.scalping.assistant.data.models.Recommendation.AVOID
        }.take(5)
    }

    private fun isQuietMarket(sessionInfo: com.scalping.assistant.engine.SessionInfo): Boolean {
        return sessionInfo.phase == com.scalping.assistant.engine.MarketPhase.CLOSED ||
                sessionInfo.phase == com.scalping.assistant.engine.MarketPhase.PRE_OPEN ||
                sessionInfo.phase == com.scalping.assistant.engine.MarketPhase.BREAK
    }

    private fun shouldAddSnapshot(last: OrderBookSnapshot?, curr: OrderBookSnapshot, isQuiet: Boolean): Boolean {
        if (last == null) return true
        return if (isQuiet) {
            last.lastPrice != curr.lastPrice
        } else {
            last.lastPrice != curr.lastPrice ||
                    last.totalBidLot != curr.totalBidLot ||
                    last.totalOfferLot != curr.totalOfferLot
        }
    }

    private fun parseSnapshot(obj: org.json.JSONObject): OrderBookSnapshot? {
        return try {
            val ticker = obj.getString("ticker").trim().uppercase()
            val lastPrice = obj.getInt("lastPrice")
            val changePercent = obj.optDouble("changePercent", 0.0)
            val timestamp = obj.optLong("timestamp", System.currentTimeMillis())
            val totalBidLot = obj.optLong("totalBidLot", 0L)
            val totalOfferLot = obj.optLong("totalOfferLot", 0L)

            val bidLevels = mutableListOf<com.scalping.assistant.data.models.PriceLevel>()
            val bidArr = obj.optJSONArray("bidLevels")
            if (bidArr != null) {
                for (b in 0 until bidArr.length()) {
                    val bObj = bidArr.getJSONObject(b)
                    bidLevels.add(com.scalping.assistant.data.models.PriceLevel(
                        price = bObj.getInt("price"), lot = bObj.getLong("lot"),
                        frequency = bObj.optInt("frequency", 0)))
                }
            }

            val offerLevels = mutableListOf<com.scalping.assistant.data.models.PriceLevel>()
            val offerArr = obj.optJSONArray("offerLevels")
            if (offerArr != null) {
                for (o in 0 until offerArr.length()) {
                    val oObj = offerArr.getJSONObject(o)
                    offerLevels.add(com.scalping.assistant.data.models.PriceLevel(
                        price = oObj.getInt("price"), lot = oObj.getLong("lot"),
                        frequency = oObj.optInt("frequency", 0)))
                }
            }

            val araPrice = obj.optInt("araPrice", 0)
            val arbPrice = obj.optInt("arbPrice", 0)

            OrderBookSnapshot(
                ticker = ticker, lastPrice = lastPrice, changePercent = changePercent,
                timestamp = timestamp, bidLevels = bidLevels, offerLevels = offerLevels,
                totalBidLot = totalBidLot, totalOfferLot = totalOfferLot,
                araPrice = araPrice, arbPrice = arbPrice
            )
        } catch (e: Exception) { null }
    }

    private suspend fun analyzeSnapshots(
        snapshots: List<OrderBookSnapshot>,
        isMovers: Boolean
    ): List<StockAnalysis> {
        val sessionInfo = MarketSession.getCurrentSession()
        val analyses = mutableListOf<StockAnalysis>()

        for (snap in snapshots) {
            val ticker = snap.ticker
            val historyKey = if (isMovers) "movers_$ticker" else ticker
            val history = historyMap[historyKey] ?: listOf(snap)

            val ofResult = OrderFlowAnalyzer.analyze(ticker, history)
            val candles = yahooRepo.fetchIntradayCandles(ticker)
            val techResult = TechnicalAnalyzer.analyze(ticker, candles, snap.lastPrice.toDouble())

            val prevRec = previousRecommendations[historyKey]
            val snapshotCount = history.size

            val analysis = ScoringEngine.generateAnalysis(snap, ofResult, techResult, sessionInfo, prevRec, snapshotCount)
            previousRecommendations[historyKey] = analysis.recommendation
            analyses.add(analysis)

            // Update banner aktif (backward compat)
            val currentTrade = currentActiveTrade
            if (currentTrade != null && currentTrade.ticker == ticker) {
                _activeTradeFlow.value = Pair(currentTrade, analysis)
            }

            // Cek bailout untuk posisi portfolio
            checkBailout(ticker, analysis, history)
        }

        return analyses
    }
}
