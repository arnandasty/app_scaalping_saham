package com.scalping.assistant.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.scalping.assistant.R
import com.scalping.assistant.data.models.Recommendation
import com.scalping.assistant.data.repository.PortfolioTrade

class PortfolioAdapter(
    private val onTakeProfit: (PortfolioTrade) -> Unit,
    private val onCutLoss: (PortfolioTrade) -> Unit,
    private val onAiConsult: (PortfolioTrade) -> Unit,
    private val onDeleteTrade: ((PortfolioTrade) -> Unit)? = null
) : RecyclerView.Adapter<PortfolioAdapter.ViewHolder>() {

    private val items = mutableListOf<PortfolioTrade>()

    fun submitList(newItems: List<PortfolioTrade>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_portfolio_trade, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvPortfolioTicker: TextView = itemView.findViewById(R.id.tvPortfolioTicker)
        private val tvPortfolioPnl: TextView = itemView.findViewById(R.id.tvPortfolioPnl)
        private val tvPortfolioEntry: TextView = itemView.findViewById(R.id.tvPortfolioEntry)
        private val tvPortfolioCurrentPriceLabel: TextView = itemView.findViewById(R.id.tvPortfolioCurrentPriceLabel)
        private val tvPortfolioCurrentPrice: TextView = itemView.findViewById(R.id.tvPortfolioCurrentPrice)
        private val tvPortfolioLot: TextView = itemView.findViewById(R.id.tvPortfolioLot)
        private val tvPortfolioAiAction: TextView = itemView.findViewById(R.id.tvPortfolioAiAction)
        private val tvPortfolioAiReason: TextView = itemView.findViewById(R.id.tvPortfolioAiReason)
        private val tvPortfolioTargetLabel: TextView = itemView.findViewById(R.id.tvPortfolioTargetLabel)
        private val tvPortfolioTarget: TextView = itemView.findViewById(R.id.tvPortfolioTarget)
        private val tvPortfolioTargetPercent: TextView = itemView.findViewById(R.id.tvPortfolioTargetPercent)
        private val tvPortfolioSLLabel: TextView = itemView.findViewById(R.id.tvPortfolioSLLabel)
        private val tvPortfolioSL: TextView = itemView.findViewById(R.id.tvPortfolioSL)
        private val tvPortfolioRecommendation: TextView = itemView.findViewById(R.id.tvPortfolioRecommendation)
        private val btnPortfolioAiConsult: Button = itemView.findViewById(R.id.btnPortfolioAiConsult)
        private val btnPortfolioTP: Button = itemView.findViewById(R.id.btnPortfolioTP)
        private val btnPortfolioCL: Button = itemView.findViewById(R.id.btnPortfolioCL)
        private val btnPortfolioDelete: Button = itemView.findViewById(R.id.btnPortfolioDelete)
        private val cardPortfolio: CardView = itemView.findViewById(R.id.cardPortfolio)

        fun bind(trade: PortfolioTrade) {
            tvPortfolioTicker.text = trade.ticker
            tvPortfolioLot.text = "${trade.lot} Lot (Rp ${formatPrice(trade.lot * 100 * trade.entryPrice / 1000)}K Modal)"
            tvPortfolioEntry.text = "Rp ${formatPrice(trade.entryPrice)}"

            // P&L display
            val pnlPct = if (trade.isActive) trade.pnlPercent else if (trade.entryPrice > 0) ((trade.closePrice - trade.entryPrice).toDouble() / trade.entryPrice) * 100 else 0.0
            val pnlRp = if (trade.isActive) trade.pnlRupiah else (trade.closePrice - trade.entryPrice).toLong() * 100 * trade.lot

            val pnlSign = if (pnlPct >= 0) "+" else ""
            val pnlColor = if (pnlPct >= 0) Color.parseColor("#10B981") else Color.parseColor("#EF4444")
            val pnlRpSign = if (pnlRp >= 0) "+" else ""
            tvPortfolioPnl.text = "$pnlSign${String.format("%.2f", pnlPct)}% ($pnlRpSign${formatRupiah(pnlRp)})"
            tvPortfolioPnl.setTextColor(pnlColor)

            // Card background tergantung profit/loss
            val bgColor = when {
                pnlPct >= 2.0 -> Color.parseColor("#0D2B1A") // Profit besar
                pnlPct >= 0.0    -> Color.parseColor("#0F1E1A") // Profit kecil
                pnlPct >= -1.5 -> Color.parseColor("#1E1010") // Loss kecil
                else                     -> Color.parseColor("#2A0A0A") // Loss besar (alert)
            }
            cardPortfolio.setCardBackgroundColor(bgColor)

            if (trade.isActive) {
                val currentOrClosePrice = trade.currentPrice
                tvPortfolioCurrentPriceLabel.text = "Harga Kini"
                tvPortfolioCurrentPrice.text = "Rp ${formatPrice(currentOrClosePrice)}"

                val tpPercent = if (trade.entryPrice > 0) ((trade.targetPrice - trade.entryPrice).toDouble() / trade.entryPrice) * 100 else 0.0
                val slPercent = if (trade.entryPrice > 0) ((trade.stopLoss - trade.entryPrice).toDouble() / trade.entryPrice) * 100 else 0.0
                tvPortfolioTargetLabel.text = "Target TP"
                tvPortfolioTarget.text = "Rp ${formatPrice(trade.targetPrice)}"
                tvPortfolioTarget.setTextColor(Color.parseColor("#10B981"))
                tvPortfolioTargetPercent.visibility = View.VISIBLE
                tvPortfolioTargetPercent.text = "+${String.format("%.1f", tpPercent)}%"
                
                tvPortfolioSLLabel.text = "Stop Loss"
                tvPortfolioSL.text = "Rp ${formatPrice(trade.stopLoss)} (${String.format("%.1f", slPercent)}%)"
                tvPortfolioSL.setTextColor(Color.parseColor("#EF4444"))

                // Rekomendasi
                tvPortfolioRecommendation.visibility = View.VISIBLE
                tvPortfolioRecommendation.text = trade.currentRecommendation.label
                tvPortfolioRecommendation.setTextColor(Color.parseColor(trade.currentRecommendation.colorCode))
                tvPortfolioRecommendation.setBackgroundResource(R.drawable.bg_score_green)

                // AI Action
                tvPortfolioAiAction.text = trade.aiAction
                val actionColor = when {
                    trade.aiAction.contains("TAKE PROFIT") -> Color.parseColor("#10B981")
                    trade.aiAction.contains("CUT LOSS") -> Color.parseColor("#EF4444")
                    trade.aiAction.contains("PERTIMBANGKAN") -> Color.parseColor("#F59E0B")
                    trade.aiAction.contains("TAMBAH") -> Color.parseColor("#10B981")
                    else -> Color.parseColor("#94A3B8")
                }
                tvPortfolioAiAction.setTextColor(actionColor)
                tvPortfolioAiReason.visibility = View.VISIBLE
                tvPortfolioAiReason.text = trade.aiReason

                btnPortfolioAiConsult.visibility = View.VISIBLE
                btnPortfolioAiConsult.setOnClickListener { onAiConsult(trade) }

                btnPortfolioTP.visibility = View.VISIBLE
                btnPortfolioCL.visibility = View.VISIBLE
                btnPortfolioDelete.visibility = View.GONE

                // Target & SL Info
                itemView.findViewById<LinearLayout>(R.id.layoutTargetSL)?.visibility = View.VISIBLE

                // Tombol TP dan CL
                btnPortfolioTP.setOnClickListener { onTakeProfit(trade) }
                btnPortfolioCL.setOnClickListener { onCutLoss(trade) }

                // Highlight tombol TP jika target sudah tercapai
                if (trade.currentPrice >= trade.targetPrice) {
                    btnPortfolioTP.setBackgroundColor(Color.parseColor("#10B981"))
                    btnPortfolioTP.text = "✅ JUAL (TP Tercapai!)"
                } else {
                    btnPortfolioTP.setBackgroundColor(Color.parseColor("#1D4ED8"))
                    btnPortfolioTP.text = "💰 TAKE PROFIT"
                }

                // Highlight tombol CL jika SL sudah tertembus
                if (trade.currentPrice <= trade.stopLoss) {
                    btnPortfolioCL.setBackgroundColor(Color.parseColor("#EF4444"))
                    btnPortfolioCL.text = "🚨 CUT LOSS (SL Tembus!)"
                } else {
                    btnPortfolioCL.setBackgroundColor(Color.parseColor("#7C3AED"))
                    btnPortfolioCL.text = "🛑 CUT LOSS"
                }
            } else {
                // TRACK RECORD (TRANSAKSI SELESAI / DITUTUP)
                val exitPrice = if (trade.closePrice > 0) trade.closePrice else trade.currentPrice
                tvPortfolioCurrentPriceLabel.text = "Harga Keluar"
                tvPortfolioCurrentPrice.text = "Rp ${formatPrice(exitPrice)}"

                val statusLabel = when (trade.status) {
                    "TP" -> "🎯 TAKE PROFIT"
                    "SL" -> "🛑 CUT LOSS"
                    else -> "DITUTUP (${trade.status})"
                }
                val statusColor = when (trade.status) {
                    "TP" -> Color.parseColor("#10B981")
                    "SL" -> Color.parseColor("#EF4444")
                    else -> Color.parseColor("#94A3B8")
                }

                tvPortfolioRecommendation.visibility = View.VISIBLE
                tvPortfolioRecommendation.text = statusLabel
                tvPortfolioRecommendation.setTextColor(statusColor)
                tvPortfolioRecommendation.setBackgroundResource(
                    when (trade.status) {
                        "TP" -> R.drawable.bg_score_green
                        "SL" -> R.drawable.bg_score_red
                        else -> R.drawable.bg_score_yellow
                    }
                )

                tvPortfolioTargetLabel.text = "Hasil Trade"
                tvPortfolioTarget.text = if (trade.status == "TP") "PROFIT" else if (trade.status == "SL") "CUT LOSS" else "REALISASI"
                tvPortfolioTarget.setTextColor(statusColor)
                tvPortfolioTargetPercent.visibility = View.GONE

                tvPortfolioSLLabel.text = "Waktu Selesai"
                val timeFormat = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault())
                tvPortfolioSL.text = if (trade.closeTime > 0) timeFormat.format(java.util.Date(trade.closeTime)) else "-"
                tvPortfolioSL.setTextColor(Color.parseColor("#94A3B8"))

                itemView.findViewById<LinearLayout>(R.id.layoutTargetSL)?.visibility = View.VISIBLE

                tvPortfolioAiAction.text = "SELESAI (Exit: Rp ${formatPrice(exitPrice)})"
                tvPortfolioAiAction.setTextColor(statusColor)

                tvPortfolioAiReason.visibility = View.VISIBLE
                tvPortfolioAiReason.text = if (trade.aiReason.isNotEmpty()) trade.aiReason else "Transaksi tersimpan di track record riwayat trading."

                btnPortfolioAiConsult.visibility = View.GONE
                btnPortfolioTP.visibility = View.GONE
                btnPortfolioCL.visibility = View.GONE

                btnPortfolioDelete.visibility = View.VISIBLE
                btnPortfolioDelete.setOnClickListener { onDeleteTrade?.invoke(trade) }
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
}
