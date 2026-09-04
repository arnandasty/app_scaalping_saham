package com.scalping.assistant.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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

        val detailTicker: android.widget.TextView = view.findViewById(R.id.detailTicker)
        val detailRecommendation: android.widget.TextView = view.findViewById(R.id.detailRecommendation)
        val detailScore: android.widget.TextView = view.findViewById(R.id.detailScore)
        val detailConfidence: android.widget.TextView = view.findViewById(R.id.detailConfidence)
        val detailEntry: android.widget.TextView = view.findViewById(R.id.detailEntry)
        val detailTarget: android.widget.TextView = view.findViewById(R.id.detailTarget)
        val detailStopLoss: android.widget.TextView = view.findViewById(R.id.detailStopLoss)
        val detailReasons: android.widget.TextView = view.findViewById(R.id.detailReasons)
        val detailWarnings: android.widget.TextView = view.findViewById(R.id.detailWarnings)
        val detailFibonacci: android.widget.TextView = view.findViewById(R.id.detailFibonacci)
        val detailSupportResistance: android.widget.TextView = view.findViewById(R.id.detailSupportResistance)
        val detailEntryStyle: android.widget.TextView = view.findViewById(R.id.detailEntryStyle)
        val etEntryPrice: EditText = view.findViewById(R.id.etEntryPrice)
        val etLotAmount: EditText = view.findViewById(R.id.etLotAmount)

        val sign = if (item.changePercent > 0) "+" else ""
        val priceFormatted = formatPrice(item.lastPrice)
        detailTicker.text = "${item.ticker} Rp $priceFormatted ($sign${item.changePercent}%)"
        
        if (item.changePercent > 0) {
            detailTicker.setTextColor(Color.parseColor("#10B981"))
        } else if (item.changePercent < 0) {
            detailTicker.setTextColor(Color.parseColor("#EF4444"))
        } else {
            detailTicker.setTextColor(Color.parseColor("#E2E8F0"))
        }
        etEntryPrice.setText(item.entryPrice.toString())
        detailScore.text = "Skor: ${item.score}/100"

        // Confidence level berdasarkan jumlah snapshot
        val confidenceText = when {
            item.snapshotCount < 3 -> "⏳ Data: ${item.snapshotCount} — Tunggu konfirmasi!"
            item.snapshotCount < 5 -> "⚠️ Data: ${item.snapshotCount} — Sinyal awal"
            item.snapshotCount < 10 -> "🔍 Data: ${item.snapshotCount} — Terkumpul"
            item.snapshotCount >= 20 -> "✅ Data: ${item.snapshotCount} — Sangat kuat"
            else -> "✅ Data: ${item.snapshotCount} — Terkonfirmasi"
        }
        val confidenceColor = when {
            item.snapshotCount < 3 -> Color.parseColor("#EF4444")
            item.snapshotCount < 5 -> Color.parseColor("#F59E0B")
            else -> Color.parseColor("#10B981")
        }
        detailConfidence.text = confidenceText
        detailConfidence.setTextColor(confidenceColor)

        detailRecommendation.text = item.recommendation.label
        val recColor = Color.parseColor(item.recommendation.colorCode)
        detailRecommendation.setTextColor(recColor)
        detailScore.setTextColor(recColor)

        detailEntry.text = "Rp ${formatPrice(item.entryPrice)}"
        detailEntryStyle.text = "(Gaya: ${item.style})"
        detailTarget.text = "Rp ${formatPrice(item.targetPrice)} (+${item.estimatedProfitPercent}%)"
        detailStopLoss.text = "Rp ${formatPrice(item.stopLoss)}"

        // Pisahkan warnings dari reasons agar tidak duplikat
        val pureReasons = item.reasons.filter { r ->
            item.warnings.none { w -> w == r }
        }

        if (pureReasons.isNotEmpty()) {
            detailReasons.text = pureReasons.joinToString("\n") { "• $it" }
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

        btnDoneBuy.setOnClickListener {
            val lotText = etLotAmount.text.toString()
            val lot = lotText.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val priceText = etEntryPrice.text.toString()
            val entryPrice = priceText.toIntOrNull() ?: item.entryPrice

            (activity as? com.scalping.assistant.MainActivity)?.let { mainActivity ->
                mainActivity.addPortfolioTrade(item.ticker, entryPrice, lot)
                mainActivity.navigateToPortfolioTab()
            }
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
