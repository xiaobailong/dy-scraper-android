package com.dy.scraper.ui

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.dy.scraper.R
import com.dy.scraper.crypto.CryptoManager
import com.dy.scraper.crypto.SupersededSourceRegistry
import com.dy.scraper.util.AppConfig
import com.dy.scraper.util.Logger
import com.dy.scraper.util.StorageAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.crypto.SecretKey

class EncryptFragment : Fragment() {

    private lateinit var rvMediaFiles: RecyclerView
    private lateinit var llNoKeyHint: View
    private lateinit var llEmptyHint: View
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var llStorageHint: View
    private lateinit var btnGrantStorage: Button
    private lateinit var adapter: EncryptAdapter

    /** 底部状态栏右侧的计数回调（由 MainActivity 注入，用于“共 N 个文件（已选 M 个）”合并显示） */
    var onCountUpdate: ((String) -> Unit)? = null

    private val mediaFileExtensions = setOf(
        "mp4", "webm", "mov", "m4v", "flv", "3gp", "ts",
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic"
    )

    private val videoExtensions = setOf("mp4", "webm", "mov", "m4v", "flv", "3gp", "ts")

    /** 已知的非媒体文件扩展名：确认与媒体无关，直接跳过（跳过时会记日志，便于排查） */
    private val ignoredExtensions = setOf(
        "json", "txt", "log", "nomedia", "tmp", "part", "crdownload", "db", "db-wal", "db-shm", "xml",
        // 华为「回收站」把删除的文件改名为 .<原名>.hwbk（内容仍在），不能当媒体文件列出来
        "hwbk"
    )

    private val keyImportLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            try {
                val inputStream = requireContext().contentResolver.openInputStream(it)
                val content = inputStream?.bufferedReader()?.readText() ?: ""
                inputStream?.close()

                val keyFile = File(requireContext().cacheDir, "imported_key.txt")
                keyFile.writeText(content)

                CryptoManager.importKey(requireContext(), keyFile)
                keyFile.delete()

                Toast.makeText(requireContext(), R.string.encrypt_key_imported, Toast.LENGTH_SHORT).show()
                refreshFileList()
            } catch (e: Exception) {
                Logger.d("EncryptFragment: 密钥导入失败: ${e.message}")
                Toast.makeText(requireContext(), R.string.encrypt_key_import_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_encrypt, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvMediaFiles = view.findViewById(R.id.rvMediaFiles)
        llNoKeyHint = view.findViewById(R.id.llNoKeyHint)
        llEmptyHint = view.findViewById(R.id.llEmptyHint)
        swipeRefresh = view.findViewById(R.id.swipeRefresh)
        llStorageHint = view.findViewById(R.id.llStorageHint)
        btnGrantStorage = view.findViewById(R.id.btnGrantStorage)

        adapter = EncryptAdapter(
            requireContext(),
            keyProvider = { getKey() },
            onItemClick = { item -> openMediaViewer(item) },
            onSelectionChanged = { selectedCount ->
                publishCount(adapter.getTotalCount(), selectedCount)
            }
        )

        rvMediaFiles.layoutManager = LinearLayoutManager(requireContext())
        rvMediaFiles.adapter = adapter

        val swipeHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT
        ) {
            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                // 首行“全选”行不允许左滑删除
                if (viewHolder is EncryptAdapter.SelectAllViewHolder) return makeMovementFlags(0, 0)
                return super.getMovementFlags(recyclerView, viewHolder)
            }

            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 0.25f

            override fun getSwipeEscapeVelocity(defaultValue: Float): Float = defaultValue * 0.5f

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return

                val item = adapter.getItem(position) ?: return

                lifecycleScope.launch {
                    val stillExists = withContext(Dispatchers.IO) {
                        try {
                            if (item.file.exists()) {
                                // 走 CryptoManager：先 rename 到隐藏名再删（EMUI 的回收站会拦 delete()）
                                CryptoManager.removeSourceFile(item.file)
                            }
                        } catch (e: Exception) {
                            Logger.d("EncryptFragment: 删除文件失败: ${e.message}")
                        }
                        val exists = item.file.exists()
                        Logger.d(
                            "EncryptFragment: 左滑删除文件: ${item.file.absolutePath}，删除后存在=$exists"
                        )
                        exists
                    }

                    Toast.makeText(
                        requireContext(),
                        if (stillExists) {
                            "未删除: ${item.displayName}（被系统回收站拦住，请在系统文件管理器里删除）"
                        } else {
                            "已删除: ${item.displayName}"
                        },
                        Toast.LENGTH_SHORT
                    ).show()

                    // 删除后重新扫描整个文件系统，确保列表与实际文件同步
                    // （用户可能从回收站恢复文件，需要能正确显示）
                    refreshFileList()
                }
            }

