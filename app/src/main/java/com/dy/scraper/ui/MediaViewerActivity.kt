package com.dy.scraper.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.dy.scraper.R
import com.dy.scraper.crypto.CryptoManager
import com.dy.scraper.util.Logger
import java.io.File

class MediaViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_PATHS = "file_paths"
        const val EXTRA_FILE_NAMES = "file_names"
        const val EXTRA_IS_VIDEOS = "is_videos"
        const val EXTRA_IS_ENCRYPTEDS = "is_encrypteds"
        const val EXTRA_CURRENT_INDEX = "current_index"
    }

    private lateinit var viewPager: ViewPager2
    private var currentIndex: Int = 0
    private var filePaths: Array<String> = emptyArray()
    private var fileNames: Array<String> = emptyArray()
    private var isVideos: BooleanArray = booleanArrayOf()
    private var isEncrypteds: BooleanArray = booleanArrayOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media_viewer)

        filePaths = intent.getStringArrayExtra(EXTRA_FILE_PATHS) ?: emptyArray()
        fileNames = intent.getStringArrayExtra(EXTRA_FILE_NAMES) ?: emptyArray()
        isVideos = intent.getBooleanArrayExtra(EXTRA_IS_VIDEOS) ?: booleanArrayOf()
        isEncrypteds = intent.getBooleanArrayExtra(EXTRA_IS_ENCRYPTEDS) ?: booleanArrayOf()
        currentIndex = intent.getIntExtra(EXTRA_CURRENT_INDEX, 0).coerceIn(0, (filePaths.size - 1).coerceAtLeast(0))

        if (filePaths.isEmpty()) {
            finish()
            return
        }

        title = fileNames.getOrElse(currentIndex) { "unknown" }

        viewPager = findViewById(R.id.vpMediaViewer)
        val tvIndex = findViewById<TextView>(R.id.tvMediaIndex)

        viewPager.adapter = MediaPagerAdapter()
        viewPager.setCurrentItem(currentIndex, false)

        updateIndexText(tvIndex)

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentIndex = position
                title = fileNames.getOrElse(position) { "unknown" }
                updateIndexText(tvIndex)
            }
        })
    }

    private fun updateIndexText(tvIndex: TextView) {
        tvIndex.text = "${currentIndex + 1} / ${filePaths.size}"
    }

    override fun onDestroy() {
        // 退出（系统返回键/手势）时统一释放播放器并删掉临时解密文件；
        // 不依赖 RecyclerView 的回收时机，也不依赖页面上的按钮
        if (::viewPager.isInitialized) {
            (viewPager.adapter as? MediaPagerAdapter)?.releaseAll()
        }
        super.onDestroy()
    }

    inner class MediaPagerAdapter : RecyclerView.Adapter<MediaPagerAdapter.MediaViewHolder>() {

        /** 当前还绑定着的页面（退出时统一释放，不依赖 RecyclerView 回收时机） */
        private val boundHolders = mutableSetOf<MediaViewHolder>()

        override fun getItemCount(): Int = filePaths.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_media_page, parent, false)
            return MediaViewHolder(view)
        }

        override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
            holder.releasePlayer()
            holder.bind(position)
            boundHolders.add(holder)
        }

        override fun onViewRecycled(holder: MediaViewHolder) {
            super.onViewRecycled(holder)
            boundHolders.remove(holder)
            holder.releasePlayer()
        }

        /** Activity 退出时统一释放播放器 + 清理临时解密文件 */
        fun releaseAll() {
            boundHolders.toList().forEach { it.releasePlayer() }
            boundHolders.clear()
        }

        inner class MediaViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val imageView: ImageView = view.findViewById(R.id.ivPageImage)
            private val playerView: PlayerView = view.findViewById(R.id.pvPageVideo)
            private var player: ExoPlayer? = null
            private var tempDecryptedFile: File? = null

            fun bind(position: Int) {
                val filePath = filePaths[position]
                val isVideo = isVideos.getOrElse(position) { false }
                val isEncrypted = isEncrypteds.getOrElse(position) { false }

                val sourceFile = File(filePath)
                if (!sourceFile.exists()) {
                    Toast.makeText(this@MediaViewerActivity, "文件不存在", Toast.LENGTH_SHORT).show()
                    return
                }

                if (isEncrypted) {
                    val key = CryptoManager.getKey(this@MediaViewerActivity)
                    if (key == null) {
                        Toast.makeText(this@MediaViewerActivity, R.string.encrypt_no_key_error, Toast.LENGTH_SHORT).show()
                        return
                    }
                    try {
                        tempDecryptedFile = File(cacheDir, "viewer_${System.currentTimeMillis()}_${fileNames.getOrElse(position) { "unknown" }}")
                        CryptoManager.decryptFile(sourceFile, tempDecryptedFile!!, key)
                        showMedia(tempDecryptedFile!!, isVideo)
                    } catch (e: Exception) {
                        Logger.d("MediaViewerActivity: 解密失败: ${e.message}")
                        Toast.makeText(this@MediaViewerActivity, R.string.media_viewer_decrypt_failed, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    showMedia(sourceFile, isVideo)
                }
            }

            private fun showMedia(file: File, isVideo: Boolean) {
                if (isVideo) {
                    playerView.visibility = View.VISIBLE
                    imageView.visibility = View.GONE
                    showVideo(file)
                } else {
                    playerView.visibility = View.GONE
                    imageView.visibility = View.VISIBLE
                    showImage(file)
                }
            }

            private fun showImage(file: File) {
                try {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    if (bitmap != null) {
                        imageView.setImageBitmap(bitmap)
                    } else {
                        Toast.makeText(this@MediaViewerActivity, "无法加载图片", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Logger.d("MediaViewerActivity: 图片加载失败: ${e.message}")
                }
            }

            private fun showVideo(file: File) {
                Logger.d("MediaViewerActivity: showVideo 开始，文件=${file.name}，路径=${file.absolutePath}")
                val exoPlayer = ExoPlayer.Builder(this@MediaViewerActivity).build()
                player = exoPlayer
                playerView.player = exoPlayer
                Logger.d("MediaViewerActivity: ExoPlayer 创建完成，已绑定到 PlayerView")

                val mediaItem = MediaItem.fromUri(file.toURI().toString())
                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
                Logger.d("MediaViewerActivity: setMediaItem + prepare + playWhenReady=true 完成")

                val playPauseBtn = playerView.findViewById<ImageButton>(R.id.exo_play_pause)
                Logger.d("MediaViewerActivity: playPauseBtn 查找结果=${playPauseBtn != null}")
                playPauseBtn?.setOnClickListener {
                    val oldVal = exoPlayer.playWhenReady
                    exoPlayer.playWhenReady = !oldVal
                    Logger.d("MediaViewerActivity: 播放/暂停按钮点击，playWhenReady $oldVal -> ${!oldVal}，currentPosition=${exoPlayer.currentPosition}")
                }
                playerView.findViewById<ImageButton>(androidx.media3.ui.R.id.exo_rew)?.setOnClickListener {
                    val target = (exoPlayer.currentPosition - 5000).coerceAtLeast(0)
                    Logger.d("MediaViewerActivity: 快退按钮点击，${exoPlayer.currentPosition} -> $target")
                    exoPlayer.seekTo(target)
                }
                playerView.findViewById<ImageButton>(androidx.media3.ui.R.id.exo_ffwd)?.setOnClickListener {
                    val target = (exoPlayer.currentPosition + 5000).coerceAtMost(exoPlayer.duration)
                    Logger.d("MediaViewerActivity: 快进按钮点击，${exoPlayer.currentPosition} -> $target，duration=${exoPlayer.duration}")
                    exoPlayer.seekTo(target)
                }

                exoPlayer.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        Logger.d("MediaViewerActivity: 视频播放错误: ${error.message}")
                        Toast.makeText(this@MediaViewerActivity, "视频播放失败: ${error.message}", Toast.LENGTH_SHORT).show()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        val stateName = when (playbackState) {
                            Player.STATE_IDLE -> "IDLE"
                            Player.STATE_BUFFERING -> "BUFFERING"
                            Player.STATE_READY -> "READY"
                            Player.STATE_ENDED -> "ENDED"
                            else -> "UNKNOWN($playbackState)"
                        }
                        Logger.d("MediaViewerActivity: onPlaybackStateChanged=$stateName，playWhenReady=${exoPlayer.playWhenReady}")
                        if (playbackState == Player.STATE_ENDED) {
                            exoPlayer.seekTo(0)
                            exoPlayer.playWhenReady = false
                            Logger.d("MediaViewerActivity: 播放结束，seekTo(0) + playWhenReady=false")
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        Logger.d("MediaViewerActivity: onIsPlayingChanged=$isPlaying")
                        playPauseBtn?.setImageResource(
                            if (isPlaying) androidx.media3.ui.R.drawable.exo_icon_pause else androidx.media3.ui.R.drawable.exo_icon_play
                        )
                    }
                })
            }

            fun releasePlayer() {
                Logger.d("MediaViewerActivity: releasePlayer 被调用")
                playerView.player = null
                player?.release()
                player = null
                tempDecryptedFile?.delete()
                tempDecryptedFile = null
            }
        }
    }
}