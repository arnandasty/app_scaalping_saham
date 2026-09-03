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

data class ActiveTrade(
    val ticker: String,
    val entryPrice: Double
)

class OrderBookRepository(private val yahooRepo: YahooFinanceRepository) {

    private val MAX_SNAPSHOT_HISTORY = 30
    private val historyMap = mutableMapOf<String, MutableList<OrderBookSnapshot>>()
    private val technicalCache = mutableMapOf<String, TechnicalResult>()
    private val previousRecommendations = mutableMapOf<String, Recommendation>()

    private val _manualFlow = MutableStateFlow<List<StockAnalysis>>(emptyList())
    val manualFlow: StateFlow<List<StockAnalysis>> = _manualFlow.asStateFlow()

    private val _moversFlow = MutableStateFlow<List<StockAnalysis>>(emptyList())
    val moversFlow: StateFlow<List<StockAnalysis>> = _moversFlow.asStateFlow()

    private val _topPicksFlow = MutableStateFlow<List<StockAnalysis>>(emptyList())
    val topPicksFlow: StateFlow<List<StockAnalysis>> = _topPicksFlow.asStateFlow()

    private val _statusFlow = MutableStateFlow("Menunggu data...")
    val statusFlow: StateFlow<String> = _statusFlow.asStateFlow()

    private val _bailoutFlow = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 5)
    val bailoutFlow = _bailoutFlow.asSharedFlow()

    private val recentBuys = mutableMapOf<String, Long>()
    private val RECENT_BUY_DURATION_MS = 5 * 60 * 1000L // 5 menit
    
    private val _activeTradeFlow = MutableStateFlow<Pair<ActiveTrade, StockAnalysis>?>(null)
    val activeTradeFlow: StateFlow<Pair<ActiveTrade, StockAnalysis>?> = _activeTradeFlow.asStateFlow()
    
    var currentActiveTrade: ActiveTrade? = null
        set(value) {
            field = value
            if (value == null) {
                _activeTradeFlow.value = null
            }
        }

    suspend fun processJsonData(jsonString: String, moversTickers: Set<String> = emptySet()) {
        try {
            val jsonArray = JSONArray(jsonString)
            if (jsonArray.length() == 0) return

            val currentSnapshots = mutableListOf<OrderBookSnapshot>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
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
                        bidLevels.add(
                            com.scalping.assistant.data.models.PriceLevel(
                                price = bObj.getInt("price"),
                                lot = bObj.getLong("lot"),
                                frequency = bObj.optInt("frequency", 0)
                            )
                        )
                    }
                }

                val offerLevels = mutableListOf<com.scalping.assistant.data.models.PriceLevel>()
                val offerArr = obj.optJSONArray("offerLevels")
                if (offerArr != null) {
                    for (o in 0 until offerArr.length()) {
                        val oObj = offerArr.getJSONObject(o)
                        offerLevels.add(
                            com.scalping.assistant.data.models.PriceLevel(
                                price = oObj.getInt("price"),
                                lot = oObj.getLong("lot"),
                                frequency = oObj.optInt("frequency", 0)
                            )
                        )
                    }
                }

                val snapshot = OrderBookSnapshot(
                    ticker = ticker,
                    lastPrice = lastPrice,
                    changePercent = changePercent,
                    timestamp = timestamp,
                    bidLevels = bidLevels,
                    offerLevels = offerLevels,
                    totalBidLot = totalBidLot,
                    totalOfferLot = totalOfferLot
                )

                currentSnapshots.add(snapshot)

                // Simpan ke riwayat hanya jika ada perubahan nyata (mencegah fluktuasi sinyal saat market diam/tutup)
                val history = historyMap.getOrPut(ticker) { mutableListOf() }
                val lastHist = history.lastOrNull()
                val isChanged = (lastHist == null ||
                        lastHist.lastPrice != snapshot.lastPrice ||
                        lastHist.totalBidLot != snapshot.totalBidLot ||
                        lastHist.totalOfferLot != snapshot.totalOfferLot)

                if (isChanged) {
                    history.add(snapshot)
                    if (history.size > MAX_SNAPSHOT_HISTORY) {
                        history.removeAt(0)
                    }
                }
            }

            // Jalankan analisis untuk semua emiten yang terbaca
            val analyses = mutableListOf<StockAnalysis>()
            val sessionInfo = MarketSession.getCurrentSession()

            for (snap in currentSnapshots) {
                val ticker = snap.ticker
                val history = historyMap[ticker] ?: listOf(snap)

                // 1. Order Flow
                val ofResult = OrderFlowAnalyzer.analyze(ticker, history)

                // 2. Technical (ambil dari cache atau fetch baru)
                var techResult = technicalCache[ticker]
                if (techResult == null) {
                    val candles = yahooRepo.fetchIntradayCandles(ticker)
                    techResult = TechnicalAnalyzer.analyze(ticker, candles, snap.lastPrice.toDouble())
                    technicalCache[ticker] = techResult
                }

                // 3. Scoring & Ranking
                val prevRec = previousRecommendations[ticker]
                val analysis = ScoringEngine.generateAnalysis(snap, ofResult, techResult, sessionInfo, prevRec)
                previousRecommendations[ticker] = analysis.recommendation
                
                // Panic Bailout Logic
                val now = System.currentTimeMillis()
                if (analysis.recommendation == Recommendation.BUY || analysis.recommendation == Recommendation.STRONG_BUY) {
                    recentBuys[ticker] = now
                }
                
                val buyTime = recentBuys[ticker]
                if (buyTime != null && (now - buyTime) < RECENT_BUY_DURATION_MS) {
                    // Jika tiba-tiba terjadi guyuran masif setelah direkomendasikan Buy (Batas: Rp 500 Juta)
                    val cumulativeDeltaValue = ofResult.cumulativeDelta * 100 * snap.lastPrice
                    if (cumulativeDeltaValue < -500_000_000L || ofResult.hasFakeWall) {
                        _bailoutFlow.tryEmit(ticker)
                        recentBuys.remove(ticker) // Hapus agar tidak spam
                    }
                }

                analyses.add(analysis)
                
                // Update Live PnL untuk Active Trade
                val currentTrade = currentActiveTrade
                if (currentTrade != null && currentTrade.ticker == ticker) {
                    _activeTradeFlow.value = Pair(currentTrade, analysis)
                }
            }

            // Pisahkan data ke dalam 3 tab
            val sorted = analyses.sortedByDescending { it.score }
            
            val manualList = sorted.filter { it.ticker !in moversTickers }
            val moversList = sorted.filter { it.ticker in moversTickers }
            val topPicksList = sorted.filter { it.score >= 75 && it.riskRewardRatio >= 1.5 }
                .take(3) // Ambil 3 terbaik dari manapun asalnya

            _manualFlow.value = manualList
            _moversFlow.value = moversList
            _topPicksFlow.value = topPicksList

            _statusFlow.value = "Terbaca ${sorted.size} saham • Update ${sessionInfo.timeDisplay}"
        } catch (e: Exception) {
            _statusFlow.value = "Error memproses data: ${e.localizedMessage}"
        }
    }

    fun updateStatus(msg: String) {
        _statusFlow.value = msg
    }
}