            override fun onChildDraw(
                canvas: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                val itemView = viewHolder.itemView
                val background = ColorDrawable(Color.parseColor("#E53935"))
                background.setBounds(
                    itemView.right + dX.toInt(),
                    itemView.top,
                    itemView.right,
                    itemView.bottom
                )
                background.draw(canvas)

                val deleteText = "删除"
                val paint = Paint().apply {
                    color = Color.WHITE
                    textSize = 48f
                    isAntiAlias = true
                    textAlign = Paint.Align.CENTER
                }
                val textY = itemView.top + (itemView.height / 2f) - 
                    ((paint.descent() + paint.ascent()) / 2f)
                val textX = itemView.right - 120f
                canvas.drawText(deleteText, textX, textY, paint)

                super.onChildDraw(canvas, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        })
        swipeHelper.attachToRecyclerView(rvMediaFiles)

        view.findViewById<View>(R.id.btnEncrypt).setOnClickListener { encryptSelected() }
        view.findViewById<View>(R.id.btnDecrypt).setOnClickListener { decryptSelected() }
        view.findViewById<View>(R.id.btnExportKey).setOnClickListener { exportKey() }
        view.findViewById<View>(R.id.btnImportKey).setOnClickListener { importKey() }

        // 列表顶部下拉刷新（原来的“刷新”按钮已移除）
        swipeRefresh.setOnRefreshListener { refreshFileList() }
        // 只有列表滚到顶部时才允许下拉（FrameLayout 自身不可滚动，必须显式判定）
        swipeRefresh.setOnChildScrollUpCallback { _, _ -> rvMediaFiles.canScrollVertically(-1) }
        view.findViewById<View>(R.id.btnGenerateKey).setOnClickListener { generateKey() }

