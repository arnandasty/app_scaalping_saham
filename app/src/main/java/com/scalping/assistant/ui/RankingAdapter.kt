package com.scalping.assistant.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.scalping.assistant.R
import com.scalping.assistant.data.models.Recommendation
import com.scalping.assistant.data.models.StockAnalysis

class RankingAdapter(
    private val onItemClick: (StockAnalysis) -> Unit
) : RecyclerView.Adapter<RankingAdapter.ViewHolder>() {

    private val items = mutableListOf<StockAnalysis>()

    fun submitList(newItems: List<StockAnalysis>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_stock_ranking, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], position + 1)
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardStock: CardView = itemView.findViewById(R.id.cardStock)
        private val tvRank: TextView = itemView.findViewById(R.id.tvRank)
        private val tvTicker: TextView = itemView.findViewById(R.id.tvTicker)
        private val tvRecommendation: TextView = itemView.findViewById(R.id.tvRecommendation)
        private val tvScore: TextView = itemView.findViewById(R.id.tvScore)
        private val tvProfitEst: TextView = itemView.findViewById(R.id.tvProfitEst)
        private val tvEntry: TextView = itemView.findViewById(R.id.tvEntry)
        private val tvTarget: TextView = itemView.findViewById(R.id.tvTarget)
        private val tvStopLoss: TextView = itemView.findViewById(R.id.tvStopLoss)
        private val tvRRRatio: TextView = itemView.findViewById(R.id.tvRRRatio)
        private val chipEMA: TextView = itemView.findViewById(R.id.chipEMA)
        private val chipMACD: TextView = itemView.findViewById(R.id.chipMACD)
        private val chipSupertrend: TextView = itemView.findViewById(R.id.chipSupertrend)
        private val chipBB: TextView = itemView.findViewById(R.id.chipBB)
        private val chipBandar: TextView = itemView.findViewById(R.id.chipBandar)
        private val tvPrimaryReason: TextView = itemView.findViewById(R.id.tvPrimaryReason)

        fun bind(item: StockAnalysis, rank: Int) {
            // Cek apakah ini mode BSJP
            val isBSJP = item.style.startsWith("BSJP")
            if (isBSJP) {
                // Warna background malam/ungu untuk BSJP
                cardStock.setCardBackgroundColor(Color.parseColor("#1E1B4B"))
            } else {
                // Default warna card scalping
                cardStock.setCardBackgroundColor(Color.parseColor("#172036"))
            }

            tvRank.text = "#$rank"
            
            val sign = if (item.changePercent > 0) "+" else ""
            val priceFormatted = formatPrice(item.lastPrice)
            tvTicker.text = "${item.ticker} Rp $priceFormatted ($sign${String.format("%.2f", item.changePercent)}%)"
            
            if (item.changePercent > 0) {
                tvTicker.setTextColor(Color.parseColor("#10B981")) // Green
            } else if (item.changePercent < 0) {
                tvTicker.setTextColor(Color.parseColor("#EF4444")) // Red
            } else {
                tvTicker.setTextColor(Color.parseColor("#E2E8F0")) // Default/Primary
            }

            tvScore.text = "Skor: ${item.score}/100"

            if (isBSJP) {
                tvRecommendation.text = "🌙 ${item.recommendation.label} BSJP"
            } else {
                tvRecommendation.text = item.recommendation.label
            }
            val recColor = Color.parseColor(item.recommendation.colorCode)
            tvRecommendation.setTextColor(recColor)
            tvScore.setTextColor(recColor)

            when (item.recommendation) {
                Recommendation.STRONG_BUY, Recommendation.BUY -> {
                    tvRecommendation.setBackgroundResource(R.drawable.bg_score_green)
                }
                Recommendation.WATCH -> {
                    tvRecommendation.setBackgroundResource(R.drawable.bg_score_yellow)
                }
                Recommendation.AVOID -> {
                    tvRecommendation.setBackgroundResource(R.drawable.bg_score_red)
                }
            }

            tvProfitEst.text = "Target: +${String.format("%.2f", item.estimatedProfitPercent)}%"
            tvEntry.text = "Entry: ${formatPrice(item.entryPrice)}"
            tvTarget.text = "Target: ${formatPrice(item.targetPrice)}"
            tvStopLoss.text = "SL: ${formatPrice(item.stopLoss)}"
            tvRRRatio.text = "RR: 1:${item.riskRewardRatio}"

            // Technical Chips
            if (item.technical.emaScore >= 4) {
                chipEMA.text = "EMA 9/21 ✅"
                chipEMA.setTextColor(Color.parseColor("#10B981"))
            } else {
                chipEMA.text = "EMA 9/21"
                chipEMA.setTextColor(Color.parseColor("#94A3B8"))
            }

            if (item.technical.macdScore >= 4) {
                chipMACD.text = "MACD ✅"
                chipMACD.setTextColor(Color.parseColor("#10B981"))
            } else {
                chipMACD.text = "MACD"
                chipMACD.setTextColor(Color.parseColor("#94A3B8"))
            }

            if (item.technical.isSupertrendBullish) {
                chipSupertrend.text = "ST 🟢"
                chipSupertrend.setTextColor(Color.parseColor("#10B981"))
            } else {
                chipSupertrend.text = "ST 🔴"
                chipSupertrend.setTextColor(Color.parseColor("#EF4444"))
            }

            if (item.technical.bbScore >= 4) {
                chipBB.text = "BB: Lower"
                chipBB.setTextColor(Color.parseColor("#38BDF8"))
            } else {
                chipBB.text = "BB: Mid/Up"
                chipBB.setTextColor(Color.parseColor("#94A3B8"))
            }

            // Bandar Detector Chip
            val bStat = item.bandarDetector
            if (bStat != null && bStat.accdistStatus.isNotEmpty() && bStat.accdistStatus != "Neutral") {
                chipBandar.visibility = View.VISIBLE
                val avgPrice = if (bStat.averagePrice > 0) " (${bStat.averagePrice.toInt()})" else ""
                when (bStat.accdistStatus) {
                    "Big Acc" -> {
                        chipBandar.text = "🟢 Big Acc$avgPrice"
                        chipBandar.setTextColor(Color.parseColor("#10B981"))
                    }
                    "Acc" -> {
                        chipBandar.text = "🟢 Acc$avgPrice"
                        chipBandar.setTextColor(Color.parseColor("#34D399"))
                    }
                    "Big Dist" -> {
                        chipBandar.text = "🔴 Big Dist$avgPrice"
                        chipBandar.setTextColor(Color.parseColor("#EF4444"))
                    }
                    "Dist" -> {
                        chipBandar.text = "🔴 Dist$avgPrice"
                        chipBandar.setTextColor(Color.parseColor("#F87171"))
                    }
                    else -> {
                        chipBandar.text = "⚪ ${bStat.accdistStatus}"
                        chipBandar.setTextColor(Color.parseColor("#94A3B8"))
                    }
                }
            } else {
                chipBandar.visibility = View.GONE
            }

            val reason = item.reasons.firstOrNull() ?: "Analisis Orderbook realtime."
            tvPrimaryReason.text = reason

            cardStock.setOnClickListener {
                onItemClick(item)
            }
        }

        private fun formatPrice(price: Int): String {
            return String.format("%,d", price).replace(',', '.')
        }
    }
}
