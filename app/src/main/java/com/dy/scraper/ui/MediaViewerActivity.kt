package com.dy.scraper.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.dy.scraper.R
import com.dy.scraper.crypto.CryptoManager
import com.dy.scraper.util.Logger
import java.io.File

class MediaViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_IS_VIDEO = "is_video"
        const val EXTRA_IS_ENCRYPTED = "is_encrypted"
    }

    private var player: ExoPlayer? = null
    private var tempDecryptedFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media_viewer)

        val filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: run {
            finish()
            return
        }
        val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "unknown"
        val isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)
        val isEncrypted = intent.getBooleanExtra(EXTRA_IS_ENCRYPTED, false)

        title = fileName

        val ivFullscreen = findViewById<ImageView>(R.id.ivFullscreen)
        val playerView = findViewById<PlayerView>(R.id.playerView)
        val btnClose = findViewById<ImageButton>(R.id.btnClose)

        btnClose.setOnClickListener { finish() }

        val sourceFile = File(filePath)
        if (!sourceFile.exists()) {
            Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (isEncrypted) {
            val key = CryptoManager.getKey(this)
            if (key == null) {
                Toast.makeText(this, R.string.encrypt_no_key_error, Toast.LENGTH_SHORT).show()
                finish()
                return
            }

            try {
                tempDecryptedFile = File(cacheDir, "viewer_" + System.currentTimeMillis() + "_" + fileName)
                CryptoManager.decryptFile(sourceFile, tempDecryptedFile!!, key)
                showMedia(tempDecryptedFile!!, isVideo, ivFullscreen, playerView)
            } catch (e: Exception) {
                Logger.d("MediaViewerActivity: 解密失败: ${e.message}")
                Toast.makeText(this, R.string.media_viewer_decrypt_failed, Toast.LENGTH_SHORT).show()
                finish()
            }
        } else {
            showMedia(sourceFile, isVideo, ivFullscreen, playerView)
        }
    }

    private fun showMedia(file: File, isVideo: Boolean, imageView: ImageView, playerView: PlayerView) {
        if (isVideo) {
            showVideo(file, playerView)
        } else {
            showImage(file, imageView)
        }
    }

    private fun showImage(file: File, imageView: ImageView) {
        imageView.visibility = View.VISIBLE
        try {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
            } else {
                Toast.makeText(this, "无法加载图片", Toast.LENGTH_SHORT).show()
                finish()
            }
        } catch (e: Exception) {
            Logger.d("MediaViewerActivity: 图片加载失败: ${e.message}")
            Toast.makeText(this, "图片加载失败", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun showVideo(file: File, playerView: PlayerView) {
        playerView.visibility = View.VISIBLE

        val exoPlayer = ExoPlayer.Builder(this).build()
        player = exoPlayer
        playerView.player = exoPlayer

        val mediaItem = MediaItem.fromUri(file.toURI().toString())
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Logger.d("MediaViewerActivity: 视频播放错误: ${error.message}")
                Toast.makeText(this@MediaViewerActivity, "视频播放失败: ${error.message}", Toast.LENGTH_SHORT).show()
                finish()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    exoPlayer.seekTo(0)
                    exoPlayer.playWhenReady = false
                }
            }
        })
    }

    override fun onStop() {
        super.onStop()
        player?.playWhenReady = false
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
        tempDecryptedFile?.delete()
    }
}