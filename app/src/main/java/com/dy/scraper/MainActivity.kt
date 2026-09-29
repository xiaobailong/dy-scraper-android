package com.dy.scraper

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.dy.scraper.core.ScraperEngine
import com.dy.scraper.core.WebViewManager
import com.dy.scraper.util.AppConfig
import com.dy.scraper.util.Logger
import com.dy.scraper.util.RunMode
import com.dy.scraper.util.Utils
import com.dy.scraper.util.YoudaoFetcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var scrollView: android.widget.ScrollView
    private lateinit var scraperWebView: WebView
    private lateinit var statusBar: TextView

    private var currentMode: RunMode = RunMode.LOCAL

    private lateinit var llLocalMode: View
    private lateinit var etUrlInput: EditText
    private lateinit var btnLocalStart: Button
    private lateinit var btnLocalStop: Button
    private lateinit var btnLocalSave: Button

    private lateinit var llYoudaoMode: View
    private lateinit var btnFetchYoudao: Button
    private lateinit var tvYoudaoLabel: TextView
    private lateinit var tvYoudaoUrlList: TextView
    private lateinit var llYoudaoButtons: View
    private lateinit var btnYoudaoStart: Button
    private lateinit var btnYoudaoStop: Button

    private var youdaoUrls: List<String> = emptyList()

    private var webViewManager: WebViewManager? = null
    private var scrapeJob: Job? = null

    /** 日志监听器（保存引用，onDestroy 时移除，避免内存泄漏） */
    private val logListener: (String) -> Unit = { message ->
        runOnUiThread {
            logView.append("$message\n")
            scrollView.post {
                scrollView.fullScroll(android.widget.ScrollView.FOCUS_DOWN)
            }
        }
    }

    /** 文件夹选择器回写目标 EditText（临时引用，dialog 关闭后置空） */
    private var pendingFolderEditText: EditText? = null

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { treeUri ->
            val path = uriToFilePath(treeUri)
            if (path != null) {
                pendingFolderEditText?.setText(path)
            } else {
                Toast.makeText(this, "无法解析所选路径，请手动输入", Toast.LENGTH_SHORT).show()
            }
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    private fun uriToFilePath(uri: Uri): String? {
        try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val split = docId.split(":")
            if (split.size >= 2 && split[0].equals("primary", ignoreCase = true)) {
                return "/storage/emulated/0/${split[1]}".trimEnd('/')
            }
        } catch (_: Exception) {
        }
        return null
    }

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

        scrollView = findViewById(R.id.scrollView)
        logView = findViewById(R.id.logView)
        scraperWebView = findViewById(R.id.scraperWebView)
        statusBar = findViewById(R.id.statusBar)

        llLocalMode = findViewById(R.id.llLocalMode)
        etUrlInput = findViewById(R.id.etUrlInput)
        btnLocalStart = findViewById(R.id.btnLocalStart)
        btnLocalStop = findViewById(R.id.btnLocalStop)
        btnLocalSave = findViewById(R.id.btnLocalSave)

        llYoudaoMode = findViewById(R.id.llYoudaoMode)
        btnFetchYoudao = findViewById(R.id.btnFetchYoudao)
        tvYoudaoLabel = findViewById(R.id.tvYoudaoLabel)
        tvYoudaoUrlList = findViewById(R.id.tvYoudaoUrlList)
        llYoudaoButtons = findViewById(R.id.llYoudaoButtons)
        btnYoudaoStart = findViewById(R.id.btnYoudaoStart)
        btnYoudaoStop = findViewById(R.id.btnYoudaoStop)

        setupButtons()
        setupLogger()
        applyMode(RunMode.fromPrefs(this))

        Logger.logSection("斗虫 (Android WebView)")
        Logger.d("========== onCreate finished ==========")
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
                    showLogSettingsDialog()
                    true
                }
                R.id.action_mode_settings -> {
                    showModeSettingsDialog()
                    true
                }
                R.id.action_screen_on_settings -> {
                    showScreenOnSettingsDialog()
                    true
                }
                R.id.action_scraper_settings -> {
                    showScraperSettingsDialog()
                    true
                }
                R.id.action_youdao_url_settings -> {
                    showYoudaoUrlSettingsDialog()
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

    private fun showLogSettingsDialog() {
        Logger.d("Log settings dialog: opened")
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val switchLogOutput = dialogView.findViewById<SwitchCompat>(R.id.switchLogOutput)
        val tvLogPath = dialogView.findViewById<TextView>(R.id.tvLogPath)

        switchLogOutput.isChecked = Logger.isEnabled()
        tvLogPath.text = logPathText()

        switchLogOutput.setOnCheckedChangeListener { _, isChecked ->
            Logger.setEnabled(this, isChecked)
            tvLogPath.text = logPathText()
            Logger.d("Log settings dialog: log output changed to $isChecked")
            Toast.makeText(
                this,
                if (isChecked) R.string.settings_log_on else R.string.settings_log_off,
                Toast.LENGTH_SHORT
            ).show()
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_log_title)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showScreenOnSettingsDialog() {
        Logger.d("Screen-on settings dialog: opened")
        val dialogView = layoutInflater.inflate(R.layout.dialog_screen_on_settings, null)
        val switchScreenOn = dialogView.findViewById<SwitchCompat>(R.id.switchScreenOn)

        switchScreenOn.isChecked = AppConfig.isKeepScreenOnEnabled(this)

        switchScreenOn.setOnCheckedChangeListener { _, isChecked ->
            AppConfig.setKeepScreenOnEnabled(this, isChecked)
            Logger.d("Screen-on settings dialog: screen-on changed to $isChecked")
            Toast.makeText(
                this,
                if (isChecked) R.string.settings_screen_on_on else R.string.settings_screen_on_off,
                Toast.LENGTH_SHORT
            ).show()
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_screen_on_title)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showScraperSettingsDialog() {
        Logger.d("Scraper settings dialog: opened")
        val dialogView = layoutInflater.inflate(R.layout.dialog_scraper_settings, null)
        val etPageLoadTimeout = dialogView.findViewById<EditText>(R.id.etPageLoadTimeout)
        val etRenderWait = dialogView.findViewById<EditText>(R.id.etRenderWait)
        val etDetailApiWait = dialogView.findViewById<EditText>(R.id.etDetailApiWait)
        val etDownloadTimeout = dialogView.findViewById<EditText>(R.id.etDownloadTimeout)
        val etMaxVideoWorkers = dialogView.findViewById<EditText>(R.id.etMaxVideoWorkers)
        val etMaxImageWorkers = dialogView.findViewById<EditText>(R.id.etMaxImageWorkers)
        val etDownloadRootPath = dialogView.findViewById<EditText>(R.id.etDownloadRootPath)
        val btnSelectFolder = dialogView.findViewById<Button>(R.id.btnSelectFolder)
        val etImageSizeFilter = dialogView.findViewById<EditText>(R.id.etImageSizeFilter)
        val etVideoSizeFilter = dialogView.findViewById<EditText>(R.id.etVideoSizeFilter)
        val switchUrlDedup = dialogView.findViewById<SwitchCompat>(R.id.switchUrlDedup)
        val switchNetworkLog = dialogView.findViewById<SwitchCompat>(R.id.switchNetworkLog)

        etPageLoadTimeout.setText(AppConfig.getPageLoadTimeoutMs(this).toString())
        etRenderWait.setText(AppConfig.getRenderWaitMs(this).toString())
        etDetailApiWait.setText(AppConfig.getDetailApiWaitMs(this).toString())
        etDownloadTimeout.setText(AppConfig.getDownloadTimeoutSeconds(this).toString())
        etMaxVideoWorkers.setText(AppConfig.getMaxVideoWorkers(this).toString())
        etMaxImageWorkers.setText(AppConfig.getMaxImageWorkers(this).toString())
        etDownloadRootPath.setText(AppConfig.getDownloadRootPath(this))
        etImageSizeFilter.setText(AppConfig.getImageSizeFilterKb(this).toString())
        etVideoSizeFilter.setText(AppConfig.getVideoSizeFilterMb(this).toString())
        switchUrlDedup.isChecked = AppConfig.isUrlDedupEnabled(this)
        switchNetworkLog.isChecked = AppConfig.isNetworkLogEnabled(this)

        btnSelectFolder.setOnClickListener {
            pendingFolderEditText = etDownloadRootPath
            folderPickerLauncher.launch(null)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.scraper_settings_title)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val pageLoadTimeout = etPageLoadTimeout.text.toString().toLongOrNull()
                val renderWait = etRenderWait.text.toString().toLongOrNull()
                val detailApiWait = etDetailApiWait.text.toString().toLongOrNull()
                val downloadTimeout = etDownloadTimeout.text.toString().toIntOrNull()
                val maxVideoWorkers = etMaxVideoWorkers.text.toString().toIntOrNull()
                val maxImageWorkers = etMaxImageWorkers.text.toString().toIntOrNull()
                val imageSizeFilter = etImageSizeFilter.text.toString().toLongOrNull()
                val videoSizeFilter = etVideoSizeFilter.text.toString().toLongOrNull()

                if (listOf(pageLoadTimeout, renderWait, detailApiWait, downloadTimeout, maxVideoWorkers, maxImageWorkers, imageSizeFilter, videoSizeFilter).any { it == null }) {
                    Toast.makeText(this, R.string.scraper_settings_param_invalid, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }

                AppConfig.setPageLoadTimeoutMs(this, pageLoadTimeout!!)
                AppConfig.setRenderWaitMs(this, renderWait!!)
                AppConfig.setDetailApiWaitMs(this, detailApiWait!!)
                AppConfig.setDownloadTimeoutSeconds(this, downloadTimeout!!)
                AppConfig.setMaxVideoWorkers(this, maxVideoWorkers!!)
                AppConfig.setMaxImageWorkers(this, maxImageWorkers!!)
                AppConfig.setImageSizeFilterKb(this, imageSizeFilter!!)
                AppConfig.setVideoSizeFilterMb(this, videoSizeFilter!!)

                val rootPath = etDownloadRootPath.text.toString().trim()
                if (rootPath.isNotEmpty() && rootPath != AppConfig.getDownloadRootPath(this)) {
                    AppConfig.setDownloadRootPath(this, rootPath)
                    AppConfig.initDirs(this)
                }

                AppConfig.setUrlDedupEnabled(this, switchUrlDedup.isChecked)
                AppConfig.setNetworkLogEnabled(this, switchNetworkLog.isChecked)
                Logger.d("Scraper settings dialog: urlDedup=${switchUrlDedup.isChecked} netLog=${switchNetworkLog.isChecked}")

                Toast.makeText(this, R.string.scraper_settings_saved, Toast.LENGTH_SHORT).show()
                Logger.d("Scraper settings dialog: saved pageLoad=$pageLoadTimeout render=$renderWait detail=$detailApiWait dlTimeout=$downloadTimeout videoW=$maxVideoWorkers imageW=$maxImageWorkers imgSizeFilter=$imageSizeFilter videoSizeFilter=$videoSizeFilter rootPath=$rootPath")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showModeSettingsDialog() {
        Logger.d("Mode settings dialog: opened")
        val dialogView = layoutInflater.inflate(R.layout.dialog_mode_settings, null)
        val rgRunMode = dialogView.findViewById<android.widget.RadioGroup>(R.id.rgRunMode)
        val rbLocal = dialogView.findViewById<android.widget.RadioButton>(R.id.rbModeLocal)
        val rbYoudao = dialogView.findViewById<android.widget.RadioButton>(R.id.rbModeYoudao)

        when (currentMode) {
            RunMode.LOCAL -> rbLocal.isChecked = true
            RunMode.YOUDAO -> rbYoudao.isChecked = true
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_mode_title)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newMode = when (rgRunMode.checkedRadioButtonId) {
                    R.id.rbModeLocal -> RunMode.LOCAL
                    R.id.rbModeYoudao -> RunMode.YOUDAO
                    else -> currentMode
                }
                if (newMode != currentMode) {
                    applyMode(newMode)
                    RunMode.saveToPrefs(this, newMode)
                    val label = when (newMode) {
                        RunMode.LOCAL -> getString(R.string.mode_local)
                        RunMode.YOUDAO -> getString(R.string.mode_youdao)
                    }
                    Toast.makeText(
                        this,
                        getString(R.string.mode_switch_toast, label),
                        Toast.LENGTH_SHORT
                    ).show()
                    Logger.d("Mode switched to: $label")
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showYoudaoUrlSettingsDialog() {
        Logger.d("Youdao URL settings dialog: opened")
        val dialogView = layoutInflater.inflate(R.layout.dialog_youdao_url, null)
        val etYoudaoUrl = dialogView.findViewById<EditText>(R.id.etYoudaoUrl)

        etYoudaoUrl.setText(YoudaoFetcher.getDisplayApiUrl(this))

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_youdao_url_title)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newUrl = etYoudaoUrl.text.toString().trim()
                if (YoudaoFetcher.saveApiUrl(this, newUrl)) {
                    Toast.makeText(
                        this,
                        getString(R.string.settings_youdao_url_saved),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this,
                        getString(R.string.settings_youdao_url_invalid),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyMode(mode: RunMode) {
        currentMode = mode
        when (mode) {
            RunMode.LOCAL -> {
                llLocalMode.visibility = View.VISIBLE
                llYoudaoMode.visibility = View.GONE
                loadDraft()
            }
            RunMode.YOUDAO -> {
                llLocalMode.visibility = View.GONE
                llYoudaoMode.visibility = View.VISIBLE
                tvYoudaoLabel.visibility = View.GONE
                tvYoudaoUrlList.visibility = View.GONE
                llYoudaoButtons.visibility = View.VISIBLE
                updateStartStopButtons(running = false)
            }
        }
    }

    private fun saveDraft() {
        val text = etUrlInput.text.toString()
        getSharedPreferences(Logger.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(RunMode.PREFS_KEY_DRAFT_URLS, text)
            .apply()
        Toast.makeText(this, R.string.draft_saved, Toast.LENGTH_SHORT).show()
        Logger.d("Draft saved: ${text.lines().size} lines")
    }

    private fun loadDraft() {
        val text = getSharedPreferences(Logger.PREFS_NAME, MODE_PRIVATE)
            .getString(RunMode.PREFS_KEY_DRAFT_URLS, "") ?: ""
        if (text.isNotBlank()) {
            etUrlInput.setText(text)
            Logger.d("Draft loaded: ${text.lines().size} lines")
        }
    }

    private fun logPathText(): String {
        val path = Logger.getLogPath()
        val suffix = if (Logger.isLogFileActive()) "" else getString(R.string.log_file_not_created)
        return getString(R.string.settings_log_path, path + suffix)
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

        AlertDialog.Builder(this)
            .setTitle(R.string.about_title)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun setupButtons() {
        btnLocalSave.setOnClickListener { saveDraft() }
        btnLocalStart.setOnClickListener {
            val urls = parseUrlInput()
            if (urls.isEmpty()) {
                Toast.makeText(this, "请输入至少一个链接", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startScraper(urls)
        }
        btnLocalStop.setOnClickListener { stopScraper() }

        btnFetchYoudao.setOnClickListener { fetchYoudaoUrls() }
        btnYoudaoStart.setOnClickListener {
            if (youdaoUrls.isEmpty()) {
                Toast.makeText(this, "请先获取URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startScraper(youdaoUrls)
        }
        btnYoudaoStop.setOnClickListener { stopScraper() }
    }

    private fun parseUrlInput(): List<String> {
        return Utils.extractUrls(etUrlInput.text.toString())
    }

    private fun fetchYoudaoUrls() {
        Logger.logSection("从有道获取URL")
        val apiUrl = YoudaoFetcher.getApiUrl(this)
        Logger.log("  有道API地址: $apiUrl")
        Toast.makeText(this, R.string.label_youdao_fetching, Toast.LENGTH_SHORT).show()
        tvYoudaoLabel.visibility = View.VISIBLE
        tvYoudaoLabel.text = getString(R.string.label_youdao_fetching)
        tvYoudaoUrlList.visibility = View.GONE
        btnYoudaoStart.isEnabled = false

        lifecycleScope.launch {
            val result = YoudaoFetcher.fetchUrls(this@MainActivity)
            if (result.error != null) {
                Logger.log("  有道获取失败: ${result.error}", "error")
                tvYoudaoLabel.text = getString(R.string.label_youdao_error, result.error)
                tvYoudaoUrlList.visibility = View.GONE
                btnYoudaoStart.isEnabled = false
                return@launch
            }
            if (result.urls.isEmpty()) {
                Logger.log("  有道返回空URL列表")
                tvYoudaoLabel.text = getString(R.string.label_youdao_empty)
                tvYoudaoUrlList.visibility = View.GONE
                btnYoudaoStart.isEnabled = false
                return@launch
            }

            youdaoUrls = result.urls
            Logger.log("  有道获取成功，共 ${result.urls.size} 个URL")
            tvYoudaoLabel.text = getString(R.string.label_url_list, result.urls.size)
            tvYoudaoUrlList.text = result.urls.joinToString("\n")
            tvYoudaoUrlList.visibility = View.VISIBLE
            btnYoudaoStart.isEnabled = true
        }
    }

    private fun startScraper(urls: List<String>) {
        if (!Logger.isEnabled()) {
            Logger.d("Log was disabled before scraper start")
        }
        Logger.logSection("启动抓取任务（Activity 直接驱动）")
        Logger.log("启动抓取任务，共 ${urls.size} 个 URL")
        Logger.log("  当前模式: ${currentMode.key}")
        urls.forEach { Logger.log("  $it") }

        updateStartStopButtons(running = true)
        updateStatus(getString(R.string.status_scraping))

        // 抓取过程中保持屏幕常亮，防止锁屏中断（受设置开关控制）
        if (AppConfig.isKeepScreenOnEnabled(this)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        // 显示 WebView（用户可观察抓取效果）
        scraperWebView.visibility = View.VISIBLE

        // 创建 WebViewManager 使用布局中已 attached 的 WebView
        val wvm = WebViewManager(this, scraperWebView)
        webViewManager = wvm

        scrapeJob = lifecycleScope.launch {
            var successCount = 0
            var failedCount = 0
            var completed = false
            try {
                val output = ScraperEngine.run(
                    context = this@MainActivity,
                    webViewManager = wvm,
                    input = ScraperEngine.Input(
                        urls = urls,
                        reportProgress = { progress ->
                            Logger.log("进度: ${progress.current}/${progress.total} (${progress.pct}%)")
                        },
                    ),
                )
                successCount = output.stats.successCount
                failedCount = output.stats.failedCount
                completed = true
                Logger.log("")
                Logger.log("抓取完成! 成功: $successCount, 失败: $failedCount")
            } catch (e: kotlinx.coroutines.CancellationException) {
                Logger.log("  抓取任务被用户取消")
                throw e
            } catch (e: Exception) {
                Logger.log("抓取异常: ${e.message}", "error")
            } finally {
                scraperWebView.visibility = View.GONE
                updateStartStopButtons(running = false)
                if (completed) {
                    updateStatus(getString(R.string.status_done, successCount, failedCount))
                }
                if (AppConfig.isKeepScreenOnEnabled(this@MainActivity)) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                wvm.cleanup()
                webViewManager = null
            }
        }
    }

    private fun stopScraper() {
        Logger.logSection("停止抓取任务")
        Logger.log("取消抓取协程...")
        scrapeJob?.cancel()
        scraperWebView.visibility = View.GONE
        updateStartStopButtons(running = false)
        updateStatus(getString(R.string.status_cancelled))
        if (AppConfig.isKeepScreenOnEnabled(this)) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        webViewManager?.cleanup()
        webViewManager = null
        Logger.log("  抓取任务已取消")
    }

    private fun updateStartStopButtons(running: Boolean) {
        Logger.d("updateStartStopButtons: running=$running, mode=${currentMode.key}")
        when (currentMode) {
            RunMode.LOCAL -> {
                btnLocalStart.isEnabled = !running
                btnLocalStop.isEnabled = running
            }
            RunMode.YOUDAO -> {
                btnYoudaoStart.isEnabled = !running && youdaoUrls.isNotEmpty()
                btnYoudaoStop.isEnabled = running
            }
        }
    }

    private fun updateStatus(message: String) {
        runOnUiThread { statusBar.text = message }
    }

    private fun setupLogger() {
        Logger.addListener(logListener)
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
        scrapeJob?.cancel()
        webViewManager?.destroy()
        webViewManager = null
        Logger.removeListener(logListener)
        super.onDestroy()
    }
}