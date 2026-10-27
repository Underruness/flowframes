package com.flowframes.android.bench

import com.flowframes.android.core.FrameMath
import com.flowframes.android.core.RifeEngine
import com.flowframes.android.core.YuvConverter
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.random.Random

/**
 * Not a correctness test: a probe that prints per-stage timings for one 1280x720 frame.
 * Skipped unless FF_BENCH=1 so a normal `gradlew test` stays fast.
 */
class PerfProbeTest {

    private val w = 1280
    private val h = 720
    private val srcW = 1920
    private val srcH = 1080

    @Test
    fun probe() {
        assumeTrue("set FF_BENCH=1 to run the benchmark", System.getenv("FF_BENCH") == "1")

        val rnd = Random(7)
        val argb = IntArray(w * h) { 0xFF000000.toInt() or rnd.nextInt(0xFFFFFF) }
        val argb4k = IntArray(srcW * srcH) { 0xFF000000.toInt() or rnd.nextInt(0xFFFFFF) }
        val planar = FloatArray(w * h * 3) { rnd.nextFloat() }
        val planar2 = FloatArray(w * h * 3) { rnd.nextFloat() }
        val out = IntArray(w * h)

        val ySize = srcW * srcH
        val uvSize = (srcW / 2) * (srcH / 2)
        val uvStride = srcW / 2
        val yBuf = ByteBuffer.allocateDirect(ySize)
        val uBuf = ByteBuffer.allocateDirect(uvSize)
        val vBuf = ByteBuffer.allocateDirect(uvSize)
        for (i in 0 until ySize) yBuf.put(i, rnd.nextInt(256).toByte())
        for (i in 0 until uvSize) {
            uBuf.put(i, rnd.nextInt(256).toByte())
            vBuf.put(i, rnd.nextInt(256).toByte())
        }

        report("resizeArgb 1920x1080 -> 1280x720") {
            FrameMath.resizeArgb(argb4k, srcW, srcH, w, h)
        }
        report("rotateArgb 1920x1080 (90deg)") {
            FrameMath.rotateArgb(argb4k, srcW, srcH, 90)
        }
        report("yuv420ToArgb 1920x1080") {
            YuvConverter.yuv420ToArgb(
                yBuf, uBuf, vBuf, srcW, uvStride, 1, srcW, srcH,
                YuvConverter.BT709_LIMITED, IntArray(srcW * srcH)
            )
        }
        report("old decode path: yuv+rotate+resize") {
            val full = IntArray(srcW * srcH)
            YuvConverter.yuv420ToArgb(
                yBuf, uBuf, vBuf, srcW, uvStride, 1, srcW, srcH,
                YuvConverter.BT709_LIMITED, full
            )
            val rot = FrameMath.rotateArgb(full, srcW, srcH, 90)
            FrameMath.resizeArgb(rot.pixels, rot.width, rot.height, w, h)
        }
        val decScratch = YuvConverter.PlaneScratch()
        report("plane copy only (1080p Y + chroma)") {
            decScratch.luma(yBuf, srcW, srcH, srcW)
            decScratch.chroma(uBuf, uvStride * ((srcH + 1) / 2))
            decScratch.chromaV(vBuf, uvStride * ((srcH + 1) / 2))
        }
        val encScratch = YuvConverter.EncScratch()
        report("fused yuv->scaled+rotated 1080p->720p") {
            YuvConverter.yuv420ToArgbScaled(
                yBuf, uBuf, vBuf, srcW, uvStride, 1, srcW, srcH, w, h, 90,
                YuvConverter.BT709_LIMITED, out, decScratch
            )
        }
        report("argbToPlanar 1280x720") {
            FrameMath.argbToPlanar(argb, planar)
        }
        report("old output: planarToArgb + argbToYuv") {
            FrameMath.planarToArgb(planar, out)
            val yb = ByteBuffer.allocateDirect(w * h)
            val ub = ByteBuffer.allocateDirect(w * h / 4)
            val vb = ByteBuffer.allocateDirect(w * h / 4)
            YuvConverter.argbToYuv420(
                out, w, h, yb, ub, vb, w, w / 2, 1, YuvConverter.BT709_LIMITED
            )
        }
        report("fused planarToYuv420 1280x720") {
            val yb = ByteBuffer.allocateDirect(w * h)
            val ub = ByteBuffer.allocateDirect(w * h / 4)
            val vb = ByteBuffer.allocateDirect(w * h / 4)
            YuvConverter.planarToYuv420(
                planar, w, h, yb, ub, vb, w, w / 2, 1, YuvConverter.BT709_LIMITED, encScratch
            )
        }
        report("dedupe stats 1280x720 (one pass)") {
            FrameMath.lumaStats(argb, argb4k, 0.08)
        }

        val model = modelFile() ?: run {
            println("BENCH: modèle introuvable, inference non mesurée")
            return
        }
        println("BENCH inference threads: ${RifeEngine.defaultThreads()}")
        RifeEngine(model, intraOpThreads = RifeEngine.defaultThreads(), log = { println("BENCH rt: $it") })
            .use { engine ->
                engine.interpolate(planar, planar2, w, h, 0.5f, FloatArray(w * h * 3)) // warmup
                report("RIFE inference 1280x720 (${engine.modelName})") {
                    engine.interpolate(planar, planar2, w, h, 0.5f, FloatArray(w * h * 3))
                }
            }
    }

    private fun modelFile(): String? {
        val candidates = listOf(
            "src/main/assets/rife49_ensemble_False_scale_1_sim.onnx",
            "app/src/main/assets/rife49_ensemble_False_scale_1_sim.onnx",
            "android/app/src/main/assets/rife49_ensemble_False_scale_1_sim.onnx"
        )
        return candidates.map { File(it) }.firstOrNull { it.isFile }?.absolutePath
    }

    private fun report(label: String, iterations: Int = 5, block: () -> Unit) {
        repeat(2) { block() } // warm the JIT
        val times = LongArray(iterations)
        for (i in 0 until iterations) {
            val t0 = System.nanoTime()
            block()
            times[i] = System.nanoTime() - t0
        }
        times.sort()
        val median = times[iterations / 2] / 1_000_000.0
        val min = times.first() / 1_000_000.0
        val spread = abs(median - min).coerceAtLeast(0.0)
        println(String.format("BENCH %-44s median %7.2f ms  min %7.2f ms", label, median, min))
    }
}
