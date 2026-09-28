package com.dy.scraper

import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.dy.scraper.worker.ScraperWorker
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var startBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        scrollView = ScrollView(this).apply {
            setPadding(32, 32, 32, 32)
        }
        logView = TextView(this).apply {
            textSize = 12f
            setLineSpacing(4f, 1f)
            isVerticalScrollBarEnabled = true
        }
        scrollView.addView(logView)

        startBtn = Button(this).apply {
            text = "开始抓取"
            setOnClickListener { startScraper() }
        }

        val rootLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(
                startBtn,
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 16 }
            )
            addView(
                scrollView,
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
        }
        setContentView(rootLayout)
    }

    private fun appendLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logView.append("[$time] $message\n")
        scrollView.post {
            scrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun startScraper() {
        appendLog("启动抓取任务...")

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<ScraperWorker>()
            .setConstraints(constraints)
            .addTag("dy_scraper")
            .build()

        WorkManager.getInstance(this)
            .enqueueUniqueWork(
                "dy_scraper_work",
                ExistingWorkPolicy.REPLACE,
                workRequest
            )

        // 监听进度
        lifecycleScope.launch {
            WorkManager.getInstance(this@MainActivity)
                .getWorkInfoByIdLiveData(workRequest.id)
                .observe(this@MainActivity) { workInfo ->
                    if (workInfo != null) {
                        val progress = workInfo.progress
                        val pct = progress.getInt("progress_pct", 0)
                        val total = progress.getInt("url_total", 0)
                        val current = progress.getInt("url_current", 0)
                        val status = progress.getString("status") ?: ""
                        appendLog("进度: $pct% [$current/$total] $status")

                        if (workInfo.state.isFinished) {
                            appendLog("抓取任务完成!")
                        }
                    }
                }
        }
    }
}