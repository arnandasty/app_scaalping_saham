package com.scalping.assistant.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.cardview.widget.CardView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.scalping.assistant.R
import com.scalping.assistant.data.repository.PortfolioTrade
import com.scalping.assistant.engine.PriceFraction

class ExitTradeBottomSheet(
    private val trade: PortfolioTrade,
    private val isTakeProfit: Boolean,
    private val onConfirmExit: (exitPrice: Int, status: String) -> Unit
) : BottomSheetDialogFragment() {

    private var currentPrice: Int = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_exit_trade, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvExitTitle: TextView = view.findViewById(R.id.tvExitTitle)
        val badgeExitType: TextView = view.findViewById(R.id.badgeExitType)
        val tvExitTicker: TextView = view.findViewById(R.id.tvExitTicker)
        val tvExitLotInfo: TextView = view.findViewById(R.id.tvExitLotInfo)
        val tvExitEntryPrice: TextView = view.findViewById(R.id.tvExitEntryPrice)
        val tvExitMarketPrice: TextView = view.findViewById(R.id.tvExitMarketPrice)
        val tvExitPlanLabel: TextView = view.findViewById(R.id.tvExitPlanLabel)
        val tvExitPlanPrice: TextView = view.findViewById(R.id.tvExitPlanPrice)

        val btnExitMinusTick: Button = view.findViewById(R.id.btnExitMinusTick)
        val btnExitPlusTick: Button = view.findViewById(R.id.btnExitPlusTick)
        val etExitPrice: EditText = view.findViewById(R.id.etExitPrice)

        val btnPresetMarket: Button = view.findViewById(R.id.btnPresetMarket)
        val btnPresetPlan: Button = view.findViewById(R.id.btnPresetPlan)
        val btnPresetMinus5: Button = view.findViewById(R.id.btnPresetMinus5)
        val btnPresetPlus5: Button = view.findViewById(R.id.btnPresetPlus5)

        val cardExitPreview: CardView = view.findViewById(R.id.cardExitPreview)
        val tvExitPreviewPnl: TextView = view.findViewById(R.id.tvExitPreviewPnl)
        val tvExitPreviewProceeds: TextView = view.findViewById(R.id.tvExitPreviewProceeds)
        val tvExitNotice: TextView = view.findViewById(R.id.tvExitNotice)

        val btnExitCancel: Button = view.findViewById(R.id.btnExitCancel)
        val btnExitConfirm: Button = view.findViewById(R.id.btnExitConfirm)

        // Setup Header & Ticker info
        tvExitTicker.text = trade.ticker
        val totalCapital = trade.lot * 100L * trade.entryPrice
        tvExitLotInfo.text = "${trade.lot} Lot (${formatRupiah(totalCapital)})"
        tvExitEntryPrice.text = "Rp ${formatPrice(trade.entryPrice)}"
        tvExitMarketPrice.text = "Rp ${formatPrice(trade.currentPrice)}"

        if (isTakeProfit) {
            tvExitTitle.text = "💰 Realisasi Take Profit"
            badgeExitType.text = "TAKE PROFIT"
            badgeExitType.setBackgroundResource(R.drawable.bg_score_green)
            badgeExitType.setTextColor(Color.parseColor("#10B981"))

            tvExitPlanLabel.text = "Target TP Rencana"
            tvExitPlanPrice.text = "Rp ${formatPrice(trade.targetPrice)}"
            tvExitPlanPrice.setTextColor(Color.parseColor("#10B981"))

            btnExitConfirm.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#10B981"))
            btnExitConfirm.text = "✅ Konfirmasi Take Profit"

            // Default initial price for TP
            currentPrice = when {
                trade.currentPrice >= trade.entryPrice -> trade.currentPrice
                trade.targetPrice > 0 -> trade.targetPrice
                else -> trade.entryPrice
            }
        } else {
            tvExitTitle.text = "🛑 Realisasi Cut Loss"
            badgeExitType.text = "CUT LOSS"
            badgeExitType.setBackgroundResource(R.drawable.bg_score_red)
            badgeExitType.setTextColor(Color.parseColor("#EF4444"))

            tvExitPlanLabel.text = "Stop Loss Rencana"
            tvExitPlanPrice.text = "Rp ${formatPrice(trade.stopLoss)}"
            tvExitPlanPrice.setTextColor(Color.parseColor("#EF4444"))

            btnExitConfirm.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
            btnExitConfirm.text = "🛑 Konfirmasi Cut Loss"

            // Default initial price for SL
            currentPrice = when {
                trade.currentPrice <= trade.entryPrice -> trade.currentPrice
                trade.stopLoss > 0 -> trade.stopLoss
                else -> trade.entryPrice
            }
        }

        // Setup Quick Preset buttons
        btnPresetMarket.text = "Pasar: ${formatPrice(trade.currentPrice)}"
        btnPresetMarket.setOnClickListener {
            currentPrice = trade.currentPrice
            etExitPrice.setText(currentPrice.toString())
            etExitPrice.setSelection(etExitPrice.text.length)
        }

        val planPrice = if (isTakeProfit) trade.targetPrice else trade.stopLoss
        val planLabel = if (isTakeProfit) "Target" else "SL"
        btnPresetPlan.text = "$planLabel: ${formatPrice(planPrice)}"
        btnPresetPlan.setOnClickListener {
            currentPrice = planPrice
            etExitPrice.setText(currentPrice.toString())
            etExitPrice.setSelection(etExitPrice.text.length)
        }

        btnPresetMinus5.setOnClickListener {
            currentPrice = PriceFraction.getNextTickDown(currentPrice, 5)
            etExitPrice.setText(currentPrice.toString())
            etExitPrice.setSelection(etExitPrice.text.length)
        }

        btnPresetPlus5.setOnClickListener {
            currentPrice = PriceFraction.getNextTickUp(currentPrice, 5)
            etExitPrice.setText(currentPrice.toString())
            etExitPrice.setSelection(etExitPrice.text.length)
        }

        btnExitMinusTick.setOnClickListener {
            currentPrice = PriceFraction.getNextTickDown(currentPrice, 1)
            etExitPrice.setText(currentPrice.toString())
            etExitPrice.setSelection(etExitPrice.text.length)
        }

        btnExitPlusTick.setOnClickListener {
            currentPrice = PriceFraction.getNextTickUp(currentPrice, 1)
            etExitPrice.setText(currentPrice.toString())
            etExitPrice.setSelection(etExitPrice.text.length)
        }

        // Live calculation helper
        fun updatePreview(price: Int) {
            if (price <= 0) {
                tvExitPreviewPnl.text = "0.00% (Rp 0)"
                tvExitPreviewProceeds.text = "Modal Balik: Rp 0"
                btnExitConfirm.isEnabled = false
                return
            }

            btnExitConfirm.isEnabled = true
            val diff = price - trade.entryPrice
            val pnlPct = if (trade.entryPrice > 0) (diff.toDouble() / trade.entryPrice) * 100 else 0.0
            val pnlRp = diff.toLong() * trade.lot * 100
            val totalProceeds = price.toLong() * trade.lot * 100

            val sign = if (pnlPct >= 0) "+" else ""
            val rpSign = if (pnlRp >= 0) "+" else ""
            tvExitPreviewPnl.text = "$sign${String.format("%.2f", pnlPct)}% ($rpSign${formatRupiah(pnlRp)})"

            val pnlColor = if (pnlPct >= 0) Color.parseColor("#10B981") else Color.parseColor("#EF4444")
            tvExitPreviewPnl.setTextColor(pnlColor)

            val cardBg = if (pnlPct >= 0) Color.parseColor("#0D2B1A") else Color.parseColor("#2A0A0A")
            cardExitPreview.setCardBackgroundColor(cardBg)

            tvExitPreviewProceeds.text = "Dana Kembali: ${formatRupiah(totalProceeds)}"

            if (isTakeProfit && price < trade.entryPrice) {
                tvExitNotice.text = "⚠️ Perhatian: Harga ini di bawah harga beli. Posisi dicatat Loss."
                tvExitNotice.setTextColor(Color.parseColor("#EF4444"))
                btnExitConfirm.text = "⚠️ Simpan TP (Rp ${formatPrice(price)})"
            } else if (!isTakeProfit && price > trade.entryPrice) {
                tvExitNotice.text = "ℹ️ Info: Harga ini di atas harga beli. Posisi dicatat Profit."
                tvExitNotice.setTextColor(Color.parseColor("#10B981"))
                btnExitConfirm.text = "✅ Simpan Cut Loss (Rp ${formatPrice(price)})"
            } else {
                tvExitNotice.text = "Harga eksekusi ini akan disimpan permanen pada riwayat trading."
                tvExitNotice.setTextColor(Color.parseColor("#94A3B8"))
                val actionPrefix = if (isTakeProfit) "✅ Simpan TP" else "🛑 Simpan Cut Loss"
                btnExitConfirm.text = "$actionPrefix (Rp ${formatPrice(price)})"
            }
        }

        // Set initial text & trigger preview
        etExitPrice.setText(currentPrice.toString())
        etExitPrice.setSelection(etExitPrice.text.length)
        updatePreview(currentPrice)

        // Text watcher for direct editing
        etExitPrice.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val parsed = s?.toString()?.toIntOrNull() ?: 0
                currentPrice = parsed
                updatePreview(parsed)
            }
        })

        btnExitCancel.setOnClickListener {
            dismiss()
        }

        btnExitConfirm.setOnClickListener {
            val finalPrice = etExitPrice.text.toString().toIntOrNull() ?: currentPrice
            if (finalPrice <= 0) return@setOnClickListener
            val finalStatus = if (isTakeProfit) "TP" else "SL"
            onConfirmExit(finalPrice, finalStatus)
            dismiss()
        }
    }

    private fun formatPrice(price: Int): String = String.format("%,d", price).replace(',', '.')

    private fun formatRupiah(amount: Long): String {
        val absAmount = kotlin.math.abs(amount)
        val prefix = if (amount < 0) "-Rp " else "Rp "
        return if (absAmount >= 1_000_000L) {
            String.format("%s%.1fJt", prefix, absAmount / 1_000_000.0)
        } else {
            prefix + String.format("%,d", absAmount).replace(',', '.')
        }
    }
}
