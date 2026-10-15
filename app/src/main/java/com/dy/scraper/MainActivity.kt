package com.dy.scraper

import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.dy.scraper.ui.MainPagerAdapter
import com.dy.scraper.util.Logger
import com.google.android.material.tabs.TabLayoutMediator

class MainActivity : AppCompatActivity() {

    private lateinit var statusBar: TextView
    private lateinit var fileCountBar: TextView
    private lateinit var viewPager: androidx.viewpager2.widget.ViewPager2
    private var encryptCountText: String = ""
    private lateinit var pagerAdapter: MainPagerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.init(this)
        Logger.d("========== onCreate ==========")
        Logger.d("SDK_INT=${Build.VERSION.SDK_INT}, MANUFACTURER=${Build.MANUFACTURER}, MODEL=${Build.MODEL}")
        Logger.d("Log file path: ${Logger.getLogPath()}")

        setContentView(R.layout.activity_main)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        val versionName = getVersionName()
        toolbar.subtitle = "v$versionName"
        Logger.d("App version: $versionName")

        statusBar = findViewById(R.id.statusBar)
        fileCountBar = findViewById(R.id.tvFileCountBar)

        viewPager = findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.viewPager)
        val tabLayout = findViewById<com.google.android.material.tabs.TabLayout>(R.id.tabLayout)

        pagerAdapter = MainPagerAdapter(this)
        viewPager.adapter = pagerAdapter
        // 保留两个 Fragment 的视图，避免切换 tab 时销毁重建（防止 WebView/抓取任务丢失）
        viewPager.offscreenPageLimit = 2

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.tab_scrape)
                1 -> getString(R.string.tab_encrypt)
                else -> ""
            }
        }.attach()

        pagerAdapter.scrapeFrag.onStatusUpdate = { message ->
            runOnUiThread { statusBar.text = message }
        }

        // 底部状态栏右侧：加密页文件计数（只在加密页显示）
        pagerAdapter.encryptFrag.onCountUpdate = { text ->
            encryptCountText = text
            renderFileCount()
        }
        viewPager.registerOnPageChangeCallback(object :
            androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                renderFileCount()
            }
        })
        renderFileCount()

        Logger.logSection("斗虫 (Android WebView)")
        Logger.d("========== onCreate finished ==========")
    }

    /** 底部状态栏右侧的“共 N 个文件（已选 M 个）”：只在加密页显示 */
    private fun renderFileCount() {
        val show = viewPager.currentItem == 1 && encryptCountText.isNotEmpty()
        fileCountBar.visibility = if (show) View.VISIBLE else View.GONE
        if (encryptCountText.isNotEmpty()) fileCountBar.text = encryptCountText
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.toolbar_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                showOverflowMenu()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showOverflowMenu() {
        Logger.d("Overflow menu: more button clicked")
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        val anchor = toolbar.findViewById<View>(R.id.action_settings) ?: toolbar
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.settings_menu, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_log_settings -> {
                    pagerAdapter.scrapeFrag.showLogSettingsDialog()
                    true
                }
                R.id.action_mode_settings -> {
                    pagerAdapter.scrapeFrag.showModeSettingsDialog()
                    true
                }
                R.id.action_screen_on_settings -> {
                    pagerAdapter.scrapeFrag.showScreenOnSettingsDialog()
                    true
                }
                R.id.action_scraper_settings -> {
                    pagerAdapter.scrapeFrag.showScraperSettingsDialog()
                    true
                }
                R.id.action_youdao_url_settings -> {
                    pagerAdapter.scrapeFrag.showYoudaoUrlSettingsDialog()
                    true
                }
                R.id.action_about -> {
                    showAboutDialog()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showAboutDialog() {
        Logger.d("About dialog: opened")
        val dialogView = layoutInflater.inflate(R.layout.dialog_about, null)
        val unknown = getString(R.string.about_value_unknown)

        dialogView.findViewById<TextView>(R.id.tvAboutVersion).text = getString(
            R.string.about_version_line,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE
        )
        dialogView.findViewById<TextView>(R.id.tvAboutBuildType).text = BuildConfig.BUILD_TYPE
        dialogView.findViewById<TextView>(R.id.tvAboutBuildTime).text =
            BuildConfig.BUILD_TIME.ifBlank { unknown }
        dialogView.findViewById<TextView>(R.id.tvAboutGitCommit).text =
            BuildConfig.GIT_COMMIT.ifBlank { unknown }
        dialogView.findViewById<TextView>(R.id.tvAboutPackage).text = packageName
        dialogView.findViewById<TextView>(R.id.tvAboutEnv).text =
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) \u00b7 ${Build.MANUFACTURER} ${Build.MODEL}"
        dialogView.findViewById<TextView>(R.id.tvAboutLogFile).text =
            Logger.getLogPath() + if (Logger.isLogFileActive()) "" else getString(R.string.log_file_not_created)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.about_title)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun getVersionName(): String {
        return try {
            val pkgInfo = packageManager.getPackageInfo(packageName, 0)
            pkgInfo.versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    override fun onDestroy() {
        Logger.d("========== onDestroy ==========")
        super.onDestroy()
    }
}