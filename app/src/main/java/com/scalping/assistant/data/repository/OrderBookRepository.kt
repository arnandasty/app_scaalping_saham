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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
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
    var isActive: Boolean = true,
    var closePrice: Int = 0,
    var closeTime: Long = 0L,
    var status: String = "ACTIVE" // ACTIVE, TP, SL, MANUAL
)

// ============================================================
// Repository Utama
// ============================================================

class OrderBookRepository(private val yahooRepo: YahooFinanceRepository) {

    private val MAX_SNAPSHOT_HISTORY = 40 // Naikkan dari 30 ke 40 untuk sinyal lebih kuat
    private val historyMap = mutableMapOf<String, MutableList<OrderBookSnapshot>>()
    private val technicalCache = mutableMapOf<String, TechnicalResult>()
    private val previousRecommendations = mutableMapOf<String, Recommendation>()
    private val tapeReadingMap = mutableMapOf<String, com.scalping.assistant.data.models.TapeReadingStat>()
    private val bandarDetectorMap = mutableMapOf<String, com.scalping.assistant.data.models.BandarDetectorStat>()

    private val _bandarDetectorFlow = MutableStateFlow<Map<String, com.scalping.assistant.data.models.BandarDetectorStat>>(emptyMap())
    val bandarDetectorFlow: StateFlow<Map<String, com.scalping.assistant.data.models.BandarDetectorStat>> = _bandarDetectorFlow.asStateFlow()

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

    fun getAnalysisForTicker(ticker: String): StockAnalysis? {
        val clean = ticker.trim().uppercase()
        return _manualFlow.value.find { it.ticker == clean }
            ?: _moversFlow.value.find { it.ticker == clean }
            ?: _topPicksFlow.value.find { it.ticker == clean }
    }

    fun getBandarDetector(ticker: String): com.scalping.assistant.data.models.BandarDetectorStat? {
        return bandarDetectorMap[ticker.trim().uppercase()]
    }

    // ============================================================
    // PORTFOLIO MANAGEMENT
    // ============================================================

    private val _portfolio = mutableListOf<PortfolioTrade>()

    fun getPortfolioList(): List<PortfolioTrade> {
        return _portfolio.toList()
    }

    fun setPortfolioData(data: List<PortfolioTrade>) {
        _portfolio.clear()
        _portfolio.addAll(data)
        _portfolioFlow.value = _portfolio.toList()
        if (_portfolio.isNotEmpty()) {
            val first = _portfolio.first()
            currentActiveTrade = ActiveTrade(first.ticker, first.entryPrice.toDouble())
        }
    }

    fun addPortfolioTrade(ticker: String, entryPrice: Int, lot: Int, targetPrice: Int, stopLoss: Int): PortfolioTrade {
        val trade = PortfolioTrade(
            ticker = ticker.uppercase(),
            entryPrice = entryPrice,
            lot = lot,
            targetPrice = targetPrice,
            stopLoss = stopLoss
        )
        _portfolio.add(trade)
        _portfolioFlow.value = _portfolio.toList()

        // Set active trade (backward compat untuk banner)
        currentActiveTrade = ActiveTrade(ticker, entryPrice.toDouble())

        return trade
    }

    fun closePortfolioTrade(tradeId: String, closePrice: Int = 0, status: String = "MANUAL") {
        val trade = _portfolio.find { it.id == tradeId }
        if (trade != null) {
            trade.isActive = false
            trade.closePrice = if (closePrice > 0) closePrice else trade.currentPrice
            trade.closeTime = System.currentTimeMillis()
            trade.status = status
            _portfolioFlow.value = _portfolio.toList()
        }
        if (_portfolio.none { it.isActive }) {
            currentActiveTrade = null
        }
    }

    fun deletePortfolioTrade(tradeId: String) {
        _portfolio.removeAll { it.id == tradeId }
        _portfolioFlow.value = _portfolio.toList()
    }

