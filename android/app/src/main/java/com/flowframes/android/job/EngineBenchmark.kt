package com.flowframes.android.job

import android.content.Context
import com.flowframes.android.core.DeviceInfo
import com.flowframes.android.core.FrameMath
import com.flowframes.android.core.InferencePool
import com.flowframes.android.core.NcnnRifeEngine
import com.flowframes.android.core.RifeEngine
import java.util.Locale
import java.util.concurrent.Future
import kotlin.math.log10

/**
 * Measures every engine on this device and reports the fastest one that is also correct.
 *
 *  - **Engines.** ncnn on the GPU (Vulkan), ncnn on the CPU and ONNX Runtime on the CPU. Each
 *    configuration's output is compared with the ONNX Runtime reference on the same input, so a
 *    GPU driver that computes garbage quickly cannot win.
 *  - **Concurrency.** One CPU inference does not keep eight cores busy, and one GPU inference
 *    leaves the GPU idle while frames are copied, so the sweep varies the calls in flight.
 *  - **Time.** A phone that has been running for minutes is slower than a cold one; the winner gets
 *    a sustained pass and the drift is reported.
 */
object EngineBenchmark {

    /** Configuration that measured fastest, offered to the user as a one-tap default. */
    data class Best(
        val label: String,
        val engine: EngineKind,
        val ncnnModel: NcnnModel,
        val fp16: Boolean,
        val coopMatrix: Boolean,
        val cpuWorkers: Int,
        val cpuThreads: Int,
        val workers: Int,
        val threadsPerWorker: Int,
        val useXnnpack: Boolean,
        val parallelOps: Boolean,
        val msPerFrame: Double
    ) {
        /** Total CPU inference threads, as stored in the settings (0 for the GPU). */
        val totalThreads: Int get() = workers * threadsPerWorker
    }

    data class Report(val text: String, val best: Best?)

    private data class Config(
        val label: String,
        val kind: EngineKind,
        val workers: Int,
        val threadsPerWorker: Int,
        val model: NcnnModel = NcnnModel.V4_9,
        val fp16: Boolean = false,
        val coop: Boolean = false,
        val cpuWorkers: Int = 0,
        val cpuThreads: Int = 0,
        val xnnpack: Boolean = false,
        val parallel: Boolean = false,
        val rounds: Int = 3
    )

    private data class Result(val config: Config, val msPerFrame: Double, val psnr: Double?, val error: String?)

    /** Output closer than this to the ONNX reference counts as correct (same model: 45-60 dB). */
    private const val SAME_MODEL_MIN_DB = 35.0

    /** One configuration (build + warm-up + measure) giving no answer for this long has hung. */
    private const val CONFIG_TIMEOUT_S = 90L

    /**
     * fp16 + cooperative matrix: the Adreno 840 driver took 98 s to build those pipelines on the
     * first run (it answered after the 90 s limit), so that configuration gets more time.
     */
    private const val COOP_TIMEOUT_S = 240L

    /** A live progress line is printed this often while a configuration runs. */
    private const val HEARTBEAT_S = 5L

    /** What a running configuration is doing, read by the heartbeat. */
    private class Progress {
        val startNs = System.nanoTime()
        @Volatile var phase = "starting"
        @Volatile var phaseStartNs = startNs
        @Volatile var futures: List<Future<*>> = emptyList()
        @Volatile var timedOut = false

        fun enter(phase: String, report: (String) -> Unit) {
            this.phase = phase
            phaseStartNs = System.nanoTime()
            report("    · $phase")
        }

        fun elapsedS(): Long = (System.nanoTime() - startNs) / 1_000_000_000L

        fun describe(): String {
            val inPhase = (System.nanoTime() - phaseStartNs) / 1_000_000_000L
            val f = futures
            val count = if (f.isEmpty()) "" else " · ${f.count { it.isDone }}/${f.size} inferences done"
            return "$phase for $inPhase s$count"
        }
    }

