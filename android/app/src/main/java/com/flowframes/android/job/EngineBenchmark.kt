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
 * The interesting settings (execution provider, thread count, execution mode, static shapes)
 * interact in ways no desktop benchmark can predict: XNNPACK may claim part of the graph, NNAPI may
 * work or silently fall back, and a big.LITTLE CPU may be slower with every core busy.
 *
 * A short sweep cannot see the two effects that matter most for a real job, so this benchmark also
 * runs a *sustained* pass on the winner: the codecs contend for the CPU in a real run, and a phone
 * that is already warm runs slower than a phone that just booted. Both show up as drift between the
 * first and the last frames, never as a per-frame average.
 */
object EngineBenchmark {

    /** Configuration that measured fastest, offered to the user as a one-tap default. */
    data class Best(
        val label: String,
        val threads: Int,
        val useXnnpack: Boolean,
        val parallelOps: Boolean,
        val msPerFrame: Double
    )

    data class Report(val text: String, val best: Best?)

    private data class Config(
        val label: String,
        val threads: Int,
        val xnnpack: Boolean,
        val nnapi: Boolean,
        val parallel: Boolean,
        val staticShapes: Boolean,
        val runs: Int
    )

    private data class Result(val config: Config, val msPerFrame: Double, val error: String?)

    /** Inferences used for the sustained pass, and how many at each end are compared. */
    private const val SUSTAINED_RUNS = 16
    private const val SUSTAINED_EDGE = 4

    fun run(
        context: Context,
        settings: Settings,
        report: (String) -> Unit = {}
    ): Report {
        // The job caps the long side at maxLongSide; 0 means "keep the source size", for which a
        // 1080p canvas is the useful reference point.
        val cap = if (settings.maxLongSide <= 0) 1920 else settings.maxLongSide
        val (fitW, fitH) = FrameMath.fitWithin(1920, 1080, cap, cap)
        // Cache friendly, and it is what the graph pads to anyway.
        val width = ((fitW + 31) / 32) * 32
        val height = ((fitH + 31) / 32) * 32
        val megapixels = width.toDouble() * height / 1_000_000.0
        val modelPath = ModelStore.prepare(context, settings.quality)
        val cores = Runtime.getRuntime().availableProcessors()
        val automatic = RifeEngine.defaultThreads()
        val inferencesPerSourceFrame = (settings.factor - 1).coerceAtLeast(1)

        val configs = ArrayList<Config>(7)
        configs += Config("CPU ${automatic}T static", automatic, false, false, false, true, 3)
        configs += Config("CPU ${cores}T static", cores, false, false, false, true, 3)
        configs += Config("XNNPACK ${automatic}T static", automatic, true, false, false, true, 3)
        configs += Config("XNNPACK ${cores}T static", cores, true, false, false, true, 3)
        configs += Config("CPU ${automatic}T static parallel", automatic, false, false, true, true, 3)
        configs += Config("CPU ${automatic}T dynamic", automatic, false, false, false, false, 2)
        if (settings.useNnapi) {
            configs += Config("NNAPI ${automatic}T static", automatic, true, true, false, true, 1)
        }

        report("Benchmark: ${width}x$height canvas (${String.format(Locale.US, "%.2f", megapixels)} MPx) · ${File(modelPath).name}")
        report(DeviceInfo.headline(context))
        report("Sweeping ${configs.size} configurations, then a sustained pass on the winner…")

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
                    parallelOps = cfg.parallel,
                    frameWidth = if (cfg.staticShapes) width else 0,
                    frameHeight = if (cfg.staticShapes) height else 0,
                    profileDir = if (index == 0) File(context.cacheDir, "ort-profile") else null
                )
                engine.use { e ->
                    e.warmUp(width, height)
                    val initMs = System.currentTimeMillis() - t0
                    val start = System.nanoTime()
                    repeat(cfg.runs) { e.interpolate(frames0, frames1, width, height, 0.5f, out) }
                    val perFrame = (System.nanoTime() - start) / 1_000_000.0 / cfg.runs
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

        // Sustained pass: reveals throttling and core contention that the short sweep cannot see.
        val sustained = best?.let { winner ->
            if (winner.config.nnapi) null else runCatching {
                runSustained(
                    modelPath, winner.config, width, height, frames0, frames1, out,
                    megapixels, inferencesPerSourceFrame, context, report
                )
            }.getOrElse { t ->
                report("Sustained pass failed: ${t.message ?: t.javaClass.simpleName}")
                null
            }
        }

        val sb = StringBuilder()
        sb.appendLine("=== FLOWFRAMES ENGINE BENCHMARK ===")
        sb.appendLine(
            "Canvas: ${width}x$height (${String.format(Locale.US, "%.2f", megapixels)} MPx) · " +
                "model ${File(modelPath).name} · factor x${settings.factor}"
        )
        DeviceInfo.lines(context).forEach { sb.appendLine(it) }
        sb.appendLine("--- results (ms per interpolated frame, lower is better) ---")
        results.sortedBy { it.msPerFrame }.forEach { r ->
            if (r.error == null) {
                sb.appendLine(
                    String.format(
                        Locale.US, "%7.1f ms  %-28s %6.1f ms/MPx  ~%.2f src fps",
                        r.msPerFrame, r.config.label,
                        r.msPerFrame / megapixels,
                        1000.0 / (r.msPerFrame * inferencesPerSourceFrame)
                    )
                )
            } else {
                sb.appendLine("   failed  ${r.config.label} — ${r.error}")
            }
        }
        best?.let {
            sb.appendLine("--- winner: ${it.config.label} (${String.format(Locale.US, "%.0f", it.msPerFrame)} ms/frame) ---")
            sb.appendLine(
                "Predicted throughput at this canvas: ~" +
                    String.format(Locale.US, "%.2f", 1000.0 / (it.msPerFrame * inferencesPerSourceFrame)) +
                    " source fps (x${settings.factor}, inference only; decoding and encoding add ~5-10 %)."
            )
            val current = describeCurrent(settings, automatic)
            sb.appendLine("Current settings: $current")
            if (it.config.threads != effectiveThreads(settings, automatic) ||
                it.config.xnnpack != settings.useXnnpack ||
                it.config.parallel != settings.parallelOps
            ) {
                sb.appendLine("→ Current settings are NOT the measured optimum; apply the winner to gain speed.")
            } else {
                sb.appendLine("→ Current settings already match the measured optimum.")
            }
        }
        if (sustained != null) {
            sb.appendLine("--- sustained pass (${SUSTAINED_RUNS} inferences on the winner) ---")
            sb.appendLine(sustained)
        }
        if (topOps.isNotEmpty()) {
            sb.appendLine("--- heaviest operators (first configuration, ONNX Runtime profiler) ---")
            topOps.forEach { sb.appendLine("  $it") }
        }
        sb.append("=== END BENCHMARK ===")

        val bestChoice = best?.let {
            Best(
                label = it.config.label,
                threads = it.config.threads,
                useXnnpack = it.config.xnnpack,
                parallelOps = it.config.parallel,
                msPerFrame = it.msPerFrame
            )
        }
        return Report(sb.toString().trimEnd(), bestChoice)
    }