    fun closeAllPortfolioTrades() {
        for (trade in _portfolio) {
            if (trade.isActive) {
                trade.isActive = false
                trade.closePrice = trade.currentPrice
                trade.closeTime = System.currentTimeMillis()
                trade.status = "MANUAL"
            }
        }
        _portfolioFlow.value = _portfolio.toList()
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
                currentPrice >= trade.targetPrice -> "🎯 TAKE PROFIT"
                currentPrice <= trade.stopLoss -> "🛑 CUT LOSS"
                analysis.recommendation == Recommendation.AVOID && analysis.score < 40 -> "⚠️ PERTIMBANGKAN CUT"
                analysis.recommendation == Recommendation.STRONG_BUY -> "🔥 TAHAN / TAMBAH"
                analysis.recommendation == Recommendation.BUY -> "✅ TAHAN"
                else -> "👁 PANTAU"
            }

            val aiReason = analysis.warnings.firstOrNull { it.contains("Guyuran") || it.contains("HAKI") || it.contains("Bearish") || it.contains("Fake Wall") || it.contains("ARA") || it.contains("naik tinggi") }
                ?: analysis.reasons.firstOrNull { it.contains("Akumulasi") || it.contains("HAKA") || it.contains("Bullish") || it.contains("Breakout") }
                ?: analysis.reasons.firstOrNull { !it.contains("Target") && !it.contains("Entry") && !it.contains("Spread") }
                ?: analysis.warnings.firstOrNull { !it.contains("Target") && !it.contains("Entry") && !it.contains("Spread") }
                ?: "Memantau pergerakan harga..."

