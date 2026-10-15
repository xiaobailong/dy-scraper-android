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
    private val onItemClick: (MediaFileItem) -> Unit,
    private val onSelectionChanged: (Int) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_ITEM = 0
        private const val TYPE_SELECT_ALL_ROW = 1
    }

    private val items = mutableListOf<MediaFileItem>()
    private val selectedPositions = mutableSetOf<Int>()
    private val thumbCache = mutableMapOf<String, Bitmap?>()

    /** 列表首行是否显示“全选”行（列表为空时隐藏） */
    var showSelectAllRow: Boolean = false
        private set

    /** 首行占位：显示“全选”行时为 1；其它位置要减掉它才能对上 items 下标 */
    private val headerOffset: Int get() = if (showSelectAllRow) 1 else 0

    fun setItems(newItems: List<MediaFileItem>) {
        items.clear()
        items.addAll(newItems)
        selectedPositions.clear()
        thumbCache.clear()
        showSelectAllRow = newItems.isNotEmpty()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    fun getSelectedItems(): List<MediaFileItem> {
        return selectedPositions.mapNotNull { pos ->
            if (pos in items.indices) items[pos] else null
        }
    }

    fun getSelectedCount(): Int = selectedPositions.size
    fun getTotalCount(): Int = items.size
    fun getAllItems(): List<MediaFileItem> = items.toList()

    fun clearSelection() {
        selectedPositions.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    fun selectAll() {
        selectedPositions.clear()
        for (i in items.indices) {
            selectedPositions.add(i)
        }
        notifyDataSetChanged()
        onSelectionChanged(selectedPositions.size)
    }

    fun isAllSelected(): Boolean {
        return items.isNotEmpty() && selectedPositions.size == items.size
    }

    /** 参数为 RecyclerView 的 adapter position（含首行“全选”行），内部换算成 items 下标 */
    fun removeItem(adapterPosition: Int): MediaFileItem? {
        val position = adapterPosition - headerOffset
        if (position !in items.indices) return null
        val item = items.removeAt(position)
        thumbCache.remove(item.file.absolutePath)

        val adjusted = selectedPositions.map { if (it > position) it - 1 else it }.toMutableSet()
        adjusted.remove(position)
        selectedPositions.clear()
        selectedPositions.addAll(adjusted)

        notifyItemRemoved(adapterPosition)
        onSelectionChanged(selectedPositions.size)
        return item
    }

    /** 参数为 RecyclerView 的 adapter position（含首行“全选”行） */
    fun getItem(adapterPosition: Int): MediaFileItem? {
        val position = adapterPosition - headerOffset
        return if (position in items.indices) items[position] else null
    }

    /** 首行“全选”点击：全选 ↔ 取消全选 */
    fun toggleSelectAll() {
        if (isAllSelected()) clearSelection() else selectAll()
    }

    /** 选中状态变化后刷新首行“全选”行 */
    private fun notifyHeaderChanged() {
        if (showSelectAllRow && items.isNotEmpty()) notifyItemChanged(0)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_SELECT_ALL_ROW) {
            val view = LayoutInflater.from(context).inflate(R.layout.item_select_all_row, parent, false)
            SelectAllViewHolder(view)
        } else {
            val view = LayoutInflater.from(context).inflate(R.layout.item_media_file, parent, false)
            ViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is SelectAllViewHolder) {
            holder.bind()
            return
        }
        val itemPosition = position - headerOffset
        (holder as ViewHolder).bind(items[itemPosition], itemPosition)
    }

    override fun getItemViewType(position: Int): Int {
        return if (showSelectAllRow && position == 0) TYPE_SELECT_ALL_ROW else TYPE_ITEM
    }

    override fun getItemCount(): Int = items.size + headerOffset

    /** 列表首行“全选”行：点击在全选 / 取消全选之间切换 */
    inner class SelectAllViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val cbSelectAll: CheckBox = view.findViewById(R.id.cbSelectAll)
        private val tvSelectAllLabel: TextView = view.findViewById(R.id.tvSelectAllLabel)

        fun bind() {
            val total = items.size
            val selected = selectedPositions.size
            val allSelected = total > 0 && selected == total
            cbSelectAll.isChecked = allSelected
            tvSelectAllLabel.text = if (allSelected) {
                context.getString(R.string.encrypt_unselect_all_row, selected, total)
            } else {
                context.getString(R.string.encrypt_select_all_row, selected, total)
            }
            itemView.setOnClickListener { toggleSelectAll() }
        }
    }

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
                onSelectionChanged(selectedPositions.size)
                notifyHeaderChanged()
            }

            itemView.setOnClickListener {
                onItemClick(item)
            }

            loadThumbnail(item, ivThumbnail)
        }

        private fun loadThumbnail(item: MediaFileItem, imageView: ImageView) {
            val cacheKey = item.file.absolutePath

            thumbCache[cacheKey]?.let { cachedBitmap ->
                imageView.setImageBitmap(cachedBitmap)
                return
            }

            if (thumbCache.containsKey(cacheKey)) {
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