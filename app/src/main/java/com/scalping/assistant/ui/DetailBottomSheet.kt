package com.scalping.assistant.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.content.res.ColorStateList
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.scalping.assistant.MainActivity
import com.scalping.assistant.R
import com.scalping.assistant.data.models.StockAnalysis
import com.scalping.assistant.data.repository.GroqAiRepository
import com.scalping.assistant.data.repository.GroqAnalysisMode
import com.scalping.assistant.data.repository.PortfolioTrade
import com.scalping.assistant.data.repository.YahooFinanceRepository
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.Calendar
import java.util.TimeZone

class DetailBottomSheet(private var item: StockAnalysis) : BottomSheetDialogFragment() {

    private val groqRepo = GroqAiRepository()
    private val yahooRepo = YahooFinanceRepository()
    private var selectedGroqMode: GroqAnalysisMode = GroqAnalysisMode.SCALPING

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

        // Groq AI Widgets
        val tvGroqSummary: TextView = view.findViewById(R.id.tvGroqSummary)
        val layoutGroqLoading: View = view.findViewById(R.id.layoutGroqLoading)
        val tvGroqLoadingStatus: TextView = view.findViewById(R.id.tvGroqLoadingStatus)
        val btnAskGroq: Button = view.findViewById(R.id.btnAskGroq)
        val btnGroqSettings: Button = view.findViewById(R.id.btnGroqSettings)
        val chipModeScalping: TextView = view.findViewById(R.id.chipModeScalping)
        val chipModeSwing: TextView = view.findViewById(R.id.chipModeSwing)
        val chipModeRescue: TextView = view.findViewById(R.id.chipModeRescue)

        val activeTrade = getActivePortfolioTrade()
        if (activeTrade != null) {
            chipModeRescue.visibility = View.VISIBLE
            // Jika sedang floating loss / harga di bawah modal, utamakan mode Dokter Rescue Porto
            if (activeTrade.pnlPercent < 0 || (activeTrade.entryPrice > 0 && item.lastPrice < activeTrade.entryPrice)) {
                selectedGroqMode = GroqAnalysisMode.PORTFOLIO_RESCUE
            } else {
                selectedGroqMode = if (isMarketOpen()) GroqAnalysisMode.SCALPING else GroqAnalysisMode.SWING
            }
        } else {
            chipModeRescue.visibility = View.GONE
            selectedGroqMode = if (isMarketOpen()) GroqAnalysisMode.SCALPING else GroqAnalysisMode.SWING
        }
        updateModeUi(chipModeScalping, chipModeSwing, chipModeRescue, btnAskGroq, tvGroqSummary)

        val initialBandar = (activity as? MainActivity)?.orderBookRepo?.getBandarDetector(item.ticker) ?: item.bandarDetector
        if (initialBandar != null) {
            item = item.copy(bandarDetector = initialBandar)
        }

        (activity as? MainActivity)?.let { main ->
            main.requestBandarDetector(item.ticker, force = true)
            main.requestMultiDayBandarDetector(item.ticker, force = true)
        }

        // Live observation of bandar detector updates
        viewLifecycleOwner.lifecycleScope.launch {
            (activity as? MainActivity)?.orderBookRepo?.bandarDetectorFlow?.collect { map ->
                val newStat = map[item.ticker]
                if (newStat != null) {
                    item = item.copy(bandarDetector = newStat)
                    bindBandarDetector(newStat, view)
                }
            }
        }

        chipModeScalping.setOnClickListener {
            selectedGroqMode = GroqAnalysisMode.SCALPING
            updateModeUi(chipModeScalping, chipModeSwing, chipModeRescue, btnAskGroq, tvGroqSummary)
        }

        chipModeSwing.setOnClickListener {
            selectedGroqMode = GroqAnalysisMode.SWING
            (activity as? MainActivity)?.requestMultiDayBandarDetector(item.ticker, force = true)
            updateModeUi(chipModeScalping, chipModeSwing, chipModeRescue, btnAskGroq, tvGroqSummary)
        }

