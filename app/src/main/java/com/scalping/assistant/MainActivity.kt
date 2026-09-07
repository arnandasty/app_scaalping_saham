package com.scalping.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.scalping.assistant.bridge.StockbitBridge
import com.scalping.assistant.data.models.Recommendation
import com.scalping.assistant.data.models.StockAnalysis
import com.scalping.assistant.data.repository.OrderBookRepository
import com.scalping.assistant.data.repository.YahooFinanceRepository
import com.scalping.assistant.engine.MarketSession
import com.scalping.assistant.ui.DetailBottomSheet
import com.scalping.assistant.ui.RankingAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var webViewMovers: WebView
    private lateinit var webViewStream: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var tabLayout: com.google.android.material.tabs.TabLayout
    private lateinit var viewPager: androidx.viewpager2.widget.ViewPager2
    private lateinit var tvSessionBadge: TextView
    private lateinit var tvSessionTime: TextView
    private lateinit var tvLiveStatus: TextView
    private lateinit var tvStockCount: TextView
    private lateinit var tvStatusLog: TextView

    // Drag handle views & Fullscreen layout
    private lateinit var webViewContainer: android.widget.FrameLayout
    private lateinit var dividerDragHandle: LinearLayout
    private lateinit var tvDragIndicatorLeft: TextView
    private lateinit var tvDragIndicatorRight: TextView
    private lateinit var llSessionBar: LinearLayout
    private lateinit var llHeaderAiPanel: LinearLayout
    private lateinit var btnToggleFullscreen: TextView
    private lateinit var aiPanelContainer: LinearLayout

    enum class LayoutMode {
        FULL_AI,    // 100% Layar penuh untuk Rekomendasi Scalping (WebView GONE)
        SPLIT,      // Split Screen (~50/50)
        FULL_WEB    // WebView maksimal, panel AI minim (~90/10)
    }

    private var currentLayoutMode = LayoutMode.SPLIT
    private val TOTAL_WEIGHT = 100f

    private lateinit var pagerAdapter: com.scalping.assistant.ui.RankingPagerAdapter
    private lateinit var yahooRepo: YahooFinanceRepository
    lateinit var orderBookRepo: OrderBookRepository

    private val handler = Handler(Looper.getMainLooper())
    private var injectorScript = ""
    private var moversInjectorScript = ""
    private var streamInjectorScript = ""
    private var streamProbeScript = ""
    private val probeLogs = mutableListOf<String>()
    private var moversTickers = listOf<String>()
    private val lastRequestedBandar = mutableMapOf<String, Long>()
    private val lastRequestedBandarMultiDay = mutableMapOf<String, Long>()
    private var stockbitAuthToken = ""
    private val PREF_STOCKBIT_TOKEN = "stockbit_auth_token"

    // ============================================================
    // Scraping Runnables
    // ============================================================

    private val scrapingRunnable = object : Runnable {
        override fun run() {
            if (::webView.isInitialized) {
                if (streamProbeScript.isNotEmpty()) {
                    webView.evaluateJavascript(streamProbeScript, null)
                }
                if (injectorScript.isNotEmpty()) {
                    webView.evaluateJavascript(injectorScript, null)
                }
            }

            if (::webViewMovers.isInitialized) {
                if (streamProbeScript.isNotEmpty()) {
                    webViewMovers.evaluateJavascript(streamProbeScript, null)
                }
                if (moversInjectorScript.isNotEmpty()) {
                    webViewMovers.evaluateJavascript(moversInjectorScript, null)
                }
            }
            
            if (::webViewStream.isInitialized) {
                if (streamProbeScript.isNotEmpty()) {
                    webViewStream.evaluateJavascript(streamProbeScript, null)
                }
                if (streamInjectorScript.isNotEmpty()) {
                    webViewStream.evaluateJavascript(streamInjectorScript, null)
                }
            }

            handler.postDelayed(this, 1000L) // Ubah delay scraping jadi 1 detik agar stream lebih update
        }
    }

    private val sessionTimerRunnable = object : Runnable {
        override fun run() {
            updateSessionUI()
            handler.postDelayed(this, 15000L)
        }
    }

    private var backPressedTime: Long = 0

    // Tracking untuk mencegah spam notifikasi TP/SL
    private val notifiedTPTrades = mutableMapOf<String, Long>()
    private val notifiedSLTrades = mutableMapOf<String, Long>()
    private var activeBailoutDialog: android.app.AlertDialog? = null
    private var isCutLossAlarmMuted = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        initServices()
        setupWebView()
        setupViewPager()
        setupDragHandle()
        observeData()

        webView.loadUrl("https://stockbit.com/orderbook")
        webViewMovers.loadUrl("https://stockbit.com/orderbook")
        webViewStream.loadUrl("https://stockbit.com/orderbook") // Nanti JS stream_injector akan klik tombol stream

        handler.post(sessionTimerRunnable)
        handler.postDelayed(scrapingRunnable, 5000L)
    }

    private fun initViews() {
        webView = findViewById(R.id.webViewStockbit)
        webViewMovers = findViewById(R.id.webViewMovers)
        webViewStream = findViewById(R.id.webViewStream)
        progressBar = findViewById(R.id.webViewProgressBar)
        tabLayout = findViewById(R.id.tabLayout)
        viewPager = findViewById(R.id.viewPager)
        tvSessionBadge = findViewById(R.id.tvSessionBadge)
        tvSessionTime = findViewById(R.id.tvSessionTime)
        tvLiveStatus = findViewById(R.id.tvLiveStatus)
        tvStockCount = findViewById(R.id.tvStockCount)
        tvStatusLog = findViewById(R.id.tvStatusLog)
        tvStatusLog.setOnClickListener {
            showProbeLogDialog()
        }
        webViewContainer = findViewById(R.id.webViewContainer)
        dividerDragHandle = findViewById(R.id.dividerDragHandle)
        tvDragIndicatorLeft = findViewById(R.id.tvDragIndicatorLeft)
        tvDragIndicatorRight = findViewById(R.id.tvDragIndicatorRight)
        llSessionBar = findViewById(R.id.llSessionBar)
        llHeaderAiPanel = findViewById(R.id.llHeaderAiPanel)
        btnToggleFullscreen = findViewById(R.id.btnToggleFullscreen)
        aiPanelContainer = findViewById(R.id.aiPanelContainer)
    }

    private fun initServices() {
        yahooRepo = YahooFinanceRepository()
        orderBookRepo = OrderBookRepository(applicationContext, yahooRepo)

        loadPortfolioFromPrefs()

        val prefs = getSharedPreferences("ScalpingPrefs", Context.MODE_PRIVATE)
        stockbitAuthToken = prefs.getString(PREF_STOCKBIT_TOKEN, "") ?: ""
        isCutLossAlarmMuted = prefs.getBoolean("pref_mute_cutloss_alarm", false)
        if (stockbitAuthToken.isNotEmpty()) {
            Log.d("BANDAR_NATIVE", "Loaded saved Stockbit token (${stockbitAuthToken.take(8)}...)")
        }

        try {
            injectorScript = assets.open("stockbit_injector.js").bufferedReader().use { it.readText() }
            moversInjectorScript = assets.open("movers_injector.js").bufferedReader().use { it.readText() }
            streamInjectorScript = assets.open("stream_injector.js").bufferedReader().use { it.readText() }
            streamProbeScript = assets.open("stream_probe.js").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            tvStatusLog.text = "Gagal memuat injector: ${e.message}"
        }
    }

    private fun loadPortfolioFromPrefs() {
        val prefs = getSharedPreferences("ScalpingPrefs", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("portfolio_data", null)
        if (jsonStr != null) {
            try {
                val array = JSONArray(jsonStr)
                val list = mutableListOf<com.scalping.assistant.data.repository.PortfolioTrade>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val entryPrice = obj.getInt("entryPrice")
                    val rawTarget = obj.optInt("targetPrice", 0)
                    // Target scalping/day-trade minimal harus >= 2.5% di atas entry price (auto-heal jika data korup/kurang dari target normal)
                    val minTarget = com.scalping.assistant.engine.PriceFraction.roundUpToValidTick(kotlin.math.ceil(entryPrice * 1.025).toInt())
                    val targetPrice = if (rawTarget < minTarget) {
                        com.scalping.assistant.engine.PriceFraction.roundUpToValidTick(kotlin.math.ceil(entryPrice * 1.028).toInt())
                    } else {
                        rawTarget
                    }
                    val defaultSL = com.scalping.assistant.engine.PriceFraction.roundDownToValidTick(kotlin.math.floor(entryPrice * 0.985).toInt())
                    val rawSL = obj.optInt("stopLoss", defaultSL)
                    val stopLoss = if (rawSL <= 0 || rawSL >= entryPrice) defaultSL else rawSL

                    val isActive = obj.optBoolean("isActive", true)
                    val closePrice = obj.optInt("closePrice", 0)
                    val currentPrice = obj.optInt("currentPrice", entryPrice)
                    val effectivePrice = if (!isActive && closePrice > 0) closePrice else currentPrice
                    val defaultPnl = if (entryPrice > 0) ((effectivePrice - entryPrice).toDouble() / entryPrice) * 100 else 0.0
                    val defaultPnlRp = (effectivePrice - entryPrice).toLong() * obj.getInt("lot") * 100
                    val pnlPercent = obj.optDouble("pnlPercent", defaultPnl)
                    val pnlRupiah = obj.optLong("pnlRupiah", defaultPnlRp)
                    val status = obj.optString("status", if (isActive) "ACTIVE" else "MANUAL")
                    val aiAction = if (isActive) "TAHAN" else when (status) {
                        "TP" -> "🎯 TAKE PROFIT"
                        "SL" -> "🛑 CUT LOSS"
                        else -> "DITUTUP ($status)"
                    }
                    val aiReason = if (isActive) "" else "Posisi direalisasikan keluar pada harga Rp $closePrice"

                    list.add(
                        com.scalping.assistant.data.repository.PortfolioTrade(
                            id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                            ticker = obj.getString("ticker"),
                            entryPrice = entryPrice,
                            lot = obj.getInt("lot"),
                            buyTime = obj.optLong("buyTime", System.currentTimeMillis()),
                            currentPrice = effectivePrice,
                            pnlPercent = pnlPercent,
                            pnlRupiah = pnlRupiah,
                            aiAction = aiAction,
                            aiReason = aiReason,
                            targetPrice = targetPrice,
                            stopLoss = stopLoss,
                            isActive = isActive,
                            closePrice = closePrice,
                            closeTime = obj.optLong("closeTime", 0L),
                            status = status
                        )
                    )
                }
                orderBookRepo.setPortfolioData(list)
                savePortfolioToPrefs(list)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun savePortfolioToPrefs(trades: List<com.scalping.assistant.data.repository.PortfolioTrade>) {
        val array = JSONArray()
        for (trade in trades) {
            val obj = JSONObject()
            obj.put("id", trade.id)
            obj.put("ticker", trade.ticker)
            obj.put("entryPrice", trade.entryPrice)
            obj.put("lot", trade.lot)
            obj.put("buyTime", trade.buyTime)
            obj.put("currentPrice", trade.currentPrice)
            obj.put("pnlPercent", trade.pnlPercent)
            obj.put("pnlRupiah", trade.pnlRupiah)
            obj.put("targetPrice", trade.targetPrice)
            obj.put("stopLoss", trade.stopLoss)
            obj.put("isActive", trade.isActive)
            obj.put("closePrice", trade.closePrice)
            obj.put("closeTime", trade.closeTime)
            obj.put("status", trade.status)
            array.put(obj)
        }
        val prefs = getSharedPreferences("ScalpingPrefs", Context.MODE_PRIVATE)
        prefs.edit().putString("portfolio_data", array.toString()).apply()
    }

    // ============================================================
    // DRAG HANDLE & FULLSCREEN SETUP
    // ============================================================

    fun setLayoutMode(mode: LayoutMode, showFeedbackToast: Boolean = false) {
        currentLayoutMode = mode
        when (mode) {
            LayoutMode.FULL_AI -> {
                webViewContainer.visibility = View.GONE
                applyLayoutWeights(0f, 100f)
                btnToggleFullscreen.text = "🗗 Split"
                btnToggleFullscreen.setTextColor(Color.parseColor("#10B981"))
                tvDragIndicatorLeft.text = "▼"
                tvDragIndicatorRight.text = "▼"
                if (showFeedbackToast) {
                    Toast.makeText(this, "📱 Rekomendasi Scalping: 1 Layar Penuh", Toast.LENGTH_SHORT).show()
                }
            }
            LayoutMode.SPLIT -> {
                webViewContainer.visibility = View.VISIBLE
                applyLayoutWeights(50f, 50f)
                btnToggleFullscreen.text = "⤢ Full"
                btnToggleFullscreen.setTextColor(Color.parseColor("#38BDF8"))
                tvDragIndicatorLeft.text = "▲"
                tvDragIndicatorRight.text = "▲"
            }
            LayoutMode.FULL_WEB -> {
                webViewContainer.visibility = View.VISIBLE
                applyLayoutWeights(88f, 12f)
                btnToggleFullscreen.text = "⤢ Full"
                btnToggleFullscreen.setTextColor(Color.parseColor("#38BDF8"))
                tvDragIndicatorLeft.text = "▲"
                tvDragIndicatorRight.text = "▲"
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragHandle() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val diffY = e2.rawY - e1.rawY
                // Swipe / Fling ke ATAS kencang -> Langsung 1 Layar Full Rekomendasi Scalping!
                if (diffY < -50 && velocityY < -200) {
                    setLayoutMode(LayoutMode.FULL_AI, showFeedbackToast = true)
                    return true
                }
                // Swipe / Fling ke BAWAH kencang
                if (diffY > 50 && velocityY > 200) {
                    if (currentLayoutMode == LayoutMode.FULL_AI) {
                        setLayoutMode(LayoutMode.SPLIT)
                    } else if (currentLayoutMode == LayoutMode.SPLIT) {
                        setLayoutMode(LayoutMode.FULL_WEB)
                    }
                    return true
                }
                return false
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // Single tap pada handle -> toggle Full AI <-> Split
                if (currentLayoutMode == LayoutMode.FULL_AI) {
                    setLayoutMode(LayoutMode.SPLIT)
                } else {
                    setLayoutMode(LayoutMode.FULL_AI, showFeedbackToast = true)
                }
                return true
            }
        })

        var isDragging = false
        var startRawY = 0f
        var startWebWeight = 50f

        val dragTouchListener = View.OnTouchListener { _, event ->
            if (gestureDetector.onTouchEvent(event)) {
                return@OnTouchListener true
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawY = event.rawY
                    startWebWeight = if (webViewContainer.visibility == View.GONE) 0f
                    else {
                        val lp = webViewContainer.layoutParams as? LinearLayout.LayoutParams
                        lp?.weight ?: 50f
                    }
                    isDragging = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isDragging) return@OnTouchListener false
                    val deltaY = event.rawY - startRawY
                    val screenHeight = resources.displayMetrics.heightPixels.toFloat()
                    val deltaWeight = (deltaY / screenHeight) * TOTAL_WEIGHT

                    val candidateWebWeight = (startWebWeight + deltaWeight).coerceIn(0f, 90f)

                    if (candidateWebWeight <= 8f) {
                        // Drag sampai dekat batas atas -> sembunyikan WebView
                        if (webViewContainer.visibility != View.GONE) {
                            webViewContainer.visibility = View.GONE
                        }
                        applyLayoutWeights(0f, 100f)
                        tvDragIndicatorLeft.text = "▼"
                        tvDragIndicatorRight.text = "▼"
                    } else {
                        if (webViewContainer.visibility != View.VISIBLE) {
                            webViewContainer.visibility = View.VISIBLE
                        }
                        applyLayoutWeights(candidateWebWeight, TOTAL_WEIGHT - candidateWebWeight)
                        tvDragIndicatorLeft.text = "▲"
                        tvDragIndicatorRight.text = "▲"
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    val currentWeight = if (webViewContainer.visibility == View.GONE) 0f
                    else {
                        val lp = webViewContainer.layoutParams as? LinearLayout.LayoutParams
                        lp?.weight ?: 50f
                    }

                    when {
                        currentWeight < 20f -> setLayoutMode(LayoutMode.FULL_AI, showFeedbackToast = true)
                        currentWeight > 75f -> setLayoutMode(LayoutMode.FULL_WEB)
                        else -> setLayoutMode(LayoutMode.SPLIT)
                    }
                    true
                }
                else -> false
            }
        }

        // Listener drag/swipe aktif pada Divider Drag Handle, Session Bar, dan Header Panel
        dividerDragHandle.setOnTouchListener(dragTouchListener)
        llSessionBar.setOnTouchListener(dragTouchListener)
        llHeaderAiPanel.setOnTouchListener(dragTouchListener)

        // Tombol Fullscreen / Split toggle langsung
        btnToggleFullscreen.setOnClickListener {
            if (currentLayoutMode == LayoutMode.FULL_AI) {
                setLayoutMode(LayoutMode.SPLIT)
            } else {
                setLayoutMode(LayoutMode.FULL_AI, showFeedbackToast = true)
            }
        }

        // Handle Back Button: jika sedang Fullscreen AI, kembalikan ke Split Screen dulu
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentLayoutMode == LayoutMode.FULL_AI) {
                    setLayoutMode(LayoutMode.SPLIT)
                } else if (currentLayoutMode == LayoutMode.FULL_WEB) {
                    setLayoutMode(LayoutMode.SPLIT)
                } else if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    if (System.currentTimeMillis() - backPressedTime < 2000) {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    } else {
                        backPressedTime = System.currentTimeMillis()
                        Toast.makeText(this@MainActivity, "Tekan sekali lagi untuk keluar", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    private fun applyLayoutWeights(webWeight: Float, aiWeight: Float) {
        val webParams = webViewContainer.layoutParams as LinearLayout.LayoutParams
        webParams.weight = webWeight
        webViewContainer.layoutParams = webParams

        val aiParams = aiPanelContainer.layoutParams as LinearLayout.LayoutParams
        aiParams.weight = aiWeight
        aiPanelContainer.layoutParams = aiParams
    }

    // ============================================================
    // PORTFOLIO MANAGEMENT (dipanggil dari DetailBottomSheet)
    // ============================================================

    fun addPortfolioTrade(ticker: String, entryPrice: Int, lot: Int, targetPrice: Int, stopLoss: Int) {
        orderBookRepo.addPortfolioTrade(ticker, entryPrice, lot, targetPrice, stopLoss)
        Toast.makeText(this, "✅ Posisi $ticker (${lot}L @ Rp ${String.format("%,d", entryPrice).replace(',', '.')}) dicatat!", Toast.LENGTH_SHORT).show()
    }

    fun navigateToPortfolioTab() {
        // Tab Portfolio ada di index 3
        viewPager.setCurrentItem(3, true)
    }

    // Backward compat
    fun setActiveTrade(ticker: String, entryPrice: Double) {
        orderBookRepo.currentActiveTrade = com.scalping.assistant.data.repository.ActiveTrade(ticker, entryPrice)
        Toast.makeText(this, "Trade $ticker dicatat pada Rp ${entryPrice.toInt()}", Toast.LENGTH_SHORT).show()
    }

    fun clearActiveTrade() {
        orderBookRepo.closeAllPortfolioTrades()
        Toast.makeText(this, "Semua trade aktif ditutup.", Toast.LENGTH_SHORT).show()
    }

    fun closeTradeByTicker(ticker: String) {
        val trade = orderBookRepo.getPortfolioList().firstOrNull { it.ticker.equals(ticker, ignoreCase = true) && it.isActive }
        if (trade != null) {
            val isProfit = trade.currentPrice >= trade.entryPrice
            showExitTradeDialog(trade, isTakeProfit = isProfit)
        }
    }

    fun showExitTradeDialog(trade: com.scalping.assistant.data.repository.PortfolioTrade, isTakeProfit: Boolean) {
        val sheet = com.scalping.assistant.ui.ExitTradeBottomSheet(trade, isTakeProfit) { exitPrice, status ->
            orderBookRepo.closePortfolioTrade(trade.id, closePrice = exitPrice, status = status)
            val diff = exitPrice - trade.entryPrice
            val pnlPct = if (trade.entryPrice > 0) (diff.toDouble() / trade.entryPrice) * 100 else 0.0
            val pnlSign = if (pnlPct >= 0) "+" else ""
            val statusLabel = if (status == "TP") "Take Profit" else "Cut Loss"
            val icon = if (status == "TP") "💰" else "🛑"
            Toast.makeText(
                this,
                "$icon Sukses $statusLabel ${trade.ticker} di Rp $exitPrice ($pnlSign${String.format("%.2f", pnlPct)}%). Tersimpan di Riwayat!",
                Toast.LENGTH_LONG
            ).show()
        }
        sheet.show(supportFragmentManager, "ExitTradeBottomSheet")
    }

    fun showDeleteTradeDialog(trade: com.scalping.assistant.data.repository.PortfolioTrade) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Hapus Riwayat ${trade.ticker}")
            .setMessage("Hapus catatan transaksi ${trade.ticker} (Rp ${trade.closePrice}) dari Track Record?")
            .setPositiveButton("Hapus") { _, _ ->
                orderBookRepo.deletePortfolioTrade(trade.id)
                Toast.makeText(this, "Catatan transaksi ${trade.ticker} telah dihapus.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    fun getActiveTrade(ticker: String): com.scalping.assistant.data.repository.PortfolioTrade? {
        if (!::orderBookRepo.isInitialized) return null
        return orderBookRepo.getPortfolioList().firstOrNull { it.ticker.equals(ticker, ignoreCase = true) && it.isActive }
    }

    // ============================================================
    // BANDAR DETECTOR AUTO-REQUEST
    // ============================================================

    fun requestBandarDetector(ticker: String, force: Boolean = false) {
        val clean = ticker.trim().uppercase()
        if (clean.isEmpty()) return
        val now = System.currentTimeMillis()
        val last = lastRequestedBandar[clean] ?: 0L
        if (!force && now - last < 10_000L) return
        lastRequestedBandar[clean] = now

        fetchBandarDetectorNative(clean, isMultiDay = false)
    }

    fun requestMultiDayBandarDetector(ticker: String, force: Boolean = false) {
        val clean = ticker.trim().uppercase()
        if (clean.isEmpty()) return
        val now = System.currentTimeMillis()
        val last = lastRequestedBandarMultiDay[clean] ?: 0L
        if (!force && now - last < 10_000L) return
        lastRequestedBandarMultiDay[clean] = now

        fetchBandarDetectorNative(clean, isMultiDay = true)
    }

    private fun fetchBandarDetectorNative(ticker: String, isMultiDay: Boolean) {
        val currentToken = getActiveStockbitToken()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val urlString = if (isMultiDay) {
                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                    val cal = java.util.Calendar.getInstance()
                    val toDate = sdf.format(cal.time)
                    cal.add(java.util.Calendar.DAY_OF_YEAR, -7)
                    val fromDate = sdf.format(cal.time)
                    "https://exodus.stockbit.com/marketdetectors/$ticker?transaction_type=TRANSACTION_TYPE_NET&market_board=MARKET_BOARD_REGULER&investor_type=INVESTOR_TYPE_ALL&limit=25&from=$fromDate&to=$toDate"
                } else {
                    "https://exodus.stockbit.com/marketdetectors/$ticker?transaction_type=TRANSACTION_TYPE_NET&market_board=MARKET_BOARD_REGULER&investor_type=INVESTOR_TYPE_ALL&limit=25&period=BROKER_SUMMARY_PERIOD_LATEST"
                }

                val url = URL(urlString)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 6000
                    readTimeout = 6000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
                    setRequestProperty("Accept", "application/json, text/plain, */*")
                    setRequestProperty("Origin", "https://stockbit.com")
                    setRequestProperty("Referer", "https://stockbit.com/")

                    val cookieManager = android.webkit.CookieManager.getInstance()
                    val sbCookies = cookieManager.getCookie("https://stockbit.com") ?: ""
                    val exCookies = cookieManager.getCookie("https://exodus.stockbit.com") ?: ""
                    val combined = buildString {
                        if (sbCookies.isNotEmpty()) append(sbCookies)
                        if (exCookies.isNotEmpty()) {
                            if (isNotEmpty()) append("; ")
                            append(exCookies)
                        }
                    }
                    if (combined.isNotEmpty()) {
                        setRequestProperty("Cookie", combined)
                    }
                    if (currentToken.isNotEmpty()) {
                        setRequestProperty("Authorization", "Bearer $currentToken")
                    }
                }

                val code = conn.responseCode
                Log.d("BANDAR_NATIVE", "[$code] $ticker (multiDay=$isMultiDay) -> Token len: ${currentToken.length}")
                if (code == 200) {
                    val json = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                    Log.d("BANDAR_NATIVE", "✅ Sukses ambil Bandar Detector $ticker (multiDay=$isMultiDay) - ${json.length} bytes")
                    withContext(Dispatchers.Main) {
                        orderBookRepo.processBandarDetectorJson(urlString, json)
                    }
                } else {
                    val err = conn.errorStream?.let { BufferedReader(InputStreamReader(it)).use { r -> r.readText() } } ?: ""
                    Log.w("BANDAR_NATIVE", "⚠️ HTTP $code untuk $ticker (multiDay=$isMultiDay): $err")
                    if (code == 401) {
                        triggerTokenExtraction()
                    }
                }
            } catch (e: Exception) {
                Log.e("BANDAR_NATIVE", "Error fetch native $ticker (multiDay=$isMultiDay): ${e.message}")
            }
        }
    }

    private fun getActiveStockbitToken(): String {
        // 1. Cek langsung dari cookies CookieManager
        val fromCookie = extractTokenFromCookies()
        if (fromCookie.isNotEmpty()) {
            if (fromCookie != stockbitAuthToken) {
                stockbitAuthToken = fromCookie
                getSharedPreferences("ScalpingPrefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString(PREF_STOCKBIT_TOKEN, fromCookie)
                    .apply()
            }
            return fromCookie
        }

        // 2. Gunakan token di memori atau prefs jika valid
        if (stockbitAuthToken.startsWith("eyJ") && stockbitAuthToken.length > 80) {
            return stockbitAuthToken
        }

        return ""
    }

    private fun extractTokenFromCookies(): String {
        try {
            val cookieManager = android.webkit.CookieManager.getInstance()
            val sbCookies = cookieManager.getCookie("https://stockbit.com") ?: ""
            if (sbCookies.isNotEmpty()) {
                val decoded = java.net.URLDecoder.decode(sbCookies, "UTF-8")
                val match = Regex(""""token":"([^"]+)"""").find(decoded)
                if (match != null) {
                    val cand = match.groupValues[1]
                    if (cand.startsWith("eyJ") && cand.length > 80) {
                        return cand
                    }
                }
                val jwtMatch = Regex("""eyJ[a-zA-Z0-9_-]{15,}\.[a-zA-Z0-9_-]{15,}\.[a-zA-Z0-9_-]{15,}""").find(decoded)
                if (jwtMatch != null && jwtMatch.value.length > 80) {
                    return jwtMatch.value
                }
            }
        } catch (e: Exception) {
            Log.e("BANDAR_NATIVE", "Error extracting token from cookie: ${e.message}")
        }
        return ""
    }

    fun triggerTokenExtraction() {
        runOnUiThread {
            if (!::webView.isInitialized) return@runOnUiThread
            getActiveStockbitToken()
        }
    }

    private fun queueBandarDetectorRequests(tickers: List<String>) {
        lifecycleScope.launch {
            for (ticker in tickers) {
                requestBandarDetector(ticker)
                kotlinx.coroutines.delay(180L)
            }
        }
    }

    // ============================================================
    // WEBVIEW SETUP
    // ============================================================

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        val cookieManager = android.webkit.CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        val customUserAgent = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
        val desktopUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = customUserAgent
        }

        webViewMovers.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = desktopUserAgent
        }
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webViewMovers, true)

        webViewStream.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = desktopUserAgent
        }
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webViewStream, true)

        val bridge = StockbitBridge(
            onDataReceived = { json ->
                lifecycleScope.launch {
                    orderBookRepo.processJsonData(json)
                }
                try {
                    val arr = org.json.JSONArray(json)
                    for (i in 0 until arr.length()) {
                        val t = arr.getJSONObject(i).getString("ticker").trim().uppercase()
                        requestBandarDetector(t)
                    }
                } catch (e: Exception) {}
            },
            onLoginNeeded = {
                runOnUiThread {
                    tvStatusLog.text = "⚠️ Silakan login akun Stockbit Anda di panel web atas."
                }
            },
            onStatus = { status ->
                runOnUiThread { tvStatusLog.text = status }
            },
            onError = { err ->
                runOnUiThread { tvStatusLog.text = "Status: $err" }
            }
        )

        webView.addJavascriptInterface(bridge, "Android")

        val moversBridge = object {
            @android.webkit.JavascriptInterface
            fun onMoversData(jsonArray: String) {
                try {
                    Log.d("MOVERS_DATA_ARR", "onMoversData: " + jsonArray.take(300))
                    val arr = org.json.JSONArray(jsonArray)
                    val tickers = mutableListOf<String>()
                    val maxTickers = Math.min(arr.length(), 50) // Ambil hingga 50 emiten teraktif
                    for (i in 0 until maxTickers) {
                        val obj = arr.getJSONObject(i)
                        val t = obj.getString("ticker").trim().uppercase()
                        if (t.isNotEmpty() && !tickers.contains(t)) {
                            tickers.add(t)
                        }
                    }
                    if (tickers.isNotEmpty()) {
                        moversTickers = tickers
                        lifecycleScope.launch {
                            orderBookRepo.processMoversTickerData(jsonArray)
                        }
                        queueBandarDetectorRequests(tickers)
                    }
                } catch (e: Exception) { }
            }

            @android.webkit.JavascriptInterface
            fun onMoversDebug(msg: String) {
                runOnUiThread { tvStatusLog.text = msg }
                Log.d("MOVERS_DEBUG", msg)
            }

            @android.webkit.JavascriptInterface
            fun onOrderBookData(jsonString: String) {
                lifecycleScope.launch {
                    orderBookRepo.processMoversJsonData(jsonString)
                }
            }

            @android.webkit.JavascriptInterface
            fun onLoginRequired() { }

            @android.webkit.JavascriptInterface
            fun onScrapingStatus(status: String) { }

            @android.webkit.JavascriptInterface
            fun onScrapingError(error: String) {
                runOnUiThread { tvStatusLog.text = "Movers Err: $error" }
            }
        }
        webViewMovers.addJavascriptInterface(moversBridge, "Android")

        val streamBridge = object {
            @android.webkit.JavascriptInterface
            fun onStreamData(jsonArray: String) {
                lifecycleScope.launch {
                    orderBookRepo.processStreamData(jsonArray)
                }
            }
        }
        webViewStream.addJavascriptInterface(streamBridge, "AndroidStream")

        val probeBridge = object {
            @android.webkit.JavascriptInterface
            fun onProbeCaptured(type: String, url: String, payload: String) {
                Log.d("PROBE_STREAM", "[$type] $url -> $payload")
                val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                val logEntry = "[$timestamp] [$type] $url\nPayload: $payload"
                synchronized(probeLogs) {
                    if (probeLogs.size >= 30) probeLogs.removeAt(0)
                    probeLogs.add(logEntry)
                }

                if (url.contains("marketdetectors/") && (type == "XHR_DATA" || type == "FETCH_DATA")) {
                    orderBookRepo.processBandarDetectorJson(url, payload)
                }

                if (type.startsWith("WS_") || type.startsWith("SSE_") || type.startsWith("FETCH_") || type.startsWith("XHR_")) {
                    runOnUiThread {
                        val shortUrl = if (url.length > 25) url.takeLast(25) else url
                        val preview = if (payload.length > 40) payload.take(40) + "..." else payload
                        tvStatusLog.text = "🎯 [$type] $shortUrl: $preview (Tap log)"
                    }
                }
            }

            @android.webkit.JavascriptInterface
            fun onTokenCaptured(token: String) {
                val clean = if (token.startsWith("Bearer ", ignoreCase = true)) token.substring(7).trim() else token.trim()
                if (clean.startsWith("eyJ") && clean.length > 80 && clean != stockbitAuthToken) {
                    stockbitAuthToken = clean
                    getSharedPreferences("ScalpingPrefs", Context.MODE_PRIVATE)
                        .edit()
                        .putString(PREF_STOCKBIT_TOKEN, clean)
                        .apply()
                    Log.d("BANDAR_NATIVE", "🔑 onTokenCaptured: tersimpan JWT (${clean.take(8)}... len: ${clean.length})")
                }
            }
        }
        webView.addJavascriptInterface(probeBridge, "AndroidProbe")
        webViewMovers.addJavascriptInterface(probeBridge, "AndroidProbe")
        webViewStream.addJavascriptInterface(probeBridge, "AndroidProbe")

        webViewStream.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (streamProbeScript.isNotEmpty() && view != null) {
                    view.evaluateJavascript(streamProbeScript, null)
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (streamProbeScript.isNotEmpty()) {
                    view.evaluateJavascript(streamProbeScript, null)
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (streamProbeScript.isNotEmpty() && view != null) {
                    view.evaluateJavascript(streamProbeScript, null)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: ""
                return !(url.startsWith("http://") || url.startsWith("https://"))
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                android.webkit.CookieManager.getInstance().flush()
                if (streamProbeScript.isNotEmpty()) {
                    view.evaluateJavascript(streamProbeScript, null)
                }
                if (injectorScript.isNotEmpty()) {
                    view.evaluateJavascript(injectorScript, null)
                }
                if (url.contains("stockbit.com") && !url.contains("/login")) {
                    triggerTokenExtraction()
                    if (::webViewMovers.isInitialized && webViewMovers.url?.contains("/login") == true) {
                        webViewMovers.loadUrl("https://stockbit.com/orderbook")
                    }
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val dialog = android.app.Dialog(this@MainActivity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                val popupWebView = WebView(this@MainActivity).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        setSupportMultipleWindows(true)
                        javaScriptCanOpenWindowsAutomatically = true
                        userAgentString = customUserAgent
                    }
                    android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(v: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                            val targetUrl = request?.url?.toString() ?: ""
                            if (targetUrl.contains("stockbit.com") && !targetUrl.contains("accounts.google.com")) {
                                webView.loadUrl(targetUrl)
                                dialog.dismiss()
                                return true
                            }
                            return false
                        }

                        override fun onPageFinished(v: WebView?, url: String?) {
                            super.onPageFinished(v, url)
                            android.webkit.CookieManager.getInstance().flush()
                            if (url != null && url.contains("stockbit.com") && !url.contains("accounts.google.com") && !url.contains("/login")) {
                                dialog.dismiss()
                                webView.reload()
                            }
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onCloseWindow(window: WebView?) {
                            dialog.dismiss()
                            webView.reload()
                        }
                    }
                }

                dialog.setContentView(popupWebView)
                dialog.show()

                val transport = resultMsg?.obj as? WebView.WebViewTransport
                transport?.webView = popupWebView
                resultMsg?.sendToTarget()
                return true
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack()
                else finish()
            }
        })
    }

    // ============================================================
    // VIEW PAGER SETUP (Sekarang 4 Tab)
    // ============================================================

    private fun setupViewPager() {
        pagerAdapter = com.scalping.assistant.ui.RankingPagerAdapter(this)
        viewPager.adapter = pagerAdapter

        // Setup callback TP/CL dari portfolio dengan pemilih harga riil
        pagerAdapter.portfolioFragment.onTakeProfit = { trade ->
            showExitTradeDialog(trade, isTakeProfit = true)
        }
        pagerAdapter.portfolioFragment.onCutLoss = { trade ->
            showExitTradeDialog(trade, isTakeProfit = false)
        }
        pagerAdapter.portfolioFragment.onDeleteTrade = { trade ->
            showDeleteTradeDialog(trade)
        }
        pagerAdapter.portfolioFragment.onAiConsult = { trade ->
            val analysis = orderBookRepo.getAnalysisForTicker(trade.ticker)
            val bandar = orderBookRepo.getBandarDetector(trade.ticker)
            val sheet = com.scalping.assistant.ui.PortfolioRescueBottomSheet(trade, analysis, bandar)
            sheet.show(supportFragmentManager, "PortfolioRescueBottomSheet")
        }

        com.google.android.material.tabs.TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Manual"
                1 -> "Movers"
                2 -> "Top Picks"
                3 -> "💼 Portfolio"
                else -> ""
            }
        }.attach()
    }

    // ============================================================
    // DATA OBSERVATION
    // ============================================================

    private fun observeData() {
        lifecycleScope.launch {
            orderBookRepo.manualFlow.collectLatest { list ->
                runOnUiThread {
                    pagerAdapter.updateManualData(list)
                    updateTotalCount()
                }
            }
        }

        lifecycleScope.launch {
            orderBookRepo.moversFlow.collectLatest { list ->
                runOnUiThread {
                    pagerAdapter.updateMoversData(list)
                    updateTotalCount()
                }
            }
        }

        lifecycleScope.launch {
            orderBookRepo.topPicksFlow.collectLatest { list ->
                runOnUiThread {
                    pagerAdapter.updateTopPicksData(list)
                }
            }
        }

        lifecycleScope.launch {
            orderBookRepo.portfolioFlow.collectLatest { trades ->
                runOnUiThread {
                    pagerAdapter.updatePortfolioData(trades)
                    // Update badge jumlah posisi aktif di tab Portfolio
                    val activeCount = trades.count { it.isActive }
                    tabLayout.getTabAt(3)?.text = if (activeCount > 0) "💼 Portfolio ($activeCount)" else "💼 Portfolio"

                    // TP Alert otomatis (Max 1x per 5 menit)
                    val now = System.currentTimeMillis()
                    val cooldownMs = 5 * 60 * 1000L // 5 menit

                    for (trade in trades) {
                        if (trade.isActive && trade.currentPrice >= trade.targetPrice) {
                            val lastTP = notifiedTPTrades[trade.id] ?: 0L
                            if (now - lastTP > cooldownMs) {
                                notifiedTPTrades[trade.id] = now
                                val pnl = String.format("%.2f", trade.pnlPercent)
                                Toast.makeText(this@MainActivity,
                                    "🎯 TARGET PROFIT ${trade.ticker} TERCAPAI! +${pnl}% — Pertimbangkan jual!",
                                    Toast.LENGTH_LONG).show()
                            }
                        } else if (trade.isActive && trade.currentPrice < trade.targetPrice) {
                            // Reset jika harga turun lagi, tapi kita pakai cooldown
                        }

                        // SL Alert otomatis: Cukup update pesan status log halus, jangan tampilkan Toast/Dialog yang mengganggu
                        if (trade.isActive && trade.currentPrice <= trade.stopLoss) {
                            val lastSL = notifiedSLTrades[trade.id] ?: 0L
                            val slCooldownMs = 30 * 60 * 1000L // 30 menit
                            if (now - lastSL > slCooldownMs) {
                                notifiedSLTrades[trade.id] = now
                                tvStatusLog.text = "🛑 Stop Loss ${trade.ticker} tertembus pada Rp ${trade.currentPrice}"
                            }
                        }
                    }
                    savePortfolioToPrefs(trades)
                }
            }
        }

        lifecycleScope.launch {
            orderBookRepo.statusFlow.collectLatest { status ->
                runOnUiThread { tvStatusLog.text = status }
            }
        }

        lifecycleScope.launch {
            orderBookRepo.bailoutFlow.collectLatest { (ticker, reason) ->
                runOnUiThread {
                    showBailoutAlert(ticker, reason)
                }
            }
        }

        lifecycleScope.launch {
            orderBookRepo.activeTradeFlow.collectLatest { tradeInfo ->
                runOnUiThread {
                    val banner = findViewById<View>(R.id.llActiveTradeBanner)
                    val tvStatus = findViewById<TextView>(R.id.tvActiveTradeStatus)
                    if (tradeInfo == null) {
                        banner.visibility = View.GONE
                    } else {
                        val (trade, analysis) = tradeInfo
                        val pnl = ((analysis.lastPrice - trade.entryPrice) / trade.entryPrice) * 100
                        val pnlStr = String.format("%+.2f%%", pnl).replace(',', '.')
                        val recommendation = analysis.recommendation.label
                        tvStatus.text = "${trade.ticker}: $pnlStr ($recommendation)"
                        banner.visibility = View.VISIBLE
                        banner.setBackgroundColor(if (pnl > 0) Color.parseColor("#10B981") else Color.parseColor("#EF4444"))
                    }
                }
            }
        }
    }

    // ============================================================
    // ALERTS & NOTIFICATIONS (NON-INTRUSIVE)
    // ============================================================

    private fun showBailoutAlert(ticker: String, reason: String) {
        // HAPUS TOTAL POPUP DIALOG: Jangan pernah memunculkan dialog modal yang memblokir layar saat trading!
        // Cukup tampilkan peringatan halus pada status bar di bawah
        tvStatusLog.text = "⚠️ Tekanan Jual $ticker: $reason"
    }

    private fun showProbeLogDialog() {
        val items = synchronized(probeLogs) { probeLogs.reversed().toTypedArray() }
        val (tickers, snaps) = if (::orderBookRepo.isInitialized) orderBookRepo.getSnapshotCacheInfo() else Pair(0, 0)
        val title = "📡 Live Probe (${items.size}) • 💾 Cache ($tickers emiten / $snaps data)"

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(if (items.isEmpty()) arrayOf("Belum ada data WebSocket/SSE/Fetch tertangkap.\nSilakan pastikan Stockbit sudah login.") else items, null)
            .setPositiveButton("Tutup", null)
            .setNeutralButton("Salin Semua") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Probe Logs", items.joinToString("\n---\n"))
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Log disalin ke clipboard!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("🗑️ Reset Snapshot") { _, _ ->
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Hapus Cache Snapshot?")
                    .setMessage("Seluruh riwayat snapshot ($tickers emiten, $snaps data) akan dihapus dan sistem akan mengumpulkan data baru dari awal.")
                    .setPositiveButton("Hapus") { _, _ ->
                        orderBookRepo.clearAllSnapshots()
                        Toast.makeText(this, "Cache snapshot telah di-reset!", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            }
            .show()
    }

    private fun updateTotalCount() {
        val manualCount = orderBookRepo.manualFlow.value.size
        val moversCount = orderBookRepo.moversFlow.value.size
        tvStockCount.text = "${manualCount + moversCount} Saham"
    }

    // ============================================================
    // SESSION UI
    // ============================================================

    private fun updateSessionUI() {
        val info = MarketSession.getCurrentSession()
        tvSessionBadge.text = info.phase.label
        tvSessionBadge.setTextColor(Color.parseColor(info.phase.colorHex))
        tvSessionTime.text = info.timeDisplay

        if (info.phase.isTradeable) {
            tvLiveStatus.text = "● LIVE"
            tvLiveStatus.setTextColor(Color.parseColor("#10B981"))
        } else {
            tvLiveStatus.text = "● TUTUP"
            tvLiveStatus.setTextColor(Color.parseColor("#64748B"))
        }
    }

    override fun onStop() {
        super.onStop()
        if (::orderBookRepo.isInitialized) {
            orderBookRepo.saveSnapshots()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::orderBookRepo.isInitialized) {
            orderBookRepo.saveSnapshots()
        }
        handler.removeCallbacks(scrapingRunnable)
        handler.removeCallbacks(sessionTimerRunnable)
        webView.destroy()
        webViewMovers.destroy()
        webViewStream.destroy()
    }
}
