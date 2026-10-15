package com.dy.scraper.util

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class PacingTest {

    @Test
    fun `nextDelayMs zero or negative base means no wait`() {
        assertEquals(0L, Pacing.nextDelayMs(0L, 0.5))
        assertEquals(0L, Pacing.nextDelayMs(-100L, 0.5))
    }

    @Test
    fun `nextDelayMs without jitter returns exact base`() {
        assertEquals(2500L, Pacing.nextDelayMs(2500L, 0.0))
        assertEquals(800L, Pacing.nextDelayMs(800L, -1.0))
    }

    @Test
    fun `nextDelayMs with jitter stays within base and base plus ratio`() {
        val base = 800L
        repeat(200) { seed ->
            val v = Pacing.nextDelayMs(base, 0.5, Random(seed))
            assertTrue("$v out of range", v in base..(base + base / 2))
        }
    }

    @Test
    fun `nextDelayMs clamps jitter ratio at 200 percent`() {
        repeat(50) { seed ->
            val v = Pacing.nextDelayMs(1000L, 9.0, Random(seed))
            assertTrue("$v out of range", v in 1000L..3000L)
        }
    }

    @Test
    fun `backoffDelayMs follows table then clamps`() {
        assertEquals(2000L, Pacing.backoffDelayMs(0, 0.0))
        assertEquals(5000L, Pacing.backoffDelayMs(1, 0.0))
        assertEquals(10000L, Pacing.backoffDelayMs(2, 0.0))
        assertEquals(10000L, Pacing.backoffDelayMs(99, 0.0))
    }

    @Test
    fun `backoffDelayMs jitter stays within 30 percent`() {
        repeat(100) { seed ->
            val v = Pacing.backoffDelayMs(1, 0.3, Random(seed))
            assertTrue("$v out of range", v in 5000L..6500L)
        }
    }

    @Test
    fun `requestStaggerMs stays in 150 to 299`() {
        repeat(100) { seed ->
            val v = Pacing.requestStaggerMs(Random(seed))
            assertTrue("$v out of range", v in 150L..299L)
        }
    }

    @Test
    fun `isRiskHttpCode detects risk codes`() {
        assertTrue(Pacing.isRiskHttpCode(403))
        assertTrue(Pacing.isRiskHttpCode(412))
        assertTrue(Pacing.isRiskHttpCode(429))
        assertFalse(Pacing.isRiskHttpCode(200))
        assertFalse(Pacing.isRiskHttpCode(404))
        assertFalse(Pacing.isRiskHttpCode(500))
    }

    @Test
    fun `formatMs prints short text`() {
        assertEquals("800ms", Pacing.formatMs(800L))
        assertEquals("2.5s", Pacing.formatMs(2500L))
        assertEquals("10.0s", Pacing.formatMs(10000L))
    }
}