            // Buat objek baru agar StateFlow mendeteksi perubahan state
            _portfolio[i] = trade.copy(
                currentPrice = currentPrice,
                currentRecommendation = analysis.recommendation,
                currentScore = analysis.score,
                pnlPercent = pnlPercent,
                pnlRupiah = pnlRupiah,
                // Kita TIDAK overwrite targetPrice dan stopLoss agar statis sesuai rencana awal user
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
    // DATA PROCESSING: WebView Stream (Running Trade)
    // ============================================================

    suspend fun processStreamData(jsonString: String) {
        try {
            val arr = JSONArray(jsonString)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val ticker = obj.getString("ticker").uppercase()
                val type = obj.getString("type")
                val lot = obj.getLong("lot")

                val stat = tapeReadingMap.getOrPut(ticker) {
                    com.scalping.assistant.data.models.TapeReadingStat(ticker)
                }
                
                if (type == "BUY") {
                    stat.totalHakaLot += lot
                    stat.hakaFrequency += 1
                } else if (type == "SELL") {
                    stat.totalHakiLot += lot
                    stat.hakiFrequency += 1
                }
                stat.lastUpdated = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            // Abaikan parsing error stream
        }
    }

    // ============================================================
    // DATA PROCESSING: Bandar Detector (Official Stockbit API)
    // ============================================================

    private val FOREIGN_BROKERS = setOf("BK", "AK", "ZP", "CS", "RX", "KZ", "YU", "CG", "DB", "MS", "ML", "CC", "NI", "OD")
    private val RETAIL_BROKERS = setOf("XC", "PD", "YP", "XL", "KK", "GR", "SQ", "CP", "HP", "AZ", "EP")

    fun processBandarDetectorJson(url: String, jsonString: String) {
        try {
            val regex = Regex("""marketdetectors/([A-Za-z0-9]{3,6})""", RegexOption.IGNORE_CASE)
            val match = regex.find(url)
            val ticker = match?.groupValues?.get(1)?.uppercase() ?: return

            val isMultiDay = url.contains("ONE_WEEK", ignoreCase = true) ||
                    url.contains("ONE_MONTH", ignoreCase = true) ||
                    url.contains("from=", ignoreCase = true)

            val root = org.json.JSONObject(jsonString)
            val data = root.optJSONObject("data") ?: return
            val bandarDetectorObj = data.optJSONObject("bandar_detector") ?: return

            val avgPrice = bandarDetectorObj.optDouble("average", 0.0)
            val avgObj = bandarDetectorObj.optJSONObject("avg")
            val accdistStatus = avgObj?.optString("accdist", "Neutral") ?: "Neutral"
            val amount = avgObj?.optLong("amount", 0L) ?: 0L
            val vol = avgObj?.optLong("vol", 0L) ?: 0L

            // 1. Ekstrak Konsentrasi Bandar (Top 1, Top 3, Top 5)
            fun formatConcentration(obj: org.json.JSONObject?, label: String): String {
                if (obj == null) return ""
                val acc = obj.optString("accdist", "")
                val pct = obj.optDouble("percent", 0.0)
                if (acc.isEmpty() && pct == 0.0) return ""
                val pctStr = if (pct > 0.0) " (%.1f%%)".format(pct) else ""
                return "$label: $acc$pctStr"
            }
            val top1Str = formatConcentration(bandarDetectorObj.optJSONObject("top1"), "Top 1")
            val top3Str = formatConcentration(bandarDetectorObj.optJSONObject("top3"), "Top 3")
            val top5Str = formatConcentration(bandarDetectorObj.optJSONObject("top5"), "Top 5")
            val topConcentration = listOf(top1Str, top3Str, top5Str).filter { it.isNotEmpty() }.joinToString(" | ")

            // 2. Ekstrak Top Broker Pembeli & Penjual dan Klasifikasi Asing vs Ritel
            var topBrokersSummary = ""
            var foreignBuySum = 0L
            var foreignSellSum = 0L
            var retailBuySum = 0L
            var retailSellSum = 0L

            val brokersArr = data.optJSONArray("brokers")
            if (brokersArr != null && brokersArr.length() > 0) {
                val buyers = mutableListOf<String>()
                val sellers = mutableListOf<String>()
                for (i in 0 until brokersArr.length()) {
                    val b = brokersArr.optJSONObject(i) ?: continue
                    val code = b.optString("broker_code", "").uppercase()
                    val netVal = b.optDouble("net_value", 0.0).toLong()

                    if (code.isNotEmpty()) {
                        if (code in FOREIGN_BROKERS) {
                            if (netVal > 0) foreignBuySum += netVal else foreignSellSum += kotlin.math.abs(netVal)
                        } else if (code in RETAIL_BROKERS) {
                            if (netVal > 0) retailBuySum += netVal else retailSellSum += kotlin.math.abs(netVal)
                        }

                        if (i < 20) {
                            if (netVal > 0) {
                                buyers.add("$code (+${formatCurrencyShort(netVal)})")
                            } else if (netVal < 0) {
                                sellers.add("$code (${formatCurrencyShort(netVal)})")
                            }
                        }
                    }
                }
                val buyStr = if (buyers.isNotEmpty()) "Top Buyer: ${buyers.take(4).joinToString(", ")}" else ""
                val sellStr = if (sellers.isNotEmpty()) "Top Seller: ${sellers.take(4).joinToString(", ")}" else ""
                topBrokersSummary = listOf(buyStr, sellStr).filter { it.isNotEmpty() }.joinToString(" | ")
            }

            // 3. Ekstrak Arus Investor Asing (Foreign Flow) Resmi Stockbit
            val foreignObj = bandarDetectorObj.optJSONObject("foreign") ?: data.optJSONObject("foreign")
            val foreignNet = when {
                foreignObj != null && foreignObj.has("net_amount") -> foreignObj.optLong("net_amount", 0L)
                foreignObj != null && foreignObj.has("amount") -> foreignObj.optLong("amount", 0L)
                else -> foreignBuySum - foreignSellSum
            }
            val foreignAcc = foreignObj?.optString("accdist", "") ?: ""
            val formattedForeignNet = (if (foreignNet > 0) "+" else "") + formatCurrencyShort(foreignNet)

            val periodLabel = if (isMultiDay) "Asing 1 Pekan" else "Asing Hari Ini"
            val foreignFlowStr = if (foreignNet != 0L || foreignAcc.isNotEmpty()) {
                if (foreignAcc.isNotEmpty()) "$periodLabel: $foreignAcc ($formattedForeignNet)" else "$periodLabel: $formattedForeignNet"
            } else ""

            // 4. Smart Money Flow Summary (Komparasi Asing Institusi vs Ritel Domestik)
            val smartMoney = when {
                foreignNet > 300_000_000L && retailSellSum > retailBuySum ->
                    "🟢 SMART MONEY ACCUMULATION (Asing serok barang dari ritel: $formattedForeignNet)"
                foreignNet < -300_000_000L && retailBuySum > retailSellSum ->
                    "🔴 SMART MONEY DISTRIBUTION (Asing guyur barang ke ritel: $formattedForeignNet)"
                foreignNet > 0L && retailBuySum > retailSellSum ->
                    "🟡 ASING & RITEL SAMA-SAMA BELI (Asing $formattedForeignNet, Ritel ikut akumulasi)"
                foreignNet < 0L && retailSellSum > retailBuySum ->
                    "⚠️ DUA PIHAK JUALAN (Asing $formattedForeignNet & Ritel distribusi bersamaan)"
                foreignNet > 0L ->
                    "🟢 ASING NET ACCUMULATION ($formattedForeignNet)"
                foreignNet < 0L ->
                    "🔴 ASING NET DISTRIBUTION ($formattedForeignNet)"
                else -> "⚪ ALIRAN ASING & RITEL SEIMBANG"
            }

            val existing = bandarDetectorMap[ticker]

            val stat = if (isMultiDay) {
                (existing ?: com.scalping.assistant.data.models.BandarDetectorStat(ticker = ticker)).copy(
                    foreignFlowMultiDay = foreignFlowStr,
                    smartMoneySummary = if (existing?.smartMoneySummary.isNullOrEmpty()) smartMoney else existing?.smartMoneySummary ?: smartMoney,
                    lastUpdated = System.currentTimeMillis()
                )
            } else {
                com.scalping.assistant.data.models.BandarDetectorStat(
                    ticker = ticker,
                    accdistStatus = accdistStatus,
                    averagePrice = avgPrice,
                    amountRupiah = amount,
                    volumeLot = vol,
                    topBrokers = topBrokersSummary,
                    topConcentration = topConcentration,
                    foreignFlow = foreignFlowStr,
                    foreignFlowMultiDay = existing?.foreignFlowMultiDay ?: "",
                    smartMoneySummary = smartMoney,
                    lastUpdated = System.currentTimeMillis()
                )
            }

            bandarDetectorMap[ticker] = stat
            _bandarDetectorFlow.value = bandarDetectorMap.toMap()
            android.util.Log.d("BANDAR_DETECTOR", "Parsed $ticker (multiDay=$isMultiDay): $accdistStatus @ Rp $avgPrice, foreign: $foreignFlowStr, smartMoney: $smartMoney")

            reAnalyzeTicker(ticker)
        } catch (e: Exception) {
            android.util.Log.e("BANDAR_DETECTOR", "Error parsing bandar detector: ${e.message}")
        }
    }

    private fun formatCurrencyShort(amount: Long): String {
        val absVal = kotlin.math.abs(amount).toDouble()
        val sign = if (amount < 0) "-" else ""
        return when {
            absVal >= 1_000_000_000_000.0 -> "${sign}Rp %.1f T".format(absVal / 1_000_000_000_000.0)
            absVal >= 1_000_000_000.0 -> "${sign}Rp %.1f M".format(absVal / 1_000_000_000.0)
            absVal >= 1_000_000.0 -> "${sign}Rp %.1f Jt".format(absVal / 1_000_000.0)
            absVal > 0 -> "${sign}Rp ${String.format("%,d", absVal.toInt()).replace(',', '.')}"
            else -> "Rp 0"
        }
    }

    private fun reAnalyzeTicker(ticker: String) {
        val manualList = _manualFlow.value.toMutableList()
        val manualIdx = manualList.indexOfFirst { it.ticker == ticker }
        if (manualIdx >= 0) {
            val oldAnalysis = manualList[manualIdx]
            val snap = historyMap[ticker]?.lastOrNull()
            if (snap != null) {
                val ofResult = oldAnalysis.orderFlow
                val techResult = oldAnalysis.technical
                val sessionInfo = MarketSession.getCurrentSession()
                val prevRec = previousRecommendations[ticker]
                val snapshotCount = historyMap[ticker]?.size ?: 0
                val tapeReadingStat = tapeReadingMap[ticker]
                val bandarStat = bandarDetectorMap[ticker]

                val newAnalysis = ScoringEngine.generateAnalysis(
                    snap, ofResult, techResult, sessionInfo, prevRec, snapshotCount, tapeReadingStat, bandarStat
                )
                manualList[manualIdx] = newAnalysis
                _manualFlow.value = manualList.sortedByDescending { it.score }
                refreshTopPicks()
                updatePortfolioPositions(_manualFlow.value)
            }
        }

        // Sinkronkan juga pembaharuan data bandar ke Tab Movers
        val moversList = _moversFlow.value.toMutableList()
        val moversIdx = moversList.indexOfFirst { it.ticker == ticker }
        if (moversIdx >= 0) {
            val oldAnalysis = moversList[moversIdx]
            val snap = historyMap["movers_$ticker"]?.lastOrNull() ?: OrderBookSnapshot(
                ticker = ticker,
                lastPrice = oldAnalysis.lastPrice,
                changePercent = oldAnalysis.changePercent,
                timestamp = System.currentTimeMillis(),
                bidLevels = emptyList(),
                offerLevels = emptyList(),
                totalBidLot = 0L,
                totalOfferLot = 0L
            )
            val ofResult = oldAnalysis.orderFlow
            val techResult = oldAnalysis.technical
            val sessionInfo = MarketSession.getCurrentSession()
            val prevRec = previousRecommendations["movers_$ticker"]
            val snapshotCount = historyMap["movers_$ticker"]?.size ?: 0
            val tapeReadingStat = tapeReadingMap[ticker]
            val bandarStat = bandarDetectorMap[ticker]

            val newAnalysis = ScoringEngine.generateAnalysis(
                snap, ofResult, techResult, sessionInfo, prevRec, snapshotCount, tapeReadingStat, bandarStat
            )
            moversList[moversIdx] = newAnalysis
            _moversFlow.value = moversList.sortedByDescending { it.score }
            refreshTopPicks()
            updatePortfolioPositions(_moversFlow.value)
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
            val analyses = coroutineScope {
                (0 until jsonArray.length()).map { i ->
                    async(Dispatchers.Default) {
                        try {
                            val obj = jsonArray.getJSONObject(i)
                            val ticker = obj.getString("ticker").trim().uppercase()
                            val lastPrice = obj.optInt("lastPrice", 0)
                            val changePercent = obj.optDouble("changePercent", 0.0)
                            val turnover = obj.optString("turnover", "")
                            val priceToUse = if (lastPrice > 0) lastPrice else 100

                            val existingSnap = historyMap["movers_$ticker"]?.lastOrNull() ?: historyMap[ticker]?.lastOrNull()
                            val snapshot = if (existingSnap != null && existingSnap.bidLevels.isNotEmpty()) {
                                existingSnap.copy(lastPrice = priceToUse, changePercent = changePercent)
                            } else {
                                OrderBookSnapshot(
                                    ticker = ticker,
                                    lastPrice = priceToUse,
                                    changePercent = changePercent,
                                    timestamp = System.currentTimeMillis(),
                                    bidLevels = emptyList(),
                                    offerLevels = emptyList(),
                                    totalBidLot = 0L,
                                    totalOfferLot = 0L
                                )
                            }

                            val candles = yahooRepo.fetchIntradayCandles(ticker)
                            val techResult = TechnicalAnalyzer.analyze(ticker, candles, priceToUse.toDouble())

                            val ofDetails = mutableListOf<String>()
                            if (turnover.isNotEmpty()) {
                                ofDetails.add("🔥 Turnover Pasar: $turnover")
                            } else {
                                ofDetails.add("📊 Data Movers teraktif Stockbit")
                            }
                            val ofResult = com.scalping.assistant.data.models.OrderFlowResult(
                                ticker = ticker,
                                totalScore = 12,
                                details = ofDetails
                            )

                            val prevRec = previousRecommendations["movers_$ticker"]
                            val snapshotCount = historyMap["movers_$ticker"]?.size ?: 0
                            val bandarStat = bandarDetectorMap[ticker]
                            val analysis = ScoringEngine.generateAnalysis(snapshot, ofResult, techResult, sessionInfo, prevRec, snapshotCount, null, bandarStat)
                            previousRecommendations["movers_$ticker"] = analysis.recommendation
                            analysis
                        } catch (e: Exception) {
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
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
            val tapeReadingStat = tapeReadingMap[ticker]
            val bandarStat = bandarDetectorMap[ticker]

            val analysis = ScoringEngine.generateAnalysis(snap, ofResult, techResult, sessionInfo, prevRec, snapshotCount, tapeReadingStat, bandarStat)
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
