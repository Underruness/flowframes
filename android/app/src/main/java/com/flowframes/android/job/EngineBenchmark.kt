package com.flowframes.android.job

import android.content.Context
import com.flowframes.android.core.DeviceInfo
import com.flowframes.android.core.FrameMath
import com.flowframes.android.core.InferencePool
import com.flowframes.android.core.RifeEngine
import java.io.File
import java.util.Locale
import java.util.concurrent.Future

/**
 * Measures the engine on this device.
 *
 * Two things decide the throughput and neither can be predicted from a desktop:
 *
 *  - **How many inferences run at once.** One inference does not keep eight cores busy (4, 6 and 8
 *    intra-op threads cost 857, 814 and 885 ms on a Snapdragon 8 Elite): the threads wait on memory.
 *    Independent inferences fill the machine instead, so the sweep varies the number of parallel
 *    sessions and the threads each one gets.
 *  - **What happens over time.** A phone that has been running for five minutes is slower than a
 *    cold one, and the codecs compete with the inference for the same cores. A few isolated frames
 *    cannot show either, so the winner gets a sustained pass and the drift is reported.
 */
object EngineBenchmark {

    /** Configuration that measured fastest, offered to the user as a one-tap default. */
    data class Best(
        val label: String,
        val workers: Int,
        val threadsPerWorker: Int,
        val useXnnpack: Boolean,
        val parallelOps: Boolean,
        val msPerFrame: Double
    ) {
        /** Total inference threads, as stored in the settings. */
        val totalThreads: Int get() = workers * threadsPerWorker
    }

    data class Report(val text: String, val best: Best?)

    private data class Config(
        val label: String,
        val workers: Int,
        val threadsPerWorker: Int,
        val xnnpack: Boolean = false,
        val nnapi: Boolean = false,
        val parallel: Boolean = false,
        val staticShapes: Boolean = true,
        val rounds: Int = 3
    )

    private data class Result(val config: Config, val msPerFrame: Double, val error: String?)

    /** Rounds used for the sustained pass, and how many at each end are compared. */
    private const val SUSTAINED_ROUNDS = 6

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
        val autoWorkers = InferencePool.defaultWorkers()
        val autoThreads = RifeEngine.defaultThreads()
        val perWorker = (autoThreads / autoWorkers).coerceAtLeast(1)
        val inferencesPerSourceFrame = (settings.factor - 1).coerceAtLeast(1)