        chipModeRescue.setOnClickListener {
            selectedGroqMode = GroqAnalysisMode.PORTFOLIO_RESCUE
            (activity as? MainActivity)?.requestMultiDayBandarDetector(item.ticker, force = true)
            updateModeUi(chipModeScalping, chipModeSwing, chipModeRescue, btnAskGroq, tvGroqSummary)
        }

        btnGroqSettings.setOnClickListener {
            showGroqApiKeyDialog()
        }

        btnAskGroq.setOnClickListener {
            requestGroqAnalysis(tvGroqSummary, layoutGroqLoading, tvGroqLoadingStatus, btnAskGroq)
        }

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
        bindBandarDetector(item.bandarDetector, view)

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

        val ma5Str = if (tech.ma5 > 0) "Rp ${formatPrice(tech.ma5.toInt())}" else "-"
        val ma9Str = if (tech.ma9 > 0) "Rp ${formatPrice(tech.ma9.toInt())}" else "-"
        val ma20Str = if (tech.ma20 > 0) "Rp ${formatPrice(tech.ma20.toInt())}" else "-"

        detailSupportResistance.text = """
            S1 (Support Utama): $s1 | R1 (Resisten Utama): $r1
            S2 (Support Dinamis): $s2 | R2 (Resis Dinamis): $r2
            MA 5: $ma5Str | MA 9: $ma9Str | MA 20: $ma20Str
            VWAP: $vwapStr | EMA 9: $ema9Str | EMA 21: $ema21Str
            BB Bawah: $bbLowerStr | BB Atas: $bbUpperStr
        """.trimIndent()

        val btnDoneBuy = view.findViewById<android.widget.Button>(R.id.btnDoneBuy)
        val btnDoneSell = view.findViewById<android.widget.Button>(R.id.btnDoneSell)
        val tvTradeStatus = view.findViewById<android.widget.TextView>(R.id.tvTradeStatus)

        if (activeTrade != null) {
            btnDoneSell.visibility = View.VISIBLE
            btnDoneSell.text = "🔴 TUTUP POSISI (${activeTrade.lot} Lot)"
            tvTradeStatus.visibility = View.VISIBLE
            val pnlSign = if (activeTrade.pnlPercent >= 0) "+" else ""
            tvTradeStatus.text = "📍 Posisi Aktif: ${activeTrade.lot} Lot @ Rp ${formatPrice(activeTrade.entryPrice)} ($pnlSign${String.format("%.2f", activeTrade.pnlPercent)}%)"
            tvTradeStatus.setTextColor(if (activeTrade.pnlPercent < 0) Color.parseColor("#EF4444") else Color.parseColor("#10B981"))
            etEntryPrice.setText(activeTrade.entryPrice.toString())
            etLotAmount.setText(activeTrade.lot.toString())
            btnDoneBuy.text = "➕ Tambah Lot (Averaging)"
        }

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

