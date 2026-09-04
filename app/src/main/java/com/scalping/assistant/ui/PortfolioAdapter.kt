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
    private val onCutLoss: (PortfolioTrade) -> Unit
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
        private val tvPortfolioCurrentPrice: TextView = itemView.findViewById(R.id.tvPortfolioCurrentPrice)
        private val tvPortfolioLot: TextView = itemView.findViewById(R.id.tvPortfolioLot)
        private val tvPortfolioAiAction: TextView = itemView.findViewById(R.id.tvPortfolioAiAction)
        private val tvPortfolioAiReason: TextView = itemView.findViewById(R.id.tvPortfolioAiReason)
        private val tvPortfolioTarget: TextView = itemView.findViewById(R.id.tvPortfolioTarget)
        private val tvPortfolioSL: TextView = itemView.findViewById(R.id.tvPortfolioSL)
        private val tvPortfolioRecommendation: TextView = itemView.findViewById(R.id.tvPortfolioRecommendation)
        private val btnPortfolioTP: Button = itemView.findViewById(R.id.btnPortfolioTP)
        private val btnPortfolioCL: Button = itemView.findViewById(R.id.btnPortfolioCL)
        private val cardPortfolio: CardView = itemView.findViewById(R.id.cardPortfolio)

        fun bind(trade: PortfolioTrade) {
            tvPortfolioTicker.text = trade.ticker
            tvPortfolioLot.text = "${trade.lot} Lot (Rp ${formatPrice(trade.lot * 100 * trade.entryPrice / 1000)}K Modal)"
            tvPortfolioEntry.text = "Entry: Rp ${formatPrice(trade.entryPrice)}"
            tvPortfolioCurrentPrice.text = "Harga Kini: Rp ${formatPrice(trade.currentPrice)}"
            tvPortfolioTarget.text = "TP: Rp ${formatPrice(trade.targetPrice)}"
            tvPortfolioSL.text = "SL: Rp ${formatPrice(trade.stopLoss)}"

            // P&L display
            val pnlSign = if (trade.pnlPercent >= 0) "+" else ""
            val pnlColor = if (trade.pnlPercent >= 0) Color.parseColor("#10B981") else Color.parseColor("#EF4444")
            val pnlRpSign = if (trade.pnlRupiah >= 0) "+" else ""
            tvPortfolioPnl.text = "$pnlSign${String.format("%.2f", trade.pnlPercent)}% ($pnlRpSign${formatRupiah(trade.pnlRupiah)})"
            tvPortfolioPnl.setTextColor(pnlColor)

            // Card background tergantung profit/loss
            val bgColor = when {
                trade.pnlPercent >= 2.0 -> Color.parseColor("#0D2B1A") // Profit besar
                trade.pnlPercent >= 0    -> Color.parseColor("#0F1E1A") // Profit kecil
                trade.pnlPercent >= -1.5 -> Color.parseColor("#1E1010") // Loss kecil
                else                     -> Color.parseColor("#2A0A0A") // Loss besar (alert)
            }
            cardPortfolio.setCardBackgroundColor(bgColor)

            // Rekomendasi
            tvPortfolioRecommendation.text = trade.currentRecommendation.label
            tvPortfolioRecommendation.setTextColor(Color.parseColor(trade.currentRecommendation.colorCode))

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
            tvPortfolioAiReason.text = trade.aiReason

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
        }

        private fun formatPrice(price: Int): String = String.format("%,d", price).replace(',', '.')
        private fun formatRupiah(amount: Long): String {
            return if (amount >= 1_000_000L) {
                String.format("%.1fJt", amount / 1_000_000.0)
            } else {
                String.format("%,d", amount).replace(',', '.')
            }
        }
    }
}
