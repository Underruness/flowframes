package com.flowframes.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Runs the bundled RIFE model through [RifeEngine] and compares it with reference outputs
 * produced by the Python/ONNX Runtime implementation (see `tools/reference/` in the repo).
 *
 * This is the test that proves the tensor layout (planar RGB, frame0 then frame1, timestep
 * semantics) matches the reference implementation.
 */
class RifeEngineTest {

    private val modelFile = File("src/main/assets/rife49_ensemble_False_scale_1_sim.onnx")
    private val modelFileEnsemble = File("src/main/assets/rife49_ensemble_True_scale_1_sim.onnx")

    private fun floats(path: String): FloatArray {
        val bytes = File(path).readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val out = FloatArray(buf.remaining())
        buf.get(out)
        return out
    }

    private fun resource(name: String): FloatArray {
        val bytes = javaClass.getResourceAsStream("/rife/$name")!!.use { it.readBytes() }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val out = FloatArray(buf.remaining())
        buf.get(out)
        return out
    }

    private fun maxAbsDiff(a: FloatArray, b: FloatArray): Float {
        var m = 0f
        for (i in a.indices) {
            val d = abs(a[i] - b[i])
            if (d > m) m = d
        }
        return m
    }

    @Test
    fun `model file is bundled`() {
        assertTrue("missing ${modelFile.absolutePath}", modelFile.isFile)
        assertTrue(modelFile.length() > 15_000_000)
    }

    @Test
    fun `engine matches the python reference at t=0_5`() {
        if (!modelFile.isFile) return
        val w = 64
        val h = 48
        val f0 = resource("frame0.f32")
        val f1 = resource("frame1.f32")
        val expected = resource("ens_false/out_0_500000_f32")
        val out = FloatArray(w * h * 3)

        RifeEngine(modelFile.absolutePath, intraOpThreads = 2, useNnapi = false).use { engine ->
            engine.interpolate(f0, f1, w, h, 0.5f, out)
        }

        assertEquals(w * h * 3, out.size)
        assertEquals(w * h * 3, expected.size)
        val diff = maxAbsDiff(out, expected)
        assertTrue("max abs diff vs reference = $diff", diff < 2e-3f)
        // sanity: the result is a plausible image and not a copy of one input
        assertTrue(out.all { it > -0.05f && it < 1.05f })
        assertTrue(maxAbsDiff(out, f0) > 1e-3f)
    }

    @Test
    fun `different timesteps give different midpoints`() {
        if (!modelFile.isFile) return
        val w = 64
        val h = 48
        val f0 = resource("frame0.f32")
        val f1 = resource("frame1.f32")
        val a = FloatArray(w * h * 3)
        val b = FloatArray(w * h * 3)
        RifeEngine(modelFile.absolutePath, intraOpThreads = 2).use { engine ->
            engine.interpolate(f0, f1, w, h, 1f / 3f, a)
            engine.interpolate(f0, f1, w, h, 2f / 3f, b)
        }
        val expectedA = resource("ens_false/out_0_333333_f32")
        val expectedB = resource("ens_false/out_0_666667_f32")
        assertTrue(maxAbsDiff(a, expectedA) < 2e-3f)
        assertTrue(maxAbsDiff(b, expectedB) < 2e-3f)
        assertTrue("phases must differ", maxAbsDiff(a, b) > 1e-3f)
    }

    @Test
    fun `ensemble model also matches its reference`() {
        if (!modelFileEnsemble.isFile) return
        val w = 64
        val h = 48
        val f0 = resource("frame0.f32")
        val f1 = resource("frame1.f32")
        val expected = resource("ens_true/out_0_500000_f32")
        val out = FloatArray(w * h * 3)
        RifeEngine(modelFileEnsemble.absolutePath, intraOpThreads = 2).use { engine ->
            engine.interpolate(f0, f1, w, h, 0.5f, out)
        }
        val diff = maxAbsDiff(out, expected)
        assertTrue("ensemble max abs diff vs reference = $diff", diff < 2e-3f)
    }

    @Test
    fun `non multiple of 32 resolution works`() {
        if (!modelFile.isFile) return
        val w = 50
        val h = 34
        val f0 = FloatArray(w * h * 3) { 0.2f }
        val f1 = FloatArray(w * h * 3) { 0.8f }
        val out = FloatArray(w * h * 3) { -1f }
        RifeEngine(modelFile.absolutePath, intraOpThreads = 2).use { engine ->
            engine.interpolate(f0, f1, w, h, 0.5f, out)
        }
        assertTrue(out.all { it in 0f..1f })
        // A uniform pair must interpolate to about the mean value, at every pixel.
        val mean = out.average().toFloat()
        assertEquals(0.5f, mean, 0.05f)
    }

    @Test
    fun `engine reports the model inputs`() {
        if (!modelFile.isFile) return
        val logs = mutableListOf<String>()
        RifeEngine(modelFile.absolutePath, intraOpThreads = 1, log = { logs.add(it) }).use { engine ->
            assertTrue(engine.modelName.endsWith(".onnx"))
        }
        assertTrue(logs.any { it.contains("img0") })
        assertTrue(logs.any { it.contains("timestep") })
    }
}