        // Android 11+：Download 目录按创建者隔离，需要“所有文件访问”才能看到目录里的全部文件
        btnGrantStorage.setOnClickListener {
            StorageAccess.openAllFilesAccessSettings(requireContext())
        }
    }

    override fun onResume() {
        super.onResume()
        refreshFileList()
    }

    /** 把“共 N 个文件（已选 M 个）”推到 Activity 底部状态栏（加密页可见时显示） */
    private fun publishCount(total: Int, selected: Int) {
        onCountUpdate?.invoke(getString(R.string.encrypt_file_count, total, selected))
    }

    private fun getKey(): SecretKey? = CryptoManager.getKey(requireContext())

    private fun refreshFileList() {
        val hasAllFiles = StorageAccess.hasAllFilesAccess()
        llStorageHint.visibility = if (hasAllFiles) View.GONE else View.VISIBLE
        if (!hasAllFiles) {
            Logger.log(
                "EncryptFragment: 未授予“所有文件访问”，Android 11+ 的 Download 目录按创建者隔离：" +
                    "非本 App 创建的文件（其他 App / 电脑传入 / 从回收站恢复）不会出现在列表里",
                "warn"
            )
        }

        val hasKey = CryptoManager.hasKey(requireContext())

        if (!hasKey) {
            llNoKeyHint.visibility = View.VISIBLE
            llEmptyHint.visibility = View.GONE
            swipeRefresh.visibility = View.GONE
            adapter.setItems(emptyList())
            publishCount(0, 0)
            swipeRefresh.isRefreshing = false
            return
        }

        llNoKeyHint.visibility = View.GONE
        swipeRefresh.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val items = withContext(Dispatchers.IO) {
                    scanMediaFiles()
                }

                // 列表常驻可见（空态提示只做覆盖层），这样列表为空时也能下拉刷新
                llEmptyHint.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE

                adapter.setItems(items)
                // 与界面显示完全一致的清单（排查"手机里有、列表里没有"时以行为准）
                Logger.d(
                    "EncryptFragment: 列表已刷新，共 ${items.size} 项（图片 ${items.count { !it.isVideo }}，视频 ${items.count { it.isVideo }}）"
                )
                items.forEach {
                    Logger.d("EncryptFragment: 列表项: ${it.displayName} (encrypted=${it.isEncrypted}, video=${it.isVideo}, size=${it.fileSize})")
                }
            } finally {
                // 下拉刷新的转圈必须停掉（扫描抛异常时也一样）
                swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun scanMediaFiles(): List<MediaFileItem> {
        val key = getKey()
        if (key == null) {
            Logger.d("EncryptFragment: scanMediaFiles 跳过，密钥为空")
            return emptyList()
        }
        val files = mutableListOf<MediaFileItem>()
        val dirs = listOf(AppConfig.downloadVideoDir, AppConfig.downloadImageDir)

        // 先清掉已经消失的残留登记，再取一次快照（扫描时逐个文件判断，不重复读 SharedPreferences）
        SupersededSourceRegistry.prune(requireContext())
        val superseded = SupersededSourceRegistry.snapshot(requireContext())

        Logger.d(
            "EncryptFragment: 所有文件访问=${StorageAccess.hasAllFilesAccess()}" +
                "（false 时 Download 目录里非本 App 创建的文件不可见）"
        )

        for (dir in dirs) {
            if (!dir.exists()) {
                Logger.d("EncryptFragment: 目录不存在，跳过: ${dir.absolutePath}")
                continue
            }

            // 1. File.listFiles() 可能漏掉通过 MTP 传输的文件
            val fsFiles = dir.listFiles()?.toList() ?: emptyList()

            // 2. MediaStore 查询补充
            val msFiles = queryMediaStoreByDir(dir)

            // 3. Os.listdir() 底层系统调用补充（绕过 FUSE/Java File API 限制）
            val osFiles = queryOsListdir(dir)

            // 4. 合并去重（按路径去重）
            val merged = (fsFiles + msFiles + osFiles).distinctBy { it.absolutePath }

            Logger.d("EncryptFragment: 扫描目录 ${dir.absolutePath}，listFiles()=${fsFiles.size} 项，MediaStore=${msFiles.size} 项，Os.listdir=${osFiles.size} 项，合并=${merged.size} 项")
            // 原始条目全量落盘：出现"手机里看得到、列表里没有"时，用这行对齐文件
            Logger.d("EncryptFragment: 目录 ${dir.name} 原始条目(${merged.size}): ${merged.joinToString(" | ") { it.name }}")

            // 历史残留：同一份文件既有明文又有密文（旧版本加密时源文件被系统拦住没删掉）→ 只列加密版，
            // 否则列表里一份文件出现两条（真机 2026-10-15 20:22：8 → 16）
            val plaintextTwins = findPlaintextTwins(merged, key)
            plaintextTwins.forEach {
                Logger.log("EncryptFragment: 明文与密文共存（同一份文件），列表只显示加密版: $it", "warn")
            }
            merged.forEach { file ->
                val ext = file.extension.lowercase()
                if (!file.isFile) {
                    Logger.log(
                        "EncryptFragment: 跳过非文件项: ${file.name} (isFile=${file.isFile}, isDir=${file.isDirectory}, exists=${file.exists()})",
                        "warn"
                    )
                    return@forEach
                }

                // 以 "." 开头的隐藏项是系统残留：华为「回收站」把删除的原文件改名为 .<原名>.hwbk，
                // 内容仍在但扩展名未知；若列进列表，加密后会出现「文件数翻倍」
                if (file.name.startsWith(".")) {
                    Logger.d("EncryptFragment: 跳过隐藏/系统残留文件: ${file.name}")
                    return@forEach
                }

                // 加/解密后系统不让 App 移走的源文件（登记在案）：列表里不再显示，
                // 否则明文与密文共存 → 「文件数翻倍」（真机 2026-10-15 20:22：8 → 16）
                if (SupersededSourceRegistry.keyOf(file) in superseded) {
                    Logger.log(
                        "EncryptFragment: 跳过加/解密残留的源文件（系统拦住了删除，已隐藏）: ${file.name}",
                        "warn"
                    )
                    return@forEach
                }

                // 与某个 .enc 是同一份文件的明文（历史上没被移走）：只显示加密版
                if (file.absolutePath in plaintextTwins) {
                    return@forEach
                }

                val isEncrypted = CryptoManager.isEncryptedFile(file)
                val item = if (isEncrypted) {
                    val nameWithoutExt = file.name.removeSuffix(CryptoManager.getEncryptedExt())
                    val originalName = CryptoManager.decryptFileName(nameWithoutExt, key)
                    if (originalName == null) {
                        Logger.log(
                            "EncryptFragment: 加密文件名解密失败（密钥不匹配?），按原文件名列出: ${file.name}",
                            "warn"
                        )
                    }
                    val shownName = originalName ?: file.name
                    val origExt = if (shownName.contains(".")) {
                        shownName.substringAfterLast(".")
                    } else ""
                    val isVideo = origExt.lowercase() in videoExtensions
                    Logger.d("EncryptFragment: 添加加密文件: ${file.name} -> $shownName (origExt=$origExt)")
                    MediaFileItem(file, shownName, true, file.length(), isVideo)
                } else {
                    when {
                        ext in ignoredExtensions -> {
                            Logger.d("EncryptFragment: 跳过已知非媒体文件: ${file.name} (ext=$ext)")
                            return@forEach
                        }
                        ext in mediaFileExtensions -> {
                            val isVideo = ext in videoExtensions
                            Logger.d("EncryptFragment: 添加媒体文件: ${file.name} (ext=$ext, isVideo=$isVideo)")
                            MediaFileItem(file, file.name, false, file.length(), isVideo)
                        }
                        else -> {
                            // 未知扩展名不再静默丢弃，否则会出现"手机里有、列表里没有"
                            Logger.log(
                                "EncryptFragment: 未知扩展名，仍列入列表: ${file.name} (ext='${ext}')",
                                "warn"
                            )
                            MediaFileItem(file, file.name, false, file.length(), false)
                        }
                    }
                }

                files.add(item)
            }
        }

        Logger.d("EncryptFragment: scanMediaFiles 完成，共 ${files.size} 个媒体文件（图片 ${files.count { !it.isVideo }}，视频 ${files.count { it.isVideo }}）")
        files.sortByDescending { it.file.lastModified() }
        return files
    }

    /**
     * 通过 MediaStore 查询指定目录下的媒体文件。
     * 补充 File.listFiles() 可能漏掉的通过 MTP 传输的文件。
     */
    private fun queryMediaStoreByDir(dir: File): List<File> {
        val context = requireContext()
        val result = mutableListOf<File>()
        val isVideoDir = dir.absolutePath.endsWith("videos")
        val collection = if (isVideoDir) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE
        )

        val selection: String
        val selectionArgs: Array<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
            // RELATIVE_PATH 格式如 "Download/dy-scraper/images/"
            val relativePath = dir.absolutePath
                .removePrefix("/storage/emulated/0/")
                .removePrefix("/storage/self/primary/") + "/"
            selectionArgs = arrayOf(relativePath)
        } else {
            selection = "${MediaStore.MediaColumns.DATA} LIKE ?"
            selectionArgs = arrayOf("${dir.absolutePath}/%")
        }

        var rowCount = 0
        val noFileNames = mutableListOf<String>()
        try {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol) ?: continue
                    if (name.isEmpty()) continue
                    rowCount++
                    val dbSize = cursor.getLong(sizeCol)
                    val file = File(dir, name)
                    val visible = file.exists() && file.isFile
                    // 逐行落盘：可见性差异（Download 目录按创建者隔离）能一眼看出
                    Logger.d(
                        "EncryptFragment: 媒体库行: $name (dbSize=$dbSize, 可见=$visible" +
                            (if (visible) ", 实际大小=${file.length()}" else "") + ")"
                    )
                    if (visible) {
                        result.add(file)
                    } else {
                        noFileNames.add(name)
                    }
                }
            }
        } catch (e: Exception) {
            Logger.d("EncryptFragment: MediaStore 查询失败: ${e.message}")
        }

        Logger.d("EncryptFragment: MediaStore 查询目录 ${dir.absolutePath} 共 $rowCount 条记录，文件存在 ${result.size} 条")
        if (noFileNames.isNotEmpty()) {
            // 媒体库残留：文件已不在磁盘，但系统相册/图库仍会显示 → 表现为"手机里有、列表里没有"
            Logger.log(
                "EncryptFragment: MediaStore 残留 ${noFileNames.size} 条（文件已不存在，系统相册可能仍显示）: ${noFileNames.joinToString(", ")}",
                "warn"
            )
        }
        return result
    }

    /**
     * 通过 Runtime.exec("ls") 列出目录下的文件。
     * 绕过 Java File API / FUSE 层可能漏掉的通过 MTP 传输的文件。
     */
    private fun queryOsListdir(dir: File): List<File> {
        val result = mutableListOf<File>()
        try {
            val process = Runtime.getRuntime().exec(arrayOf("ls", "-1", dir.absolutePath))
            val names = process.inputStream.bufferedReader().useLines { it.toList() }
            process.waitFor()
            Logger.d("EncryptFragment: ls 原始输出 ${names.size} 行: ${names.joinToString(" | ") { it.trim() }}")
            for (name in names) {
                val trimmed = name.trim()
                if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") continue
                val file = File(dir, trimmed)
                if (file.isFile) {
                    result.add(file)
                } else {
                    Logger.log(
                        "EncryptFragment: ls 条目非文件，跳过: $trimmed (isDir=${file.isDirectory}, exists=${file.exists()})",
                        "warn"
                    )
                }
            }
        } catch (e: Exception) {
            Logger.d("EncryptFragment: ls 命令失败: ${e.message}")
        }
        Logger.d("EncryptFragment: ls 查询目录 ${dir.absolutePath} 得到 ${result.size} 个文件")
        return result
    }

    /**
     * 找出「明文与 `.enc` 密文共存」的同一份文件（历史上加密时源文件没被系统移走）。
     * 双重校验，两关都过才算：① 加密文件名能用当前密钥反解出明文名；
     * ② 密文大小 = 明文 + 28B（`CryptoManager.OVERHEAD_BYTES`，GCM 的 IV 12 + tag 16）。
     * 命中后列表只显示加密版，避免同一份文件在列表里出现两条（「文件数翻倍」）。
     */
    private fun findPlaintextTwins(files: List<File>, key: SecretKey): Set<String> {
        val twins = mutableSetOf<String>()
        files.forEach { enc ->
            if (!enc.isFile || !CryptoManager.isEncryptedFile(enc)) return@forEach
            val encName = enc.name.removeSuffix(CryptoManager.getEncryptedExt())
            val originalName = CryptoManager.decryptFileName(encName, key) ?: return@forEach
            val plain = File(enc.parentFile, originalName)
            if (plain.isFile && plain.length() + CryptoManager.OVERHEAD_BYTES == enc.length()) {
                twins.add(plain.absolutePath)
            }
        }
        return twins
    }

    /** 目录内「可见文件」数（排除 . 开头的系统残留、已登记的加/解密残留、明文/密文共存的重复项，口径与列表一致） */
    private fun countFilesInDirs(): Int {
        val superseded = SupersededSourceRegistry.snapshot(requireContext())
        val key = getKey() ?: return 0
        return listOf(AppConfig.downloadVideoDir, AppConfig.downloadImageDir)
            .sumOf { dir ->
                val visible = dir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") } ?: emptyList()
                val twins = findPlaintextTwins(visible, key)
                visible.count {
                    SupersededSourceRegistry.keyOf(it) !in superseded && it.absolutePath !in twins
                }
            }
    }

    private fun encryptSelected() {
        val selected = adapter.getSelectedItems()
        if (selected.isEmpty()) {
            Toast.makeText(requireContext(), R.string.encrypt_no_selection, Toast.LENGTH_SHORT).show()
            return
        }

        val key = getKey()
        if (key == null) {
            Toast.makeText(requireContext(), R.string.encrypt_no_key_error, Toast.LENGTH_SHORT).show()
            return
        }

        val unencryptedItems = selected.filter { !it.isEncrypted }
        if (unencryptedItems.isEmpty()) {
            Toast.makeText(requireContext(), "所选文件已是加密状态", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(requireContext(), R.string.encrypt_processing, Toast.LENGTH_SHORT).show()

        val fileCountBefore = countFilesInDirs()
        Logger.d(
            "EncryptFragment: 开始加密 ${unencryptedItems.size} 个文件，目录内文件总数=$fileCountBefore，待加密: ${unencryptedItems.joinToString(", ") { it.displayName }}"
        )

        lifecycleScope.launch {
            var successCount = 0
            var errorMsg: String? = null
            val leftoverFiles = mutableListOf<File>()

            try {
                withContext(Dispatchers.IO) {
                    for (item in unencryptedItems) {
                        try {
                            CryptoManager.encryptFileInPlace(item.file, key)
                            successCount++
                            // 源文件是否真的移走了？EMUI 的「回收站」连 rename 都可能拦，
                            // 拦住了就登记（列表里隐藏它），否则明文和密文共存 → 文件数翻倍
                            if (item.file.exists()) {
                                leftoverFiles.add(item.file)
                                SupersededSourceRegistry.record(requireContext(), item.file)
                            }
                        } catch (e: Exception) {
                            Logger.d("EncryptFragment: 加密失败 ${item.file.name}: ${e.message}")
                            errorMsg = e.message
                        }
                    }
                }

                Logger.d(
                    "EncryptFragment: 加密结束，成功 $successCount/${unencryptedItems.size}，目录内文件总数 ${countFilesInDirs()}（加密前 $fileCountBefore），源文件未移走 ${leftoverFiles.size} 个"
                )
                if (leftoverFiles.isNotEmpty()) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.encrypt_leftover_hidden, leftoverFiles.size),
                        Toast.LENGTH_LONG
                    ).show()
                }
                if (successCount > 0) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.encrypt_success, successCount),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                if (errorMsg != null && successCount == 0) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.encrypt_failed, errorMsg),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.encrypt_failed, e.message ?: "未知错误"),
                    Toast.LENGTH_LONG
                ).show()
            }

            refreshFileList()
        }
    }

    private fun decryptSelected() {
        val selected = adapter.getSelectedItems()
        if (selected.isEmpty()) {
            Toast.makeText(requireContext(), R.string.encrypt_no_selection, Toast.LENGTH_SHORT).show()
            return
        }

        val key = getKey()
        if (key == null) {
            Toast.makeText(requireContext(), R.string.encrypt_no_key_error, Toast.LENGTH_SHORT).show()
            return
        }

        val encryptedItems = selected.filter { it.isEncrypted }
        if (encryptedItems.isEmpty()) {
            Toast.makeText(requireContext(), "所选文件未加密", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(requireContext(), R.string.encrypt_processing, Toast.LENGTH_SHORT).show()

        val fileCountBefore = countFilesInDirs()
        Logger.d(
            "EncryptFragment: 开始解密 ${encryptedItems.size} 个文件，目录内文件总数=$fileCountBefore，待解密: ${encryptedItems.joinToString(", ") { it.file.name }}"
        )

        lifecycleScope.launch {
            var successCount = 0
            var errorMsg: String? = null
            val leftoverFiles = mutableListOf<File>()

            try {
                withContext(Dispatchers.IO) {
                    for (item in encryptedItems) {
                        try {
                            CryptoManager.decryptFileInPlace(item.file, key)
                            successCount++
                            // 加密源文件没被移走（系统拦住）时同样登记隐藏，避免列表里多出一份
                            if (item.file.exists()) {
                                leftoverFiles.add(item.file)
                                SupersededSourceRegistry.record(requireContext(), item.file)
                            }
                        } catch (e: Exception) {
                            Logger.d("EncryptFragment: 解密失败 ${item.file.name}: ${e.message}")
                            errorMsg = e.message
                        }
                    }
                }

                Logger.d(
                    "EncryptFragment: 解密结束，成功 $successCount/${encryptedItems.size}，目录内文件总数 ${countFilesInDirs()}（解密前 $fileCountBefore），源文件未移走 ${leftoverFiles.size} 个"
                )
                if (leftoverFiles.isNotEmpty()) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.decrypt_leftover_hidden, leftoverFiles.size),
                        Toast.LENGTH_LONG
                    ).show()
                }
                if (successCount > 0) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.decrypt_success, successCount),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                if (errorMsg != null && successCount == 0) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.decrypt_failed, errorMsg),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.decrypt_failed, e.message ?: "未知错误"),
                    Toast.LENGTH_LONG
                ).show()
            }

            refreshFileList()
        }
    }

    private fun exportKey() {
        val key = getKey()
        if (key == null) {
            Toast.makeText(requireContext(), R.string.encrypt_no_key_error, Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val exportFile = CryptoManager.getDefaultKeyExportFile()
            CryptoManager.exportKey(requireContext(), exportFile)
            Toast.makeText(
                requireContext(),
                getString(R.string.encrypt_key_exported),
                Toast.LENGTH_LONG
            ).show()
            Logger.d("EncryptFragment: 密钥已导出到 ${exportFile.absolutePath}")
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                "密钥导出失败: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun importKey() {
        keyImportLauncher.launch("*/*")
    }

    private fun generateKey() {
        CryptoManager.generateAndSaveKey(requireContext())
        Toast.makeText(requireContext(), "密钥已生成", Toast.LENGTH_SHORT).show()
        refreshFileList()
    }

    private fun openMediaViewer(item: MediaFileItem) {
        val allItems = adapter.getAllItems()
        val currentIndex = allItems.indexOfFirst { it.file.absolutePath == item.file.absolutePath }
        val intent = Intent(requireContext(), MediaViewerActivity::class.java).apply {
            putExtra(MediaViewerActivity.EXTRA_FILE_PATHS, allItems.map { it.file.absolutePath }.toTypedArray())
            putExtra(MediaViewerActivity.EXTRA_FILE_NAMES, allItems.map { it.displayName }.toTypedArray())
            putExtra(MediaViewerActivity.EXTRA_IS_VIDEOS, BooleanArray(allItems.size) { allItems[it].isVideo })
            putExtra(MediaViewerActivity.EXTRA_IS_ENCRYPTEDS, BooleanArray(allItems.size) { allItems[it].isEncrypted })
            putExtra(MediaViewerActivity.EXTRA_CURRENT_INDEX, currentIndex.coerceAtLeast(0))
        }
        startActivity(intent)
    }
}