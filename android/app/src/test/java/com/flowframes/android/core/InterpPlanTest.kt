package com.flowframes.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InterpPlanTest {

    @Test
    fun `phases for factor 2 is a single midpoint`() {
        val p = InterpPlan.phases(2)
        assertEquals(1, p.size)
        assertEquals(0.5, p[0], 1e-9)
    }

    @Test
    fun `phases for factor 4 are evenly spread`() {
        val p = InterpPlan.phases(4)
        assertEquals(3, p.size)
        assertEquals(0.25, p[0], 1e-9)
        assertEquals(0.5, p[1], 1e-9)
        assertEquals(0.75, p[2], 1e-9)
    }

    @Test
    fun `factor 1 produces no intermediate frame`() {
        assertEquals(0, InterpPlan.phases(1).size)
    }

    @Test
    fun `output fps is multiplied`() {
        assertEquals(60.0, InterpPlan.outputFps(30.0, 2), 1e-9)
        assertEquals(120.0, InterpPlan.outputFps(30.0, 4), 1e-9)
        assertEquals(0.0, InterpPlan.outputFps(0.0, 2), 1e-9)
    }

    @Test
    fun `max output fps lowers the factor`() {
        assertEquals(2, InterpPlan.effectiveFactor(4, 24.0, 60.0))
        assertEquals(4, InterpPlan.effectiveFactor(4, 24.0, 120.0))
        assertEquals(1, InterpPlan.effectiveFactor(4, 60.0, 60.0))
        assertEquals(4, InterpPlan.effectiveFactor(4, 24.0, 0.0))
    }

    @Test
    fun `duplicates are skipped only when dedupe is on`() {
        val frame = IntArray(16) { 0xFF101010.toInt() }
        val opts = InterpPlan.Options(dedupe = true, dedupeThreshold = 0.0025)
        assertEquals(InterpPlan.Decision.SKIP_DUPLICATE, InterpPlan.decide(frame, frame, opts, 0.0, 0.0))
        val off = opts.copy(dedupe = false)
        assertEquals(InterpPlan.Decision.INTERPOLATE, InterpPlan.decide(frame, frame, off, 0.0, 0.0))
    }

    @Test
    fun `scene cuts are not interpolated`() {
        val frame = IntArray(16) { 0xFF101010.toInt() }
        val opts = InterpPlan.Options(fixSceneChanges = true, sceneThreshold = 0.35)
        assertEquals(InterpPlan.Decision.CUT, InterpPlan.decide(frame, frame, opts, 0.4, 0.9))
        val fixed = opts.copy(fixSceneChanges = false)
        assertEquals(InterpPlan.Decision.INTERPOLATE, InterpPlan.decide(frame, frame, fixed, 0.4, 0.9))
    }

    @Test
    fun `first frame is always interpolated`() {
        val frame = IntArray(4)
        assertEquals(
            InterpPlan.Decision.INTERPOLATE,
            InterpPlan.decide(null, frame, InterpPlan.Options(dedupe = true, fixSceneChanges = true), 0.0, 0.0)
        )
    }

    @Test
    fun `dedupe takes precedence over scene cuts`() {
        val frame = IntArray(4)
        val opts = InterpPlan.Options(dedupe = true, dedupeThreshold = 0.5, fixSceneChanges = true, sceneThreshold = 0.1)
        assertEquals(InterpPlan.Decision.SKIP_DUPLICATE, InterpPlan.decide(frame, frame, opts, 0.2, 0.9))
    }

    @Test
    fun `frames per pair equals the factor`() {
        assertTrue(InterpPlan.framesPerPair(2) == 2)
    }
}
