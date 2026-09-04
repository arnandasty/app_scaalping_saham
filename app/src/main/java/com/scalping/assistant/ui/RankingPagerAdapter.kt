package com.scalping.assistant.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.scalping.assistant.data.models.StockAnalysis
import com.scalping.assistant.data.repository.PortfolioTrade

class RankingPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    private val manualFragment = RankingFragment()
    private val moversFragment = RankingFragment()
    private val topPicksFragment = RankingFragment()
    val portfolioFragment = PortfolioFragment()

    private val fragments = listOf(
        manualFragment,     // Tab 0: Manual
        moversFragment,     // Tab 1: Movers
        topPicksFragment,   // Tab 2: Top Picks
        portfolioFragment   // Tab 3: Portfolio 💼
    )

    override fun getItemCount(): Int = fragments.size

    override fun createFragment(position: Int): Fragment = fragments[position]

    fun updateManualData(data: List<StockAnalysis>) {
        manualFragment.updateData(data)
    }

    fun updateMoversData(data: List<StockAnalysis>) {
        moversFragment.updateData(data)
    }

    fun updateTopPicksData(data: List<StockAnalysis>) {
        topPicksFragment.updateData(data)
    }

    fun updatePortfolioData(trades: List<PortfolioTrade>) {
        portfolioFragment.updateData(trades)
    }
}
