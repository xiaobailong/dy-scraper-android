package com.dy.scraper.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.dy.scraper.R
import com.dy.scraper.crypto.CryptoManager
import com.dy.scraper.util.Logger
import java.io.File
import java.io.FileOutputStream
import javax.crypto.SecretKey
import kotlin.concurrent.thread

data class MediaFileItem(
    val file: File,
    val displayName: String,
    val isEncrypted: Boolean,
    val fileSize: Long,
    val isVideo: Boolean
) {
    val sizeText: String
        get() {
            val kb = fileSize / 1024
            return if (kb >= 1024) "${kb / 1024}.${(kb % 1024) / 100} MB" else "$kb KB"
        }
}

class EncryptAdapter(
    private val context: Context,
    private val keyProvider: () -> SecretKey?,
    private val onItemClick: (MediaFileItem) -> Unit
) : RecyclerView.Adapter<EncryptAdapter.ViewHolder>() {

    private val items = mutableListOf<MediaFileItem>()
    private val selectedPositions = mutableSetOf<Int>()
    private val thumbCache = mutableMapOf<String, Bitmap?>()

    fun setItems(newItems: List<MediaFileItem>) {
        items.clear()
        items.addAll(newItems)
        selectedPositions.clear()
        thumbCache.clear()
        notifyDataSetChanged()
    }

    fun getSelectedItems(): List<MediaFileItem> {
        return selectedPositions.mapNotNull { pos ->
            if (pos in items.indices) items[pos] else null
        }
    }

    fun clearSelection() {
        selectedPositions.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_media_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val cbSelect: CheckBox = view.findViewById(R.id.cbSelect)
        private val ivThumbnail: ImageView = view.findViewById(R.id.ivThumbnail)
        private val tvFileName: TextView = view.findViewById(R.id.tvFileName)
        private val tvFileInfo: TextView = view.findViewById(R.id.tvFileInfo)
        private val tvEncryptStatus: TextView = view.findViewById(R.id.tvEncryptStatus)

        fun bind(item: MediaFileItem, position: Int) {
            tvFileName.text = item.displayName
            tvFileInfo.text = "${item.sizeText}  ${if (item.isVideo) "视频" else "图片"}"
            tvEncryptStatus.text = if (item.isEncrypted)
                context.getString(R.string.encrypt_status_encrypted)
            else
                context.getString(R.string.encrypt_status_unencrypted)

            cbSelect.setOnCheckedChangeListener(null)
            cbSelect.isChecked = selectedPositions.contains(position)
            cbSelect.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selectedPositions.add(position)
                else selectedPositions.remove(position)
            }

            itemView.setOnClickListener {
                onItemClick(item)
            }

            loadThumbnail(item, ivThumbnail)
        }

        private fun loadThumbnail(item: MediaFileItem, imageView: ImageView) {
            val cacheKey = item.file.absolutePath

            thumbCache[cacheKey]?.let { bitmap ->
                if (bitmap != null) imageView.setImageBitmap(bitmap)
                return
            }

            imageView.setImageResource(android.R.drawable.ic_menu_gallery)

            thread {
                val bitmap = generateThumbnail(item)
                thumbCache[cacheKey] = bitmap
                imageView.post {
                    if (bitmap != null) {
                        imageView.setImageBitmap(bitmap)
                    }
                }
            }
        }

        private fun generateThumbnail(item: MediaFileItem): Bitmap? {
            return try {
                val sourceFile = if (item.isEncrypted) {
                    val key = keyProvider() ?: return null
                    val cacheFile = File(
                        CryptoManager.getThumbCacheDir(context),
                        item.file.nameWithoutExtension + "_thumb"
                    )
                    if (!cacheFile.exists()) {
                        try {
                            CryptoManager.decryptFile(item.file, cacheFile, key)
                        } catch (e: Exception) {
                            Logger.d("EncryptAdapter: 缩略图解密失败: ${e.message}")
                            return null
                        }
                    }
                    cacheFile
                } else {
                    item.file
                }

                if (item.isVideo) {
                    generateVideoThumbnail(sourceFile)
                } else {
                    generateImageThumbnail(sourceFile)
                }
            } catch (e: Exception) {
                Logger.d("EncryptAdapter: 缩略图生成失败: ${e.message}")
                null
            }
        }

        private fun generateImageThumbnail(file: File): Bitmap? {
            val options = BitmapFactory.Options().apply {
                inSampleSize = 4
            }
            return BitmapFactory.decodeFile(file.absolutePath, options)
        }

        private fun generateVideoThumbnail(file: File): Bitmap? {
            var retriever: MediaMetadataRetriever? = null
            return try {
                retriever = MediaMetadataRetriever()
                retriever.setDataSource(file.absolutePath)
                retriever.frameAtTime
            } catch (e: Exception) {
                Logger.d("EncryptAdapter: 视频缩略图获取失败: ${e.message}")
                null
            } finally {
                retriever?.release()
            }
        }
    }
}