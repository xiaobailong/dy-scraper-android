package com.dy.scraper.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.dy.scraper.R
import com.dy.scraper.core.ScraperEngine
import com.dy.scraper.core.WebViewManager
import com.dy.scraper.util.AppConfig
import com.dy.scraper.util.Logger
import com.dy.scraper.util.RunMode
import com.dy.scraper.util.Utils
import com.dy.scraper.util.YoudaoFetcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

class ScrapeFragment : Fragment() {

    private lateinit var logView: TextView
    private lateinit var scrollView: android.widget.ScrollView
    private lateinit var scraperWebView: WebView

    var currentMode: RunMode = RunMode.LOCAL
        private set

    private lateinit var llLocalMode: View
    private lateinit var etUrlInput: EditText
    private lateinit var btnClearInput: Button
    private lateinit var btnLocalStart: Button
    private lateinit var btnLocalStop: Button

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

    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchSlop = 0

    var onStatusUpdate: ((String) -> Unit)? = null

    private val logListener: (String) -> Unit = { message ->
        activity?.runOnUiThread {
            logView.append("$message\n")
            scrollView.post {
                scrollView.fullScroll(android.widget.ScrollView.FOCUS_DOWN)
            }
        }
    }

    private var pendingFolderEditText: EditText? = null

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { treeUri ->
            val path = uriToFilePath(treeUri)
            if (path != null) {
                pendingFolderEditText?.setText(path)
            } else {
                Toast.makeText(requireContext(), "无法解析所选路径，请手动输入", Toast.LENGTH_SHORT).show()
            }
            requireActivity().contentResolver.takePersistableUriPermission(
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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_scrape, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        scrollView = view.findViewById(R.id.scrollView)
        logView = view.findViewById(R.id.logView)
        scraperWebView = view.findViewById(R.id.scraperWebView)

        // 解决 ViewPager2 与 WebView 的水平滑动冲突：
        // 当用户在 WebView 内水平滑动（如抖音滑块验证）时，禁止父 ViewPager2 拦截触摸事件，
        // 让 WebView 完整处理水平滑动，避免拖动滑块时整个 tab 跟着切换。
        touchSlop = android.view.ViewConfiguration.get(requireContext()).scaledTouchSlop
        scraperWebView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.x
                    touchStartY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = abs(event.x - touchStartX)
                    val dy = abs(event.y - touchStartY)
                    if (dx > dy && dx > touchSlop) {
                        v.parent.requestDisallowInterceptTouchEvent(true)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent.requestDisallowInterceptTouchEvent(false)
                }
            }
            false
        }

        // 防御性处理：若视图被系统回收后重建，webViewManager 仍持有旧 WebView 引用，需销毁重建
        if (webViewManager != null) {
            Logger.d("ScrapeFragment: 视图重建，清理旧 WebViewManager")
            webViewManager?.destroy()
            webViewManager = null
            scraperWebView.visibility = View.GONE
        }

        llLocalMode = view.findViewById(R.id.llLocalMode)
        etUrlInput = view.findViewById(R.id.etUrlInput)
        btnClearInput = view.findViewById(R.id.btnClearInput)
        btnLocalStart = view.findViewById(R.id.btnLocalStart)
        btnLocalStop = view.findViewById(R.id.btnLocalStop)

        llYoudaoMode = view.findViewById(R.id.llYoudaoMode)
        btnFetchYoudao = view.findViewById(R.id.btnFetchYoudao)
        tvYoudaoLabel = view.findViewById(R.id.tvYoudaoLabel)
        tvYoudaoUrlList = view.findViewById(R.id.tvYoudaoUrlList)
        llYoudaoButtons = view.findViewById(R.id.llYoudaoButtons)
        btnYoudaoStart = view.findViewById(R.id.btnYoudaoStart)
        btnYoudaoStop = view.findViewById(R.id.btnYoudaoStop)

        setupButtons()
        setupLogger()
        applyMode(RunMode.fromPrefs(requireContext()))

        etUrlInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                saveDraftSilent()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        if (currentMode == RunMode.LOCAL) {
            loadDraft()
        }
    }

    fun showScraperSettingsDialog() {
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
        val etPageInterval = dialogView.findViewById<EditText>(R.id.etPageInterval)
        val etBatchInterval = dialogView.findViewById<EditText>(R.id.etBatchInterval)
        val etJitterPercent = dialogView.findViewById<EditText>(R.id.etJitterPercent)
        val switchRiskPause = dialogView.findViewById<SwitchCompat>(R.id.switchRiskPause)

        val ctx = requireContext()
        etPageLoadTimeout.setText(AppConfig.getPageLoadTimeoutMs(ctx).toString())
        etRenderWait.setText(AppConfig.getRenderWaitMs(ctx).toString())
        etDetailApiWait.setText(AppConfig.getDetailApiWaitMs(ctx).toString())
        etDownloadTimeout.setText(AppConfig.getDownloadTimeoutSeconds(ctx).toString())
        etMaxVideoWorkers.setText(AppConfig.getMaxVideoWorkers(ctx).toString())
        etMaxImageWorkers.setText(AppConfig.getMaxImageWorkers(ctx).toString())
        etDownloadRootPath.setText(AppConfig.getDownloadRootPath(ctx))
        etImageSizeFilter.setText(AppConfig.getImageSizeFilterKb(ctx).toString())
        etVideoSizeFilter.setText(AppConfig.getVideoSizeFilterMb(ctx).toString())
        switchUrlDedup.isChecked = AppConfig.isUrlDedupEnabled(ctx)
        switchNetworkLog.isChecked = AppConfig.isNetworkLogEnabled(ctx)
        etPageInterval.setText(AppConfig.getPageIntervalMs(ctx).toString())
        etBatchInterval.setText(AppConfig.getBatchIntervalMs(ctx).toString())
        etJitterPercent.setText(AppConfig.getJitterPercent(ctx).toString())
        switchRiskPause.isChecked = AppConfig.isRiskPauseEnabled(ctx)

        btnSelectFolder.setOnClickListener {
            pendingFolderEditText = etDownloadRootPath
            folderPickerLauncher.launch(null)
        }

        AlertDialog.Builder(ctx)
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
                val pageInterval = etPageInterval.text.toString().toLongOrNull()
                val batchInterval = etBatchInterval.text.toString().toLongOrNull()
                val jitterPercent = etJitterPercent.text.toString().toIntOrNull()

                if (listOf(pageLoadTimeout, renderWait, detailApiWait, downloadTimeout, maxVideoWorkers, maxImageWorkers, imageSizeFilter, videoSizeFilter, pageInterval, batchInterval, jitterPercent).any { it == null }) {
                    Toast.makeText(ctx, R.string.scraper_settings_param_invalid, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }

                AppConfig.setPageLoadTimeoutMs(ctx, pageLoadTimeout!!)
                AppConfig.setRenderWaitMs(ctx, renderWait!!)
                AppConfig.setDetailApiWaitMs(ctx, detailApiWait!!)
                AppConfig.setDownloadTimeoutSeconds(ctx, downloadTimeout!!)
                AppConfig.setMaxVideoWorkers(ctx, maxVideoWorkers!!)
                AppConfig.setMaxImageWorkers(ctx, maxImageWorkers!!)
                AppConfig.setImageSizeFilterKb(ctx, imageSizeFilter!!)
                AppConfig.setVideoSizeFilterMb(ctx, videoSizeFilter!!)

                val rootPath = etDownloadRootPath.text.toString().trim()
                if (rootPath.isNotEmpty() && rootPath != AppConfig.getDownloadRootPath(ctx)) {
                    AppConfig.setDownloadRootPath(ctx, rootPath)
                    AppConfig.initDirs(ctx)
                }

                AppConfig.setUrlDedupEnabled(ctx, switchUrlDedup.isChecked)
                AppConfig.setNetworkLogEnabled(ctx, switchNetworkLog.isChecked)
                Logger.setNetworkLogEnabled(ctx, switchNetworkLog.isChecked)
                AppConfig.setPageIntervalMs(ctx, pageInterval!!)
                AppConfig.setBatchIntervalMs(ctx, batchInterval!!)
                AppConfig.setJitterPercent(ctx, jitterPercent!!)
                AppConfig.setRiskPauseEnabled(ctx, switchRiskPause.isChecked)

                Toast.makeText(ctx, R.string.scraper_settings_saved, Toast.LENGTH_SHORT).show()
                Logger.d("Scraper settings dialog: saved")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showModeSettingsDialog() {
        Logger.d("Mode settings dialog: opened")
        val dialogView = layoutInflater.inflate(R.layout.dialog_mode_settings, null)
        val rgRunMode = dialogView.findViewById<android.widget.RadioGroup>(R.id.rgRunMode)
        val rbLocal = dialogView.findViewById<android.widget.RadioButton>(R.id.rbModeLocal)
        val rbYoudao = dialogView.findViewById<android.widget.RadioButton>(R.id.rbModeYoudao)

        when (currentMode) {
            RunMode.LOCAL -> rbLocal.isChecked = true
            RunMode.YOUDAO -> rbYoudao.isChecked = true
        }

        AlertDialog.Builder(requireContext())
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
                    RunMode.saveToPrefs(requireContext(), newMode)
                    val label = when (newMode) {
                        RunMode.LOCAL -> getString(R.string.mode_local)
                        RunMode.YOUDAO -> getString(R.string.mode_youdao)
                    }
                    Toast.makeText(requireContext(), getString(R.string.mode_switch_toast, label), Toast.LENGTH_SHORT).show()
                    Logger.d("Mode switched to: $label")
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showYoudaoUrlSettingsDialog() {
        Logger.d("Youdao URL settings dialog: opened")
        val dialogView = layoutInflater.inflate(R.layout.dialog_youdao_url, null)
        val etYoudaoUrl = dialogView.findViewById<EditText>(R.id.etYoudaoUrl)

        etYoudaoUrl.setText(YoudaoFetcher.getDisplayApiUrl(requireContext()))

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_youdao_url_title)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newUrl = etYoudaoUrl.text.toString().trim()
                if (YoudaoFetcher.saveApiUrl(requireContext(), newUrl)) {
                    Toast.makeText(requireContext(), getString(R.string.settings_youdao_url_saved), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), getString(R.string.settings_youdao_url_invalid), Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showLogSettingsDialog() {
        Logger.d("Log settings dialog: opened")
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val switchLogOutput = dialogView.findViewById<SwitchCompat>(R.id.switchLogOutput)
        val tvLogPath = dialogView.findViewById<TextView>(R.id.tvLogPath)

        switchLogOutput.isChecked = Logger.isEnabled()
        tvLogPath.text = logPathText()

        switchLogOutput.setOnCheckedChangeListener { _, isChecked ->
            Logger.setEnabled(requireContext(), isChecked)
            tvLogPath.text = logPathText()
            Toast.makeText(
                requireContext(),
                if (isChecked) R.string.settings_log_on else R.string.settings_log_off,
                Toast.LENGTH_SHORT
            ).show()
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_log_title)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    fun showScreenOnSettingsDialog() {
        Logger.d("Screen-on settings dialog: opened")
        val dialogView = layoutInflater.inflate(R.layout.dialog_screen_on_settings, null)
        val switchScreenOn = dialogView.findViewById<SwitchCompat>(R.id.switchScreenOn)

        switchScreenOn.isChecked = AppConfig.isKeepScreenOnEnabled(requireContext())

        switchScreenOn.setOnCheckedChangeListener { _, isChecked ->
            AppConfig.setKeepScreenOnEnabled(requireContext(), isChecked)
            Toast.makeText(
                requireContext(),
                if (isChecked) R.string.settings_screen_on_on else R.string.settings_screen_on_off,
                Toast.LENGTH_SHORT
            ).show()
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_screen_on_title)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun logPathText(): String {
        val path = Logger.getLogPath()
        val suffix = if (Logger.isLogFileActive()) "" else getString(R.string.log_file_not_created)
        return getString(R.string.settings_log_path, path + suffix)
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

    private fun saveDraftSilent() {
        val text = etUrlInput.text.toString()
        requireActivity().getSharedPreferences(Logger.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(RunMode.PREFS_KEY_DRAFT_URLS, text)
            .apply()
        Logger.d("Draft saved: ${text.lines().size} lines")
    }

    private fun loadDraft() {
        val text = requireActivity().getSharedPreferences(Logger.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .getString(RunMode.PREFS_KEY_DRAFT_URLS, "") ?: ""
        if (text.isNotBlank()) {
            etUrlInput.setText(text)
            Logger.d("Draft loaded: ${text.lines().size} lines")
        }
    }

    private fun setupButtons() {
        btnClearInput.setOnClickListener {
            etUrlInput.text.clear()
            saveDraftSilent()
        }
        btnLocalStart.setOnClickListener {
            val urls = parseUrlInput()
            if (urls.isEmpty()) {
                Toast.makeText(requireContext(), "请输入至少一个链接", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startScraper(urls)
        }
        btnLocalStop.setOnClickListener { stopScraper() }

        btnFetchYoudao.setOnClickListener { fetchYoudaoUrls() }
        btnYoudaoStart.setOnClickListener {
            if (youdaoUrls.isEmpty()) {
                Toast.makeText(requireContext(), "请先获取URL", Toast.LENGTH_SHORT).show()
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
        val apiUrl = YoudaoFetcher.getApiUrl(requireContext())
        Logger.log("  有道API地址: $apiUrl")
        Toast.makeText(requireContext(), R.string.label_youdao_fetching, Toast.LENGTH_SHORT).show()
        tvYoudaoLabel.visibility = View.VISIBLE
        tvYoudaoLabel.text = getString(R.string.label_youdao_fetching)
        tvYoudaoUrlList.visibility = View.GONE
        btnYoudaoStart.isEnabled = false

        lifecycleScope.launch {
            val result = YoudaoFetcher.fetchUrls(requireContext())
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
        onStatusUpdate?.invoke(getString(R.string.status_scraping))

        val activity = requireActivity()
        if (AppConfig.isKeepScreenOnEnabled(activity)) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        scraperWebView.visibility = View.VISIBLE

        val wvm = WebViewManager(activity, scraperWebView)
        webViewManager = wvm
        // 反风控：检测到验证页时由引擎回调（工作线程）→ 切回 UI 线程提示并让出 WebView
        wvm.onVerificationStateChange = { active ->
            activity.runOnUiThread { onVerificationStateChanged(active) }
        }

        scrapeJob = lifecycleScope.launch {
            var successCount = 0
            var failedCount = 0
            var completed = false
            try {
                val output = ScraperEngine.run(
                    context = activity,
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
                updateStartStopButtons(running = false)
                if (completed) {
                    onStatusUpdate?.invoke(getString(R.string.status_done, successCount, failedCount))
                }
                if (AppConfig.isKeepScreenOnEnabled(activity)) {
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                wvm.cleanup()
                webViewManager = null
                // 若任务在验证页暂停期间结束，恢复日志区显示
                view?.findViewById<View>(R.id.scrollView)?.visibility = View.VISIBLE
            }
        }
    }

    private fun stopScraper() {
        Logger.logSection("停止抓取任务")
        Logger.log("取消抓取协程...")
        scrapeJob?.cancel()
        updateStartStopButtons(running = false)
        onStatusUpdate?.invoke(getString(R.string.status_cancelled))
        val activity = requireActivity()
        if (AppConfig.isKeepScreenOnEnabled(activity)) {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        webViewManager?.cleanup()
        webViewManager = null
        Logger.log("  抓取任务已取消")
    }

    /**
     * 反风控：检测到验证页时隐藏日志区、把整块区域让给 WebView 供手动完成验证；通过后恢复日志区。
     * 由 WebViewManager 在工作线程回调，调用方已切回 UI 线程。
     */
    private fun onVerificationStateChanged(active: Boolean) {
        view?.findViewById<View>(R.id.scrollView)?.visibility = if (active) View.GONE else View.VISIBLE
        scraperWebView.visibility = View.VISIBLE
        onStatusUpdate?.invoke(
            getString(
                if (active) R.string.status_verification_required
                else R.string.status_verification_passed
            )
        )
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

    private fun setupLogger() {
        Logger.addListener(logListener)
    }

    override fun onDestroyView() {
        Logger.d("ScrapeFragment: onDestroyView")
        // 注意：不在这里销毁 webViewManager，因为 offscreenPageLimit=2 通常不会触发视图销毁；
        // 若极端情况下被系统回收，抓取任务仍需保留 WebView 实例，待 onDestroy 统一清理。
        scrapeJob?.cancel()
        Logger.removeListener(logListener)
        super.onDestroyView()
    }

    override fun onDestroy() {
        Logger.d("ScrapeFragment: onDestroy")
        webViewManager?.destroy()
        webViewManager = null
        super.onDestroy()
    }
}