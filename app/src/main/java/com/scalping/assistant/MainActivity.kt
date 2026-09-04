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

    // Drag handle views
    private lateinit var webViewContainer: android.widget.FrameLayout
    private lateinit var dividerDragHandle: LinearLayout
    private lateinit var aiPanelContainer: LinearLayout

    private lateinit var pagerAdapter: com.scalping.assistant.ui.RankingPagerAdapter
    private lateinit var yahooRepo: YahooFinanceRepository
    private lateinit var orderBookRepo: OrderBookRepository

    private val handler = Handler(Looper.getMainLooper())
    private var injectorScript = ""
    private var moversInjectorScript = ""
    private var streamInjectorScript = ""
    private var moversTickers = listOf<String>()
    private val notifiedBuyTickers = mutableSetOf<String>()

    // ============================================================
    // Drag Handle State
    // ============================================================
    private var dragStartY = 0f
    private var dragStartWebviewWeight = 55f
    private var dragStartAiWeight = 45f
    private val TOTAL_WEIGHT = 100f
    // 3 preset layout mode
    private var layoutMode = 0 // 0=55/45, 1=35/65 (AI besar), 2=70/30 (WebView besar)

    // ============================================================
    // Scraping Runnables
    // ============================================================

    private val scrapingRunnable = object : Runnable {
        override fun run() {
            if (::webView.isInitialized && injectorScript.isNotEmpty()) {
                webView.evaluateJavascript(injectorScript, null)
            }

            if (::webViewMovers.isInitialized) {
                if (moversInjectorScript.isNotEmpty()) {
                    webViewMovers.evaluateJavascript(moversInjectorScript, null)
                }
                if (moversTickers.isNotEmpty() && injectorScript.isNotEmpty()) {
                    val tickersJson = org.json.JSONArray(moversTickers).toString()
                    val autoFillAndScrape = "if(typeof window.autoFillTickers === 'function') { window.autoFillTickers($tickersJson); } $injectorScript"
                    handler.postDelayed({
                        webViewMovers.evaluateJavascript(autoFillAndScrape, null)
                    }, 500L)
                }
            }
            
            if (::webViewStream.isInitialized && streamInjectorScript.isNotEmpty()) {
                webViewStream.evaluateJavascript(streamInjectorScript, null)
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
        webViewContainer = findViewById(R.id.webViewContainer)
        dividerDragHandle = findViewById(R.id.dividerDragHandle)
        aiPanelContainer = findViewById(R.id.aiPanelContainer)
    }

    private fun initServices() {
        yahooRepo = YahooFinanceRepository()
        orderBookRepo = OrderBookRepository(yahooRepo)

        loadPortfolioFromPrefs()

        try {
            injectorScript = assets.open("stockbit_injector.js").bufferedReader().use { it.readText() }
            moversInjectorScript = assets.open("movers_injector.js").bufferedReader().use { it.readText() }
            streamInjectorScript = assets.open("stream_injector.js").bufferedReader().use { it.readText() }
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
                    list.add(
                        com.scalping.assistant.data.repository.PortfolioTrade(
                            id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                            ticker = obj.getString("ticker"),
                            entryPrice = obj.getInt("entryPrice"),
                            lot = obj.getInt("lot"),
                            buyTime = obj.optLong("buyTime", System.currentTimeMillis()),
                            currentPrice = obj.optInt("currentPrice", obj.getInt("entryPrice")),
                            targetPrice = obj.optInt("targetPrice", (obj.getInt("entryPrice") * 1.025).toInt()),
                            stopLoss = obj.optInt("stopLoss", (obj.getInt("entryPrice") * 0.985).toInt())
                        )
                    )
                }
                orderBookRepo.setPortfolioData(list)
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
            obj.put("targetPrice", trade.targetPrice)
            obj.put("stopLoss", trade.stopLoss)
            array.put(obj)
        }
        val prefs = getSharedPreferences("ScalpingPrefs", Context.MODE_PRIVATE)
        prefs.edit().putString("portfolio_data", array.toString()).apply()
    }

    // ============================================================
    // DRAG HANDLE SETUP
    // ============================================================

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragHandle() {
        dividerDragHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartY = event.rawY
                    dragStartWebviewWeight = (webViewContainer.layoutParams as LinearLayout.LayoutParams).weight
                    dragStartAiWeight = (aiPanelContainer.layoutParams as LinearLayout.LayoutParams).weight
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaY = event.rawY - dragStartY
                    val screenHeight = resources.displayMetrics.heightPixels.toFloat()
                    val deltaWeight = (deltaY / screenHeight) * TOTAL_WEIGHT

                    val newWebWeight = (dragStartWebviewWeight + deltaWeight).coerceIn(20f, 75f)
                    val newAiWeight = TOTAL_WEIGHT - newWebWeight

                    applyLayoutWeights(newWebWeight, newAiWeight)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // Snap ke preset terdekat saat dilepas
                    val currentWebWeight = (webViewContainer.layoutParams as LinearLayout.LayoutParams).weight
                    snapToNearestPreset(currentWebWeight)
                    true
                }
                else -> false
            }
        }

        // Tap pada drag handle → cycle through 3 preset mode
        dividerDragHandle.setOnClickListener {
            layoutMode = (layoutMode + 1) % 3
            when (layoutMode) {
                0 -> { applyLayoutWeights(55f, 45f); Toast.makeText(this, "Mode: Stockbit Lebih Besar", Toast.LENGTH_SHORT).show() }
                1 -> { applyLayoutWeights(35f, 65f); Toast.makeText(this, "Mode: AI Panel Lebih Besar", Toast.LENGTH_SHORT).show() }
                2 -> { applyLayoutWeights(70f, 30f); Toast.makeText(this, "Mode: Stockbit Maksimal", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun applyLayoutWeights(webWeight: Float, aiWeight: Float) {
        val webParams = webViewContainer.layoutParams as LinearLayout.LayoutParams
        webParams.weight = webWeight
        webViewContainer.layoutParams = webParams

        val aiParams = aiPanelContainer.layoutParams as LinearLayout.LayoutParams
        aiParams.weight = aiWeight
        aiPanelContainer.layoutParams = aiParams
    }

    private fun snapToNearestPreset(currentWeight: Float) {
        val presets = listOf(35f, 55f, 70f)
        val nearest = presets.minByOrNull { Math.abs(it - currentWeight) } ?: 55f
        layoutMode = presets.indexOf(nearest)
        applyLayoutWeights(nearest, TOTAL_WEIGHT - nearest)
    }

    // ============================================================
    // PORTFOLIO MANAGEMENT (dipanggil dari DetailBottomSheet)
    // ============================================================

    fun addPortfolioTrade(ticker: String, entryPrice: Int, lot: Int) {
        orderBookRepo.addPortfolioTrade(ticker, entryPrice, lot)
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
        orderBookRepo.currentActiveTrade = null
        Toast.makeText(this, "Trade selesai.", Toast.LENGTH_SHORT).show()
    }

    // ============================================================
    // WEBVIEW SETUP
    // ============================================================

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
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
                    val arr = org.json.JSONArray(jsonArray)
                    val tickers = mutableListOf<String>()
                    for (i in 0 until Math.min(arr.length(), 6)) { // Ambil 6 teratas agar rotasi cukup cepat (18 detik/cycle)
                        val obj = arr.getJSONObject(i)
                        tickers.add(obj.getString("ticker"))
                    }
                    if (tickers.isNotEmpty()) {
                        moversTickers = tickers
                        lifecycleScope.launch {
                            orderBookRepo.processMoversTickerData(jsonArray)
                        }
                    }
                } catch (e: Exception) { }
            }

            @android.webkit.JavascriptInterface
            fun onMoversDebug(msg: String) {
                runOnUiThread { tvStatusLog.text = "Movers: $msg" }
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

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: ""
                return !(url.startsWith("http://") || url.startsWith("https://"))
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                android.webkit.CookieManager.getInstance().flush()
                if (injectorScript.isNotEmpty()) {
                    view.evaluateJavascript(injectorScript, null)
                }
                if (url.contains("stockbit.com") && !url.contains("/login")) {
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

        // Setup callback TP/CL dari portfolio
        pagerAdapter.portfolioFragment.onTakeProfit = { trade ->
            orderBookRepo.closePortfolioTrade(trade.id)
            val pnl = String.format("%+.2f", trade.pnlPercent).replace(',', '.')
            Toast.makeText(this, "💰 Take Profit ${trade.ticker}: $pnl%! Posisi ditutup.", Toast.LENGTH_LONG).show()
            vibrateDevice()
        }
        pagerAdapter.portfolioFragment.onCutLoss = { trade ->
            orderBookRepo.closePortfolioTrade(trade.id)
            val pnl = String.format("%+.2f", trade.pnlPercent).replace(',', '.')
            Toast.makeText(this, "🛑 Cut Loss ${trade.ticker}: $pnl%. Posisi ditutup.", Toast.LENGTH_LONG).show()
            vibrateDeviceHeavy()
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
                    checkAndNotifyBuySignals(list)
                }
            }
        }

        lifecycleScope.launch {
            orderBookRepo.moversFlow.collectLatest { list ->
                runOnUiThread {
                    pagerAdapter.updateMoversData(list)
                    updateTotalCount()
                    checkAndNotifyBuySignals(list)
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

                    // TP Alert otomatis
                    for (trade in trades) {
                        if (trade.isActive && trade.currentPrice >= trade.targetPrice) {
                            val pnl = String.format("%.2f", trade.pnlPercent)
                            vibrateDevice()
                            Toast.makeText(this@MainActivity,
                                "🎯 TARGET PROFIT ${trade.ticker} TERCAPAI! +${pnl}% — Pertimbangkan jual!",
                                Toast.LENGTH_LONG).show()
                        }
                        // SL Alert otomatis
                        if (trade.isActive && trade.currentPrice <= trade.stopLoss) {
                            vibrateDeviceHeavy()
                            Toast.makeText(this@MainActivity,
                                "🚨 STOP LOSS ${trade.ticker} TERTEMBUS! Pertimbangkan CUT LOSS segera!",
                                Toast.LENGTH_LONG).show()
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
    // ALERTS & NOTIFICATIONS
    // ============================================================

    private fun showBailoutAlert(ticker: String, reason: String) {
        vibrateDeviceHeavy()
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("🚨 PERINGATAN GUYURAN — $ticker")
        builder.setMessage(
            "Terdeteksi tekanan jual masif pada $ticker dan harga sedang melemah.\n\n" +
            "Alasan: $reason\n\n" +
            "Perhatian: Pastikan ini BUKAN akumulasi bandar sebelum memutuskan. " +
            "Cek apakah harga masih di atas support dan bid wall masih ada."
        )
        builder.setPositiveButton("Pantau Lebih") { dialog, _ -> dialog.dismiss() }
        builder.setNegativeButton("Lihat Portfolio") { dialog, _ ->
            dialog.dismiss()
            navigateToPortfolioTab()
        }
        builder.show()
    }

    private fun checkAndNotifyBuySignals(list: List<StockAnalysis>) {
        // Hanya notify jika sinyal sudah terkonfirmasi (snapshotCount >= 5)
        val strongBuys = list.filter {
            it.recommendation == Recommendation.STRONG_BUY && it.snapshotCount >= 5
        }
        for (sb in strongBuys) {
            if (!notifiedBuyTickers.contains(sb.ticker)) {
                notifiedBuyTickers.add(sb.ticker)
                vibrateDevice()
                Toast.makeText(
                    this,
                    "🔥 Sinyal Kuat: ${sb.ticker} — ${sb.style} (Skor ${sb.score})",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        // Reset notifikasi jika saham sudah keluar dari STRONG_BUY
        val currentStrongBuyTickers = strongBuys.map { it.ticker }.toSet()
        notifiedBuyTickers.retainAll { ticker ->
            currentStrongBuyTickers.contains(ticker) ||
            list.any { it.ticker == ticker && it.recommendation == Recommendation.BUY }
        }
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

    // ============================================================
    // VIBRATION
    // ============================================================

    private fun vibrateDevice() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(300)
                }
            }
        } catch (e: Exception) { }
    }

    private fun vibrateDeviceHeavy() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val timings = longArrayOf(0, 500, 200, 500, 200, 1000)
                    val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255)
                    vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(longArrayOf(0, 500, 200, 500, 200, 1000), -1)
                }
            }
        } catch (e: Exception) { }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(scrapingRunnable)
        handler.removeCallbacks(sessionTimerRunnable)
        webView.destroy()
        webViewMovers.destroy()
    }
}
