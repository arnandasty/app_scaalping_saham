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

        // Bandar Detector live stream binding
        val detailBandarStatus: TextView = view.findViewById(R.id.detailBandarStatus)
        val detailBandarAvg: TextView = view.findViewById(R.id.detailBandarAvg)
        val detailBandarAmount: TextView = view.findViewById(R.id.detailBandarAmount)
        val detailBandarPullbackNote: TextView = view.findViewById(R.id.detailBandarPullbackNote)

        val bandar = item.bandarDetector
        if (bandar != null) {
            val statusClean = bandar.accdistStatus.trim()
            val (statusText, statusBg, statusTextColor) = when (statusClean) {
                "Big Acc" -> Triple("🟢 Big Accumulation", R.drawable.bg_score_green, Color.parseColor("#10B981"))
                "Acc" -> Triple("🟢 Normal Accumulation", R.drawable.bg_score_green, Color.parseColor("#10B981"))
                "Big Dist" -> Triple("🔴 Big Distribution", R.drawable.bg_score_red, Color.parseColor("#EF4444"))
                "Dist" -> Triple("🔴 Normal Distribution", R.drawable.bg_score_red, Color.parseColor("#EF4444"))
                else -> Triple("⚪ Neutral / Seimbang", R.drawable.bg_chip, Color.parseColor("#94A3B8"))
            }
            detailBandarStatus.text = statusText
            detailBandarStatus.setBackgroundResource(statusBg)
            detailBandarStatus.setTextColor(statusTextColor)

            detailBandarAvg.text = if (bandar.averagePrice > 0) "Rp ${formatPrice(bandar.averagePrice.toInt())}" else "-"
            detailBandarAmount.text = formatCurrencyShort(bandar.amountRupiah)

            // Flow & Pullback Note
            val avgDiff = if (bandar.averagePrice > 0) ((item.lastPrice - bandar.averagePrice) / bandar.averagePrice) * 100 else 0.0
            val diffStr = if (avgDiff >= 0) "+%.1f%%".format(avgDiff) else "%.1f%%".format(avgDiff)
            val note = when {
                statusClean.contains("Dist") -> "⚠️ Hati-hati! Bandar sedang distribusi masif. Dilarang beli / hindari perangkap pucuk!"
                bandar.averagePrice > 0 && item.lastPrice <= bandar.averagePrice -> "💎 Harga saat ini ($diffStr dari avg bandar) berada di bawah/setara harga modal bandar — Risk/Reward sangat menguntungkan!"
                bandar.averagePrice > 0 && item.lastPrice > bandar.averagePrice -> "ℹ️ Harga saat ini $diffStr di atas avg bandar (Rp ${formatPrice(bandar.averagePrice.toInt())}). Pastikan ada bantalan support jika ingin masuk."
                else -> "Status arus akumulasi bandar: $statusClean"
            }
            detailBandarPullbackNote.text = note
            detailBandarPullbackNote.setTextColor(if (statusClean.contains("Dist")) Color.parseColor("#EF4444") else Color.parseColor("#38BDF8"))
        } else {
            detailBandarStatus.text = "Menunggu Stream"
            detailBandarAvg.text = "-"
            detailBandarAmount.text = "-"
            detailBandarPullbackNote.text = "Live broker detector sedang disinkronkan dari data session WebView..."
        }

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

        val fibs = item.technical.fibLevels.filter { it.value > 0.0 }
        if (fibs.isNotEmpty()) {
            val fibText = fibs.entries.take(5).joinToString(" | ") { (k, v) ->
                "$k: ${formatPrice(v.toInt())}"
            }
            detailFibonacci.text = fibText
        } else {
            val f38 = formatPrice(com.scalping.assistant.engine.PriceFraction.roundToValidTick((item.lastPrice * 1.025).toInt()))
            val f50 = formatPrice(item.lastPrice)
            val f61 = formatPrice(com.scalping.assistant.engine.PriceFraction.roundToValidTick((item.lastPrice * 0.975).toInt()))
            detailFibonacci.text = "61.8%: Rp $f61 | 50.0%: Rp $f50 | 38.2%: Rp $f38 (Estimasi Intraday)"
        }

        val tech = item.technical
        val s1 = if (tech.nearestSupport > 0) "Rp ${formatPrice(tech.nearestSupport.toInt())}" else "Rp ${formatPrice(com.scalping.assistant.engine.PriceFraction.roundDownToValidTick((item.lastPrice * 0.97).toInt()))}"
        val r1 = if (tech.nearestResistance > 0) "Rp ${formatPrice(tech.nearestResistance.toInt())}" else "Rp ${formatPrice(com.scalping.assistant.engine.PriceFraction.roundUpToValidTick((item.lastPrice * 1.03).toInt()))}"
        val s2 = if (tech.nearestSupport2 > 0) "Rp ${formatPrice(tech.nearestSupport2.toInt())}" else "-"
        val r2 = if (tech.nearestResistance2 > 0) "Rp ${formatPrice(tech.nearestResistance2.toInt())}" else "-"
        val vwapStr = if (tech.vwap > 0) "Rp ${formatPrice(tech.vwap.toInt())}" else "-"
        val ema9Str = if (tech.ema9 > 0) "Rp ${formatPrice(tech.ema9.toInt())}" else "-"
        val ema21Str = if (tech.ema21 > 0) "Rp ${formatPrice(tech.ema21.toInt())}" else "-"
        val bbLowerStr = if (tech.bbLower > 0) "Rp ${formatPrice(tech.bbLower.toInt())}" else "-"
        val bbUpperStr = if (tech.bbUpper > 0) "Rp ${formatPrice(tech.bbUpper.toInt())}" else "-"

        detailSupportResistance.text = """
            S1 (Support Utama): $s1 | R1 (Resisten Utama): $r1
            S2 (Support Dinamis): $s2 | R2 (Resis Dinamis): $r2
            VWAP: $vwapStr
            EMA 9: $ema9Str | EMA 21: $ema21Str
            BB Bawah: $bbLowerStr | BB Atas: $bbUpperStr
        """.trimIndent()

        val btnDoneBuy = view.findViewById<android.widget.Button>(R.id.btnDoneBuy)
        val btnDoneSell = view.findViewById<android.widget.Button>(R.id.btnDoneSell)

        btnDoneBuy.setOnClickListener {
            val lotText = etLotAmount.text.toString()
            val lot = lotText.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val priceText = etEntryPrice.text.toString()
            val entryPrice = priceText.toIntOrNull() ?: item.entryPrice

            // Jika user mengubah harga beli manual, kita sesuaikan TP/SL-nya secara proporsional atau default persentase
            val targetPrice = if (entryPrice == item.entryPrice) item.targetPrice else com.scalping.assistant.engine.PriceFraction.roundUpToValidTick(kotlin.math.ceil(entryPrice * 1.028).toInt())
            val stopLoss = if (entryPrice == item.entryPrice) item.stopLoss else com.scalping.assistant.engine.PriceFraction.roundDownToValidTick(kotlin.math.floor(entryPrice * 0.985).toInt())

            (activity as? com.scalping.assistant.MainActivity)?.let { mainActivity ->
                mainActivity.addPortfolioTrade(item.ticker, entryPrice, lot, targetPrice, stopLoss)
                mainActivity.navigateToPortfolioTab()
            }
            dismiss()
        }

        btnDoneSell.setOnClickListener {
            (activity as? com.scalping.assistant.MainActivity)?.closeTradeByTicker(item.ticker)
            dismiss()
        }
    }

    private fun formatPrice(price: Int): String {
        return String.format("%,d", price).replace(',', '.')
    }

    private fun formatCurrencyShort(amount: Long): String {
        val absVal = kotlin.math.abs(amount).toDouble()
        val sign = if (amount < 0) "-" else ""
        return when {
            absVal >= 1_000_000_000_000.0 -> "${sign}Rp %.1f T".format(absVal / 1_000_000_000_000.0)
            absVal >= 1_000_000_000.0 -> "${sign}Rp %.1f M".format(absVal / 1_000_000_000.0)
            absVal >= 1_000_000.0 -> "${sign}Rp %.1f Jt".format(absVal / 1_000_000.0)
            absVal > 0 -> "${sign}Rp ${formatPrice(absVal.toInt())}"
            else -> "Rp 0"
        }
    }
}
