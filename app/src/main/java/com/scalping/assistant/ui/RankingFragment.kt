package com.scalping.assistant.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.scalping.assistant.R
import com.scalping.assistant.data.models.StockAnalysis

class RankingFragment : Fragment() {

    private lateinit var rvStockRanking: RecyclerView
    private lateinit var layoutEmptyState: LinearLayout
    private lateinit var rankingAdapter: RankingAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_ranking_list, container, false)
        rvStockRanking = view.findViewById(R.id.rvStockRanking)
        layoutEmptyState = view.findViewById(R.id.layoutEmptyState)
        
        rankingAdapter = RankingAdapter { analysis ->
            (activity as? com.scalping.assistant.MainActivity)?.requestBandarDetector(analysis.ticker)
            val bottomSheet = DetailBottomSheet(analysis)
            bottomSheet.show(childFragmentManager, "DetailBottomSheet")
        }
        
        rvStockRanking.layoutManager = LinearLayoutManager(requireContext())
        rvStockRanking.adapter = rankingAdapter
        
        return view
    }

    fun updateData(analyses: List<StockAnalysis>) {
        if (!::rankingAdapter.isInitialized) return
        
        if (analyses.isEmpty()) {
            rvStockRanking.visibility = View.GONE
            layoutEmptyState.visibility = View.VISIBLE
        } else {
            rvStockRanking.visibility = View.VISIBLE
            layoutEmptyState.visibility = View.GONE
            rankingAdapter.submitList(analyses)
        }
    }
}
