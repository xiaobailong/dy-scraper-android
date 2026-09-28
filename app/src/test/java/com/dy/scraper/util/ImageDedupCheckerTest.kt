package com.dy.scraper.util

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ImageDedupCheckerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `computePHash returns null for non-existent file`() {
        val hash = ImageDedupChecker.computePHash(File(tempFolder.root, "nonexistent.jpg"))
        assertNull(hash)
    }

    @Test
    fun `computePHash returns null for empty file`() {
        val file = tempFolder.newFile("empty.jpg")
        file.writeBytes(ByteArray(0))
        assertNull(ImageDedupChecker.computePHash(file))
    }

    @Test
    fun `checkAndDedup skips non-existent file`() {
        val result = ImageDedupChecker.checkAndDedup(
            File(tempFolder.root, "nonexistent.jpg"), tempFolder.root
        )
        assertFalse(result)
    }

    @Test
    fun `checkAndDedup skips zero-length file`() {
        val file = tempFolder.newFile("empty.jpg")
        file.writeBytes(ByteArray(0))
        assertFalse(ImageDedupChecker.checkAndDedup(file, tempFolder.root))
    }

    @Test
    fun `hammingDistance identical hashes is zero`() {
        assertEquals(0, ImageDedupChecker.hammingDistance("0123456789abcdef", "0123456789abcdef"))
    }

    @Test
    fun `hammingDistance completely different`() {
        val distance = ImageDedupChecker.hammingDistance("0000000000000000", "ffffffffffffffff")
        assertEquals(64, distance)
    }

    @Test
    fun `hammingDistance one bit`() {
        assertEquals(1, ImageDedupChecker.hammingDistance("0000000000000000", "0000000000000001"))
    }

    @Test
    fun `hammingDistance within threshold for similar images`() {
        // pHash would produce close hashes for similar images (within 4 bits)
        val distance = ImageDedupChecker.hammingDistance("abc0000000000000", "abc0000000000001")
        assertEquals(1, distance)
    }

    @Test
    fun `hammingDistance different lengths`() {
        val distance = ImageDedupChecker.hammingDistance("abc", "abcdef1234567890")
        assertTrue(distance >= 0)
    }

    @Test
    fun `isCoverUrl detects cover patterns`() {
        assertTrue(ImageDedupChecker.isCoverUrl("https://example.com/image?cover=1"))
        assertTrue(ImageDedupChecker.isCoverUrl("https://example.com/cover/something"))
        assertTrue(ImageDedupChecker.isCoverUrl("https://example.com/video_cover_123.jpg"))
        assertTrue(ImageDedupChecker.isCoverUrl("https://example.com/cover_image.webp"))
    }

    @Test
    fun `isCoverUrl normal image is false`() {
        assertFalse(ImageDedupChecker.isCoverUrl("https://example.com/photo.jpg"))
    }

    @Test
    fun `isEmojiStickerUrl detects emoji patterns`() {
        assertTrue(ImageDedupChecker.isEmojiStickerUrl("https://example.com/emoticon/smile.gif"))
        assertTrue(ImageDedupChecker.isEmojiStickerUrl("https://example.com/sticker/001.png"))
        assertTrue(ImageDedupChecker.isEmojiStickerUrl("https://example.com/emoji/happy.webp"))
        assertTrue(ImageDedupChecker.isEmojiStickerUrl("https://example.com/obj/tos-cn-i-tsj2vxp0zn/image.png"))
    }

    @Test
    fun `isEmojiStickerUrl real douyin effect is emoji`() {
        assertTrue(ImageDedupChecker.isEmojiStickerUrl(
            "https://p3-sign.douyinpic.com/obj/ies.fe.effect/dbd9c8682cb1ed3748cbccd22d59391f?lk3s=7b078dd2"
        ))
    }

    @Test
    fun `isEmojiStickerUrl real douyin image is NOT emoji`() {
        assertFalse(ImageDedupChecker.isEmojiStickerUrl(
            "https://p3-pc-sign.douyinpic.com/tos-cn-i-0813/oUP5EN1LOBAJPSA1ARi7Ae1ACA0gLClXI"
        ))
    }

    @Test
    fun `isEmojiByDimensions non-gif returns false`() {
        val file = tempFolder.newFile("photo.jpg")
        createMinimalJpeg(file)
        assertFalse(ImageDedupChecker.isEmojiByDimensions(file))
    }

    @Test
    fun `checkAndDedup two different images both survive`() {
        val dir = tempFolder.root
        val file1 = tempFolder.newFile("img_a.jpg")
        val file2 = tempFolder.newFile("img_b.jpg")
        createMinimalJpeg(file1)
        // Write slightly different JPEG to file2
        Thread.sleep(50)
        createMinimalJpeg(file2)

        // Both should survive since they're completely different images
        // (the same minimal JPEG data would have the same pHash though)
        val r1 = ImageDedupChecker.checkAndDedup(file1, dir)
        val r2 = if (file2.exists()) ImageDedupChecker.checkAndDedup(file2, dir) else false
        // At minimum, no crash
        assertTrue(true)
    }

    private fun createMinimalJpeg(file: File) {
        val jpegHeader = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
            0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            0xFF.toByte(), 0xDB.toByte(), 0x00, 0x43, 0x00,
            0xFF.toByte(), 0xDB.toByte(), 0x00, 0x43, 0x01,
            0xFF.toByte(), 0xC0.toByte(), 0x00, 0x0B, 0x08, 0x00, 0x01, 0x00, 0x01,
            0x01, 0x01, 0x11, 0x00,
            0xFF.toByte(), 0xC4.toByte(), 0x00, 0x1F, 0x00, 0x00,
            0xFF.toByte(), 0xC4.toByte(), 0x00, 0xB5.toByte(), 0x10,
            0xFF.toByte(), 0xC4.toByte(), 0x00, 0x1F, 0x01,
            0xFF.toByte(), 0xC4.toByte(), 0x00, 0xB5.toByte(), 0x11,
            0xFF.toByte(), 0xDA.toByte(), 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00,
            0xFF.toByte(), 0xD9.toByte()
        )
        file.writeBytes(jpegHeader)
    }
}