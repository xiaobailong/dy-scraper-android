package com.dy.scraper

import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.dy.scraper.util.Logger
import com.dy.scraper.util.RunMode
import com.dy.scraper.util.YoudaoFetcher
import com.dy.scraper.worker.ScraperWorker
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var scrollView: android.widget.ScrollView

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

        val currentUrl = YoudaoFetcher.getApiUrl(this)
        etYoudaoUrl.setText(currentUrl)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_youdao_url_title)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newUrl = etYoudaoUrl.text.toString().trim()
                YoudaoFetcher.saveApiUrl(this, newUrl)
                val savedUrl = YoudaoFetcher.getApiUrl(this)
                Toast.makeText(
                    this,
                    getString(R.string.settings_youdao_url_saved) + ": " + savedUrl,
                    Toast.LENGTH_SHORT
                ).show()
                Logger.d("Youdao URL settings dialog: saved API URL = $savedUrl")
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
                llYoudaoButtons.visibility = View.GONE
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
        val urlRegex = Regex("""https?://[^\s，。；;,\u0000-\u001F\u007F]+""")
        return etUrlInput.text.toString()
            .lines()
            .flatMap { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("#")) return@flatMap emptyList()
                urlRegex.findAll(trimmed).map { it.value.trimEnd('.', ',', ';', '，', '。') }.toList()
            }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun fetchYoudaoUrls() {
        Logger.logSection("从有道获取URL")
        val apiUrl = YoudaoFetcher.getApiUrl(this)
        Logger.log("  有道API地址: $apiUrl")
        Toast.makeText(this, R.string.label_youdao_fetching, Toast.LENGTH_SHORT).show()
        tvYoudaoLabel.visibility = View.VISIBLE
        tvYoudaoLabel.text = getString(R.string.label_youdao_fetching)
        tvYoudaoUrlList.visibility = View.GONE
        llYoudaoButtons.visibility = View.GONE

        lifecycleScope.launch {
            val result = YoudaoFetcher.fetchUrls(this@MainActivity)
            if (result.error != null) {
                Logger.log("  有道获取失败: ${result.error}", "error")
                tvYoudaoLabel.text = getString(R.string.label_youdao_error, result.error)
                tvYoudaoUrlList.visibility = View.GONE
                llYoudaoButtons.visibility = View.GONE
                return@launch
            }
            if (result.urls.isEmpty()) {
                Logger.log("  有道返回空URL列表")
                tvYoudaoLabel.text = getString(R.string.label_youdao_empty)
                tvYoudaoUrlList.visibility = View.GONE
                llYoudaoButtons.visibility = View.GONE
                return@launch
            }

            youdaoUrls = result.urls
            Logger.log("  有道获取成功，共 ${result.urls.size} 个URL")
            tvYoudaoLabel.text = getString(R.string.label_url_list, result.urls.size)
            tvYoudaoUrlList.text = result.urls.joinToString("\n")
            tvYoudaoUrlList.visibility = View.VISIBLE
            llYoudaoButtons.visibility = View.VISIBLE
        }
    }

    private fun startScraper(urls: List<String>) {
        if (!Logger.isEnabled()) {
            Logger.d("Log was disabled before scraper start")
        }
        Logger.logSection("启动抓取任务")
        Logger.log("启动抓取任务，共 ${urls.size} 个 URL")
        Logger.log("  当前模式: ${currentMode.key}")
        urls.forEach { Logger.log("  $it") }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<ScraperWorker>()
            .setConstraints(constraints)
            .addTag("dy_scraper")
            .setInputData(
                androidx.work.workDataOf(
                    ScraperWorker.KEY_URLS to urls.joinToString("\n")
                )
            )
            .build()

        WorkManager.getInstance(this)
            .enqueueUniqueWork(
                "dy_scraper_work",
                ExistingWorkPolicy.REPLACE,
                workRequest
            )

        Logger.d("WorkManager task enqueued, id=${workRequest.id}")
        updateStartStopButtons(running = true)

        lifecycleScope.launch {
            WorkManager.getInstance(this@MainActivity)
                .getWorkInfoByIdLiveData(workRequest.id)
                .observe(this@MainActivity) { workInfo ->
                    if (workInfo != null) {
                        val progress = workInfo.progress
                        val pct = progress.getInt("progress_pct", 0)
                        val total = progress.getInt("url_total", 0)
                        val done = progress.getInt("url_done", 0)
                        Logger.log("进度: $done/$total ($pct%)")

                        if (workInfo.state.isFinished) {
                            Logger.log("WorkManager任务完成, state=${workInfo.state}")
                            updateStartStopButtons(running = false)
                        }
                    }
                }
        }
    }

    private fun stopScraper() {
        Logger.logSection("停止抓取任务")
        Logger.log("取消所有 dy_scraper 任务...")
        WorkManager.getInstance(this).cancelUniqueWork("dy_scraper_work")
        WorkManager.getInstance(this).cancelAllWorkByTag("dy_scraper")
        Logger.log("  所有抓取任务已取消")
        updateStartStopButtons(running = false)
    }

    private fun updateStartStopButtons(running: Boolean) {
        Logger.d("updateStartStopButtons: running=$running, mode=${currentMode.key}")
        when (currentMode) {
            RunMode.LOCAL -> {
                btnLocalStart.isEnabled = !running
                btnLocalStop.isEnabled = running
            }
            RunMode.YOUDAO -> {
                btnYoudaoStart.isEnabled = !running
                btnYoudaoStop.isEnabled = running
            }
        }
    }

    private fun setupLogger() {
        Logger.addListener { message ->
            runOnUiThread {
                logView.append("$message\n")
                scrollView.post {
                    scrollView.fullScroll(android.widget.ScrollView.FOCUS_DOWN)
                }
            }
        }
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