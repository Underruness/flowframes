package com.flowframes.android.job

import android.content.Context
import com.flowframes.android.core.DeviceInfo
import com.flowframes.android.core.FrameMath
import com.flowframes.android.core.RifeEngine
import java.io.File
import java.util.Locale

/**
 * Measures the engine on this device.
 *
 * The interesting settings (execution provider, thread count, static shapes) interact in ways no
 * desktop benchmark can predict: XNNPACK may claim part of the graph, NNAPI may work or silently
 * fall back, and a big.LITTLE CPU may be slower with every core busy. So the app measures itself
 * and reports what actually won here.
 */
object EngineBenchmark {

    private data class Config(
        val label: String,
        val threads: Int,
        val xnnpack: Boolean,
        val nnapi: Boolean,
        val staticShapes: Boolean
    )

    private data class Result(val config: Config, val msPerFrame: Double, val error: String?)

    fun run(
        context: Context,
        settings: Settings,
        runs: Int = 3,
        report: (String) -> Unit = {}
    ): String {
        val cap = if (settings.maxLongSide <= 0) 1280 else settings.maxLongSide
        val (w, h) = FrameMath.fitWithin(cap, (cap * 9) / 16, cap, cap)
        // Keep the buffers cache friendly: a 1080p canvas costs ~25 MB per frame array.
        val width = ((w + 31) / 32) * 32
        val height = ((h + 31) / 32) * 32
        val modelPath = ModelStore.prepare(context, settings.quality)
        val defaultThreads = RifeEngine.defaultThreads()

        val configs = listOf(
            Config("XNNPACK+static ${defaultThreads}T", defaultThreads, true, false, true),
            Config("XNNPACK+dynamic ${defaultThreads}T", defaultThreads, true, false, false),
            Config("XNNPACK+static 4T", 4, true, false, true),
            Config("CPU only+static ${defaultThreads}T", defaultThreads, false, false, true),
            Config("NNAPI+XNNPACK+static ${defaultThreads}T", defaultThreads, true, true, true)
        )

        report("Benchmark: ${width}x$height canvas · ${File(modelPath).name} · $runs runs per configuration")
        report(DeviceInfo.headline(context))

        val frames0 = FloatArray(width * height * 3) { (it % 251) / 251f }
        val frames1 = FloatArray(width * height * 3) { (it % 197) / 197f }
        val out = FloatArray(width * height * 3)
        val results = ArrayList<Result>(configs.size)
        var topOps: List<String> = emptyList()

        for ((index, cfg) in configs.withIndex()) {
            val result = runCatching {
                val t0 = System.currentTimeMillis()
                val engine = RifeEngine(
                    modelPath = modelPath,
                    intraOpThreads = cfg.threads,
                    useNnapi = cfg.nnapi,
                    useXnnpack = cfg.xnnpack,
                    frameWidth = if (cfg.staticShapes) width else 0,
                    frameHeight = if (cfg.staticShapes) height else 0,
                    profileDir = if (index == 0) File(context.cacheDir, "ort-profile") else null,
                    log = report
                )
                engine.use { e ->
                    e.warmUp(width, height)
                    val initMs = System.currentTimeMillis() - t0
                    val start = System.nanoTime()
                    repeat(runs) { e.interpolate(frames0, frames1, width, height, 0.5f, out) }
                    val perFrame = (System.nanoTime() - start) / 1_000_000.0 / runs
                    if (index == 0) topOps = e.stopProfiling()
                    report(
                        String.format(
                            Locale.US, "[%d/%d] %s → %.0f ms/frame (session init %d ms)",
                            index + 1, configs.size, cfg.label, perFrame, initMs
                        )
                    )
                    Result(cfg, perFrame, null)
                }
            }.getOrElse { t ->
                report("[${index + 1}/${configs.size}] ${cfg.label} → failed: ${t.message ?: t.javaClass.simpleName}")
                Result(cfg, Double.MAX_VALUE, t.message ?: t.javaClass.simpleName)
            }
            results += result
        }

        val best = results.filter { it.error == null }.minByOrNull { it.msPerFrame }
        val sb = StringBuilder()
        sb.appendLine("=== FLOWFRAMES ENGINE BENCHMARK ===")
        sb.appendLine("Canvas: ${width}x$height · model ${File(modelPath).name} · $runs runs per config")
        DeviceInfo.lines(context).forEach { sb.appendLine(it) }
        sb.appendLine("--- results (ms per interpolated frame, lower is better) ---")
        results.sortedBy { it.msPerFrame }.forEach { r ->
            if (r.error == null) {
                sb.appendLine(String.format(Locale.US, "%7.1f ms  %s", r.msPerFrame, r.config.label))
            } else {
                sb.appendLine("   failed  ${r.config.label} — ${r.error}")
            }
        }
        best?.let {
            sb.appendLine("--- winner: ${it.config.label} (${String.format(Locale.US, "%.0f", it.msPerFrame)} ms/frame) ---")
        }
        if (topOps.isNotEmpty()) {
            sb.appendLine("--- heaviest operators (winner, ONNX Runtime profiler) ---")
            topOps.forEach { sb.appendLine("  $it") }
        }
        sb.append("=== END BENCHMARK ===")
        return sb.toString().trimEnd()
    }
}
