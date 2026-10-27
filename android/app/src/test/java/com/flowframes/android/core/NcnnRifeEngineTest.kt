package com.flowframes.android.core

import com.flowframes.android.job.EngineFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Future
import kotlin.math.log10

/**
 * Drives the real native engine (JNI + ncnn) and checks it against the ONNX Runtime references of
 * RIFE 4.9: same weights, so the output must agree to within fp16 rounding.
 *
 * Needs the host build of libflowframes_rife (see android/native-test); skipped otherwise. The GPU
 * cases run on whatever Vulkan device the host has (Mesa lavapipe works).
 */
class NcnnRifeEngineTest {

    private val modelDir = File("src/main/assets/ncnn/rife-v4.9")
    private val w = 64
    private val h = 48

    private fun resource(name: String): FloatArray {
        val bytes = javaClass.getResourceAsStream("/rife/$name")!!.use { it.readBytes() }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buf.remaining()).also { buf.get(it) }
    }

    private fun psnr(a: FloatArray, b: FloatArray): Double {
        var se = 0.0
        for (i in a.indices) {
            val d = (a[i] - b[i].coerceIn(0f, 1f)).toDouble()
            se += d * d
        }
        return 10.0 * log10(1.0 / (se / a.size).coerceAtLeast(1e-12))
    }

    private fun run(engine: NcnnRifeEngine, t: Float): FloatArray {
        val out = FloatArray(w * h * 3)
        engine.interpolate(resource("frame0.f32"), resource("frame1.f32"), w, h, t, out)
        return out
    }

    @Test
    fun `ncnn CPU matches the ONNX reference`() {
        assumeTrue("native test library not built", NcnnRifeEngine.isAvailable)
        NcnnRifeEngine(modelDir.absolutePath, gpuId = -1, cpuThreads = 2, fp16 = false).use { engine ->
            for ((t, ref) in listOf(1f / 3f to "out_0_333333_f32", 0.5f to "out_0_500000_f32", 2f / 3f to "out_0_666667_f32")) {
                val db = psnr(run(engine, t), resource("ens_false/$ref"))
                assertTrue("t=$t: $db dB against ONNX Runtime", db > 55.0)
            }
        }
    }

    @Test
    fun `ncnn GPU matches the ONNX reference in fp16`() {
        assumeTrue(NcnnRifeEngine.isAvailable && NcnnRifeEngine.gpuCount() > 0)
        for (fp16 in listOf(false, true)) {
            NcnnRifeEngine(modelDir.absolutePath, gpuId = 0, fp16 = fp16).use { engine ->
                val db = psnr(run(engine, 0.5f), resource("ens_false/out_0_500000_f32"))
                assertTrue("fp16=$fp16 on ${engine.deviceName}: $db dB", db > if (fp16) 45.0 else 55.0)
            }
        }
    }

    @Test
    fun `endpoints copy the inputs`() {
        assumeTrue(NcnnRifeEngine.isAvailable)
        NcnnRifeEngine(modelDir.absolutePath, gpuId = -1, cpuThreads = 1).use { engine ->
            assertArrayEquals(resource("frame0.f32"), run(engine, 0f), 0f)
            assertArrayEquals(resource("frame1.f32"), run(engine, 1f), 0f)
        }
    }

    @Test
    fun `one shared engine serves a pool of concurrent workers`() {
        assumeTrue(NcnnRifeEngine.isAvailable)
        val gpu = if (NcnnRifeEngine.gpuCount() > 0) 0 else -1
        val engine = NcnnRifeEngine(modelDir.absolutePath, gpuId = gpu, cpuThreads = 1)
        val expected = run(engine, 0.5f)
        InferencePool(listOf<FrameEngine>(engine), workers = 3, threadsPerWorker = 0).use { pool ->
            val f0 = resource("frame0.f32")
            val f1 = resource("frame1.f32")
            val outs = Array(6) { FloatArray(w * h * 3) }
            val futures = ArrayList<Future<*>>()
            outs.forEach { futures += pool.submit(f0, f1, w, h, 0.5f, it) }
            futures.forEach { it.get() }
            outs.forEach { assertArrayEquals(expected, it, 0f) }
        }
    }

    @Test
    fun `self-test accepts a working engine at a non-aligned size`() {
        assumeTrue(NcnnRifeEngine.isAvailable)
        NcnnRifeEngine(modelDir.absolutePath, gpuId = -1, cpuThreads = 2).use { engine ->
            val db = EngineFactory.selfTest(engine, 100, 70)
            assertTrue("self-test $db dB", db > 40.0)
        }
    }

    @Test
    fun `self-test rejects an engine that returns garbage`() {
        val broken = object : FrameEngine {
            override val label = "broken"
            override val threadSafe = true
            override fun interpolate(frame0: FloatArray, frame1: FloatArray, width: Int, height: Int, t: Float, out: FloatArray) {
                out.fill(0f)
            }
            override fun close() {}
        }
        assertTrue(EngineFactory.selfTest(broken, 64, 48) < 15.0)
    }

    @Test
    fun `hybrid pool of a GPU and a CPU engine gives the same frames`() {
        assumeTrue(NcnnRifeEngine.isAvailable)
        val gpuId = if (NcnnRifeEngine.gpuCount() > 0) 0 else -1
        val gpu = NcnnRifeEngine(modelDir.absolutePath, gpuId = gpuId, cpuThreads = 1)
        val cpu = NcnnRifeEngine(modelDir.absolutePath, gpuId = -1, cpuThreads = 1)
        val reference = resource("ens_false/out_0_500000_f32")
        InferencePool(listOf<FrameEngine>(gpu, gpu, cpu), workers = 3, threadsPerWorker = 1).use { pool ->
            assertTrue(pool.distinctEngines.size == 2)
            val f0 = resource("frame0.f32")
            val f1 = resource("frame1.f32")
            val outs = Array(9) { FloatArray(w * h * 3) }
            val futures = ArrayList<Future<*>>()
            outs.forEach { futures += pool.submit(f0, f1, w, h, 0.5f, it) }
            futures.forEach { it.get() }
            outs.forEach { assertTrue(psnr(it, reference) > 55.0) }
            val stats = pool.engineStats()
            assertTrue(stats, stats.isNotEmpty())
        }
    }

    @Test
    fun `self-test gives up on an engine that never returns`() {
        val hung = object : FrameEngine {
            override val label = "hung"
            override val threadSafe = true
            override fun interpolate(frame0: FloatArray, frame1: FloatArray, width: Int, height: Int, t: Float, out: FloatArray) {
                Thread.sleep(60_000)
            }
            override fun close() {}
        }
        val start = System.nanoTime()
        val error = runCatching { EngineFactory.selfTestWithTimeout(hung, 64, 48, timeoutS = 1) }.exceptionOrNull()
        assertTrue("$error", error is java.util.concurrent.TimeoutException)
        assertTrue((System.nanoTime() - start) < 10_000_000_000L)
    }

    @Test
    fun `8-bit ARGB path matches the float path`() {
        assumeTrue(NcnnRifeEngine.isAvailable)
        val f0 = resource("frame0.f32")
        val f1 = resource("frame1.f32")
        val a0 = IntArray(w * h).also { FrameMath.planarToArgb(f0, it) }
        val a1 = IntArray(w * h).also { FrameMath.planarToArgb(f1, it) }
        // Float reference from the same 8-bit inputs, so only the output rounding differs.
        val q0 = FloatArray(w * h * 3).also { FrameMath.argbToPlanar(a0, it) }
        val q1 = FloatArray(w * h * 3).also { FrameMath.argbToPlanar(a1, it) }
        NcnnRifeEngine(modelDir.absolutePath, gpuId = -1, cpuThreads = 2).use { engine ->
            val ref = FloatArray(w * h * 3)
            engine.interpolate(q0, q1, w, h, 0.5f, ref)
            val expected = IntArray(w * h).also { FrameMath.planarToArgb(ref, it) }
            val got = IntArray(w * h)
            engine.interpolateArgb(a0, a1, w, h, 0.5f, got)
            var maxDiff = 0
            for (i in got.indices) for (sh in intArrayOf(0, 8, 16)) {
                maxDiff = maxOf(maxDiff, kotlin.math.abs(((got[i] ushr sh) and 0xFF) - ((expected[i] ushr sh) and 0xFF)))
            }
            assertTrue("max channel difference $maxDiff", maxDiff <= 1)
            assertTrue(got.all { (it ushr 24) == 0xFF })
        }
    }

    @Test
    fun `native ARGB to YUV matches the Kotlin converter`() {
        assumeTrue(NcnnRifeEngine.isAvailable)
        val ww = 37
        val hh = 21
        val rnd = java.util.Random(7)
        val argb = IntArray(ww * hh) { 0xFF000000.toInt() or (rnd.nextInt() and 0xFFFFFF) }
        for (spec in listOf(YuvConverter.BT601_LIMITED, YuvConverter.BT709_LIMITED, YuvConverter.BT709_FULL)) {
            val yStride = 40
            val uvStride = 20
            fun bufs() = Triple(
                ByteBuffer.allocateDirect(yStride * hh),
                ByteBuffer.allocateDirect(uvStride * ((hh + 1) / 2)),
                ByteBuffer.allocateDirect(uvStride * ((hh + 1) / 2))
            )
            val (ky, ku, kv) = bufs()
            YuvConverter.argbToYuv420(argb, ww, hh, ky, ku, kv, yStride, uvStride, 1, spec)
            val (ny, nu, nv) = bufs()
            assertTrue(NativeFrames.argbToYuv420(argb, ww, hh, ny, nu, nv, yStride, uvStride, 1, spec))
            for ((k, n) in listOf(ky to ny, ku to nu, kv to nv)) {
                for (i in 0 until k.capacity()) {
                    assertTrue("${spec.label} byte $i: ${k.get(i)} vs ${n.get(i)}", kotlin.math.abs(k.get(i) - n.get(i)) <= 1)
                }
            }
        }
    }

    @Test
    fun `GPU shader cache is written to disk`() {
        assumeTrue(NcnnRifeEngine.isAvailable && NcnnRifeEngine.gpuCount() > 0)
        val dir = java.nio.file.Files.createTempDirectory("ffcache").toFile()
        NcnnRifeEngine.setShaderCacheDir(dir)
        NcnnRifeEngine(modelDir.absolutePath, gpuId = 0).use { engine -> run(engine, 0.5f) }
        assertTrue(NcnnRifeEngine.shaderCacheStats(), File(dir, "spirv.cache").length() > 0)
    }
}
