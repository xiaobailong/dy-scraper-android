package com.dy.scraper.ui

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.dy.scraper.MainActivity

class MainPagerAdapter(activity: MainActivity) : FragmentStateAdapter(activity) {

    private val scrapeFragment = ScrapeFragment()
    private val encryptFragment = EncryptFragment()

    val scrapeFrag: ScrapeFragment get() = scrapeFragment

    override fun getItemCount(): Int = 2

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> scrapeFragment
            1 -> encryptFragment
            else -> throw IllegalArgumentException("Invalid position: $position")
        }
    }
}