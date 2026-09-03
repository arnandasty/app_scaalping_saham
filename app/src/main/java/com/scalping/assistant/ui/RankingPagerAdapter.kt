package com.scalping.assistant.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.scalping.assistant.data.models.StockAnalysis

class RankingPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    private val fragments = listOf(
        RankingFragment(), // Tab 1: Manual
        RankingFragment(), // Tab 2: Movers
        RankingFragment()  // Tab 3: Top Picks
    )

    override fun getItemCount(): Int = fragments.size

    override fun createFragment(position: Int): Fragment = fragments[position]

    fun updateManualData(data: List<StockAnalysis>) {
        fragments[0].updateData(data)
    }

    fun updateMoversData(data: List<StockAnalysis>) {
        fragments[1].updateData(data)
    }

    fun updateTopPicksData(data: List<StockAnalysis>) {
        fragments[2].updateData(data)
    }
}
