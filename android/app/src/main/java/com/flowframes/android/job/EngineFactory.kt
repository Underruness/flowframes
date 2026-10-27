package com.flowframes.android.job

import android.content.Context
import com.flowframes.android.core.FrameEngine
import com.flowframes.android.core.InferencePool
import com.flowframes.android.core.NcnnRifeEngine
import com.flowframes.android.core.RifeEngine
import java.util.Locale
import kotlin.math.log10

/**
 * Picks and builds the inference engine for a job.
 *
 * The GPU path is only trusted after a self-test at the job's own resolution: RIFE asked to
 * interpolate a frame with itself must give that frame back. A Vulkan driver that crashes, returns
 * zeros or NaNs fails that test and the job falls back to the next engine instead of producing a
 * broken video. The same pass warms the engine up (shader compilation, allocations).
 */
object EngineFactory {

    /** Minimum PSNR of the identity test; a working engine scores 40-60 dB, a broken one < 15. */
    private const val SELF_TEST_MIN_DB = 30.0

    /**
     * Engine plan. [cpuWorkers] > 0 on a GPU kind is the hybrid mode: that many ncnn CPU workers of
     * [cpuThreads] threads each join the GPU workers on the same queue.
     */
    data class Choice(
        val kind: EngineKind,
        val workers: Int,
        val threadsPerWorker: Int,
        val cpuWorkers: Int = 0,
        val cpuThreads: Int = 0
    )

    /** A GPU call that never returns (driver hang) is given up on after this long. */
    const val SELF_TEST_TIMEOUT_S = 45L

    /**
     * Set once a GPU call hung in this process: the queue may stay wedged, so later jobs skip the
     * GPU until the app restarts instead of hanging again.
     */
    @Volatile
    var gpuHung: Boolean = false
        private set

    fun markGpuHung() {
        gpuHung = true
    }

    /** A call given up on finally answered: it was slow, not wedged. */
    fun clearGpuHung() {
        gpuHung = false
    }

    fun chain(settings: Settings): List<EngineKind> = when {
        // The ensemble ("High quality") weights only exist as an ONNX export.
        settings.quality == AiQuality.HIGH -> listOf(EngineKind.ORT_CPU)
        else -> when (settings.engine) {
            EngineKind.AUTO, EngineKind.NCNN_GPU -> listOf(EngineKind.NCNN_GPU, EngineKind.NCNN_CPU, EngineKind.ORT_CPU)
            EngineKind.NCNN_CPU -> listOf(EngineKind.NCNN_CPU, EngineKind.ORT_CPU)
            EngineKind.ORT_CPU -> listOf(EngineKind.ORT_CPU)
        }
    }

    /** Workers and threads for [kind], honouring the manual settings when they are set. */
    fun plan(kind: EngineKind, settings: Settings): Choice {
        val totalThreads = if (settings.threads > 0) settings.threads else RifeEngine.defaultThreads()
        return when (kind) {
            // Two calls in flight keep the GPU busy while the other one copies frames in and out.
            EngineKind.NCNN_GPU -> Choice(
                kind, if (settings.workers > 0) settings.workers else 2, 2,
                cpuWorkers = if (settings.cpuAssist) settings.cpuAssistWorkers.coerceIn(1, 4) else 0,
                cpuThreads = settings.cpuAssistThreads.coerceIn(1, 8)
            )
            else -> {
                val workers = (if (settings.workers > 0) settings.workers else InferencePool.defaultWorkers())
                    .coerceAtMost(totalThreads)
                Choice(kind, workers, (totalThreads / workers).coerceAtLeast(1))
            }
        }
    }

    fun build(
        context: Context,
        settings: Settings,
        width: Int,
        height: Int,
        log: (String) -> Unit
    ): InferencePool {
        if (settings.quality == AiQuality.HIGH && settings.engine != EngineKind.ORT_CPU) {
            log("High quality (ensemble) runs on ONNX Runtime: the GPU engine only has the fast model")
        }
        val failures = ArrayList<String>()
        for (kind in chain(settings)) {
            val choice = plan(kind, settings)
            val pool = runCatching { create(context, settings, choice, width, height, log) }
                .getOrElse { t ->
                    val reason = t.message ?: t.javaClass.simpleName
                    failures += "${kind.label}: $reason"
                    log("Engine ${kind.label} unavailable: $reason")
                    null
                } ?: continue
            val verdict = runCatching {
                // fp16 + coopmat pipelines took 98 s to build on an Adreno 840: slow, not hung.
                val timeout = if (kind == EngineKind.NCNN_GPU && settings.fp16 && settings.coopMatrix) 240L else SELF_TEST_TIMEOUT_S
                if (timeout > SELF_TEST_TIMEOUT_S) log("Engine self-test: fp16 + coopmat, the first GPU pipeline build can take up to 2 min…")
                pool.distinctEngines.minOf { engine -> selfTestWithTimeout(engine, width, height, timeout) }
            }
            val db = verdict.getOrNull()
            if (db != null && db >= SELF_TEST_MIN_DB) {
                log(String.format(Locale.US, "Engine self-test passed (%.1f dB) · %s", db, pool.label))
                if (kind == EngineKind.NCNN_GPU) log("GPU: ${NcnnRifeEngine.gpuInfo(0)}")
                if (failures.isNotEmpty()) log("Fell back after: ${failures.joinToString(" · ")}")
                return pool
            }
            val reason = verdict.exceptionOrNull()?.message
                ?: String.format(Locale.US, "self-test %.1f dB < %.0f dB (wrong output)", db ?: 0.0, SELF_TEST_MIN_DB)
            failures += "${kind.label}: $reason"
            log("Engine ${kind.label} rejected: $reason")
            if (kind == EngineKind.NCNN_GPU) log("GPU: ${NcnnRifeEngine.gpuInfo(0)}")
            // A hung engine is still running native code: closing it would crash. Leak it instead.
            if (verdict.exceptionOrNull() !is java.util.concurrent.TimeoutException) runCatching { pool.close() }
        }
        throw IllegalStateException("No inference engine works on this device: ${failures.joinToString(" · ")}")
    }

