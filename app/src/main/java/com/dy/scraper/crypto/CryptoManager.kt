package com.dy.scraper.crypto

import android.content.Context
import android.util.Base64
import com.dy.scraper.util.Logger
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoManager {

    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_SIZE = 256
    private const val GCM_TAG_LENGTH = 128
    private const val GCM_IV_LENGTH = 12

    private const val PREFS_NAME = "dy_crypto_prefs"
    private const val KEY_CRYPTO_KEY = "crypto_key_base64"

    private const val ENCRYPTED_EXT = ".enc"

    fun isEncryptedFile(file: File): Boolean {
        return file.name.endsWith(ENCRYPTED_EXT)
    }

    fun getEncryptedExt(): String = ENCRYPTED_EXT

    // ── 密钥管理 ──

    fun hasKey(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .contains(KEY_CRYPTO_KEY)
    }

    fun getKey(context: Context): SecretKey? {
        val base64 = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CRYPTO_KEY, null) ?: return null
        return try {
            val keyBytes = Base64.decode(base64, Base64.NO_WRAP)
            SecretKeySpec(keyBytes, ALGORITHM)
        } catch (e: Exception) {
            Logger.d("CryptoManager: 密钥解析失败: ${e.message}")
            null
        }
    }

    fun generateAndSaveKey(context: Context): SecretKey {
        val keyBytes = ByteArray(KEY_SIZE / 8)
        SecureRandom().nextBytes(keyBytes)
        val key = SecretKeySpec(keyBytes, ALGORITHM)
        saveKey(context, key)
        return key
    }

    fun saveKey(context: Context, key: SecretKey) {
        val base64 = Base64.encodeToString(key.encoded, Base64.NO_WRAP)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_CRYPTO_KEY, base64).apply()
        Logger.d("CryptoManager: 密钥已保存")
    }

    fun exportKey(context: Context, outputFile: File) {
        val base64 = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CRYPTO_KEY, null)
            ?: throw IllegalStateException("没有可导出的密钥")
        outputFile.parentFile?.mkdirs()
        outputFile.writeText(base64)
        Logger.d("CryptoManager: 密钥已导出到 ${outputFile.absolutePath}")
    }

    fun importKey(context: Context, inputFile: File): SecretKey {
        val base64 = inputFile.readText().trim()
        val keyBytes = Base64.decode(base64, Base64.NO_WRAP)
        if (keyBytes.size != KEY_SIZE / 8) {
            throw IllegalArgumentException("密钥文件格式不正确，需要 ${KEY_SIZE / 8} 字节")
        }
        val key = SecretKeySpec(keyBytes, ALGORITHM)
        saveKey(context, key)
        Logger.d("CryptoManager: 密钥已从 ${inputFile.absolutePath} 导入")
        return key
    }

    fun getDefaultKeyExportFile(): File {
        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        return File(downloadsDir, "dy-scraper-key.txt")
    }

    // ── 文件内容加解密 ──

    fun encryptFile(inputFile: File, outputFile: File, key: SecretKey) {
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)

        outputFile.parentFile?.mkdirs()

        FileOutputStream(outputFile).use { fos ->
            fos.write(iv)
            CipherOutputStream(fos, cipher).use { cos ->
                FileInputStream(inputFile).use { fis ->
                    fis.copyTo(cos, 64 * 1024)
                }
            }
        }
    }

    fun decryptFile(inputFile: File, outputFile: File, key: SecretKey) {
        FileInputStream(inputFile).use { fis ->
            val iv = ByteArray(GCM_IV_LENGTH)
            val read = fis.read(iv)
            if (read != GCM_IV_LENGTH) {
                throw IllegalArgumentException("加密文件格式错误：IV 长度不足")
            }

            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)

            outputFile.parentFile?.mkdirs()

            CipherInputStream(fis, cipher).use { cis ->
                FileOutputStream(outputFile).use { fos ->
                    cis.copyTo(fos, 64 * 1024)
                }
            }
        }
    }

    // ── 文件名加解密 ──

    fun encryptFileName(originalName: String, key: SecretKey): String {
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)

        val nameBytes = originalName.toByteArray(Charsets.UTF_8)
        val encrypted = cipher.doFinal(nameBytes)

        val combined = iv + encrypted
        return "enc_" + Base64.encodeToString(combined, Base64.NO_WRAP or Base64.URL_SAFE)
    }

    fun decryptFileName(encryptedName: String, key: SecretKey): String? {
        return try {
            if (!encryptedName.startsWith("enc_")) return null

            val base64Part = encryptedName.removePrefix("enc_")
            val combined = Base64.decode(base64Part, Base64.NO_WRAP or Base64.URL_SAFE)

            if (combined.size < GCM_IV_LENGTH) return null

            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)

            val decrypted = cipher.doFinal(ciphertext)
            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            Logger.d("CryptoManager: 文件名解密失败: ${e.message}")
            null
        }
    }

    // ── 便捷方法：加密单个文件（自动生成加密文件名、删除源文件） ──

    fun encryptFileInPlace(file: File, key: SecretKey): File {
        val encryptedName = encryptFileName(file.name, key)
        val encryptedFile = File(file.parentFile, encryptedName + ENCRYPTED_EXT)

        encryptFile(file, encryptedFile, key)

        if (encryptedFile.exists() && encryptedFile.length() > 0) {
            file.delete()
            Logger.d("CryptoManager: 加密完成 ${file.name} -> ${encryptedFile.name}")
        }

        return encryptedFile
    }

    fun decryptFileInPlace(file: File, key: SecretKey): File? {
        if (!isEncryptedFile(file)) return null

        val encryptedNameWithoutExt = file.name.removeSuffix(ENCRYPTED_EXT)
        val originalName = decryptFileName(encryptedNameWithoutExt, key) ?: return null

        val outputFile = File(file.parentFile, originalName)

        decryptFile(file, outputFile, key)

        if (outputFile.exists() && outputFile.length() > 0) {
            file.delete()
            Logger.d("CryptoManager: 解密完成 ${file.name} -> ${outputFile.name}")
        }

        return outputFile
    }

    // ── 获取缓存缩略图目录 ──

    fun getThumbCacheDir(context: Context): File {
        val dir = File(context.cacheDir, "thumb_cache")
        dir.mkdirs()
        return dir
    }
}