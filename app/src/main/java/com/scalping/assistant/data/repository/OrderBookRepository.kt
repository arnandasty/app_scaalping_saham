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
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.scalping.assistant.data.models.Recommendation
import com.scalping.assistant.engine.PriceFraction
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray

// ============================================================
// Data Models untuk Portfolio / Posisi Aktif & Signal Lock
// ============================================================

data class ActiveTrade(
    val ticker: String,
    val entryPrice: Double
)

data class SignalLock(
    val ticker: String,
    val lockedRecommendation: Recommendation,
    val lockTime: Long,
    val lockPrice: Int,
    val durationMs: Long = 25_000L // Kunci sinyal minimal 25 detik agar tidak kedap-kedip
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

class OrderBookRepository(
    private val context: android.content.Context,
    private val yahooRepo: YahooFinanceRepository
) {

    private val MAX_SNAPSHOT_HISTORY = 40 // Naikkan dari 30 ke 40 untuk sinyal lebih kuat
    private val historyMap = mutableMapOf<String, MutableList<OrderBookSnapshot>>()
    private val technicalCache = mutableMapOf<String, TechnicalResult>()
    private val previousRecommendations = mutableMapOf<String, Recommendation>()
    private val tapeReadingMap = mutableMapOf<String, com.scalping.assistant.data.models.TapeReadingStat>()
    private val bandarDetectorMap = mutableMapOf<String, com.scalping.assistant.data.models.BandarDetectorStat>()
    
    // Coroutine Scope & Persistence State
    private val repoScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    @Volatile private var isDirty = false

    // Sistem Lock Sinyal & Anti-Flicker Debounce
    private val signalLocks = ConcurrentHashMap<String, SignalLock>()
    private val demotionCounters = ConcurrentHashMap<String, Int>()

    init {
        loadCachedSnapshots()
        startPeriodicAutoSave()
    }

    private fun loadCachedSnapshots() {
        repoScope.launch(Dispatchers.IO) {
            val loaded = SnapshotStorageManager.loadSnapshots(context, maxAgeHours = 48L)
            if (loaded.historyMap.isNotEmpty()) {
                synchronized(historyMap) {
                    historyMap.putAll(loaded.historyMap)
                }
            }
            if (loaded.bandarMap.isNotEmpty()) {
                synchronized(bandarDetectorMap) {
                    bandarDetectorMap.putAll(loaded.bandarMap)
                }
                _bandarDetectorFlow.value = bandarDetectorMap.toMap()
            }

            if (loaded.totalLoadedCount > 0) {
                // Analisis awal dari snapshot yang baru dimuat agar kartu & sinyal langsung tampil tanpa cold-start
                val manualSnaps = mutableListOf<OrderBookSnapshot>()
                val moversSnaps = mutableListOf<OrderBookSnapshot>()
                synchronized(historyMap) {
                    for ((key, list) in historyMap) {
                        val last = list.lastOrNull() ?: continue
                        if (key.startsWith("movers_")) {
                            moversSnaps.add(last)
                        } else {
                            manualSnaps.add(last)
                        }
                    }
                }

                if (manualSnaps.isNotEmpty()) {
                    val analyses = analyzeSnapshots(manualSnaps, isMovers = false)
                    _manualFlow.value = analyses.sortedByDescending { it.score }
                }

                if (moversSnaps.isNotEmpty()) {
                    val analyses = analyzeSnapshots(moversSnaps, isMovers = true)
                    _moversFlow.value = analyses.sortedByDescending { it.score }
                }

                refreshTopPicks()
                val totalCount = _manualFlow.value.size + _moversFlow.value.size
                val sessionInfo = MarketSession.getCurrentSession()
                _statusFlow.value = "Memuat $totalCount saham dari snapshot sebelumnya • ${sessionInfo.timeDisplay}"
            }
        }
    }

    private fun startPeriodicAutoSave() {
        repoScope.launch(Dispatchers.IO) {
            while (true) {
                kotlinx.coroutines.delay(15_000L) // Auto-save tiap 15 detik jika ada data snapshot baru
                if (isDirty) {
                    saveSnapshotsInternal()
                }
            }
        }
    }

    private fun saveSnapshotsInternal() {
        try {
            val historyCopy = synchronized(historyMap) {
                historyMap.mapValues { it.value.toList() }
            }
            val bandarCopy = synchronized(bandarDetectorMap) {
                bandarDetectorMap.toMap()
            }
            SnapshotStorageManager.saveSnapshots(context, historyCopy, bandarCopy)
            isDirty = false
        } catch (e: Exception) {
            android.util.Log.e("SNAPSHOT_CACHE", "Error saveSnapshotsInternal: ${e.message}")
        }
    }

    fun saveSnapshots() {
        repoScope.launch(Dispatchers.IO) {
            saveSnapshotsInternal()
        }
    }

    fun clearAllSnapshots() {
        repoScope.launch(Dispatchers.IO) {
            SnapshotStorageManager.clearSnapshots(context)
            synchronized(historyMap) { historyMap.clear() }
            synchronized(bandarDetectorMap) { bandarDetectorMap.clear() }
            signalLocks.clear()
            demotionCounters.clear()
            _manualFlow.value = emptyList()
            _moversFlow.value = emptyList()
            _topPicksFlow.value = emptyList()
            _bandarDetectorFlow.value = emptyMap()
            _statusFlow.value = "Cache snapshot telah di-reset (0 saham)"
        }
    }

    fun getSnapshotCacheInfo(): Pair<Int, Int> {
        val totalSnaps = synchronized(historyMap) { historyMap.values.sumOf { it.size } }
        val totalTickers = synchronized(historyMap) { historyMap.size }
        return Pair(totalTickers, totalSnaps)
    }

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
        val index = _portfolio.indexOfFirst { it.id == tradeId }
        if (index != -1) {
            val trade = _portfolio[index]
            val finalClosePrice = if (closePrice > 0) closePrice else trade.currentPrice
            val pnlPercent = if (trade.entryPrice > 0) {
                ((finalClosePrice - trade.entryPrice).toDouble() / trade.entryPrice) * 100
            } else 0.0
            val pnlRupiah = (finalClosePrice - trade.entryPrice).toLong() * trade.lot * 100

            _portfolio[index] = trade.copy(
                isActive = false,
                closePrice = finalClosePrice,
                currentPrice = finalClosePrice,
                pnlPercent = pnlPercent,
                pnlRupiah = pnlRupiah,
                closeTime = System.currentTimeMillis(),
                status = status,
                aiAction = when (status) {
                    "TP" -> "🎯 TAKE PROFIT"
                    "SL" -> "🛑 CUT LOSS"
                    else -> "DITUTUP ($status)"
                },
                aiReason = "Posisi direalisasi pada harga Rp $finalClosePrice"
            )
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
            if (!trade.isActive) continue // Jangan ubah data posisi yang sudah ditutup (Track Record)
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
    // Cooldown antar alert bailout per ticker agar tidak spam/berisik (30 Menit)
    private val lastBailoutAlertPerTicker = mutableMapOf<String, Long>()
    private val BAILOUT_ALERT_COOLDOWN_MS = 30 * 60 * 1000L

    private fun checkBailout(ticker: String, analysis: StockAnalysis, history: List<OrderBookSnapshot>) {
        // Hanya cek untuk saham AKTIF yang ada di portfolio
        val trade = _portfolio.find { it.ticker == ticker && it.isActive } ?: return

        // Cooldown: tidak trigger bailout dalam 2 menit pertama setelah beli
        val now = System.currentTimeMillis()
        val timeSinceBuy = now - trade.buyTime
        if (timeSinceBuy < BAILOUT_COOLDOWN_MS) return

        // Cek cooldown alert per ticker (30 menit)
        val lastAlert = lastBailoutAlertPerTicker[ticker] ?: 0L
        if (now - lastAlert < BAILOUT_ALERT_COOLDOWN_MS) return

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
                // Guyuran dikonfirmasi! Set cooldown 30 menit
                lastBailoutAlertPerTicker[ticker] = now
                val reason = "Penjualan masif (${ofResult.cumulativeDelta} lot) + harga turun ${
                    String.format("%.1f", ((latestPrice - firstPriceAfterBuy).toDouble() / firstPriceAfterBuy) * 100)
                }%"
                _bailoutFlow.tryEmit(Pair(ticker, reason))
                bailoutConfirmCount.remove(ticker) // Reset counter
            }
        } else {
            // Kondisi tidak memenuhi semua kriteria → reset counter konfirmasi
            if (bailoutConfirmCount.containsKey(ticker)) {
                bailoutConfirmCount.remove(ticker)
            }
        }

        // Cek apakah fake wall sudah terkonfirmasi DAN harga juga turun (bukan akumulasi)
        if (ofResult.hasFakeWall && isPriceFalling && isNotAccumulation) {
            val lastFw = lastBailoutAlertPerTicker["fw_$ticker"] ?: 0L
            if (now - lastFw < BAILOUT_ALERT_COOLDOWN_MS) return

            val count = (bailoutConfirmCount["fw_$ticker"] ?: 0) + 1
            bailoutConfirmCount["fw_$ticker"] = count
            if (count >= 2) {
                lastBailoutAlertPerTicker["fw_$ticker"] = now
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

                val history = synchronized(historyMap) { historyMap.getOrPut(snapshot.ticker) { mutableListOf() } }
                if (shouldAddSnapshot(history.lastOrNull(), snapshot, isQuietMarket)) {
                    synchronized(historyMap) {
                        history.add(snapshot)
                        if (history.size > MAX_SNAPSHOT_HISTORY) history.removeAt(0)
                    }
                    isDirty = true
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
                val price = obj.optInt("price", 0)

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

                // Update harga langsung seketika (real-time tick by tick)
                if (price > 0) {
                    updateRealtimePrice(ticker, price)
                }
            }
        } catch (e: Exception) {
            // Abaikan parsing error stream
        }
    }

    fun updateRealtimePrice(ticker: String, price: Int) {
        if (price <= 0) return
        val clean = ticker.trim().uppercase()

        // 0. VALIDASI HARGA ANTI-FLUTTER (Mencegah lonjakan ke High 190, Open/Low 180, atau Lot size):
        val currentSnap = synchronized(historyMap) {
            historyMap[clean]?.lastOrNull() ?: historyMap["movers_$clean"]?.lastOrNull()
        }
        if (currentSnap != null && currentSnap.bidLevels.isNotEmpty() && currentSnap.offerLevels.isNotEmpty()) {
            val bestBid = currentSnap.bidLevels.first().price
            val bestOffer = currentSnap.offerLevels.first().price
            if (bestBid > 0 && bestOffer > 0 && bestBid <= bestOffer) {
                val tick = PriceFraction.getTickSize(bestBid)
                val minValid = bestBid - (2 * tick)
                val maxValid = bestOffer + (2 * tick)
                if (price < minValid || price > maxValid) {
                    android.util.Log.w("PRICE_GUARD", "⚠️ Abaikan lonjakan harga liar $clean: Rp $price (BestBid: $bestBid, BestOffer: $bestOffer)")
                    return
                }
            }
        } else {
            val existingPrice = _manualFlow.value.find { it.ticker == clean }?.lastPrice
                ?: _moversFlow.value.find { it.ticker == clean }?.lastPrice
                ?: 0
            if (existingPrice > 0) {
                val diffPct = kotlin.math.abs(price - existingPrice).toDouble() / existingPrice
                if (diffPct > 0.05) {
                    android.util.Log.w("PRICE_GUARD", "⚠️ Abaikan lonjakan harga liar $clean: Rp $price vs Rp $existingPrice")
                    return
                }
            }
        }

        // 1. Update di Manual Flow
        val manual = _manualFlow.value
        val mIdx = manual.indexOfFirst { it.ticker == clean }
        if (mIdx >= 0) {
            val old = manual[mIdx]
            if (old.lastPrice != price) {
                val updated = manual.toMutableList()
                updated[mIdx] = old.copy(lastPrice = price)
                _manualFlow.value = updated
            }
        }

        // 2. Update di Movers Flow
        val movers = _moversFlow.value
        val movIdx = movers.indexOfFirst { it.ticker == clean }
        if (movIdx >= 0) {
            val old = movers[movIdx]
            if (old.lastPrice != price) {
                val updated = movers.toMutableList()
                updated[movIdx] = old.copy(lastPrice = price)
                _moversFlow.value = updated
            }
        }

        // 3. Update di Top Picks Flow
        val top = _topPicksFlow.value
        val tIdx = top.indexOfFirst { it.ticker == clean }
        if (tIdx >= 0) {
            val old = top[tIdx]
            if (old.lastPrice != price) {
                val updated = top.toMutableList()
                updated[tIdx] = old.copy(lastPrice = price)
                _topPicksFlow.value = updated
            }
        }

        // 4. Update di Portfolio Flow
        updatePortfolioPrice(clean, price)

        // 5. Sinkronkan snapshot terakhir di historyMap agar tidak terjadi regresi harga
        synchronized(historyMap) {
            val hManual = historyMap[clean]
            if (hManual != null && hManual.isNotEmpty()) {
                val last = hManual.last()
                if (last.lastPrice != price) {
                    hManual[hManual.size - 1] = last.copy(lastPrice = price)
                }
            }
            val hMovers = historyMap["movers_$clean"]
            if (hMovers != null && hMovers.isNotEmpty()) {
                val last = hMovers.last()
                if (last.lastPrice != price) {
                    hMovers[hMovers.size - 1] = last.copy(lastPrice = price)
                }
            }
        }
    }

    private fun updatePortfolioPrice(ticker: String, newPrice: Int) {
        if (_portfolio.isEmpty()) return
        var changed = false
        for (i in _portfolio.indices) {
            val trade = _portfolio[i]
            // Hanya update posisi aktif, jangan ganggu riwayat track record yang sudah ditutup
            if (trade.isActive && trade.ticker == ticker && trade.currentPrice != newPrice) {
                val pnlPercent = ((newPrice - trade.entryPrice).toDouble() / trade.entryPrice) * 100
                val pnlRupiah = ((newPrice - trade.entryPrice).toLong() * trade.lot * 100)
                _portfolio[i] = trade.copy(
                    currentPrice = newPrice,
                    pnlPercent = pnlPercent,
                    pnlRupiah = pnlRupiah
                )
                changed = true
            }
        }
        if (changed) {
            _portfolioFlow.value = _portfolio.toList()
        }
    }

    // ============================================================
    // DATA PROCESSING: Bandar Detector (Official Stockbit API)
    // ============================================================

    // ============================================================
    // KLASIFIKASI BROKER BURSA EFEK INDONESIA (BEI / IDX)
    // ============================================================

    // 1. Broker Ritel Murni (Domisili investor ritel & trader ritel amatir)
    private val RETAIL_BROKERS = setOf(
        "YP", // Mirae Asset Sekuritas Indonesia (Raksasa ritel #1)
        "PD", // Indo Premier Sekuritas / IPOT (Raksasa ritel #2)
        "XC", // Ajaib Sekuritas (Ritel milenial)
        "XL", // Stockbit Sekuritas (Ritel)
        "KK", // Phillip Sekuritas (Mayoritas ritel)
        "SQ", // BCA Sekuritas (Ritel)
        "GR", // Panin Sekuritas (Ritel)
        "EP", // MNC Sekuritas (Ritel)
        "NI"  // BNI Sekuritas (Cabang ritel)
    )

    // 2. Broker Bandar Lokal / Market Maker / Scalper Bandars (Whale, Institusi, Konglomerat)
    private val BANDAR_LOKAL_BROKERS = setOf(
        "MG", // Semesta Indovest (Raja Scalper & Bandar ARA Hunter #1 di BEI!)
        "AZ", // Sucor Sekuritas (Market Maker & Bandar Saham Konglomerat/Gorengan)
        "CP", // KB Valbury Sekuritas (Bandar gorengan / scalper)
        "DR", // RHB Sekuritas Indonesia (Bandar agresif)
        "LG", // Trimegah Sekuritas (Institusi lokal / bandar)
        "HP", // Henan Putihrai Sekuritas (Whale konglomerat / sindikasi market maker)
        "CC", // Mandiri Sekuritas (Institusi BUMN / Investment Bank / Whale)
        "TP", // OCBC Sekuritas
        "KI", // Ciptadana Sekuritas (Bandar institusi)
        "CD", // Ciptadana Sekuritas
        "HD", // KGI Sekuritas (Whale)
        "AI", // UOB Kay Hian Sekuritas (Bandar lokal)
        "SF", // Surya Fajar Sekuritas (Market maker)
        "IF", // Samuel Sekuritas (Institusi)
        "XA", // Woori Korindo Sekuritas
        "SH", // Danareksa Sekuritas
        "AN", // Wanteg Sekuritas
        "OD", // BRI Danareksa Sekuritas
        "AT", // Phintraco Sekuritas
        "BB", // Verdhana Sekuritas
        "LH", // Binaartha Sekuritas
        "AP"  // Pacific Sekuritas
    )

    // 3. Broker Institusi Asing / Smart Money Global
    private val FOREIGN_BROKERS = setOf(
        "BK", // J.P. Morgan Sekuritas
        "AK", // UBS Sekuritas
        "CS", // Credit Suisse Sekuritas
        "RX", // Macquarie Sekuritas
        "KZ", // CLSA Sekuritas
        "ZP", // Maybank Sekuritas
        "YU", // CGS-CIMB Sekuritas
        "CG", // Citigroup Sekuritas
        "DB", // Deutsche Bank
        "MS", // Morgan Stanley
        "DP", // DBS Vickers Sekuritas
        "GW"  // HSBC Sekuritas
    )

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

            val avgObj = bandarDetectorObj.optJSONObject("avg")
            val stockbitAvg = when {
                bandarDetectorObj.has("average") -> bandarDetectorObj.optDouble("average", 0.0)
                avgObj != null && avgObj.has("average_buy") -> avgObj.optDouble("average_buy", 0.0)
                avgObj != null && avgObj.has("average") -> avgObj.optDouble("average", 0.0)
                else -> 0.0
            }
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

            // 2. Ekstrak Detail Broker dan Klasifikasi Ritel vs Bandar
            data class BrokerItem(
                val code: String,
                val netVal: Long,
                val buyVal: Double,
                val buyLot: Long,
                val buyAvg: Double,
                val isBuyer: Boolean
            )

            fun parseJsonDouble(obj: org.json.JSONObject, vararg keys: String): Double {
                for (k in keys) {
                    if (obj.has(k)) {
                        val d = obj.optDouble(k, Double.NaN)
                        if (!d.isNaN()) return d
                        val str = obj.optString(k, "")
                        val p = str.replace(",", "").toDoubleOrNull()
                        if (p != null) return p
                    }
                }
                return 0.0
            }

            fun parseJsonLong(obj: org.json.JSONObject, vararg keys: String): Long {
                for (k in keys) {
                    if (obj.has(k)) {
                        val l = obj.optLong(k, -1L)
                        if (l != -1L) return l
                        val str = obj.optString(k, "")
                        val p = str.replace(",", "").toLongOrNull()
                        if (p != null) return p
                    }
                }
                return 0L
            }

            fun parseBrokerCode(obj: org.json.JSONObject): String {
                val cand = listOf("netbs_broker_code", "broker_code", "code", "broker", "brokerCode", "net_buy_broker", "net_sell_broker", "buy_broker", "sell_broker", "broker_id", "broker_name", "type", "symbol")
                for (k in cand) {
                    val opt = obj.opt(k)
                    if (opt is org.json.JSONObject) {
                        val subCode = opt.optString("code", opt.optString("broker_code", opt.optString("netbs_broker_code", ""))).trim().uppercase()
                        if (subCode.isNotEmpty() && subCode.length in 2..4) return subCode
                    }
                    val s = obj.optString(k, "").trim().uppercase()
                    if (s.isNotEmpty() && s.length in 2..4 && !s.all { it.isDigit() }) return s
                }
                return ""
            }

            val parsedBrokers = mutableListOf<BrokerItem>()
            var topBrokersSummary = ""
            var foreignBuySum = 0L
            var foreignSellSum = 0L
            var retailBuySum = 0L
            var retailSellSum = 0L
            var bandarBuySum = 0L
            var bandarSellSum = 0L

            val brokerSummaryObj = data.optJSONObject("broker_summary")
                ?: bandarDetectorObj.optJSONObject("broker_summary")
                ?: root.optJSONObject("broker_summary")

            val rawBB = brokerSummaryObj?.opt("brokers_buy")
            val rawBS = brokerSummaryObj?.opt("brokers_sell")

            val buyersArr = brokerSummaryObj?.optJSONArray("brokers_buy")
                ?: brokerSummaryObj?.optJSONArray("buyer")
                ?: brokerSummaryObj?.optJSONArray("buyers")
                ?: brokerSummaryObj?.optJSONArray("buy")
                ?: data.optJSONArray("brokers_buy")
                ?: data.optJSONArray("buyer")
                ?: data.optJSONArray("buyers")
                ?: data.optJSONArray("buy")
                ?: bandarDetectorObj.optJSONArray("brokers_buy")
                ?: bandarDetectorObj.optJSONArray("buyer")
                ?: bandarDetectorObj.optJSONArray("buyers")

            val sellersArr = brokerSummaryObj?.optJSONArray("brokers_sell")
                ?: brokerSummaryObj?.optJSONArray("seller")
                ?: brokerSummaryObj?.optJSONArray("sellers")
                ?: brokerSummaryObj?.optJSONArray("sell")
                ?: data.optJSONArray("brokers_sell")
                ?: data.optJSONArray("seller")
                ?: data.optJSONArray("sellers")
                ?: data.optJSONArray("sell")
                ?: bandarDetectorObj.optJSONArray("brokers_sell")
                ?: bandarDetectorObj.optJSONArray("seller")
                ?: bandarDetectorObj.optJSONArray("sellers")

            val genericBrokersArr = data.optJSONArray("brokers")
                ?: brokerSummaryObj?.optJSONArray("brokers")
                ?: brokerSummaryObj?.optJSONArray("data")
                ?: data.optJSONArray("broker_summary")
                ?: bandarDetectorObj.optJSONArray("brokers")
                ?: bandarDetectorObj.optJSONArray("data")

            val buyersStrList = mutableListOf<String>()
            val sellersStrList = mutableListOf<String>()

            // 2A. Parsing format tabel Buyer vs Seller terpisah (Format Standar Stockbit Broker Summary)
            if (buyersArr != null || sellersArr != null) {
                if (buyersArr != null) {
                    for (i in 0 until buyersArr.length()) {
                        val b = buyersArr.optJSONObject(i) ?: continue
                        val code = parseBrokerCode(b)
                        if (code.isEmpty()) continue
                        val buyVal = parseJsonDouble(b, "bval", "bvalv", "b_val", "buy_value", "buy_val", "val", "value", "net_val", "net_value")
                        val buyLot = parseJsonLong(b, "blot", "blotv", "b_lot", "buy_lot", "buy_volume", "lot", "vol", "volume", "net_lot", "net_volume")
                        val buyAvg = parseJsonDouble(b, "netbs_buy_avg_price", "b_avg", "buy_avg", "buy_average", "avg_buy_price", "avg_price", "avg", "average", "price")
                            .let { if (it > 0.0) it else if (buyLot > 0) buyVal / (buyLot * 100.0) else 0.0 }
                        val netVal = buyVal.toLong()
                        parsedBrokers.add(BrokerItem(code, netVal, buyVal, buyLot, buyAvg, isBuyer = true))

                        val isRetail = code in RETAIL_BROKERS
                        val isForeign = code in FOREIGN_BROKERS
                        val isBandarLokal = code in BANDAR_LOKAL_BROKERS

                        if (isForeign) {
                            foreignBuySum += netVal
                            bandarBuySum += netVal
                        } else if (isBandarLokal) {
                            bandarBuySum += netVal
                        } else if (isRetail) {
                            retailBuySum += netVal
                        }

                        if (i < 5) {
                            val tag = if (isForeign) "(Asing)" else if (isBandarLokal) "(Bandar)" else if (isRetail) "(Ritel)" else ""
                            buyersStrList.add("$code$tag (+${formatCurrencyShort(netVal)})")
                        }
                    }
                }

                if (sellersArr != null) {
                    for (i in 0 until sellersArr.length()) {
                        val b = sellersArr.optJSONObject(i) ?: continue
                        val code = parseBrokerCode(b)
                        if (code.isEmpty()) continue
                        val sellVal = parseJsonDouble(b, "sval", "svalv", "s_val", "sell_value", "sell_val", "val", "value", "net_val", "net_value")
                        val sellLot = parseJsonLong(b, "slot", "slotv", "s_lot", "sell_lot", "sell_volume", "lot", "vol", "volume", "net_lot", "net_volume")
                        val sellAvg = parseJsonDouble(b, "netbs_sell_avg_price", "s_avg", "sell_avg", "sell_average", "avg_sell_price", "avg_price", "avg", "average", "price")
                            .let { if (it > 0.0) it else if (sellLot > 0) sellVal / (sellLot * 100.0) else 0.0 }
                        val netVal = -sellVal.toLong()
                        parsedBrokers.add(BrokerItem(code, netVal, sellVal, sellLot, sellAvg, isBuyer = false))

                        val isRetail = code in RETAIL_BROKERS
                        val isForeign = code in FOREIGN_BROKERS
                        val isBandarLokal = code in BANDAR_LOKAL_BROKERS

                        if (isForeign) {
                            foreignSellSum += sellVal.toLong()
                            bandarSellSum += sellVal.toLong()
                        } else if (isBandarLokal) {
                            bandarSellSum += sellVal.toLong()
                        } else if (isRetail) {
                            retailSellSum += sellVal.toLong()
                        }

                        if (i < 5) {
                            val tag = if (isForeign) "(Asing)" else if (isBandarLokal) "(Bandar)" else if (isRetail) "(Ritel)" else ""
                            sellersStrList.add("$code$tag (-${formatCurrencyShort(sellVal.toLong())})")
                        }
                    }
                }
            }

            // 2B. Fallback ke format flat brokers array jika format di atas kosong
            if (parsedBrokers.isEmpty() && genericBrokersArr != null && genericBrokersArr.length() > 0) {
                for (i in 0 until genericBrokersArr.length()) {
                    val b = genericBrokersArr.optJSONObject(i) ?: continue
                    val code = parseBrokerCode(b)
                    if (code.isEmpty()) continue
                    val netVal = parseJsonDouble(b, "net_value", "net_val", "net").toLong()
                    val buyVal = parseJsonDouble(b, "b_val", "buy_value", "buy_val")
                    val sellVal = parseJsonDouble(b, "s_val", "sell_value", "sell_val")
                    val buyLot = parseJsonLong(b, "b_lot", "buy_lot", "buy_volume")
                    val buyAvg = parseJsonDouble(b, "b_avg", "buy_avg", "buy_average", "avg_buy_price")
                        .let { if (it > 0.0) it else if (buyLot > 0) buyVal / (buyLot * 100.0) else 0.0 }
                    val effectiveNet = if (netVal != 0L) netVal else (buyVal - sellVal).toLong()
                    val isBuyer = effectiveNet >= 0

                    parsedBrokers.add(BrokerItem(code, effectiveNet, if (isBuyer) buyVal else sellVal, buyLot, buyAvg, isBuyer = isBuyer))

                    val isRetail = code in RETAIL_BROKERS
                    val isForeign = code in FOREIGN_BROKERS
                    val isBandarLokal = code in BANDAR_LOKAL_BROKERS

                    if (isForeign) {
                        if (effectiveNet > 0) foreignBuySum += effectiveNet else foreignSellSum += kotlin.math.abs(effectiveNet)
                        if (effectiveNet > 0) bandarBuySum += effectiveNet else bandarSellSum += kotlin.math.abs(effectiveNet)
                    } else if (isBandarLokal) {
                        if (effectiveNet > 0) bandarBuySum += effectiveNet else bandarSellSum += kotlin.math.abs(effectiveNet)
                    } else if (isRetail) {
                        if (effectiveNet > 0) retailBuySum += effectiveNet else retailSellSum += kotlin.math.abs(effectiveNet)
                    }

                    if (i < 20) {
                        val tag = if (isForeign) "(Asing)" else if (isBandarLokal) "(Bandar)" else if (isRetail) "(Ritel)" else ""
                        if (effectiveNet > 0) {
                            buyersStrList.add("$code$tag (+${formatCurrencyShort(effectiveNet)})")
                        } else if (effectiveNet < 0) {
                            sellersStrList.add("$code$tag (${formatCurrencyShort(effectiveNet)})")
                        }
                    }
                }
            }

            val buyStr = if (buyersStrList.isNotEmpty()) "Top Buyer: ${buyersStrList.take(5).joinToString(", ")}" else ""
            val sellStr = if (sellersStrList.isNotEmpty()) "Top Seller: ${sellersStrList.take(5).joinToString(", ")}" else ""
            topBrokersSummary = listOf(buyStr, sellStr).filter { it.isNotEmpty() }.joinToString(" | ")

            if (parsedBrokers.isEmpty()) {
                val dataKeys = data.keys().asSequence().toList()
                android.util.Log.w("BANDAR_DETECTOR", "⚠️ No brokers parsed for $ticker. Keys in 'data': $dataKeys")
                brokerSummaryObj?.let {
                    android.util.Log.w("BANDAR_DETECTOR", "Keys in 'broker_summary': ${it.keys().asSequence().toList()}")
                }
            } else {
                android.util.Log.d("BANDAR_DETECTOR", "✅ Parsed ${parsedBrokers.size} brokers for $ticker ($topBrokersSummary)")
            }

            // 3. Hitung Harga Modal Avg Bandar Riil dari Top Net Buyers
            val netBuyers = parsedBrokers.filter { it.netVal > 0 }.sortedByDescending { it.netVal }
            val netSellers = parsedBrokers.filter { it.netVal < 0 }.sortedBy { it.netVal }
            val top3Buyers = netBuyers.take(3)
            val totalTop3BuyVal = top3Buyers.sumOf { it.buyVal }
            val totalTop3BuyLot = top3Buyers.sumOf { it.buyLot }
            val calculatedBandarAvg = if (totalTop3BuyLot > 0) (totalTop3BuyVal / (totalTop3BuyLot * 100.0)) else 0.0
            val effectiveAvgPrice = if (stockbitAvg > 0) stockbitAvg else calculatedBandarAvg

            val avgCalculationSource = if (top3Buyers.isNotEmpty()) {
                "Dihitung dari Top Net Buyer: " + top3Buyers.joinToString(", ") { b ->
                    val brokerTag = if (b.code in FOREIGN_BROKERS) "Asing" else if (b.code in BANDAR_LOKAL_BROKERS) "Bandar" else if (b.code in RETAIL_BROKERS) "Ritel" else "Lokal"
                    "${b.code} ($brokerTag @ Rp ${b.buyAvg.toInt()})"
                }
            } else ""

            // 4. Analisa Bandarmologi: Broker Ritel vs Broker Bandar
            val topBuyerCodes = netBuyers.take(5).map { it.code }
            val topSellerCodes = netSellers.take(5).map { it.code }

            // CRITICAL SAFEGUARD: isNotEmpty() prevents Kotlin vacuous truth bug on emptyList.all {}
            val topBuyerIsRetail = topBuyerCodes.isNotEmpty() && topBuyerCodes.take(2).all { it in RETAIL_BROKERS }
            val topBuyerHasBandar = topBuyerCodes.isNotEmpty() && topBuyerCodes.any { it in BANDAR_LOKAL_BROKERS || it in FOREIGN_BROKERS }
            val topSellerIsRetail = topSellerCodes.isNotEmpty() && topSellerCodes.any { it in RETAIL_BROKERS }

            val isRealBandarDumping = (bandarSellSum > bandarBuySum * 1.5 && bandarSellSum > 400_000_000L) ||
                    accdistStatus.contains("Dist", ignoreCase = true)

            val (bandarProfile, bandarProfileLabel, bandarProfileReason) = when {
                // KONDISI KHUSUS: Tidak ada data broker sama sekali dari response API
                parsedBrokers.isEmpty() -> {
                    val fallbackLabel = if (accdistStatus.contains("Acc", ignoreCase = true)) "🟢 AKUMULASI (RINGKASAN)"
                    else if (accdistStatus.contains("Dist", ignoreCase = true)) "🔴 DISTRIBUSI (RINGKASAN)"
                    else "⚪ ALIRAN SEIMBANG"
                    Triple(
                        "NEUTRAL",
                        fallbackLabel,
                        "Status akumulasi: $accdistStatus. Menunggu data detail transaksi broker dari bursa."
                    )
                }

                // A. DISTRIBUSI BANDAR NYATA (Guyuran Masif ke Pasar):
                isRealBandarDumping && !topBuyerHasBandar -> {
                    val sellerList = topSellerCodes.filter { it in BANDAR_LOKAL_BROKERS || it in FOREIGN_BROKERS }
                        .ifEmpty { topSellerCodes.take(2) }.joinToString("/")
                    val buyerList = topBuyerCodes.filter { it in RETAIL_BROKERS }
                        .ifEmpty { topBuyerCodes.take(2) }.joinToString("/")
                    if (topBuyerIsRetail) {
                        Triple(
                            "RETAIL_TRAP",
                            "⚠️ PERANGKAP CUCI GUDANG ($buyerList)",
                            "Waspada Guyuran! Bandar/institusi ($sellerList) distribusi masif buang barang ke ritel ($buyerList) yang menampung. Dilarang FOMO!"
                        )
                    } else {
                        val safeSeller = if (sellerList.isNotEmpty()) " ($sellerList)" else ""
                        Triple(
                            "DISTRIBUTION",
                            "🔴 DISTRIBUSI BANDAR$safeSeller",
                            "Broker bandar/institusi$safeSeller distribusi masif buang barang ke pasar. Dilarang beli!"
                        )
                    }
                }

                // B. BANDAR SCALPER / GORENGAN AKTIF:
                topBuyerCodes.take(2).any { it in setOf("MG", "CP", "AZ") } -> {
                    val scalper = topBuyerCodes.take(2).first { it in setOf("MG", "CP", "AZ") }
                    Triple(
                        "SCALPER_ACTIVE",
                        "⚡ BANDAR SCALPER AKTIF ($scalper)",
                        "Terdeteksi broker bandar scalper/gorengan ($scalper). Gerakan harga cepat, prioritaskan TP kilat!"
                    )
                }

                // C. AKUMULASI BANDAR MURNI:
                topBuyerHasBandar && (bandarBuySum > retailBuySum * 1.2) && topSellerIsRetail -> {
                    val bandarList = topBuyerCodes.filter { it in BANDAR_LOKAL_BROKERS || it in FOREIGN_BROKERS }.take(3).joinToString("/")
                    val ritelList = topSellerCodes.filter { it in RETAIL_BROKERS }.take(3).joinToString("/")
                    Triple(
                        "PURE_ACCUMULATION",
                        "🔥 AKUMULASI BANDAR MURNI ($bandarList)",
                        "Akumulasi Murni: Broker bandar ($bandarList) agresif menyerok barang dari ritel ($ritelList)!"
                    )
                }

                // D. MOMENTUM RITEL / SCALPER PUBLIK (Bukan Guyuran):
                topBuyerIsRetail || (retailBuySum > bandarBuySum * 1.3 && retailBuySum > 300_000_000L) -> {
                    val ritelList = topBuyerCodes.filter { it in RETAIL_BROKERS }.ifEmpty { topBuyerCodes.take(2) }.joinToString("/")
                    Triple(
                        "RETAIL_MOMENTUM",
                        "⚡ MOMENTUM RITEL RAMAI ($ritelList)",
                        "Saham ramai didorong broker ritel ($ritelList). Likuid untuk scalping kilat 1-3 tick, disiplin pasang trailing stop!"
                    )
                }

                else -> {
                    Triple(
                        "NEUTRAL",
                        "⚪ ALIRAN SEIMBANG",
                        "Aliran transaksi ritel dan institusi/bandar relatif seimbang."
                    )
                }
            }

            val retailVsBandarSummary = if (parsedBrokers.isNotEmpty()) {
                "Ritel: Beli ${formatCurrencyShort(retailBuySum)} / Jual ${formatCurrencyShort(retailSellSum)} • Bandar: Beli ${formatCurrencyShort(bandarBuySum)} / Jual ${formatCurrencyShort(bandarSellSum)}"
            } else ""

            // 5. Ekstrak Arus Investor Asing (Foreign Flow)
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

            val smartMoney = bandarProfileReason

            val existing = bandarDetectorMap[ticker]

            val stat = if (isMultiDay) {
                (existing ?: com.scalping.assistant.data.models.BandarDetectorStat(ticker = ticker)).copy(
                    accdistStatus = if (existing?.accdistStatus.isNullOrEmpty()) accdistStatus else existing?.accdistStatus ?: accdistStatus,
                    averagePrice = if ((existing?.averagePrice ?: 0.0) <= 0.0) effectiveAvgPrice else existing?.averagePrice ?: effectiveAvgPrice,
                    bandarProfile = if (existing?.bandarProfile.isNullOrEmpty() || existing?.bandarProfile == "NEUTRAL") bandarProfile else existing?.bandarProfile ?: bandarProfile,
                    bandarProfileLabel = if (existing?.bandarProfileLabel.isNullOrEmpty()) bandarProfileLabel else existing?.bandarProfileLabel ?: bandarProfileLabel,
                    topBrokers = if (existing?.topBrokers.isNullOrEmpty()) topBrokersSummary else existing?.topBrokers ?: topBrokersSummary,
                    topConcentration = if (existing?.topConcentration.isNullOrEmpty()) topConcentration else existing?.topConcentration ?: topConcentration,
                    foreignFlow = if (existing?.foreignFlow.isNullOrEmpty()) foreignFlowStr else existing?.foreignFlow ?: foreignFlowStr,
                    foreignFlowMultiDay = foreignFlowStr,
                    smartMoneySummary = if (existing?.smartMoneySummary.isNullOrEmpty()) smartMoney else existing?.smartMoneySummary ?: smartMoney,
                    retailVsBandarSummary = if (existing?.retailVsBandarSummary.isNullOrEmpty()) retailVsBandarSummary else existing?.retailVsBandarSummary ?: retailVsBandarSummary,
                    lastUpdated = System.currentTimeMillis()
                )
            } else {
                com.scalping.assistant.data.models.BandarDetectorStat(
                    ticker = ticker,
                    accdistStatus = accdistStatus,
                    averagePrice = effectiveAvgPrice,
                    avgCalculationSource = avgCalculationSource,
                    bandarProfile = bandarProfile,
                    bandarProfileLabel = bandarProfileLabel,
                    amountRupiah = amount,
                    volumeLot = vol,
                    topBrokers = topBrokersSummary,
                    topConcentration = topConcentration,
                    foreignFlow = foreignFlowStr,
                    foreignFlowMultiDay = existing?.foreignFlowMultiDay ?: "",
                    smartMoneySummary = smartMoney,
                    retailVsBandarSummary = retailVsBandarSummary,
                    lastUpdated = System.currentTimeMillis()
                )
            }

            synchronized(bandarDetectorMap) {
                bandarDetectorMap[ticker] = stat
            }
            _bandarDetectorFlow.value = bandarDetectorMap.toMap()
            isDirty = true
            android.util.Log.d("BANDAR_DETECTOR", "Parsed $ticker (multiDay=$isMultiDay): $bandarProfileLabel @ Rp $effectiveAvgPrice, foreign: $foreignFlowStr")

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

    private fun applySignalLock(raw: StockAnalysis): StockAnalysis {
        val ticker = raw.ticker
        val existingLock = signalLocks[ticker]
        val now = System.currentTimeMillis()

        // 1. KONDISI DARURAT (Emergency / Hard Invalidation) yang MEMBATALKAN LOCK SECARA INSTAN:
        // Jika harga jatuh di bawah stop loss (turun > 2% dari entry lock), bandar Big Dist, atau guyuran masif
        val isPriceDumped = existingLock != null && raw.lastPrice <= PriceFraction.roundDownToValidTick((existingLock.lockPrice * 0.98).toInt())
        val isBandarDumping = raw.bandarDetector?.accdistStatus == "Big Dist"
        val isMassiveHaki = raw.orderFlow.hasFakeWall && raw.orderFlow.cumulativeDelta < -2000L
        val isAraHit = raw.snapshotCount > 0 && raw.changePercent >= 24.0
        val isEmergency = isPriceDumped || isBandarDumping || isMassiveHaki || isAraHit

        if (isEmergency) {
            signalLocks.remove(ticker)
            demotionCounters.remove(ticker)
            return raw
        }

        // 2. JIKA SEDANG TERKUNCI (Dalam masa lock 25 detik):
        if (existingLock != null && (now - existingLock.lockTime) < existingLock.durationMs) {
            demotionCounters.remove(ticker)
            val effectiveRec = if (raw.recommendation == Recommendation.STRONG_BUY) {
                Recommendation.STRONG_BUY
            } else {
                existingLock.lockedRecommendation
            }
            return raw.copy(
                recommendation = effectiveRec
            )
        }

        // 3. JIKA ANALISIS RAW ADALAH BUY ATAU STRONG_BUY (Dapatkan Kunci Baru):
        if (raw.recommendation == Recommendation.STRONG_BUY || raw.recommendation == Recommendation.BUY) {
            signalLocks[ticker] = SignalLock(
                ticker = ticker,
                lockedRecommendation = raw.recommendation,
                lockTime = now,
                lockPrice = raw.lastPrice,
                durationMs = 25_000L
            )
            demotionCounters.remove(ticker)
            return raw
        }

        // 4. JIKA INGIN TURUN KELAS (Dari BUY/STRONG_BUY ke WATCH):
        // Anti-Flicker Debounce: butuh 4 pembacaan berturut-turut sebelum resmi turun
        if (existingLock != null) {
            val count = (demotionCounters[ticker] ?: 0) + 1
            demotionCounters[ticker] = count
            if (count < 4) {
                return raw.copy(recommendation = existingLock.lockedRecommendation)
            } else {
                signalLocks.remove(ticker)
                demotionCounters.remove(ticker)
            }
        }

        // 5. PENYANGGA WATCH vs AVOID (Hysteresis Buffer):
        if (raw.recommendation == Recommendation.AVOID && raw.score >= 42 && !isBandarDumping && !isMassiveHaki) {
            return raw.copy(recommendation = Recommendation.WATCH)
        }

        return raw
    }

    private fun reAnalyzeTicker(ticker: String) {
        val manualList = _manualFlow.value.toMutableList()
        val manualIdx = manualList.indexOfFirst { it.ticker == ticker }
        if (manualIdx >= 0) {
            val oldAnalysis = manualList[manualIdx]
            val rawSnap = historyMap[ticker]?.lastOrNull()
            if (rawSnap != null) {
                val bestBid = rawSnap.bidLevels.firstOrNull()?.price ?: 0
                val bestOffer = rawSnap.offerLevels.firstOrNull()?.price ?: 0
                var effectivePrice = if (rawSnap.lastPrice > 0) rawSnap.lastPrice else oldAnalysis.lastPrice
                if (bestBid > 0 && bestOffer > 0 && bestBid <= bestOffer) {
                    val tick = PriceFraction.getTickSize(bestBid)
                    val minValid = bestBid - (2 * tick)
                    val maxValid = bestOffer + (2 * tick)
                    if (effectivePrice < minValid || effectivePrice > maxValid) {
                        effectivePrice = if (rawSnap.changePercent > 0) bestOffer else bestBid
                    }
                }
                val effectiveChange = if (rawSnap.changePercent != 0.0) rawSnap.changePercent else oldAnalysis.changePercent
                val snap = rawSnap.copy(lastPrice = effectivePrice, changePercent = effectiveChange)

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
                val lockedAnalysis = applySignalLock(newAnalysis)
                previousRecommendations[ticker] = lockedAnalysis.recommendation
                manualList[manualIdx] = lockedAnalysis
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
            val rawSnap = historyMap["movers_$ticker"]?.lastOrNull() ?: OrderBookSnapshot(
                ticker = ticker,
                lastPrice = oldAnalysis.lastPrice,
                changePercent = oldAnalysis.changePercent,
                timestamp = System.currentTimeMillis(),
                bidLevels = emptyList(),
                offerLevels = emptyList(),
                totalBidLot = 0L,
                totalOfferLot = 0L
            )
            val bestBid = rawSnap.bidLevels.firstOrNull()?.price ?: 0
            val bestOffer = rawSnap.offerLevels.firstOrNull()?.price ?: 0
            var effectivePrice = if (rawSnap.lastPrice > 0) rawSnap.lastPrice else oldAnalysis.lastPrice
            if (bestBid > 0 && bestOffer > 0 && bestBid <= bestOffer) {
                val tick = PriceFraction.getTickSize(bestBid)
                val minValid = bestBid - (2 * tick)
                val maxValid = bestOffer + (2 * tick)
                if (effectivePrice < minValid || effectivePrice > maxValid) {
                    effectivePrice = if (rawSnap.changePercent > 0) bestOffer else bestBid
                }
            }
            val effectiveChange = if (rawSnap.changePercent != 0.0) rawSnap.changePercent else oldAnalysis.changePercent
            val snap = rawSnap.copy(lastPrice = effectivePrice, changePercent = effectiveChange)

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
            val lockedAnalysis = applySignalLock(newAnalysis)
            previousRecommendations["movers_$ticker"] = lockedAnalysis.recommendation
            moversList[moversIdx] = lockedAnalysis
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

                val history = synchronized(historyMap) { historyMap.getOrPut("movers_${snapshot.ticker}") { mutableListOf() } }
                if (shouldAddSnapshot(history.lastOrNull(), snapshot, isQuietMarket)) {
                    synchronized(historyMap) {
                        history.add(snapshot)
                        if (history.size > MAX_SNAPSHOT_HISTORY) history.removeAt(0)
                    }
                    isDirty = true
                }
            }

            val newAnalyses = analyzeSnapshots(currentSnapshots, isMovers = true)
            val currentList = _moversFlow.value.toMutableList()
            if (currentList.isEmpty()) {
                val sorted = newAnalyses.sortedByDescending { it.score }
                _moversFlow.value = sorted
            } else {
                val newMap = newAnalyses.associateBy { it.ticker }
                val merged = currentList.map { existing ->
                    newMap[existing.ticker] ?: existing
                }.toMutableList()

                for (item in newAnalyses) {
                    if (merged.none { it.ticker == item.ticker }) {
                        merged.add(item)
                    }
                }
                val sorted = merged.sortedByDescending { it.score }
                _moversFlow.value = sorted
            }
            refreshTopPicks()
            updatePortfolioPositions(_moversFlow.value)

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
                            val source = obj.optString("source", "")
                            val historyKey = "movers_$ticker"
                            val existingSnap = synchronized(historyMap) { historyMap[historyKey]?.lastOrNull() ?: historyMap[ticker]?.lastOrNull() }
                            val rawPriceToUse = if (lastPrice > 0) lastPrice else (existingSnap?.lastPrice ?: _moversFlow.value.find { it.ticker == ticker }?.lastPrice ?: 0)
                            if (rawPriceToUse <= 0) return@async null

                            // Sanitize terhadap existing orderbook jika tersedia
                            var priceToUse = rawPriceToUse
                            val existingBid = existingSnap?.bidLevels?.firstOrNull()?.price ?: 0
                            val existingOffer = existingSnap?.offerLevels?.firstOrNull()?.price ?: 0
                            if (existingBid > 0 && existingOffer > 0 && existingBid <= existingOffer) {
                                val tick = PriceFraction.getTickSize(existingBid)
                                val minValid = existingBid - (2 * tick)
                                val maxValid = existingOffer + (2 * tick)
                                if (priceToUse < minValid || priceToUse > maxValid) {
                                    priceToUse = if (changePercent > 0) existingOffer else existingBid
                                }
                            }

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

                            val moverHistory = synchronized(historyMap) { historyMap.getOrPut(historyKey) { mutableListOf() } }
                            if (shouldAddSnapshot(moverHistory.lastOrNull(), snapshot, isQuietMarket(sessionInfo))) {
                                synchronized(historyMap) {
                                    moverHistory.add(snapshot)
                                    if (moverHistory.size > MAX_SNAPSHOT_HISTORY) moverHistory.removeAt(0)
                                }
                                isDirty = true
                            }

                            val cachedTech = technicalCache[ticker]
                            val techResult = if (cachedTech != null) {
                                cachedTech.copy(lastClosePrice = priceToUse.toDouble())
                            } else {
                                val candles = yahooRepo.fetchIntradayCandles(ticker)
                                val res = TechnicalAnalyzer.analyze(ticker, candles, priceToUse.toDouble())
                                if (candles.isNotEmpty()) {
                                    technicalCache[ticker] = res
                                }
                                res
                            }
                            val existingAnalysis = _moversFlow.value.find { it.ticker == ticker }
                            val ofResult = if (moverHistory != null && moverHistory.isNotEmpty() && moverHistory.last().bidLevels.isNotEmpty()) {
                                OrderFlowAnalyzer.analyze(ticker, moverHistory)
                            } else if (existingAnalysis != null && existingAnalysis.orderFlow.totalScore > 12) {
                                existingAnalysis.orderFlow
                            } else {
                                val ofDetails = mutableListOf<String>()
                                if (source.isNotEmpty()) {
                                    ofDetails.add("⚡ Kategori: $source")
                                }
                                if (turnover.isNotEmpty()) {
                                    ofDetails.add("🔥 Turnover Pasar: $turnover")
                                } else {
                                    ofDetails.add("📊 $source (Stockbit)")
                                }
                                com.scalping.assistant.data.models.OrderFlowResult(
                                    ticker = ticker,
                                    totalScore = 14,
                                    details = ofDetails
                                )
                            }

                            val prevRec = previousRecommendations["movers_$ticker"]
                            val snapshotCount = moverHistory?.size ?: 0
                            val bandarStat = bandarDetectorMap[ticker]
                            val tapeStat = tapeReadingMap[ticker]
                            val rawAnalysis = ScoringEngine.generateAnalysis(snapshot, ofResult, techResult, sessionInfo, prevRec, snapshotCount, tapeStat, bandarStat)
                            val lockedAnalysis = applySignalLock(rawAnalysis)
                            previousRecommendations["movers_$ticker"] = lockedAnalysis.recommendation
                            lockedAnalysis
                        } catch (e: Exception) {
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
            }

            if (analyses.isNotEmpty()) {
                val currentList = _moversFlow.value.toMutableList()
                val newMap = analyses.associateBy { it.ticker }
                val merged = analyses.toMutableList()

                // Pertahankan emiten lama jika belum ter-scrape di siklus saat ini (karena rotasi Top Movers vs Top Freq)
                for (item in currentList) {
                    if (!newMap.containsKey(item.ticker)) {
                        merged.add(item)
                    }
                }
                val sorted = merged.sortedByDescending { it.score }
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
        if (curr.bidLevels.isEmpty()) {
            // Ticker-only snapshot (Top Movers / Top Freq)
            return last.lastPrice != curr.lastPrice ||
                    kotlin.math.abs(last.changePercent - curr.changePercent) >= 0.1 ||
                    (curr.timestamp - last.timestamp >= 30_000L)
        }
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

            // Validasi sanitasi harga: jika ada bid/offer, pastikan lastPrice tidak melompat ke High/Open/Low
            val bestBid = bidLevels.firstOrNull()?.price ?: 0
            val bestOffer = offerLevels.firstOrNull()?.price ?: 0
            var finalPrice = lastPrice

            if (bestBid > 0 && bestOffer > 0 && bestBid <= bestOffer) {
                val tick = PriceFraction.getTickSize(bestBid)
                val minValid = bestBid - (2 * tick)
                val maxValid = bestOffer + (2 * tick)
                if (finalPrice < minValid || finalPrice > maxValid) {
                    finalPrice = if (changePercent > 0) bestOffer else bestBid
                }
            } else if (bestBid > 0 && (finalPrice <= 0 || finalPrice < bestBid * 0.85 || finalPrice > bestBid * 1.15)) {
                finalPrice = bestBid
            } else if (bestOffer > 0 && (finalPrice <= 0 || finalPrice < bestOffer * 0.85 || finalPrice > bestOffer * 1.15)) {
                finalPrice = bestOffer
            }

            OrderBookSnapshot(
                ticker = ticker, lastPrice = finalPrice, changePercent = changePercent,
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

            val rawAnalysis = ScoringEngine.generateAnalysis(snap, ofResult, techResult, sessionInfo, prevRec, snapshotCount, tapeReadingStat, bandarStat)
            val lockedAnalysis = applySignalLock(rawAnalysis)
            previousRecommendations[historyKey] = lockedAnalysis.recommendation
            analyses.add(lockedAnalysis)

            // Update banner aktif (backward compat)
            val currentTrade = currentActiveTrade
            if (currentTrade != null && currentTrade.ticker == ticker) {
                _activeTradeFlow.value = Pair(currentTrade, lockedAnalysis)
            }

            // Cek bailout untuk posisi portfolio
            checkBailout(ticker, lockedAnalysis, history)
        }

        return analyses
    }
}
