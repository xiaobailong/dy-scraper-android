package com.dy.scraper.util

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

class YoudaoFetcherTest {

    private val validUrl = "https://note.youdao.com/yws/api/note/70f23df9766d959e890f02747415d2f4?sev=j1&editorType=1&unloginId=c6e838d2-5c51-045a-6067-49fa4b84b37f&editorVersion=new-json-editor&sec=v1"
    private val invalidBaseUrl = "https://note.youdao.com/yws/api/personal/file/"

    // ===== getApiUrl =====

    @Test
    fun `getApiUrl returns default when no saved URL`() {
        val ctx = mockContextWithSavedUrl("")
        assertEquals(YoudaoFetcher.DEFAULT_YOUDAO_API, YoudaoFetcher.getApiUrl(ctx))
    }

    @Test
    fun `getApiUrl returns default when saved URL is invalid base path`() {
        val ctx = mockContextWithSavedUrl(invalidBaseUrl)
        assertEquals(YoudaoFetcher.DEFAULT_YOUDAO_API, YoudaoFetcher.getApiUrl(ctx))
    }

    @Test
    fun `getApiUrl returns default when saved URL has no note ID`() {
        val ctx = mockContextWithSavedUrl("https://note.youdao.com/yws/api/note/")
        assertEquals(YoudaoFetcher.DEFAULT_YOUDAO_API, YoudaoFetcher.getApiUrl(ctx))
    }

    @Test
    fun `getApiUrl returns saved URL when valid`() {
        val ctx = mockContextWithSavedUrl(validUrl)
        assertEquals(validUrl, YoudaoFetcher.getApiUrl(ctx))
    }

    @Test
    fun `getApiUrl returns default for generic youdao URL`() {
        val ctx = mockContextWithSavedUrl("https://note.youdao.com/")
        assertEquals(YoudaoFetcher.DEFAULT_YOUDAO_API, YoudaoFetcher.getApiUrl(ctx))
    }

    @Test
    fun `getApiUrl rejects wrong api path`() {
        val ctx = mockContextWithSavedUrl("https://note.youdao.com/yws/api/personal/file/70f23df9766d959e890f02747415d2f4")
        assertEquals(YoudaoFetcher.DEFAULT_YOUDAO_API, YoudaoFetcher.getApiUrl(ctx))
    }

    // ===== saveApiUrl =====

    @Test
    fun `saveApiUrl returns true for valid URL`() {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString("youdao_api_url", "") } returns ""
        every { prefs.edit() } returns mockk(relaxed = true)

        val ctx = mockContextWithPrefs(prefs)
        assertTrue(YoudaoFetcher.saveApiUrl(ctx, validUrl))
    }

    @Test
    fun `saveApiUrl returns false for blank URL`() {
        val ctx = mockContextWithPrefs(mockk(relaxed = true))
        assertFalse(YoudaoFetcher.saveApiUrl(ctx, ""))
        assertFalse(YoudaoFetcher.saveApiUrl(ctx, "   "))
    }

    @Test
    fun `saveApiUrl returns false for invalid base URL`() {
        val ctx = mockContextWithPrefs(mockk(relaxed = true))
        assertFalse(YoudaoFetcher.saveApiUrl(ctx, invalidBaseUrl))
    }

    @Test
    fun `saveApiUrl returns false for URL without note prefix`() {
        val ctx = mockContextWithPrefs(mockk(relaxed = true))
        assertFalse(YoudaoFetcher.saveApiUrl(ctx, "https://note.youdao.com/yws/api/file/"))
    }

    @Test
    fun `saveApiUrl returns false for URL with short ID`() {
        val ctx = mockContextWithPrefs(mockk(relaxed = true))
        assertFalse(YoudaoFetcher.saveApiUrl(ctx, "https://note.youdao.com/yws/api/note/abc123"))
    }

    // ===== getDisplayApiUrl =====

    @Test
    fun `getDisplayApiUrl shows default when nothing saved`() {
        val ctx = mockContextWithSavedUrl("")
        assertEquals(YoudaoFetcher.DEFAULT_YOUDAO_API, YoudaoFetcher.getDisplayApiUrl(ctx))
    }

    @Test
    fun `getDisplayApiUrl shows default when invalid saved`() {
        val ctx = mockContextWithSavedUrl(invalidBaseUrl)
        assertEquals(YoudaoFetcher.DEFAULT_YOUDAO_API, YoudaoFetcher.getDisplayApiUrl(ctx))
    }

    @Test
    fun `getDisplayApiUrl shows saved when valid`() {
        val ctx = mockContextWithSavedUrl(validUrl)
        assertEquals(validUrl, YoudaoFetcher.getDisplayApiUrl(ctx))
    }

    // ===== isNoteApiUrlValid =====

    @Test
    fun `isNoteApiUrlValid true for valid`() {
        assertTrue(YoudaoFetcher.isNoteApiUrlValid(validUrl))
    }

    @Test
    fun `isNoteApiUrlValid false for invalid`() {
        assertFalse(YoudaoFetcher.isNoteApiUrlValid(invalidBaseUrl))
    }

    @Test
    fun `isNoteApiUrlValid false for blank`() {
        assertFalse(YoudaoFetcher.isNoteApiUrlValid(""))
    }

    // ===== DEFAULT_YOUDAO_API =====

    @Test
    fun `default API URL is valid`() {
        assertTrue(YoudaoFetcher.DEFAULT_YOUDAO_API.contains("/yws/api/note/"))
        assertTrue(Regex("note/([a-f0-9]{32})").containsMatchIn(YoudaoFetcher.DEFAULT_YOUDAO_API))
    }

    // ===== helpers =====

    private fun mockContextWithSavedUrl(savedUrl: String): Context {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString("youdao_api_url", "") } returns savedUrl
        return mockContextWithPrefs(prefs)
    }

    private fun mockContextWithPrefs(prefs: SharedPreferences): Context {
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(Logger.PREFS_NAME, Context.MODE_PRIVATE) } returns prefs
        return context
    }
}