    private fun effectiveThreads(settings: Settings, automatic: Int): Int =
        if (settings.threads > 0) settings.threads else automatic

    private fun describeCurrent(settings: Settings, automatic: Int): String {
        val threads = effectiveThreads(settings, automatic)
        val ep = if (settings.useNnapi) "NNAPI," else ""
        return "$threads threads · $ep${if (settings.useXnnpack) "XNNPACK" else "CPU"} · " +
            (if (settings.parallelOps) "parallel" else "sequential") + " ops · static shapes"
    }

    /**
     * Runs the winning configuration long enough that the CPU heats up and the pool competes with
     * the rest of the process, then reports the drift between the first and the last inferences.
     */
    private fun runSustained(
        modelPath: String,
        cfg: Config,
        width: Int,
        height: Int,
        frames0: FloatArray,
        frames1: FloatArray,
        out: FloatArray,
        megapixels: Double,
        inferencesPerSourceFrame: Int,
        context: Context,
        report: (String) -> Unit
    ): String {
        val before = DeviceInfo.lines(context).firstOrNull { it.startsWith("Thermal") } ?: "Thermal status: unknown"
        RifeEngine(
            modelPath = modelPath,
            intraOpThreads = cfg.threads,
            useNnapi = cfg.nnapi,
            useXnnpack = cfg.xnnpack,
            parallelOps = cfg.parallel,
            frameWidth = width,
            frameHeight = height
        ).use { engine ->
            engine.warmUp(width, height)
            val samples = IntArray(SUSTAINED_RUNS)
            for (i in 0 until SUSTAINED_RUNS) {
                val t = System.nanoTime()
                engine.interpolate(frames0, frames1, width, height, 0.5f, out)
                samples[i] = ((System.nanoTime() - t) / 1_000_000).toInt()
            }
            val early = samples.take(SUSTAINED_EDGE).average()
            val late = samples.takeLast(SUSTAINED_EDGE).average()
            val drift = if (early > 0) (late - early) / early * 100.0 else 0.0
            val after = DeviceInfo.lines(context).firstOrNull { it.startsWith("Thermal") } ?: "Thermal status: unknown"
            val verdict = when {
                drift > 12.0 -> "throttling: the phone slows down once warm. Shorter runs or a lower resolution keep the average up."
                drift > 4.0 -> "mild slowdown once warm, expected on a phone."
                drift < -4.0 -> "faster once warm (cold start effects), no throttling seen."
                else -> "stable: no measurable throttling over ${SUSTAINED_RUNS} inferences."
            }
            report(
                String.format(
                    Locale.US,
                    "Sustained: first %d → %.0f ms · last %d → %.0f ms · drift %+.0f%%",
                    SUSTAINED_EDGE, early, SUSTAINED_EDGE, late, drift
                )
            )
            val predicted = 1000.0 / (late * inferencesPerSourceFrame)
            return buildString {
                appendLine(
                    String.format(
                        Locale.US,
                        "  early %.0f ms/frame · late %.0f ms/frame · drift %+.0f%% over %d inferences",
                        early, late, drift, SUSTAINED_RUNS
                    )
                )
                appendLine("  $before → $after (after the sustained pass)")
                appendLine(
                    "  ${String.format(Locale.US, "%.1f", late / megapixels)} ms/MPx warm · " +
                        "~${String.format(Locale.US, "%.2f", predicted)} source fps at x$inferencesPerSourceFrame warm"
                )
                append("  verdict: $verdict")
            }
        }
    }
}
