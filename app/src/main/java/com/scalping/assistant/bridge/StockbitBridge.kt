package com.scalping.assistant.bridge

import android.webkit.JavascriptInterface

class StockbitBridge(
    private val onDataReceived: (String) -> Unit,
    private val onLoginNeeded: () -> Unit,
    private val onStatus: (String) -> Unit,
    private val onError: (String) -> Unit
) {

    @JavascriptInterface
    fun onOrderBookData(jsonString: String) {
        onDataReceived(jsonString)
    }

    @JavascriptInterface
    fun onLoginRequired() {
        onLoginNeeded()
    }

    @JavascriptInterface
    fun onScrapingStatus(status: String) {
        onStatus(status)
    }

    @JavascriptInterface
    fun onScrapingError(error: String) {
        onError(error)
    }
}