    /**
     * Runs [block] on its own daemon thread, prints a progress line every [HEARTBEAT_S] seconds
     * and gives up after [seconds]. The block keeps running after a timeout (a native call cannot
     * be interrupted); it can see that through [Progress.timedOut].
     */
    private fun <T> withTimeout(
        seconds: Long,
        progress: Progress,
        report: (String) -> Unit,
        onTimeout: () -> Unit,
        block: () -> T
    ): T {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "benchmark-config").apply { isDaemon = true }
        }
        try {
            val future = executor.submit<T> { block() }
            while (true) {
                try {
                    return future.get(HEARTBEAT_S, java.util.concurrent.TimeUnit.SECONDS)
                } catch (e: java.util.concurrent.TimeoutException) {
                    val elapsed = progress.elapsedS()
                    if (elapsed >= seconds) {
                        progress.timedOut = true
                        onTimeout()
                        throw e
                    }
                    report("    … ${elapsed} s / ${seconds} s max · ${progress.describe()}")
                }
            }
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        } finally {
            executor.shutdown()
        }
    }

    /** Rounds used for the sustained pass, and how many at each end are compared. */
    private const val SUSTAINED_ROUNDS = 8

    fun run(
        context: Context,
        settings: Settings,
        report: (String) -> Unit = {}
    ): Report {
        // The job caps the long side at maxLongSide; 0 means "keep the source size", for which a
        // 1080p canvas is the useful reference point.
        val cap = if (settings.maxLongSide <= 0) 1920 else settings.maxLongSide
        val (fitW, fitH) = FrameMath.fitWithin(1920, 1080, cap, cap)
        val width = ((fitW + 31) / 32) * 32
        val height = ((fitH + 31) / 32) * 32
        val megapixels = width.toDouble() * height / 1_000_000.0
        val inferencesPerSourceFrame = (settings.factor - 1).coerceAtLeast(1)
        val autoWorkers = InferencePool.defaultWorkers()
        val autoThreads = RifeEngine.defaultThreads()
        val perWorker = (autoThreads / autoWorkers).coerceAtLeast(1)
        val gpus = NcnnRifeEngine.gpuCount()

        val configs = ArrayList<Config>()
        // Reference first: its output is what every other configuration is compared with.
        configs += Config("ONNX Runtime $autoWorkers × ${perWorker}T", EngineKind.ORT_CPU, autoWorkers, perWorker)
        if (gpus > 0) {
            configs += Config("GPU 4.9 ×1", EngineKind.NCNN_GPU, 1, 0)
            configs += Config("GPU 4.9 ×2", EngineKind.NCNN_GPU, 2, 0, rounds = 4)
            configs += Config("GPU 4.9 ×3", EngineKind.NCNN_GPU, 3, 0, rounds = 4)
            configs += Config("GPU 4.6 ×2", EngineKind.NCNN_GPU, 2, 0, model = NcnnModel.V4_6, rounds = 4)
            // Hybrid: CPU workers pull phases from the same queue as the GPU ones.
            configs += Config("GPU ×2 + CPU 1×4T", EngineKind.NCNN_GPU, 2, 0, cpuWorkers = 1, cpuThreads = 4, rounds = 8)
            configs += Config("GPU ×2 + CPU 1×6T", EngineKind.NCNN_GPU, 2, 0, cpuWorkers = 1, cpuThreads = 6, rounds = 8)
            configs += Config("GPU ×2 + CPU 2×3T", EngineKind.NCNN_GPU, 2, 0, cpuWorkers = 2, cpuThreads = 3, rounds = 8)
        }
        if (NcnnRifeEngine.isAvailable) {
            configs += Config("CPU ncnn 4.9 $autoWorkers × ${perWorker}T", EngineKind.NCNN_CPU, autoWorkers, perWorker)
        }
        if (gpus > 0) {
            // Last, and behind a timeout: fp16 math hung this benchmark on a Snapdragon 8 Elite.
            configs += Config("GPU 4.9 fp16 math ×2", EngineKind.NCNN_GPU, 2, 0, fp16 = true, rounds = 4)
            configs += Config("GPU 4.9 fp16 math + coopmat ×2", EngineKind.NCNN_GPU, 2, 0, fp16 = true, coop = true, rounds = 4)
        }

        report("Benchmark: ${width}x$height canvas (${String.format(Locale.US, "%.2f", megapixels)} MPx), RIFE fast model")
        report(DeviceInfo.headline(context))
        report(
            if (gpus > 0) "Vulkan GPU: ${NcnnRifeEngine.gpuInfo(0)}"
            else if (NcnnRifeEngine.isAvailable) "No usable Vulkan GPU: CPU engines only"
            else "Native engine missing: ONNX Runtime only"
        )
        report("Sweeping ${configs.size} configurations, then a sustained pass on the winner…")

        // Smooth content with a moving pattern, so the flow estimation has something to track.
        val frames0 = syntheticFrame(width, height, 0)
        val frames1 = syntheticFrame(width, height, 6)
        val results = ArrayList<Result>(configs.size)
        var reference: FloatArray? = null
        val fastSettings = settings.copy(quality = AiQuality.FAST)

        for ((index, cfg) in configs.withIndex()) {
            if (cfg.kind == EngineKind.NCNN_GPU && EngineFactory.gpuHung) {
                report("[${index + 1}/${configs.size}] ${cfg.label} → skipped: the GPU hung earlier")
                results += Result(cfg, Double.MAX_VALUE, null, "skipped after a GPU hang")
                continue
            }
            val tag = "[${index + 1}/${configs.size}] ${cfg.label}"
            val limit = if (cfg.fp16 && cfg.coop) COOP_TIMEOUT_S else CONFIG_TIMEOUT_S
            report("$tag …")
            val progress = Progress()
            val result = runCatching {
                withTimeout(limit, progress, report, onTimeout = {
                    if (cfg.kind == EngineKind.NCNN_GPU) EngineFactory.markGpuHung()
                }) {
                    val t0 = System.currentTimeMillis()
                    progress.enter("building the engine (loading the model)", report)
                    // Not closed on a timeout: a hung native call must not see its engine freed.
                    val p = makePool(context, fastSettings, cfg, width, height)
                    try {
                        progress.enter(
                            if (cfg.kind == EngineKind.NCNN_GPU) "warm-up (first run builds the GPU pipelines)" else "warm-up",
                            report
                        )
                        p.warmUp(width, height)
                        val initMs = System.currentTimeMillis() - t0
                        val total = cfg.workers + cfg.cpuWorkers
                        progress.enter("quality check against ONNX", report)
                        val single = FloatArray(width * height * 3)
                        p.submit(frames0, frames1, width, height, 0.5f, single).get()
                        val psnr = reference?.let { ref -> psnr(single, ref) }
                        if (reference == null && cfg.kind == EngineKind.ORT_CPU) reference = single
                        // Throughput: everything queued at once, as the job does. Outputs are
                        // shared between calls (only the single run above is checked).
                        val outs = Array(total) { FloatArray(width * height * 3) }
                        val count = cfg.rounds * total
                        val futures = ArrayList<Future<*>>(count)
                        progress.enter("measuring $count inferences", report)
                        val start = System.nanoTime()
                        for (i in 0 until count) futures += p.submit(frames0, frames1, width, height, 0.5f, outs[i % total])
                        progress.futures = futures
                        futures.forEach { it.get() }
                        val perFrame = (System.nanoTime() - start) / 1_000_000.0 / count
                        val split = if (cfg.cpuWorkers > 0) " · " + p.engineStats() else ""
                        val line = String.format(
                            Locale.US, "%.0f ms/inference%s (init %d ms)%s",
                            perFrame,
                            psnr?.let { String.format(Locale.US, " · %.1f dB vs ONNX", it) } ?: "",
                            initMs, split
                        )
                        p.close()
                        if (progress.timedOut) {
                            // Finished after the limit: slow, not hung. The GPU is usable again.
                            if (cfg.kind == EngineKind.NCNN_GPU) EngineFactory.clearGpuHung()
                            report(
                                "$tag → late answer after ${progress.elapsedS()} s: $line. Not a driver hang, " +
                                    "a very slow first build of the GPU pipelines (not counted in the ranking)."
                            )
                        } else {
                            report("$tag → $line")
                        }
                        Result(cfg, perFrame, psnr, null)
                    } catch (t: Throwable) {
                        if (t !is InterruptedException) runCatching { p.close() }
                        throw t
                    }
                }
            }.getOrElse { t ->
                val msg = if (t is java.util.concurrent.TimeoutException) {
                    "no answer after $limit s (driver hang or very slow pipeline build), later GPU tests skipped"
                } else t.message ?: t.javaClass.simpleName
                report("$tag → failed: $msg")
                Result(cfg, Double.MAX_VALUE, null, msg)
            }
            results += result
        }

        // Same weights must agree with the reference; RIFE 4.6 is a different model, so only a
        // sanity floor applies to it.
        fun trusted(r: Result): Boolean {
            if (r.error != null) return false
            val db = r.psnr ?: return true
            return db >= if (r.config.model == NcnnModel.V4_9) SAME_MODEL_MIN_DB else 25.0
        }
        val best = results.filter(::trusted).minByOrNull { it.msPerFrame }

        val sustained = best?.takeIf { !(it.config.kind == EngineKind.NCNN_GPU && EngineFactory.gpuHung) }?.let { winner ->
            runCatching {
                report("Sustained pass on ${winner.config.label} ($SUSTAINED_ROUNDS rounds) …")
                val progress = Progress()
                withTimeout(CONFIG_TIMEOUT_S * 2, progress, report, onTimeout = {}) {
                    runSustained(context, fastSettings, winner.config, width, height, frames0, frames1,
                        megapixels, inferencesPerSourceFrame, report, progress)
                }
            }.getOrElse { t ->
                report("Sustained pass failed: ${t.message ?: t.javaClass.simpleName}")
                null
            }
        }

        val sb = StringBuilder()
        sb.appendLine("=== FLOWFRAMES ENGINE BENCHMARK ===")
        sb.appendLine(
            "Canvas: ${width}x$height (${String.format(Locale.US, "%.2f", megapixels)} MPx) · factor x${settings.factor}"
        )
        DeviceInfo.lines(context).forEach { sb.appendLine(it) }
        sb.appendLine("Vulkan GPUs: $gpus" + if (gpus > 0) " (${NcnnRifeEngine.gpuInfo(0)})" else "")
        if (gpus > 0) NcnnRifeEngine.shaderCacheStats().takeIf { it.isNotBlank() }?.let { sb.appendLine("GPU cache: $it") }
        sb.appendLine(SystemMonitor.sample(context).shortLine())
        sb.appendLine("--- results (ms per interpolated frame, lower is better) ---")
        results.sortedBy { it.msPerFrame }.forEach { r ->
            if (r.error == null) {
                sb.appendLine(
                    String.format(
                        Locale.US, "%7.1f ms  %-32s %6.1f ms/MPx  ~%.2f src fps%s%s",
                        r.msPerFrame, r.config.label,
                        r.msPerFrame / megapixels,
                        1000.0 / (r.msPerFrame * inferencesPerSourceFrame),
                        r.psnr?.let { String.format(Locale.US, "  %.1f dB", it) } ?: "  (reference)",
                        if (trusted(r)) "" else "  REJECTED: output differs"
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
                    "source fps (x${settings.factor}, inference only)."
            )
            val at540 = it.msPerFrame * (544.0 * 544.0 / 1_000_000.0) / megapixels
            sb.appendLine(
                "Scaled to 540x540: ~${String.format(Locale.US, "%.0f", at540)} ms/frame → " +
                    "~${String.format(Locale.US, "%.1f", 1000.0 / (at540 * inferencesPerSourceFrame))} source fps"
            )
            sb.appendLine(
                "Current settings: ${settings.engine.label} · ${settings.ncnnModel.label} · fp16 math ${settings.fp16}" +
                    " · CPU assist " + if (settings.cpuAssist) "${settings.cpuAssistWorkers}×${settings.cpuAssistThreads}T" else "off"
            )
        }
        if (sustained != null) {
            sb.appendLine("--- sustained pass (${SUSTAINED_ROUNDS} rounds of ${best.config.workers} inferences) ---")
            sb.appendLine(sustained)
        }
        sb.append("=== END BENCHMARK ===")

        val bestChoice = best?.let {
            Best(
                label = it.config.label,
                engine = it.config.kind,
                ncnnModel = it.config.model,
                fp16 = it.config.fp16,
                coopMatrix = it.config.coop,
                cpuWorkers = it.config.cpuWorkers,
                cpuThreads = it.config.cpuThreads,
                workers = it.config.workers,
                threadsPerWorker = it.config.threadsPerWorker,
                useXnnpack = it.config.xnnpack,
                parallelOps = it.config.parallel,
                msPerFrame = it.msPerFrame
            )
        }
        return Report(sb.toString().trimEnd(), bestChoice)
    }

    private fun makePool(context: Context, settings: Settings, cfg: Config, width: Int, height: Int): InferencePool =
        EngineFactory.create(
            context,
            settings.copy(
                ncnnModel = cfg.model, fp16 = cfg.fp16, coopMatrix = cfg.coop, useXnnpack = cfg.xnnpack,
                parallelOps = cfg.parallel, useNnapi = false
            ),
            EngineFactory.Choice(
                cfg.kind, cfg.workers, cfg.threadsPerWorker.coerceAtLeast(1),
                cpuWorkers = cfg.cpuWorkers, cpuThreads = cfg.cpuThreads
            ),
            width, height, log = {}
        )

    private fun syntheticFrame(width: Int, height: Int, shift: Int): FloatArray {
        val plane = width * height
        val f = FloatArray(plane * 3)
        for (c in 0 until 3) {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val xs = x - shift
                    val v = 0.5 + 0.3 * kotlin.math.sin(xs * 0.05 + c) * kotlin.math.cos(y * 0.04 - c) +
                        0.15 * kotlin.math.sin((xs + y) * 0.013)
                    f[c * plane + y * width + x] = v.toFloat().coerceIn(0f, 1f)
                }
            }
        }
        return f
    }

    private fun psnr(a: FloatArray, b: FloatArray): Double {
        var se = 0.0
        for (i in a.indices) {
            val x = a[i].coerceIn(0f, 1f)
            val y = b[i].coerceIn(0f, 1f)
            if (x.isNaN()) return 0.0
            val d = (x - y).toDouble()
            se += d * d
        }
        val mse = se / a.size
        return if (mse <= 1e-12) 99.0 else 10.0 * log10(1.0 / mse)
    }

    /**
     * Runs the winning configuration long enough that the phone heats up, then reports the drift
     * between the first and the last rounds.
     */
    private fun runSustained(
        context: Context,
        settings: Settings,
        cfg: Config,
        width: Int,
        height: Int,
        frames0: FloatArray,
        frames1: FloatArray,
        megapixels: Double,
        inferencesPerSourceFrame: Int,
        report: (String) -> Unit,
        progress: Progress
    ): String {
        val before = DeviceInfo.lines(context).firstOrNull { it.startsWith("Thermal") } ?: "Thermal status: unknown"
        progress.enter("building the engine and warming up", report)
        makePool(context, settings, cfg, width, height).use { pool ->
            pool.warmUp(width, height)
            val total = cfg.workers + cfg.cpuWorkers
            val batch = if (cfg.cpuWorkers > 0) total * 4 else total
            val outs = Array(total) { FloatArray(width * height * 3) }
            val rounds = DoubleArray(SUSTAINED_ROUNDS)
            for (r in 0 until SUSTAINED_ROUNDS) {
                val start = System.nanoTime()
                val futures = ArrayList<Future<*>>(batch)
                progress.phase = "round ${r + 1}/$SUSTAINED_ROUNDS"
                for (w in 0 until batch) futures += pool.submit(frames0, frames1, width, height, 0.5f, outs[w % total])
                progress.futures = futures
                futures.forEach { it.get() }
                rounds[r] = (System.nanoTime() - start) / 1_000_000.0 / batch
                report(String.format(Locale.US, "    · round %d/%d → %.0f ms/inference", r + 1, SUSTAINED_ROUNDS, rounds[r]))
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
                        early, late, drift, SUSTAINED_ROUNDS * batch
                    )
                )
                appendLine("  $before → $after (after the sustained pass)")
                appendLine(
                    "  ${String.format(Locale.US, "%.1f", late / megapixels)} ms/MPx warm · " +
                        "~${String.format(Locale.US, "%.2f", 1000.0 / (late * inferencesPerSourceFrame))} " +
                        "source fps at x${inferencesPerSourceFrame + 1} warm"
                )
                append("  verdict: $verdict")
            }
        }
    }
}
