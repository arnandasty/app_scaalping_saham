package com.scalping.assistant.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.scalping.assistant.MainActivity
import com.scalping.assistant.R
import com.scalping.assistant.data.models.BandarDetectorStat
import com.scalping.assistant.data.models.StockAnalysis
import com.scalping.assistant.data.repository.GroqAiRepository
import com.scalping.assistant.data.repository.PortfolioTrade
import com.scalping.assistant.data.repository.YahooFinanceRepository
import kotlinx.coroutines.launch

class PortfolioRescueBottomSheet(
    private val trade: PortfolioTrade,
    private val analysis: StockAnalysis?,
    private val bandarDetector: BandarDetectorStat? = null
) : BottomSheetDialogFragment() {

    private val groqRepo = GroqAiRepository()
    private val yahooRepo = YahooFinanceRepository()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.bottom_sheet_portfolio_rescue, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvRescueTicker: TextView = view.findViewById(R.id.tvRescueTicker)
        val tvRescuePnl: TextView = view.findViewById(R.id.tvRescuePnl)
        val badgeRescueStatus: TextView = view.findViewById(R.id.badgeRescueStatus)
        val tvRescueEntry: TextView = view.findViewById(R.id.tvRescueEntry)
        val tvRescueCurrentPrice: TextView = view.findViewById(R.id.tvRescueCurrentPrice)
        val tvRescueLot: TextView = view.findViewById(R.id.tvRescueLot)
        val tvRescueContent: TextView = view.findViewById(R.id.tvRescueContent)
        val layoutRescueLoading: View = view.findViewById(R.id.layoutRescueLoading)
        val btnRefreshRescue: Button = view.findViewById(R.id.btnRefreshRescue)
        val btnExecuteCutLoss: Button = view.findViewById(R.id.btnExecuteCutLoss)

        tvRescueTicker.text = trade.ticker
        val pnlSign = if (trade.pnlPercent >= 0) "+" else ""
        tvRescuePnl.text = "$pnlSign${String.format("%.2f", trade.pnlPercent)}% (${trade.pnlRupiah} Rp)"
        if (trade.pnlPercent < 0) {
            tvRescuePnl.setTextColor(Color.parseColor("#EF4444"))
            badgeRescueStatus.text = "FLOATING LOSS"
            badgeRescueStatus.setBackgroundResource(R.drawable.bg_score_red)
            badgeRescueStatus.setTextColor(Color.parseColor("#EF4444"))
        } else {
            tvRescuePnl.setTextColor(Color.parseColor("#10B981"))
            badgeRescueStatus.text = "PROFIT"
            badgeRescueStatus.setBackgroundResource(R.drawable.bg_score_green)
            badgeRescueStatus.setTextColor(Color.parseColor("#10B981"))
        }

        tvRescueEntry.text = "Rp ${formatPrice(trade.entryPrice)}"
        tvRescueCurrentPrice.text = "Rp ${formatPrice(trade.currentPrice)}"
        tvRescueLot.text = "${trade.lot} Lot"

        btnRefreshRescue.setOnClickListener {
            runRescueAnalysis(tvRescueContent, layoutRescueLoading, btnRefreshRescue)
        }

        btnExecuteCutLoss.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Konfirmasi Cut Loss")
                .setMessage("Apakah Anda yakin ingin mengeksekusi cut loss untuk ${trade.ticker} pada harga pasar Rp ${trade.currentPrice}?")
                .setPositiveButton("Ya, Cut Loss") { _, _ ->
                    (activity as? MainActivity)?.closeTradeByTicker(trade.ticker)
                    dismiss()
                }
                .setNegativeButton("Batal", null)
                .show()
        }

        runRescueAnalysis(tvRescueContent, layoutRescueLoading, btnRefreshRescue)
    }

    private fun runRescueAnalysis(
        tvRescueContent: TextView,
        layoutRescueLoading: View,
        btnRefreshRescue: Button
    ) {
        val prefs = requireContext().getSharedPreferences("ScalpingPrefs", Context.MODE_PRIVATE)
        val key = prefs.getString("groq_api_key", "") ?: ""

        if (key.isBlank()) {
            tvRescueContent.text = "⚠️ Groq API Key belum disetel. Silakan buka detail saham mana saja lalu atur di tombol ⚙️ API Key."
            tvRescueContent.setTextColor(Color.parseColor("#EF4444"))
            return
        }

        btnRefreshRescue.isEnabled = false
        layoutRescueLoading.visibility = View.VISIBLE
        tvRescueContent.text = "Mendiagnosa akumulasi bandar, support teknikal harian, dan rasio risiko..."
        tvRescueContent.setTextColor(Color.parseColor("#94A3B8"))

        lifecycleScope.launch {
            val dailyTech = yahooRepo.fetchDailyTechnicals(trade.ticker)
            val result = groqRepo.getPortfolioRescueAnalysis(trade, analysis, bandarDetector, dailyTech, key)

            if (!isAdded) return@launch
            btnRefreshRescue.isEnabled = true
            layoutRescueLoading.visibility = View.GONE

            if (result.isSuccess) {
                tvRescueContent.text = result.getOrNull()
                tvRescueContent.setTextColor(Color.parseColor("#F1F5F9"))
            } else {
                val err = result.exceptionOrNull()?.message ?: "Gagal menghubungi Groq AI"
                tvRescueContent.text = "⚠️ $err"
                tvRescueContent.setTextColor(Color.parseColor("#EF4444"))
            }
        }
    }

    private fun formatPrice(price: Int): String {
        return String.format("%,d", price).replace(',', '.')
    }
}
