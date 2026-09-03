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
import org.json.JSONArray

class OrderBookRepository(private val yahooRepo: YahooFinanceRepository) {

    private val MAX_SNAPSHOT_HISTORY = 30
    private val historyMap = mutableMapOf<String, MutableList<OrderBookSnapshot>>()
    private val technicalCache = mutableMapOf<String, TechnicalResult>()

    private val _rankingFlow = MutableStateFlow<List<StockAnalysis>>(emptyList())
    val rankingFlow: StateFlow<List<StockAnalysis>> = _rankingFlow.asStateFlow()

    private val _statusFlow = MutableStateFlow("Menunggu data...")
    val statusFlow: StateFlow<String> = _statusFlow.asStateFlow()

    suspend fun processJsonData(jsonString: String) {
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

                // Simpan ke riwayat
                val history = historyMap.getOrPut(ticker) { mutableListOf() }
                history.add(snapshot)
                if (history.size > MAX_SNAPSHOT_HISTORY) {
                    history.removeAt(0)
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
                val analysis = ScoringEngine.generateAnalysis(snap, ofResult, techResult, sessionInfo)
                analyses.add(analysis)
            }

            // Urutkan dari skor tertinggi
            val sorted = analyses.sortedByDescending { it.score }
            _rankingFlow.value = sorted
            _statusFlow.value = "Terbaca ${sorted.size} saham • Update ${sessionInfo.timeDisplay}"
        } catch (e: Exception) {
            _statusFlow.value = "Error memproses data: ${e.localizedMessage}"
        }
    }

    fun updateStatus(msg: String) {
        _statusFlow.value = msg
    }
}