    /** Builds a pool without the self-test (the benchmark measures failures itself). */
    fun create(
        context: Context,
        settings: Settings,
        choice: Choice,
        width: Int,
        height: Int,
        log: (String) -> Unit
    ): InferencePool = when (choice.kind) {
        EngineKind.NCNN_GPU, EngineKind.AUTO -> {
            check(NcnnRifeEngine.isAvailable) { "native library missing" }
            check(!gpuHung) { "the GPU hung earlier in this session; restart the app to retry it" }
            check(NcnnRifeEngine.gpuCount() > 0) { "no Vulkan GPU" }
            val dir = ModelStore.prepareNcnn(context, settings.ncnnModel, log)
            val gpu = NcnnRifeEngine(
                dir, gpuId = 0, cpuThreads = 2, padding = settings.ncnnModel.padding,
                fp16 = settings.fp16, coopMatrix = settings.coopMatrix
            )
            val helper = if (choice.cpuWorkers > 0) {
                runCatching {
                    // fp32 on the CPU: ncnn's ARM fp16 kernels are a separate, unmeasured risk.
                    NcnnRifeEngine(
                        dir, gpuId = -1, cpuThreads = choice.cpuThreads,
                        padding = settings.ncnnModel.padding, fp16 = false
                    )
                }.onFailure { log("CPU assist unavailable: ${it.message}") }.getOrNull()
            } else null
            val engines = ArrayList<FrameEngine>()
            repeat(choice.workers) { engines += gpu }
            if (helper != null) repeat(choice.cpuWorkers) { engines += helper }
            InferencePool(engines, engines.size, if (helper != null) choice.cpuThreads else 0, log)
        }
        EngineKind.NCNN_CPU -> {
            check(NcnnRifeEngine.isAvailable) { "native library missing" }
            val dir = ModelStore.prepareNcnn(context, settings.ncnnModel, log)
            val engine = NcnnRifeEngine(
                dir, gpuId = -1, cpuThreads = choice.threadsPerWorker,
                padding = settings.ncnnModel.padding, fp16 = settings.fp16
            )
            InferencePool(listOf<FrameEngine>(engine), choice.workers, choice.threadsPerWorker, log)
        }
        EngineKind.ORT_CPU -> InferencePool(
            modelPath = ModelStore.prepare(context, settings.quality, log),
            workers = choice.workers,
            threadsPerWorker = choice.threadsPerWorker,
            useNnapi = settings.useNnapi,
            useXnnpack = settings.useXnnpack,
            parallelOps = settings.parallelOps,
            frameWidth = width,
            frameHeight = height,
            log = log
        )
    }

    /** [selfTest] on a separate thread, so a GPU call that never returns cannot block the job. */
    fun selfTestWithTimeout(engine: FrameEngine, width: Int, height: Int, timeoutS: Long = SELF_TEST_TIMEOUT_S): Double {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "rife-selftest").apply { isDaemon = true }
        }
        try {
            val future = executor.submit<Double> { selfTest(engine, width, height) }
            return try {
                future.get(timeoutS, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                if (engine is NcnnRifeEngine && engine.gpuId >= 0) markGpuHung()
                throw java.util.concurrent.TimeoutException(
                    "no answer after $timeoutS s (${engine.label}): driver hang"
                )
            } catch (e: java.util.concurrent.ExecutionException) {
                throw e.cause ?: e
            }
        } finally {
            executor.shutdown()
        }
    }

    /**
     * Interpolates a textured frame with itself at t = 0.5 and returns the PSNR of the result
     * against that frame. Any correct RIFE returns the input (no motion to compensate).
     */
    fun selfTest(engine: FrameEngine, width: Int, height: Int): Double {
        val plane = width * height
        val frame = FloatArray(plane * 3)
        for (c in 0 until 3) {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val v = 0.5 + 0.25 * kotlin.math.sin((x + 7 * c) * 0.11) * kotlin.math.cos((y - 3 * c) * 0.07) +
                        0.2 * ((x + y + 13 * c) % 64) / 64.0
                    frame[c * plane + y * width + x] = v.toFloat().coerceIn(0f, 1f)
                }
            }
        }
        val out = FloatArray(plane * 3)
        engine.interpolate(frame, frame, width, height, 0.5f, out)
        var se = 0.0
        for (i in frame.indices) {
            val o = out[i]
            if (o.isNaN() || o.isInfinite()) return 0.0
            val d = (o - frame[i]).toDouble()
            se += d * d
        }
        val mse = se / frame.size
        return if (mse <= 1e-12) 99.0 else 10.0 * log10(1.0 / mse)
    }
}
