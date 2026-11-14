package com.flowframes.android.job

import android.content.Context
import com.flowframes.android.BuildConfig
import com.flowframes.android.core.DeviceInfo
import com.flowframes.android.core.FrameMath
import com.flowframes.android.core.InferencePool
import com.flowframes.android.core.QnnConfig
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
        val useQnn: Boolean,
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
        val qnn: Boolean = false,
        val parallel: Boolean = false,
        val staticShapes: Boolean = true,
        val rounds: Int = 3,
        /** QNN profiling pass: writes the HTP CSV and is not eligible for the winner. */
        val profileQnn: Boolean = false,
        /** Profiling overhead makes this row's timing meaningless. */
        val excludeFromBest: Boolean = false
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
        val autoWorkers = InferencePool.defaultWorkers()
        val autoThreads = RifeEngine.defaultThreads()
        val perWorker = (autoThreads / autoWorkers).coerceAtLeast(1)
        val inferencesPerSourceFrame = (settings.factor - 1).coerceAtLeast(1)

        val configs = ArrayList<Config>(10)
        configs += Config("1 × ${autoThreads}T", workers = 1, threadsPerWorker = autoThreads, rounds = 3)
        configs += Config("2 × ${(autoThreads / 2).coerceAtLeast(1)}T", workers = 2, threadsPerWorker = (autoThreads / 2).coerceAtLeast(1))
        configs += Config("3 × ${perWorker}T", workers = 3, threadsPerWorker = perWorker)
        configs += Config("4 × 2T", workers = 4, threadsPerWorker = 2)
        configs += Config("XNNPACK 3 × ${perWorker}T", workers = 3, threadsPerWorker = perWorker, xnnpack = true)
        configs += Config("3 × ${perWorker}T dynamic shapes", workers = 3, threadsPerWorker = perWorker, staticShapes = false)
        if (BuildConfig.QNN_BUILD) {
            // Two sessions, because profiling changes the provider options and its overhead would
            // contaminate the timing: the first pass proves which QNN ops run and writes the CSV,
            // the second is the clean strict timing. They are added even when the QNN setting is
            // off: in a QNN build the benchmark must always report whether QNN works.
            configs += Config(
                "QNN HTP profile (strict, not ranked)",
                workers = 1,
                threadsPerWorker = autoThreads,
                qnn = true,
                rounds = 1,
                profileQnn = true,
                excludeFromBest = true
            )
            configs += Config(
                "QNN HTP strict 1 × ${autoThreads}T",
                workers = 1,
                threadsPerWorker = autoThreads,
                qnn = true
            )
        }
        if (settings.useNnapi) {
            configs += Config("NNAPI 3 × ${perWorker}T", workers = 3, threadsPerWorker = perWorker, xnnpack = true, nnapi = true, rounds = 1)
        }

        report("Benchmark: ${width}x$height canvas (${String.format(Locale.US, "%.2f", megapixels)} MPx) · ${File(modelPath).name}")
        report(DeviceInfo.headline(context))
        report("Sweeping ${configs.size} configurations, then a sustained pass on the winner…")
        if (configs.any { it.qnn }) {
            report(
                "QNN: strict sessions (no CPU fallback). A profile pass writes the HTP CSV; " +
                    "the separate clean row is the timing reference."
            )
        }

        val frames0 = FloatArray(width * height * 3) { (it % 251) / 251f }
        val frames1 = FloatArray(width * height * 3) { (it % 197) / 197f }
        val out = FloatArray(width * height * 3)
        val results = ArrayList<Result>(configs.size)
        var topOps: List<String> = emptyList()
        var qnnProfile: String? = null

        for ((index, cfg) in configs.withIndex()) {
            val profileFile = if (cfg.profileQnn) {
                File(
                    context.getExternalFilesDir(null) ?: context.filesDir,
                    "qnn-profile-${index}.csv"
                ).also {
                    it.parentFile?.mkdirs()
                    // A failed session must not leave a previous run's CSV looking like a result.
                    it.delete()
                    File(it.parentFile, it.nameWithoutExtension + "_qnn.log").delete()
                }
            } else null
            val result = runCatching {
                val t0 = System.currentTimeMillis()
                val pool = InferencePool(
                    modelPath = modelPath,
                    workers = cfg.workers,
                    threadsPerWorker = cfg.threadsPerWorker,
                    useNnapi = cfg.nnapi,
                    useXnnpack = cfg.xnnpack,
                    qnn = if (cfg.qnn) {
                        val lib = File(context.applicationInfo.nativeLibraryDir, "libQnnHtp.so")
                        QnnConfig(
                            backendPath = if (lib.isFile) lib.absolutePath else "",
                            profilingLevel = if (profileFile != null) "detailed" else null,
                            profilingFilePath = profileFile?.absolutePath
                        )
                    } else null,
                    parallelOps = cfg.parallel,
                    frameWidth = if (cfg.staticShapes) width else 0,
                    frameHeight = if (cfg.staticShapes) height else 0,
                    profileDir = if (index == 0) File(context.cacheDir, "ort-profile") else null,
                    log = if (cfg.qnn) report else { _: String -> }
                )
                pool.use { p ->
                    if (cfg.qnn && !p.primary.qnnSessionCreated) {
                        throw IllegalStateException(
                            "QNN HTP session could not be created (strict, no CPU fallback)"
                        )
                    }
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
                }.also {
                    if (profileFile != null) {
                        qnnProfile = qnnProfileText(profileFile)
                        report("QNN profile: $qnnProfile")
                    }
                }
            }.getOrElse { t ->
                report("[${index + 1}/${configs.size}] ${cfg.label} → failed: ${t.message ?: t.javaClass.simpleName}")
                if (profileFile != null) {
                    qnnProfile = qnnProfileText(profileFile)
                    report("QNN profile: $qnnProfile")
                }
                Result(cfg, Double.MAX_VALUE, t.message ?: t.javaClass.simpleName)
            }
            results += result
        }

        val best = results.filter { it.error == null && !it.config.excludeFromBest }
            .minByOrNull { it.msPerFrame }

        val sustained = best?.let { winner ->
            // A second QNN session would recompile the whole graph; keep the benchmark bounded.
            if (winner.config.nnapi || winner.config.qnn) null else runCatching {
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
        sb.appendLine(
            "QNN build: ${if (BuildConfig.QNN_BUILD) "yes" else "no"} · " +
                "QNN setting: ${if (settings.useQnn) "on" else "off"}"
        )
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
        val qnnResult = results.filter { it.error == null && it.config.qnn && !it.config.excludeFromBest }
            .minByOrNull { it.msPerFrame }
        val nonQnnBest = results.filter { it.error == null && !it.config.qnn }.minByOrNull { it.msPerFrame }
        if (qnnResult != null && nonQnnBest != null) {
            val ratio = nonQnnBest.msPerFrame / qnnResult.msPerFrame
            sb.appendLine(
                "QNN strict vs best non-QNN: ${String.format(Locale.US, "%.2f", ratio)}x " +
                    if (ratio >= 1.0) "faster" else "slower"
            )
            sb.appendLine(
                "  (strict QNN: creation succeeds only when QNN claimed every node; " +
                    "the profile row lists the HTP ops.)"
            )
        } else if (results.any { it.config.qnn }) {
            sb.appendLine("QNN: no comparable non-QNN result; see the failed row above.")
        }
        if (qnnProfile != null) {
            sb.appendLine("--- QNN HTP profile (strict, not timed) ---")
            sb.appendLine("  $qnnProfile")
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
                it.config.parallel == settings.parallelOps &&
                it.config.qnn == (settings.useQnn && BuildConfig.QNN_BUILD)
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
        if (best?.config?.qnn == true) {
            sb.appendLine(
                "Sustained pass skipped for QNN: every session compiles the graph for the HTP. " +
                    "Read the job log for thermal status and per-minute inference latency."
            )
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
                useQnn = it.config.qnn,
                msPerFrame = it.msPerFrame
            )
        }
        return Report(sb.toString().trimEnd(), bestChoice)
    }

    /** Reports the profile CSV and a best-effort list of the QNN ops it contains. */
    private fun qnnProfileText(csv: File): String {
        if (!csv.isFile) return "no CSV at ${csv.absolutePath}"
        val log = File(csv.parentFile, csv.nameWithoutExtension + "_qnn.log")
        val summary = runCatching { qnnProfileSummary(csv) }.getOrNull()
        return buildString {
            append(csv.absolutePath)
            append(" (")
            append(csv.length())
            append(" bytes)")
            if (summary != null) append(" · ").append(summary)
            if (log.isFile) append(" · log: ").append(log.absolutePath)
        }
    }

    private fun qnnProfileSummary(csv: File): String? {
        val lines = csv.useLines { it.take(5000).toList() }
        val headerIndex = lines.indexOfFirst { line ->
            line.contains("Op", ignoreCase = true) &&
                (line.contains("Name", ignoreCase = true) || line.contains("Type", ignoreCase = true))
        }
        if (headerIndex < 0) return null
        val header = lines[headerIndex].split(',').map { it.trim().trim('"') }
        val opColumn = header.indexOfFirst { name ->
            name.contains("Op", ignoreCase = true) &&
                (name.contains("Name", ignoreCase = true) || name.contains("Type", ignoreCase = true))
        }
        if (opColumn < 0) return null
        val ops = lines.drop(headerIndex + 1)
            .mapNotNull { line ->
                line.split(',').getOrNull(opColumn)?.trim()?.trim('"')?.takeIf { it.isNotEmpty() }
            }
            .distinct()
            .take(20)
        return if (ops.isEmpty()) null else "${ops.size} ops: ${ops.joinToString(", ")}"
    }

    private fun effectiveWorkers(settings: Settings, auto: Int): Int =
        if (settings.workers > 0) settings.workers else auto

    private fun effectiveTotalThreads(settings: Settings, automatic: Int): Int =
        if (settings.threads > 0) settings.threads else automatic

    private fun describeCurrent(settings: Settings, autoThreads: Int, autoWorkers: Int): String {
        val total = effectiveTotalThreads(settings, autoThreads)
        val qnn = settings.useQnn && BuildConfig.QNN_BUILD
        val workers = if (qnn) 1 else effectiveWorkers(settings, autoWorkers).coerceAtMost(total)
        val provider = when {
            qnn -> "QNN(HTP strict)"
            settings.useNnapi -> "NNAPI,XNNPACK"
            settings.useXnnpack -> "XNNPACK"
            else -> "CPU"
        }
        return "$workers × ${(total / workers).coerceAtLeast(1)}T · $provider · " +
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