    private fun bindBandarDetector(bandar: com.scalping.assistant.data.models.BandarDetectorStat?, root: View) {
        val detailBandarStatus: TextView = root.findViewById(R.id.detailBandarStatus) ?: return
        val detailBandarAvg: TextView = root.findViewById(R.id.detailBandarAvg) ?: return
        val detailBandarAmount: TextView = root.findViewById(R.id.detailBandarAmount) ?: return
        val detailBandarPullbackNote: TextView = root.findViewById(R.id.detailBandarPullbackNote) ?: return

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

            val avgDiff = if (bandar.averagePrice > 0) ((item.lastPrice - bandar.averagePrice) / bandar.averagePrice) * 100 else 0.0
            val diffStr = if (avgDiff >= 0) "+%.1f%%".format(avgDiff) else "%.1f%%".format(avgDiff)
            val note = when {
                statusClean.contains("Dist") -> "⚠️ Hati-hati! Bandar sedang distribusi masif. Dilarang beli / hindari perangkap pucuk!"
                bandar.averagePrice > 0 && item.lastPrice <= bandar.averagePrice -> "💎 Harga saat ini ($diffStr dari avg bandar) berada di bawah/setara harga modal bandar — Risk/Reward sangat menguntungkan!"
                bandar.averagePrice > 0 && item.lastPrice > bandar.averagePrice -> "ℹ️ Harga saat ini $diffStr di atas avg bandar (Rp ${formatPrice(bandar.averagePrice.toInt())}). Pastikan ada bantalan support jika ingin masuk."
                else -> "Status arus akumulasi bandar: $statusClean"
            }
            val extraDetails = buildString {
                append(note)
                if (bandar.topConcentration.isNotEmpty()) append("\n📊 ${bandar.topConcentration}")
                if (bandar.foreignFlow.isNotEmpty()) append("\n🌐 ${bandar.foreignFlow}")
                if (bandar.foreignFlowMultiDay.isNotEmpty()) append("\n🗓️ ${bandar.foreignFlowMultiDay}")
                if (bandar.smartMoneySummary.isNotEmpty()) append("\n⚡ ${bandar.smartMoneySummary}")
                if (bandar.topBrokers.isNotEmpty()) append("\n💼 ${bandar.topBrokers}")
            }
            detailBandarPullbackNote.text = extraDetails
            detailBandarPullbackNote.setTextColor(if (statusClean.contains("Dist")) Color.parseColor("#EF4444") else Color.parseColor("#38BDF8"))
        } else {
            detailBandarStatus.text = "Menghubungkan Data..."
            detailBandarAvg.text = "-"
            detailBandarAmount.text = "-"
            detailBandarPullbackNote.text = "Sedang menarik data live broker detector & flow asing dari bursa..."
            detailBandarPullbackNote.setTextColor(Color.parseColor("#94A3B8"))
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

    private fun getGroqApiKey(): String {
        val prefs = requireContext().getSharedPreferences("ScalpingPrefs", Context.MODE_PRIVATE)
        return prefs.getString("groq_api_key", "") ?: ""
    }

    private fun saveGroqApiKey(key: String) {
        val prefs = requireContext().getSharedPreferences("ScalpingPrefs", Context.MODE_PRIVATE)
        prefs.edit().putString("groq_api_key", key.trim()).apply()
    }

    private fun showGroqApiKeyDialog(onSaved: (() -> Unit)? = null) {
        val ctx = context ?: return
        val currentKey = getGroqApiKey()

        val input = EditText(ctx).apply {
            hint = "gsk_..."
            setText(currentKey)
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(32, 28, 32, 28)
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 12)
            addView(input)
        }

        AlertDialog.Builder(ctx)
            .setTitle("⚙️ Konfigurasi Groq AI")
            .setMessage("Model: Groq Cloud Ultra-Fast (<300ms)\n\nMasukkan API Key Groq Anda. Jika belum punya, ambil gratis tanpa kartu kredit di console.groq.com/keys")
            .setView(container)
            .setPositiveButton("Simpan") { _, _ ->
                val key = input.text.toString().trim()
                saveGroqApiKey(key)
                if (key.isNotEmpty()) {
                    Toast.makeText(ctx, "✅ API Key Groq berhasil disimpan", Toast.LENGTH_SHORT).show()
                    onSaved?.invoke()
                } else {
                    Toast.makeText(ctx, "API Key dikosongkan", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Batal", null)
            .setNeutralButton("Daftar Groq") { _, _ ->
                try {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys"))
                    startActivity(browserIntent)
                } catch (e: Exception) {
                    Toast.makeText(ctx, "Gagal membuka browser", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun isMarketOpen(): Boolean {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Jakarta"))
        val day = cal.get(Calendar.DAY_OF_WEEK)
        if (day == Calendar.SATURDAY || day == Calendar.SUNDAY) return false
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val timeMinutes = hour * 60 + minute
        return (timeMinutes in 540..720) || (timeMinutes in 810..960)
    }

    private fun updateModeUi(
        chipScalping: TextView,
        chipSwing: TextView,
        chipRescue: TextView,
        btnAskGroq: Button,
        tvGroqSummary: TextView
    ) {
        when (selectedGroqMode) {
            GroqAnalysisMode.SCALPING -> {
                chipScalping.setBackgroundResource(R.drawable.bg_chip_active)
                chipScalping.setTextColor(Color.WHITE)
                chipSwing.setBackgroundResource(R.drawable.bg_chip)
                chipSwing.setTextColor(Color.parseColor("#94A3B8"))
                chipRescue.setBackgroundResource(R.drawable.bg_chip)
                chipRescue.setTextColor(Color.parseColor("#94A3B8"))

                btnAskGroq.text = "⚡ Minta Opini Scalper (Groq AI)"
                btnAskGroq.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F97316"))
            }
            GroqAnalysisMode.SWING -> {
                chipScalping.setBackgroundResource(R.drawable.bg_chip)
                chipScalping.setTextColor(Color.parseColor("#94A3B8"))
                chipSwing.setBackgroundResource(R.drawable.bg_chip_active_blue)
                chipSwing.setTextColor(Color.WHITE)
                chipRescue.setBackgroundResource(R.drawable.bg_chip)
                chipRescue.setTextColor(Color.parseColor("#94A3B8"))

                btnAskGroq.text = "🌙 Analisis Swing & Broksum (Groq AI)"
                btnAskGroq.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#0EA5E9"))
            }
            GroqAnalysisMode.PORTFOLIO_RESCUE -> {
                chipScalping.setBackgroundResource(R.drawable.bg_chip)
                chipScalping.setTextColor(Color.parseColor("#94A3B8"))
                chipSwing.setBackgroundResource(R.drawable.bg_chip)
                chipSwing.setTextColor(Color.parseColor("#94A3B8"))
                chipRescue.setBackgroundResource(R.drawable.bg_chip_active_red)
                chipRescue.setTextColor(Color.WHITE)

                btnAskGroq.text = "🩺 Diagnosa Penyelamatan Porto (Groq AI)"
                btnAskGroq.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
            }
        }
    }

    private fun requestGroqAnalysis(
        tvGroqSummary: TextView,
        layoutGroqLoading: View,
        tvGroqLoadingStatus: TextView,
        btnAskGroq: Button
    ) {
        val key = getGroqApiKey()
        if (key.isBlank()) {
            showGroqApiKeyDialog {
                requestGroqAnalysis(tvGroqSummary, layoutGroqLoading, tvGroqLoadingStatus, btnAskGroq)
            }
            return
        }

        btnAskGroq.isEnabled = false
        layoutGroqLoading.visibility = View.VISIBLE

        when (selectedGroqMode) {
            GroqAnalysisMode.PORTFOLIO_RESCUE -> {
                tvGroqLoadingStatus.text = "Dokter Saham Mendiagnosa..."
                tvGroqSummary.text = "Mendiagnosa akumulasi bandar, support teknikal harian, dan rasio risiko cut loss / avg down..."
            }
            GroqAnalysisMode.SWING -> {
                tvGroqLoadingStatus.text = "Menghubungi Groq AI..."
                tvGroqSummary.text = "Sedang menarik data harian Yahoo Finance & menganalisis posisi swing..."
            }
            else -> {
                tvGroqLoadingStatus.text = "Menghubungi Groq AI..."
                tvGroqSummary.text = "Memproses analisis orderflow & bandarmologi scalping..."
            }
        }
        tvGroqSummary.setTextColor(Color.parseColor("#94A3B8"))

        lifecycleScope.launch {
            val mainAct = activity as? MainActivity
            var currentBandar = mainAct?.orderBookRepo?.getBandarDetector(item.ticker) ?: item.bandarDetector
            if (currentBandar == null || (selectedGroqMode == GroqAnalysisMode.SWING && currentBandar.foreignFlowMultiDay.isEmpty())) {
                mainAct?.requestBandarDetector(item.ticker, force = true)
                mainAct?.requestMultiDayBandarDetector(item.ticker, force = true)
                var attempts = 0
                while ((currentBandar == null || (selectedGroqMode == GroqAnalysisMode.SWING && currentBandar?.foreignFlowMultiDay.isNullOrEmpty())) && attempts < 6) {
                    kotlinx.coroutines.delay(300L)
                    currentBandar = mainAct?.orderBookRepo?.getBandarDetector(item.ticker) ?: currentBandar
                    attempts++
                }
            }

            val analysisItem = (mainAct?.orderBookRepo?.getAnalysisForTicker(item.ticker) ?: item).let {
                if (currentBandar != null) it.copy(bandarDetector = currentBandar) else it
            }

            val result = if (selectedGroqMode == GroqAnalysisMode.PORTFOLIO_RESCUE) {
                val trade = getActivePortfolioTrade() ?: PortfolioTrade(
                    ticker = item.ticker,
                    entryPrice = item.entryPrice,
                    lot = 1,
                    currentPrice = item.lastPrice
                )
                val dailyTech = yahooRepo.fetchDailyTechnicals(item.ticker)
                groqRepo.getPortfolioRescueAnalysis(trade, analysisItem, currentBandar, dailyTech, key)
            } else {
                val dailyTech = if (selectedGroqMode == GroqAnalysisMode.SWING) {
                    yahooRepo.fetchDailyTechnicals(item.ticker)
                } else null
                groqRepo.getAiAnalysis(analysisItem, key, selectedGroqMode, dailyTech)
            }
            if (!isAdded) return@launch

            btnAskGroq.isEnabled = true
            layoutGroqLoading.visibility = View.GONE

            if (result.isSuccess) {
                tvGroqSummary.text = result.getOrNull()
                tvGroqSummary.setTextColor(Color.parseColor("#F1F5F9"))
                btnAskGroq.text = when (selectedGroqMode) {
                    GroqAnalysisMode.PORTFOLIO_RESCUE -> "🔄 Segarkan Diagnosa Dokter Porto"
                    GroqAnalysisMode.SWING -> "🔄 Segarkan Analisis Swing"
                    else -> "🔄 Segarkan Opini Scalper"
                }
            } else {
                val err = result.exceptionOrNull()?.message ?: "Gagal menghubungi Groq"
                tvGroqSummary.text = "⚠️ $err"
                tvGroqSummary.setTextColor(Color.parseColor("#EF4444"))
                btnAskGroq.text = "⚡ Coba Lagi (Groq AI)"

                if (err.contains("401") || err.contains("API Key", ignoreCase = true) || err.contains("invalid", ignoreCase = true)) {
                    Toast.makeText(requireContext(), "API Key mungkin tidak valid. Silakan periksa di tombol ⚙️ API Key.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun getActivePortfolioTrade(): PortfolioTrade? {
        (activity as? MainActivity)?.getActiveTrade(item.ticker)?.let { return it }
        val prefs = context?.getSharedPreferences("ScalpingPrefs", Context.MODE_PRIVATE) ?: return null
        val jsonStr = prefs.getString("portfolio_data", null) ?: return null
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                if (obj.optString("ticker").equals(item.ticker, ignoreCase = true) && obj.optBoolean("isActive", true)) {
                    val entryPrice = obj.getInt("entryPrice")
                    val currentPrice = if (item.lastPrice > 0) item.lastPrice else obj.optInt("currentPrice", entryPrice)
                    val pnl = if (entryPrice > 0) ((currentPrice.toDouble() - entryPrice) / entryPrice) * 100.0 else 0.0
                    return PortfolioTrade(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        ticker = obj.getString("ticker"),
                        entryPrice = entryPrice,
                        lot = obj.getInt("lot"),
                        buyTime = obj.optLong("buyTime", System.currentTimeMillis()),
                        currentPrice = currentPrice,
                        pnlPercent = pnl,
                        pnlRupiah = ((currentPrice - entryPrice) * obj.getInt("lot") * 100).toLong(),
                        targetPrice = obj.optInt("targetPrice", 0),
                        stopLoss = obj.optInt("stopLoss", 0),
                        isActive = true
                    )
                }
            }
        } catch (e: Exception) {}
        return null
    }
}
