package com.dy.scraper.core

import com.dy.scraper.util.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * document-start 钩子（PageHook）单测 —— 本机 JVM 可跑，无需设备/网络。
 *
 * 覆盖视频下载失败的两个「代码级」成因：
 *   1. 注入脚本必须真的挂上 XHR/fetch 钩子、读 SSR 快照，并经桥回传（否则永远拿不到详情 body）；
 *   2. 详情 API 去重键必须带 aweme_id —— 否则第二个作品会被当成重复丢掉。
 */
class PageHookTest {

    @Test
    fun `api patterns stay in sync with app config`() {
        assertEquals(AppConfig.DETAIL_API_PATTERNS, PageHook.API_PATTERNS)
    }

    @Test
    fun `script hooks xhr and fetch and ssr snapshot`() {
        val script = PageHook.SCRIPT
        assertTrue("缺少 XHR 钩子", script.contains("var XHR = window.XMLHttpRequest"))
        assertTrue("缺少 XHR open 钩子", script.contains("XHR.prototype.open"))
        assertTrue("缺少 XHR send 钩子", script.contains("XHR.prototype.send"))
        assertTrue("缺少 fetch 钩子", script.contains("window.fetch"))
        assertTrue("缺少 SSR 变量快照", script.contains(PageHook.SSR_SNAPSHOT_VAR))
        assertTrue("缺少 _ROUTER_DATA 快照", script.contains("_ROUTER_DATA"))
        assertTrue("缺少桥对象名", script.contains(PageHook.BRIDGE_NAME))
        assertTrue("缺少桥方法名", script.contains(PageHook.BRIDGE_METHOD))
        assertTrue("缺少幂等保护", script.contains("__dyHookV1"))
        assertTrue("缺少 HTML 壳过滤", script.contains("s.charAt(0) === '<'"))
    }

    @Test
    fun `script embeds every api pattern`() {
        val script = PageHook.SCRIPT
        for (p in PageHook.API_PATTERNS) {
            assertTrue("未嵌入模式串 $p", script.contains("\"$p\""))
        }
    }

    @Test
    fun `build script is parameterized`() {
        val script = PageHook.buildScript(listOf("/custom/api/"), listOf("MY_SSR"))
        assertTrue(script.contains("\"/custom/api/\""))
        assertTrue(script.contains("'MY_SSR'"))
        assertFalse(script.contains("/aweme/v1/web/aweme/detail/"))
    }

    @Test
    fun `detail api url detection`() {
        assertTrue(
            PageHook.isDetailApiUrl(
                "https://www.douyin.com/aweme/v1/web/aweme/detail/?device_platform=webapp&aid=6383&aweme_id=7690515078848542890"
            )
        )
        assertTrue(PageHook.isDetailApiUrl("https://www.douyin.com/aweme/v1/web/note/7690497070327342821/"))
        assertFalse(PageHook.isDetailApiUrl("https://www.douyin.com/aweme/v1/web/comment/list/"))
        assertFalse(PageHook.isDetailApiUrl(""))
    }

    @Test
    fun `usable body filters html shell and empty json`() {
        assertFalse(PageHook.isUsableBody("{}"))
        assertFalse(PageHook.isUsableBody(""))
        assertFalse(PageHook.isUsableBody("<!DOCTYPE html><html><body></body></html>"))
        assertFalse(PageHook.isUsableBody("<html lang=\"zh-CN\">"))
        assertTrue(PageHook.isUsableBody("""{"aweme_detail":{"aweme_id":"7690515078848542890"}}"""))
        assertTrue(PageHook.isUsableBody("""[{"aweme_id":"7690515078848542890"}]"""))
    }

    @Test
    fun `dedupe key keeps different aweme ids apart`() {
        val u1 = "https://www.douyin.com/aweme/v1/web/aweme/detail/?device_platform=webapp&aweme_id=7690515078848542890&a_bogus=x"
        val u2 = "https://www.douyin.com/aweme/v1/web/aweme/detail/?device_platform=webapp&aweme_id=7690156149479314361&a_bogus=y"
        assertNotEquals(PageHook.apiDedupeKey(u1), PageHook.apiDedupeKey(u2))

        // 同一作品的重试/带不同签名的两次请求 → 同一个键（只解析一次）
        val u1Again = "https://www.douyin.com/aweme/v1/web/aweme/detail/?aweme_id=7690515078848542890&a_bogus=z"
        assertEquals(PageHook.apiDedupeKey(u1), PageHook.apiDedupeKey(u1Again))

        // note 接口同样支持
        assertNotEquals(
            PageHook.apiDedupeKey("https://www.douyin.com/aweme/v1/web/note/?note_id=1111111111111111111"),
            PageHook.apiDedupeKey("https://www.douyin.com/aweme/v1/web/note/?note_id=2222222222222222222")
        )
    }
}
