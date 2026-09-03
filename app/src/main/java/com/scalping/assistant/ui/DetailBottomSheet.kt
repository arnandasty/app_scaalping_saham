package com.scalping.assistant.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.scalping.assistant.R
import com.scalping.assistant.data.models.StockAnalysis

class DetailBottomSheet(private val item: StockAnalysis) : BottomSheetDialogFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.bottom_sheet_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val detailTicker: TextView = view.findViewById(R.id.detailTicker)
        val detailRecommendation: TextView = view.findViewById(R.id.detailRecommendation)
        val detailScore: TextView = view.findViewById(R.id.detailScore)
        val detailEntry: TextView = view.findViewById(R.id.detailEntry)
        val detailTarget: TextView = view.findViewById(R.id.detailTarget)
        val detailStopLoss: TextView = view.findViewById(R.id.detailStopLoss)
        val detailReasons: TextView = view.findViewById(R.id.detailReasons)
        val detailWarnings: TextView = view.findViewById(R.id.detailWarnings)
        val detailFibonacci: TextView = view.findViewById(R.id.detailFibonacci)
        val detailSupportResistance: TextView = view.findViewById(R.id.detailSupportResistance)

        val detailEntryStyle: TextView = view.findViewById(R.id.detailEntryStyle)

        detailTicker.text = item.ticker
        detailScore.text = "Skor: ${item.score}/100"

        detailRecommendation.text = item.recommendation.label
        val recColor = Color.parseColor(item.recommendation.colorCode)
        detailRecommendation.setTextColor(recColor)
        detailScore.setTextColor(recColor)

        detailEntry.text = "Rp ${formatPrice(item.entryPrice)}"
        
        val detailEntryStyle = view.findViewById<TextView>(R.id.detailEntryStyle)
        detailEntryStyle.text = "(Gaya: ${item.style})"
        
        detailTarget.text = "Rp ${formatPrice(item.targetPrice)} (+${item.estimatedProfitPercent}%)"
        detailStopLoss.text = "Rp ${formatPrice(item.stopLoss)}"

        if (item.reasons.isNotEmpty()) {
            detailReasons.text = item.reasons.joinToString("\n") { "• $it" }
        } else {
            detailReasons.text = "• Mengumpulkan riwayat orderbook..."
        }

        if (item.warnings.isNotEmpty()) {
            detailWarnings.text = item.warnings.joinToString("\n") { "• $it" }
            detailWarnings.setTextColor(Color.parseColor("#F59E0B"))
        } else {
            detailWarnings.text = "✅ Tidak ada indikasi bahaya atau anomali."
            detailWarnings.setTextColor(Color.parseColor("#10B981"))
        }

        val fibs = item.technical.fibLevels
        if (fibs.isNotEmpty()) {
            val fibText = fibs.entries.take(5).joinToString(" | ") { (k, v) ->
                "$k: ${formatPrice(v.toInt())}"
            }
            detailFibonacci.text = fibText
        } else {
            detailFibonacci.text = "Data candle teknikal sedang disinkronkan..."
        }
        
        detailSupportResistance.text = "S1 (Support): Rp ${formatPrice(item.technical.nearestSupport.toInt())} | R1 (Resistance): Rp ${formatPrice(item.technical.nearestResistance.toInt())}"
        
        val btnDoneBuy = view.findViewById<android.widget.Button>(R.id.btnDoneBuy)
        val btnDoneSell = view.findViewById<android.widget.Button>(R.id.btnDoneSell)
        val tvTradeStatus = view.findViewById<TextView>(R.id.tvTradeStatus)

        btnDoneBuy.setOnClickListener {
            (activity as? com.scalping.assistant.MainActivity)?.setActiveTrade(item.ticker, item.entryPrice.toDouble())
            dismiss()
        }
        btnDoneSell.setOnClickListener {
            (activity as? com.scalping.assistant.MainActivity)?.clearActiveTrade()
            dismiss()
        }
    }

    private fun formatPrice(price: Int): String {
        return String.format("%,d", price).replace(',', '.')
    }
}
