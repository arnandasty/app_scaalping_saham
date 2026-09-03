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

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var webViewMovers: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var tabLayout: com.google.android.material.tabs.TabLayout
    private lateinit var viewPager: androidx.viewpager2.widget.ViewPager2
    private lateinit var tvSessionBadge: TextView
    private lateinit var tvSessionTime: TextView
    private lateinit var tvLiveStatus: TextView
    private lateinit var tvStockCount: TextView
    private lateinit var tvStatusLog: TextView

    private lateinit var pagerAdapter: com.scalping.assistant.ui.RankingPagerAdapter
    private lateinit var yahooRepo: YahooFinanceRepository
    private lateinit var orderBookRepo: OrderBookRepository

    private val handler = Handler(Looper.getMainLooper())
    private var injectorScript = ""
    private var moversInjectorScript = ""
    private val notifiedBuyTickers = mutableSetOf<String>()

    private val scrapingRunnable = object : Runnable {
        override fun run() {
            if (::webView.isInitialized && injectorScript.isNotEmpty()) {
                webView.evaluateJavascript(injectorScript, null)
            }
            if (::webViewMovers.isInitialized && moversInjectorScript.isNotEmpty()) {
                webViewMovers.evaluateJavascript(moversInjectorScript, null)
            }
            handler.postDelayed(this, 3000L) // Poll setiap 3 detik
        }
    }

    private val sessionTimerRunnable = object : Runnable {
        override fun run() {
            updateSessionUI()
            handler.postDelayed(this, 15000L) // Cek sesi pasar setiap 15 detik
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
        observeData()

        // Load Stockbit
        webView.loadUrl("https://stockbit.com/orderbook")
        // WebView Movers load halaman Market khusus TOP FREQ / TOP VALUE untuk dapat saham hot hari ini
        webViewMovers.loadUrl("https://stockbit.com/market/hot")

        // Mulai polling timer
        handler.post(sessionTimerRunnable)
        handler.postDelayed(scrapingRunnable, 5000L) // Mulai scrape setelah 5 detik pertama
    }

    private fun initViews() {
        webView = findViewById(R.id.webViewStockbit)
        webViewMovers = findViewById(R.id.webViewMovers)
        progressBar = findViewById(R.id.webViewProgressBar)
        tabLayout = findViewById(R.id.tabLayout)
        viewPager = findViewById(R.id.viewPager)
        tvSessionBadge = findViewById(R.id.tvSessionBadge)
        tvSessionTime = findViewById(R.id.tvSessionTime)
        tvLiveStatus = findViewById(R.id.tvLiveStatus)
        tvStockCount = findViewById(R.id.tvStockCount)
        tvStatusLog = findViewById(R.id.tvStatusLog)
    }

    private fun initServices() {
        yahooRepo = YahooFinanceRepository()
        orderBookRepo = OrderBookRepository(yahooRepo)

        try {
            injectorScript = assets.open("stockbit_injector.js").bufferedReader().use { it.readText() }
            moversInjectorScript = injectorScript // WebView Movers juga pakai injector yang sama untuk baca orderbook!
        } catch (e: Exception) {
            tvStatusLog.text = "Gagal memuat injector: ${e.message}"
        }
    }

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
            userAgentString = desktopUserAgent // Sulap: Menyamar jadi laptop agar dapat panel Movers!
        }

        val bridge = StockbitBridge(
            onDataReceived = { json ->
                lifecycleScope.launch {
                    // Data dari WebView utama SELALU masuk ke tab Manual
                    orderBookRepo.processJsonData(json)
                }
            },
            onLoginNeeded = {
                runOnUiThread {
                    tvStatusLog.text = "⚠️ Silakan login akun Stockbit Anda di panel web atas."
                }
            },
            onStatus = { status ->
                runOnUiThread {
                    tvStatusLog.text = status
                }
            },
            onError = { err ->
                runOnUiThread {
                    tvStatusLog.text = "Status: $err"
                }
            }
        )

        webView.addJavascriptInterface(bridge, "Android")
        
        val moversBridge = object {
            // Menerima data ORDERBOOK lengkap dari webViewMovers
            @android.webkit.JavascriptInterface
            fun onOrderBookData(jsonArray: String) {
                lifecycleScope.launch {
                    // Data dari WebView movers SELALU masuk ke tab Movers
                    orderBookRepo.processMoversJsonData(jsonArray)
                }
            }

            @android.webkit.JavascriptInterface
            fun onDebug(msg: String) {
                runOnUiThread {
                    tvStatusLog.text = "Movers: $msg"
                }
            }

            @android.webkit.JavascriptInterface
            fun onScrapingStatus(status: String) {
                runOnUiThread {
                    tvStatusLog.text = "Movers: $status"
                }
            }

            @android.webkit.JavascriptInterface
            fun onScrapingError(err: String) {
                runOnUiThread {
                    tvStatusLog.text = "Movers Err: $err"
                }
            }
        }
        webViewMovers.addJavascriptInterface(moversBridge, "Android") // Pakai nama 'Android' agar pakai stockbit_injector.js yang sama!

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: ""
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false
                }
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                android.webkit.CookieManager.getInstance().flush()
                // Trigger injeksi pertama
                if (injectorScript.isNotEmpty()) {
                    view.evaluateJavascript(injectorScript, null)
                }
                
                // Pastikan Movers WebView juga pindah dari halaman login jika sudah login
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
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    private fun setupViewPager() {
        pagerAdapter = com.scalping.assistant.ui.RankingPagerAdapter(this)
        viewPager.adapter = pagerAdapter

        com.google.android.material.tabs.TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Manual"
                1 -> "Movers"
                2 -> "Top Picks"
                else -> ""
            }
        }.attach()
    }

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
            orderBookRepo.statusFlow.collectLatest { status ->
                runOnUiThread {
                    tvStatusLog.text = status
                }
            }
        }

        lifecycleScope.launch {
            orderBookRepo.bailoutFlow.collectLatest { ticker ->
                runOnUiThread {
                    showBailoutAlert(ticker)
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
                        
                        if (pnl > 0) {
                            banner.setBackgroundColor(Color.parseColor("#10B981")) // Hijau
                        } else {
                            banner.setBackgroundColor(Color.parseColor("#EF4444")) // Merah
                        }
                    }
                }
            }
        }
    }

    fun setActiveTrade(ticker: String, entryPrice: Double) {
        orderBookRepo.currentActiveTrade = com.scalping.assistant.data.repository.ActiveTrade(ticker, entryPrice)
        Toast.makeText(this, "Trade $ticker dicatat pada Rp ${entryPrice.toInt()}", Toast.LENGTH_SHORT).show()
    }

    fun clearActiveTrade() {
        orderBookRepo.currentActiveTrade = null
        Toast.makeText(this, "Trade selesai.", Toast.LENGTH_SHORT).show()
    }

    private fun showBailoutAlert(ticker: String) {
        vibrateDeviceHeavy()
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("🚨 GUYURAN BANDAR!")
        builder.setMessage("Saham $ticker sedang diguyur masif (Delta Volume negatif parah). Jika Anda punya barang, pertimbangkan untuk BAILOUT / CUTLOSS sekarang juga!")
        builder.setPositiveButton("Mengerti") { dialog, _ -> dialog.dismiss() }
        builder.show()
    }

    private fun checkAndNotifyBuySignals(list: List<StockAnalysis>) {
        val strongBuys = list.filter { it.recommendation == Recommendation.STRONG_BUY }
        for (sb in strongBuys) {
            if (!notifiedBuyTickers.contains(sb.ticker)) {
                notifiedBuyTickers.add(sb.ticker)
                vibrateDevice()
                Toast.makeText(this, "🔥 Sinyal Beli Terdeteksi: ${sb.ticker} (Skor ${sb.score})", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateTotalCount() {
        val manualCount = orderBookRepo.manualFlow.value.size
        val moversCount = orderBookRepo.moversFlow.value.size
        tvStockCount.text = "${manualCount + moversCount} Saham"
    }

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
        } catch (e: Exception) {
            // Ignore vibration error if not permitted
        }
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
        } catch (e: Exception) {
        }
    }

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

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(scrapingRunnable)
        handler.removeCallbacks(sessionTimerRunnable)
        webView.destroy()
    }
}