        val configs = ArrayList<Config>(7)
        configs += Config("1 × ${autoThreads}T", workers = 1, threadsPerWorker = autoThreads, rounds = 3)
        configs += Config("2 × ${(autoThreads / 2).coerceAtLeast(1)}T", workers = 2, threadsPerWorker = (autoThreads / 2).coerceAtLeast(1))
        configs += Config("3 × ${perWorker}T", workers = 3, threadsPerWorker = perWorker)
        configs += Config("4 × 2T", workers = 4, threadsPerWorker = 2)
        configs += Config("XNNPACK 3 × ${perWorker}T", workers = 3, threadsPerWorker = perWorker, xnnpack = true)
        configs += Config("3 × ${perWorker}T dynamic shapes", workers = 3, threadsPerWorker = perWorker, staticShapes = false)
        // ONNX Runtime's PARALLEL execution mode is exposed in the settings, so it is measured here
        // instead of being taken on faith: it stayed off by default while nothing could check it.
        configs += Config("3 × ${perWorker}T parallel ops", workers = 3, threadsPerWorker = perWorker, parallel = true)
        if (settings.useNnapi) {
            configs += Config("NNAPI 3 × ${perWorker}T", workers = 3, threadsPerWorker = perWorker, xnnpack = true, nnapi = true, rounds = 1)
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
                val pool = InferencePool(
                    modelPath = modelPath,
                    workers = cfg.workers,
                    threadsPerWorker = cfg.threadsPerWorker,
                    useNnapi = cfg.nnapi,
                    useXnnpack = cfg.xnnpack,
                    parallelOps = cfg.parallel,
                    frameWidth = if (cfg.staticShapes) width else 0,
                    frameHeight = if (cfg.staticShapes) height else 0,
                    profileDir = if (index == 0) File(context.cacheDir, "ort-profile") else null
                )
                pool.use { p ->
                    p.warmUp(width, height)
                    val initMs = System.currentTimeMillis() - t0
                    val start = System.nanoTime()
                    var done = 0
                    repeat(cfg.rounds) {
                        val futures = ArrayList<Future<*>>(cfg.workers)
                        repeat(cfg.workers) {
                            futures += p.submit(frames0, frames1, width, height, 0.5f, out)
                        }
                        futures.forEach { it.get() }
                        done += cfg.workers
                    }
                    val perFrame = (System.nanoTime() - start) / 1_000_000.0 / done
                    if (index == 0) topOps = p.primary.stopProfiling()
                    report(
                        String.format(
                            Locale.US, "[%d/%d] %s → %.0f ms/inference (session init %d ms)",
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
                        Locale.US, "%7.1f ms  %-30s %6.1f ms/MPx  ~%.2f src fps",
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
            val predicted = 1000.0 / (it.msPerFrame * inferencesPerSourceFrame)
            sb.appendLine("--- winner: ${it.config.label} (${String.format(Locale.US, "%.0f", it.msPerFrame)} ms/frame) ---")
            sb.appendLine(
                "Predicted throughput at this canvas: ~${String.format(Locale.US, "%.2f", predicted)} " +
                    "source fps (x${settings.factor}, inference only; decoding and encoding add ~5-10 %)."
            )
            sb.appendLine("Current settings: ${describeCurrent(settings, autoThreads, autoWorkers)}")
            val same = effectiveWorkers(settings, autoWorkers) == it.config.workers &&
                effectiveTotalThreads(settings, autoThreads) == it.config.workers * it.config.threadsPerWorker &&
                it.config.xnnpack == settings.useXnnpack &&
                it.config.parallel == settings.parallelOps
            if (same) {
                sb.appendLine("→ Current settings already match the measured optimum.")
            } else {
                sb.appendLine("→ Current settings are NOT the measured optimum; apply the winner to gain speed.")
            }
            if (it.config.workers > 1) {
                sb.appendLine(
                    "Parallel sessions use ${it.config.workers} model copies, so the app needs roughly " +
                        "${it.config.workers}× the model's memory while a job runs."
                )
            }
        }
        if (sustained != null) {
            sb.appendLine("--- sustained pass (${SUSTAINED_ROUNDS} rounds of ${best.config.workers} inferences) ---")
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
                workers = it.config.workers,
                threadsPerWorker = it.config.threadsPerWorker,
                useXnnpack = it.config.xnnpack,
                parallelOps = it.config.parallel,
                msPerFrame = it.msPerFrame
            )
        }
        return Report(sb.toString().trimEnd(), bestChoice)
    }

    private fun effectiveWorkers(settings: Settings, auto: Int): Int =
        if (settings.workers > 0) settings.workers else auto

    private fun effectiveTotalThreads(settings: Settings, automatic: Int): Int =
        if (settings.threads > 0) settings.threads else automatic

    private fun describeCurrent(settings: Settings, autoThreads: Int, autoWorkers: Int): String {
        val total = effectiveTotalThreads(settings, autoThreads)
        val workers = effectiveWorkers(settings, autoWorkers).coerceAtMost(total)
        val ep = if (settings.useNnapi) "NNAPI," else ""
        return "$workers × ${(total / workers).coerceAtLeast(1)}T · $ep" +
            "${if (settings.useXnnpack) "XNNPACK" else "CPU"} · " +
            (if (settings.parallelOps) "parallel" else "sequential") + " ops · static shapes"
    }

    /**
     * Runs the winning configuration long enough that the CPU heats up and the pool competes with
     * the rest of the process, then reports the drift between the first and the last round.
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
        InferencePool(
            modelPath = modelPath,
            workers = cfg.workers,
            threadsPerWorker = cfg.threadsPerWorker,
            useNnapi = cfg.nnapi,
            useXnnpack = cfg.xnnpack,
            parallelOps = cfg.parallel,
            frameWidth = width,
            frameHeight = height
        ).use { pool ->
            pool.warmUp(width, height)
            val rounds = DoubleArray(SUSTAINED_ROUNDS)
            for (r in 0 until SUSTAINED_ROUNDS) {
                val start = System.nanoTime()
                val futures = ArrayList<Future<*>>(cfg.workers)
                repeat(cfg.workers) { futures += pool.submit(frames0, frames1, width, height, 0.5f, out) }
                futures.forEach { it.get() }
                rounds[r] = (System.nanoTime() - start) / 1_000_000.0 / cfg.workers
            }
            val edge = (SUSTAINED_ROUNDS / 3).coerceAtLeast(1)
            val early = rounds.take(edge).average()
            val late = rounds.takeLast(edge).average()
            val drift = if (early > 0) (late - early) / early * 100.0 else 0.0
            val after = DeviceInfo.lines(context).firstOrNull { it.startsWith("Thermal") } ?: "Thermal status: unknown"
            val verdict = when {
                drift > 12.0 ->
                    "throttling: the phone slows down once warm. Shorter runs or a lower resolution keep the average up."
                drift > 4.0 -> "mild slowdown once warm, expected on a phone."
                drift < -4.0 -> "faster once warm (cold start effects), no throttling seen."
                else -> "stable: no measurable throttling over $SUSTAINED_ROUNDS rounds."
            }
            report(
                String.format(
                    Locale.US,
                    "Sustained: first %d → %.0f ms · last %d → %.0f ms · drift %+.0f%%",
                    edge, early, edge, late, drift
                )
            )
            return buildString {
                appendLine(
                    String.format(
                        Locale.US,
                        "  early %.0f ms/frame · late %.0f ms/frame · drift %+.0f%% over %d inferences",
                        early, late, drift, SUSTAINED_ROUNDS * cfg.workers
                    )
                )
                appendLine("  $before → $after (after the sustained pass)")
                appendLine(
                    "  ${String.format(Locale.US, "%.1f", late / megapixels)} ms/MPx warm · " +
                        "~${String.format(Locale.US, "%.2f", 1000.0 / (late * inferencesPerSourceFrame))} " +
                        "source fps at x$inferencesPerSourceFrame warm"
                )
                append("  verdict: $verdict")
            }
        }
    }
}
