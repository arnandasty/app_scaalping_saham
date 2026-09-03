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
    private lateinit var rvStockRanking: RecyclerView
    private lateinit var layoutEmptyState: LinearLayout
    private lateinit var tvSessionBadge: TextView
    private lateinit var tvSessionTime: TextView
    private lateinit var tvLiveStatus: TextView
    private lateinit var tvStockCount: TextView
    private lateinit var tvStatusLog: TextView

    private lateinit var rankingAdapter: RankingAdapter
    private lateinit var yahooRepo: YahooFinanceRepository
    private lateinit var orderBookRepo: OrderBookRepository

    private val handler = Handler(Looper.getMainLooper())
    private var injectorScript = ""
    private var moversInjectorScript = ""
    private var topTickers = listOf<String>()
    private val notifiedBuyTickers = mutableSetOf<String>()

    private val scrapingRunnable = object : Runnable {
        override fun run() {
            if (::webView.isInitialized && injectorScript.isNotEmpty()) {
                val jsCommand = if (topTickers.isNotEmpty()) {
                    val tickersJson = org.json.JSONArray(topTickers).toString()
                    "if(typeof window.autoFillTickers === 'function') { window.autoFillTickers($tickersJson); } $injectorScript"
                } else {
                    injectorScript
                }
                webView.evaluateJavascript(jsCommand, null)
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
        setupRecyclerView()
        observeData()

        // Load Stockbit
        webView.loadUrl("https://stockbit.com/orderbook")
        webViewMovers.loadUrl("https://stockbit.com/market/movers")

        // Mulai polling timer
        handler.post(sessionTimerRunnable)
        handler.postDelayed(scrapingRunnable, 5000L) // Mulai scrape setelah 5 detik pertama
    }

    private fun initViews() {
        webView = findViewById(R.id.webViewStockbit)
        webViewMovers = findViewById(R.id.webViewMovers)
        progressBar = findViewById(R.id.webViewProgressBar)
        rvStockRanking = findViewById(R.id.rvStockRanking)
        layoutEmptyState = findViewById(R.id.layoutEmptyState)
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
            moversInjectorScript = assets.open("movers_injector.js").bufferedReader().use { it.readText() }
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
            userAgentString = customUserAgent
        }

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
            @android.webkit.JavascriptInterface
            fun onTopTickers(jsonArray: String) {
                try {
                    val array = org.json.JSONArray(jsonArray)
                    val newTickers = mutableListOf<String>()
                    for (i in 0 until array.length()) {
                        newTickers.add(array.getString(i))
                    }
                    if (newTickers.isNotEmpty()) {
                        topTickers = newTickers
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
        webViewMovers.addJavascriptInterface(moversBridge, "MoversAndroid")

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

    private fun setupRecyclerView() {
        rankingAdapter = RankingAdapter { item ->
            val sheet = DetailBottomSheet(item)
            sheet.show(supportFragmentManager, "DetailBottomSheet")
        }
        rvStockRanking.layoutManager = LinearLayoutManager(this)
        rvStockRanking.adapter = rankingAdapter
    }

    private fun observeData() {
        lifecycleScope.launch {
            orderBookRepo.rankingFlow.collectLatest { list ->
                runOnUiThread {
                    if (list.isNotEmpty()) {
                        layoutEmptyState.visibility = View.GONE
                        rvStockRanking.visibility = View.VISIBLE
                        tvStockCount.text = "${list.size} Saham"
                        rankingAdapter.submitList(list)

                        // Trigger getar jika ada sinyal STRONG BUY baru
                        checkAndNotifyBuySignals(list)
                    } else {
                        layoutEmptyState.visibility = View.VISIBLE
                        rvStockRanking.visibility = View.GONE
                        tvStockCount.text = "0 Saham"
                    }
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
