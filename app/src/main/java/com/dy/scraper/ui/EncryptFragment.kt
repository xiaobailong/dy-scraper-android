package com.dy.scraper.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dy.scraper.R
import com.dy.scraper.crypto.CryptoManager
import com.dy.scraper.util.AppConfig
import com.dy.scraper.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.crypto.SecretKey

class EncryptFragment : Fragment() {

    private lateinit var rvMediaFiles: RecyclerView
    private lateinit var tvFileCount: TextView
    private lateinit var llNoKeyHint: View
    private lateinit var llEmptyHint: View
    private lateinit var adapter: EncryptAdapter

    private val mediaFileExtensions = setOf(
        "mp4", "webm", "mov", "m4v", "flv", "3gp", "ts",
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic"
    )

    private val videoExtensions = setOf("mp4", "webm", "mov", "m4v", "flv", "3gp", "ts")

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
        tvFileCount = view.findViewById(R.id.tvFileCount)
        llNoKeyHint = view.findViewById(R.id.llNoKeyHint)
        llEmptyHint = view.findViewById(R.id.llEmptyHint)

        adapter = EncryptAdapter(
            requireContext(),
            keyProvider = { getKey() },
            onItemClick = { item -> openMediaViewer(item) }
        )

        rvMediaFiles.layoutManager = LinearLayoutManager(requireContext())
        rvMediaFiles.adapter = adapter

        view.findViewById<View>(R.id.btnEncrypt).setOnClickListener { encryptSelected() }
        view.findViewById<View>(R.id.btnDecrypt).setOnClickListener { decryptSelected() }
        view.findViewById<View>(R.id.btnExportKey).setOnClickListener { exportKey() }
        view.findViewById<View>(R.id.btnImportKey).setOnClickListener { importKey() }
        view.findViewById<View>(R.id.btnGenerateKey).setOnClickListener { generateKey() }
    }

    override fun onResume() {
        super.onResume()
        refreshFileList()
    }

    private fun getKey(): SecretKey? = CryptoManager.getKey(requireContext())

    private fun refreshFileList() {
        val hasKey = CryptoManager.hasKey(requireContext())

        if (!hasKey) {
            llNoKeyHint.visibility = View.VISIBLE
            llEmptyHint.visibility = View.GONE
            rvMediaFiles.visibility = View.GONE
            adapter.setItems(emptyList())
            tvFileCount.text = getString(R.string.encrypt_file_count, 0, 0)
            return
        }

        llNoKeyHint.visibility = View.GONE

        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                scanMediaFiles()
            }

            if (items.isEmpty()) {
                llEmptyHint.visibility = View.VISIBLE
                rvMediaFiles.visibility = View.GONE
            } else {
                llEmptyHint.visibility = View.GONE
                rvMediaFiles.visibility = View.VISIBLE
            }

            adapter.setItems(items)
            tvFileCount.text = getString(R.string.encrypt_file_count, items.size, 0)
        }
    }

    private fun scanMediaFiles(): List<MediaFileItem> {
        val key = getKey() ?: return emptyList()
        val files = mutableListOf<MediaFileItem>()
        val dirs = listOf(AppConfig.downloadVideoDir, AppConfig.downloadImageDir)

        for (dir in dirs) {
            if (!dir.exists()) continue

            dir.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach

                val isEncrypted = CryptoManager.isEncryptedFile(file)
                val item = if (isEncrypted) {
                    val nameWithoutExt = file.nameWithoutExtension
                    val originalName = CryptoManager.decryptFileName(nameWithoutExt, key)
                        ?: file.name
                    val ext = if (originalName.contains(".")) {
                        originalName.substringAfterLast(".")
                    } else ""
                    val isVideo = ext.lowercase() in videoExtensions
                    MediaFileItem(file, originalName, true, file.length(), isVideo)
                } else {
                    val ext = file.extension.lowercase()
                    if (ext !in mediaFileExtensions) return@forEach
                    val isVideo = ext in videoExtensions
                    MediaFileItem(file, file.name, false, file.length(), isVideo)
                }

                files.add(item)
            }
        }

        files.sortByDescending { it.file.lastModified() }
        return files
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

        lifecycleScope.launch {
            var successCount = 0
            var errorMsg: String? = null

            try {
                withContext(Dispatchers.IO) {
                    for (item in unencryptedItems) {
                        try {
                            CryptoManager.encryptFileInPlace(item.file, key)
                            successCount++
                        } catch (e: Exception) {
                            Logger.d("EncryptFragment: 加密失败 ${item.file.name}: ${e.message}")
                            errorMsg = e.message
                        }
                    }
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

        lifecycleScope.launch {
            var successCount = 0
            var errorMsg: String? = null

            try {
                withContext(Dispatchers.IO) {
                    for (item in encryptedItems) {
                        try {
                            CryptoManager.decryptFileInPlace(item.file, key)
                            successCount++
                        } catch (e: Exception) {
                            Logger.d("EncryptFragment: 解密失败 ${item.file.name}: ${e.message}")
                            errorMsg = e.message
                        }
                    }
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
        val intent = Intent(requireContext(), MediaViewerActivity::class.java).apply {
            putExtra(MediaViewerActivity.EXTRA_FILE_PATH, item.file.absolutePath)
            putExtra(MediaViewerActivity.EXTRA_FILE_NAME, item.displayName)
            putExtra(MediaViewerActivity.EXTRA_IS_VIDEO, item.isVideo)
            putExtra(MediaViewerActivity.EXTRA_IS_ENCRYPTED, item.isEncrypted)
        }
        startActivity(intent)
    }
}