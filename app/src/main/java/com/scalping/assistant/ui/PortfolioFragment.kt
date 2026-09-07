package com.scalping.assistant.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.scalping.assistant.R
import com.scalping.assistant.data.repository.PortfolioTrade

class PortfolioFragment : Fragment() {

    private lateinit var rvPortfolio: androidx.recyclerview.widget.RecyclerView
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var rgPortfolioMode: android.widget.RadioGroup
    private lateinit var portfolioAdapter: PortfolioAdapter
    private var pendingTrades: List<PortfolioTrade>? = null
    private var allTrades: List<PortfolioTrade> = emptyList()
    private var isHistoryMode = false

    var onTakeProfit: ((PortfolioTrade) -> Unit)? = null
    var onCutLoss: ((PortfolioTrade) -> Unit)? = null
    var onAiConsult: ((PortfolioTrade) -> Unit)? = null
    var onDeleteTrade: ((PortfolioTrade) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_portfolio, container, false)
        rvPortfolio = view.findViewById(R.id.rvPortfolio)
        layoutEmpty = view.findViewById(R.id.layoutPortfolioEmpty)
        rgPortfolioMode = view.findViewById(R.id.rgPortfolioMode)

        portfolioAdapter = PortfolioAdapter(
            onTakeProfit = { trade -> onTakeProfit?.invoke(trade) },
            onCutLoss = { trade -> onCutLoss?.invoke(trade) },
            onAiConsult = { trade -> onAiConsult?.invoke(trade) },
            onDeleteTrade = { trade -> onDeleteTrade?.invoke(trade) }
        )

        rvPortfolio.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        rvPortfolio.adapter = portfolioAdapter

        rgPortfolioMode.setOnCheckedChangeListener { _, checkedId ->
            isHistoryMode = checkedId == R.id.rbHistory
            refreshList()
        }

        pendingTrades?.let { updateData(it) }

        return view
    }

    fun updateData(trades: List<PortfolioTrade>) {
        if (!::portfolioAdapter.isInitialized) {
            pendingTrades = trades
            return
        }
        allTrades = trades
        refreshList()
    }

    private fun refreshList() {
        val filteredList = if (isHistoryMode) {
            allTrades.filter { !it.isActive }.sortedByDescending { it.closeTime }
        } else {
            allTrades.filter { it.isActive }
        }
        
        if (filteredList.isEmpty()) {
            rvPortfolio.visibility = View.GONE
            layoutEmpty.visibility = View.VISIBLE
        } else {
            rvPortfolio.visibility = View.VISIBLE
            layoutEmpty.visibility = View.GONE
            portfolioAdapter.submitList(filteredList)
        }
    }
}
