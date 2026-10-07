package com.scalping.assistant.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.scalping.assistant.R
import com.scalping.assistant.data.repository.ScoredScreenerStock
import com.scalping.assistant.engine.FibSetupState
import com.scalping.assistant.engine.StructureTrend

/**
 * Adapter daftar Screener. Elemen utama kini SKOR (0-100) karena daftar
 * sudah diurutkan dari skor tertinggi oleh ScreenerScoringEngine.
 *
 * Skor berasal dari indikator & struktur pasar (volume, bandarmology, MACD,
 * RSI, moving average, Fibonacci, SMC, struktur HH/HL) — BUKAN dari orderbook.
 * Harga acuan entry tetap dari Stockbit (data di sini delayed).
 */
class ScreenerAdapter(
    private val onItemClick: (ScoredScreenerStock) -> Unit
) : RecyclerView.Adapter<ScreenerAdapter.ViewHolder>() {

    private val items = mutableListOf<ScoredScreenerStock>()

    fun submitList(newItems: List<ScoredScreenerStock>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_screener_stock, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], position + 1)
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardScreener: CardView = itemView.findViewById(R.id.cardScreener)
        private val tvRank: TextView = itemView.findViewById(R.id.tvScreenerRank)
        private val tvTicker: TextView = itemView.findViewById(R.id.tvScreenerTicker)
        private val tvRating: TextView = itemView.findViewById(R.id.tvScreenerRating)
        private val tvPrice: TextView = itemView.findViewById(R.id.tvScreenerPrice)
        private val tvChange: TextView = itemView.findViewById(R.id.tvScreenerChange)
        private val tvVolume: TextView = itemView.findViewById(R.id.tvScreenerVolume)
        private val chipRvol: TextView = itemView.findViewById(R.id.chipScreenerRvol)
        private val chipRsi: TextView = itemView.findViewById(R.id.chipScreenerRsi)
        private val chipTrend: TextView = itemView.findViewById(R.id.chipScreenerTrend)
        private val chipEma: TextView = itemView.findViewById(R.id.chipScreenerEma)
        private val tvSector: TextView = itemView.findViewById(R.id.tvScreenerSector)

        fun bind(item: ScoredScreenerStock, rank: Int) {
            val stock = item.stock
            val score = item.score

            tvRank.text = "#$rank"
            tvTicker.text = stock.ticker

            // Badge skor: menampilkan skor + grade singkat.
            tvRating.text = "${score.gradeEmoji} ${score.total}"
            val scoreColor = when {
                score.total >= 80 -> "#10B981"
                score.total >= 65 -> "#34D399"
                score.total >= 50 -> "#F59E0B"
                score.total >= 35 -> "#FB923C"
                else -> "#EF4444"
            }
            tvRating.setTextColor(Color.parseColor(scoreColor))
            tvRating.setBackgroundResource(
                when {
                    score.total >= 65 -> R.drawable.bg_score_green
                    score.total >= 50 -> R.drawable.bg_score_yellow
                    else -> R.drawable.bg_score_red
                }
            )

            tvPrice.text = "Rp ${formatNumber(stock.close)}"

            val sign = if (stock.changePercent > 0) "+" else ""
            tvChange.text = "$sign${String.format("%.2f", stock.changePercent)}%"
            tvChange.setTextColor(
                when {
                    stock.changePercent > 0 -> Color.parseColor("#10B981")
                    stock.changePercent < 0 -> Color.parseColor("#EF4444")
                    else -> Color.parseColor("#E2E8F0")
                }
            )

            tvVolume.text = "Vol: ${formatVolume(stock.volume)}"

            chipRvol.text = "RVOL ${String.format("%.1f", stock.relativeVolume)}x"
            chipRvol.setTextColor(
                if (stock.relativeVolume >= 3.0) Color.parseColor("#38BDF8") else Color.parseColor("#94A3B8")
            )

            chipRsi.text = "RSI ${stock.rsi.toInt()}"
            chipRsi.setTextColor(
                when {
                    stock.rsi >= 75 -> Color.parseColor("#EF4444")
                    stock.rsi <= 35 -> Color.parseColor("#38BDF8")
                    else -> Color.parseColor("#94A3B8")
                }
            )

            // Chip tren: menampilkan struktur hasil SMC bila tersedia, jika tidak pakai Supertrend.
            val structure = score.structure
            if (structure != null) {
                val (label, color) = when (structure.trend) {
                    StructureTrend.BULLISH -> "HH/HL ↑" to "#10B981"
                    StructureTrend.BEARISH -> "LH/LL ↓" to "#EF4444"
                    StructureTrend.RANGING -> "RANGING" to "#F59E0B"
                }
                chipTrend.text = label
                chipTrend.setTextColor(Color.parseColor(color))
            } else {
                chipTrend.text = if (stock.isSupertrendBullish) "ST 🟢" else "ST 🔴"
                chipTrend.setTextColor(
                    Color.parseColor(if (stock.isSupertrendBullish) "#10B981" else "#EF4444")
                )
            }

            // Chip EMA: menunjuk sinyal SMC bila ada, karena lebih informatif.
            if (score.hasBullishOrderBlock) {
                chipEma.text = "OB bull ✅"
                chipEma.setTextColor(Color.parseColor("#10B981"))
            } else if (stock.isBullishEmaStack) {
                chipEma.text = "EMA9>21 ✅"
                chipEma.setTextColor(Color.parseColor("#10B981"))
            } else {
                chipEma.text = "EMA9<21"
                chipEma.setTextColor(Color.parseColor("#94A3B8"))
            }

            // Baris bawah: sektor + level Fibonacci + penanda kedalaman analisis + alasan.
            // Penanda ini penting: skor snapshot maksimal 70 (komponen candle 0),
            // sedangkan skor dengan analisis lengkap bisa mencapai 100.
            // Fibonacci DUA TARIKAN: tampilkan zona beli (tarikan premier) atau
            // zona jual (tarikan sekunder) sesuai status setup, bukan sekadar level terdekat.
            val fibText = score.fibonacci?.let { fib ->
                when (fib.state) {
                    FibSetupState.VALID -> " · 🎯 Beli ${formatNumber(fib.entryHigh)}-${formatNumber(fib.entryLow)}"
                    FibSetupState.WAITING -> " · ⏳ Tunggu koreksi 0,5"
                    FibSetupState.IN_SELL -> " · 🎯 Jual ${formatNumber(fib.sellHigh)}-${formatNumber(fib.sellLow)}"
                    FibSetupState.OVERSHOOT -> " · ⚠️ Fib gugur (>0,618)"
                    FibSetupState.NONE -> ""
                }
            } ?: ""
            val depthTag = if (item.deepAnalyzed) " · 🧠 SMC ${score.timeframe.label}" else " · snapshot"
            // Nama perusahaan ditampilkan karena pengguna bisa menemukan emiten lewat
            // fitur pencarian ("Cari kode / nama saham") tanpa tahu kodenya lebih dulu.
            val company = stock.companyName.ifEmpty { stock.name }
            val sektor = stock.sector.ifEmpty { "—" }
            tvSector.text = "$company · $sektor$fibText$depthTag · Gaya: ${score.style.label} · ${score.summary}"

            cardScreener.setOnClickListener { onItemClick(item) }
        }

        private fun formatNumber(value: Double): String =
            String.format("%,d", value.toLong()).replace(',', '.')

        private fun formatVolume(value: Long): String = when {
            value >= 1_000_000_000 -> String.format("%.1fB", value / 1_000_000_000.0)
            value >= 1_000_000 -> String.format("%.1fM", value / 1_000_000.0)
            value >= 1_000 -> String.format("%.1fK", value / 1_000.0)
            else -> value.toString()
        }
    }
}
