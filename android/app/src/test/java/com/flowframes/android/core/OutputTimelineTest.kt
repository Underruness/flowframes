package com.flowframes.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputTimelineTest {

    @Test
    fun `multiplier gives evenly spaced frames ending on the source`() {
        val tl = OutputTimeline.multiplier(4)
        val items = tl.between(0, 40_000)
        assertEquals(listOf(10_000L, 20_000L, 30_000L, 40_000L), items.map { it.ptsUs })
        assertEquals(listOf(0.25, 0.5, 0.75, 1.0), items.map { it.t })
        assertEquals(3, items.count { it.needsInference })
        assertEquals(listOf(OutputTimeline.Item(40_000, 1.0)), tl.cut(0, 40_000))
    }

    /** Runs a whole clip through the timeline and returns all output timestamps. */
    private fun run(tl: OutputTimeline, srcFps: Double, frames: Int): Pair<List<OutputTimeline.Item>, Int> {
        val pts = (0 until frames).map { Math.round(it * 1_000_000.0 / srcFps) }
        tl.originUs = pts[0]
        val out = ArrayList<OutputTimeline.Item>()
        out += OutputTimeline.Item(pts[0], 0.0)
        for (i in 1 until frames) out += tl.between(pts[i - 1], pts[i])
        return out to out.count { it.needsInference }
    }

    @Test
    fun `24 to 60 fps lands on an exact grid`() {
        val (items, infer) = run(OutputTimeline.targetFps(60.0), 24.0, 241) // 10 s
        assertEquals(601, items.size)
        items.zipWithNext().forEach { (a, b) ->
            val d = b.ptsUs - a.ptsUs
            assertTrue("step $d", d in 16_666..16_667)
        }
        // 60/24 = 2.5: every other source frame is on the grid, the rest is interpolated.
        assertEquals(601 - 121, infer)
        assertTrue(items.all { it.t in 0.0..1.0 })
    }

    @Test
    fun `30 to 60 fps is x2 and 30 to 30 needs no inference`() {
        val (x2, infer2) = run(OutputTimeline.targetFps(60.0), 30.0, 31)
        assertEquals(61, x2.size)
        assertEquals(30, infer2)
        assertTrue(x2.filter { it.needsInference }.all { kotlin.math.abs(it.t - 0.5) < 1e-3 })
        val (same, infer1) = run(OutputTimeline.targetFps(30.0), 30.0, 31)
        assertEquals(31, same.size)
        assertEquals(0, infer1)
    }

    @Test
    fun `29_97 to 59_94 and down-conversion`() {
        val (items, infer) = run(OutputTimeline.targetFps(59.94), 29.97, 300)
        assertTrue(items.size in 597..599)
        assertTrue(infer in 298..300)
        val (down, _) = run(OutputTimeline.targetFps(24.0), 60.0, 601)
        assertEquals(241, down.size)
    }

    @Test
    fun `cuts show the nearest source frame`() {
        val tl = OutputTimeline.targetFps(60.0).also { it.originUs = 0 }
        val cut = tl.cut(0, 41_667)
        assertTrue(cut.none { it.needsInference })
        assertEquals(listOf(16_667L, 33_333L), cut.map { it.ptsUs })
        assertEquals(listOf(0.0, 1.0), cut.map { it.t })
    }
}
