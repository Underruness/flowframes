package com.flowframes.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyStatsTest {

    @Test
    fun `empty stats report zero`() {
        val stats = LatencyStats()
        assertEquals(0, stats.size)
        assertEquals(0.0, stats.mean(), 1e-9)
        assertEquals(0.0, stats.percentile(0.5), 1e-9)
        assertEquals("", stats.summary("Inference"))
    }

    @Test
    fun `mean and percentiles follow the samples`() {
        val stats = LatencyStats()
        listOf(100, 200, 300, 400, 500, 900).forEach { stats.add(it) }
        assertEquals(6, stats.size)
        assertEquals(400.0, stats.mean(), 1e-9)
        assertEquals(100, stats.minMs())
        assertEquals(900, stats.maxMs())
        // nearest rank over 6 samples: p50 -> index 2 (300), p95 -> index 4 (500)
        assertEquals(300.0, stats.percentile(0.5), 1e-9)
        assertEquals(500.0, stats.percentile(0.95), 1e-9)
    }

    @Test
    fun `percentiles are clamped and sorted`() {
        val stats = LatencyStats()
        listOf(900, 100, 500).forEach { stats.add(it) }
        assertEquals(100.0, stats.percentile(0.0), 1e-9)
        assertEquals(900.0, stats.percentile(1.0), 1e-9)
        assertEquals(900.0, stats.percentile(2.0), 1e-9)
    }

    @Test
    fun `summary carries the label and the sample count`() {
        val stats = LatencyStats()
        repeat(4) { stats.add(380) }
        val line = stats.summary("Inference latency")
        assertTrue(line.startsWith("Inference latency: min 380 ms"))
        assertTrue(line.contains("(4 samples)"))
    }

    @Test
    fun `drift stays silent until both windows are full`() {
        val stats = LatencyStats()
        repeat(10) { stats.add(380) }
        assertTrue(!stats.hasDrift())
        assertEquals(0.0, stats.driftPercent(), 1e-9)
        assertTrue(!stats.summary("Inference").contains("drift"))
    }

    @Test
    fun `throttling shows up as positive drift`() {
        val stats = LatencyStats()
        // 64 fast frames, then 64 frames twice as slow: the mean alone would hide this.
        repeat(64) { stats.add(380) }
        repeat(64) { stats.add(760) }
        assertTrue(stats.hasDrift())
        assertEquals(100.0, stats.driftPercent(), 0.5)
        assertEquals(380.0, stats.openingMeanMs(), 1e-9)
        assertEquals(760.0, stats.recentMeanMs(), 1e-9)
        assertTrue(stats.summary("Inference").contains("drift +100%"))
    }

    @Test
    fun `a steady run reports no drift`() {
        val stats = LatencyStats()
        repeat(200) { stats.add(380) }
        assertTrue(stats.hasDrift())
        assertEquals(0.0, stats.driftPercent(), 1e-9)
        assertTrue(stats.summary("Inference").contains("steady"))
    }

    @Test
    fun `buffer capacity truncates samples but not the aggregates`() {
        val stats = LatencyStats(capacity = 4)
        repeat(20) { stats.add(if (it == 19) 4000 else 100) }
        assertEquals(4, stats.size)
        assertEquals(4000, stats.maxMs())
        assertEquals(100, stats.minMs())
        assertEquals(295.0, stats.mean(), 1e-9)
    }
}
