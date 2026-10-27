package com.flowframes.android.job

import android.content.Context
import com.flowframes.android.core.FrameEngine
import com.flowframes.android.core.NcnnRifeEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Builds the GPU engine when the app starts, instead of when a job starts.
 *
 * `ncnn load_model` is where a job used to sit and wait: it compiles every GLSL compute shader, has
 * the Vulkan driver build its pipelines and converts the weights (fp16 storage is always on). On an
 * Adreno that is up to two minutes the first time a configuration runs — and it was paid again at
 * every job start, because a job builds its own engine. That cost does not depend on the video, so
 * it is paid here, on a background thread, as soon as the shader cache directory is set.
 *
 * The engines then stay alive and [EngineFactory.build] borrows them: a job starts immediately, and
 * the whole queue and the next queue reuse the same engines. They are *borrowed*, not owned — the
 * pool a job gets from here leaves them open (`InferencePool(ownsEngines = false)`). They are closed
 * when the settings they were built for change, and rebuilt in the background.
 */
object EngineWarmup {

    /** What the preparation is doing, for the UI. */
    enum class Phase { IDLE, BUILDING, READY, FAILED, NOT_APPLICABLE }

    data class Status(
        val phase: Phase = Phase.IDLE,
        val detail: String = "",
        /** How long the last successful build took, in ms. */
        val millis: Long = 0L,
        val label: String = ""
    )

    /** Warm-up resolution: enough to build every pipeline, cheap to run. */
    private const val WARM_WIDTH = 512
    private const val WARM_HEIGHT = 288

    /** How long a job waits for a preparation already in flight before building its own engine. */
    private const val WAIT_FOR_BUILD_MS = 90_000L

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> get() = _status

    /** What the engines in [holder] were built for; two engines are interchangeable iff this matches. */
    private class Request(val model: NcnnModel, val fp16: Boolean, val coopMatrix: Boolean)

    private class Holder(
        val request: Request,
        val gpu: FrameEngine,
        val cpuHelper: FrameEngine?,
        val cpuThreads: Int,
        val millis: Long
    ) {
        /** A job is using these engines right now. */
        @Volatile var inUse = false

        /** The settings changed while a job was using them: close as soon as that job ends. */
        @Volatile var retire = false
    }

    private val lock = Any()
    private var holder: Holder? = null
    private var building = false
    private var buildRequest: Request? = null

    /**
     * Settings the prepared engine is valid for, or null when the GPU is not the engine a job would
     * use (ONNX, ensemble, no Vulkan GPU) or when the shader cache directory is not set yet.
     */
    private fun requestOf(settings: Settings): Request? = when {
        // The ensemble weights exist as ONNX only, and the ONNX sessions are tied to the job's
        // resolution: their build is short enough to stay where it is.
        settings.quality == AiQuality.HIGH -> null
        EngineFactory.chain(settings).first() != EngineKind.NCNN_GPU -> null
        !NcnnRifeEngine.isAvailable -> null
        !NcnnRifeEngine.shaderCacheDirReady -> null
        EngineFactory.gpuHung -> null
        NcnnRifeEngine.gpuCount() <= 0 -> null
        else -> Request(settings.ncnnModel, settings.fp16, settings.coopMatrix)
    }

    /**
     * Starts the build in the background unless the prepared engines already match [settings].
     * Safe to call repeatedly: it returns at once when nothing needs rebuilding.
     */
    @JvmOverloads
    fun prepare(context: Context, settings: Settings, log: (String) -> Unit = {}) {
        val request = requestOf(settings)
        if (request == null) {
            synchronized(lock) {
                val h = holder
                if (h != null) closeLocked()
                _status.value = if (!NcnnRifeEngine.isAvailable || EngineFactory.gpuHung ||
                    NcnnRifeEngine.gpuCount() <= 0
                ) Status(Phase.NOT_APPLICABLE, "GPU unavailable: jobs run on the CPU") else Status(
                    Phase.NOT_APPLICABLE, "GPU not used by the current settings"
                )
            }
            return
        }
        synchronized(lock) {
            if (building) return
            val h = holder
            if (h != null && h.request == request && !h.retire) return
            if (h != null && h.inUse) {
                // A job is running on them: they are closed and rebuilt when that job ends.
                h.retire = true
                return
            }
            closeLocked()
            building = true
            buildRequest = request
        }
        _status.value = Status(Phase.BUILDING, "Preparing the GPU engine…")
        log(
            "GPU: building the engine now, in the background (shader compilation, driver pipelines, " +
                "fp16 weights) so every job starts immediately…"
        )
        val thread = Thread({ build(context, settings, request, log) }, "gpu-warmup")
        thread.isDaemon = true
        thread.priority = Thread.MIN_PRIORITY
        thread.start()
    }

    private fun build(context: Context, settings: Settings, request: Request, log: (String) -> Unit) {
        val start = System.currentTimeMillis()
        try {
            val choice = EngineFactory.plan(EngineKind.NCNN_GPU, settings)
            val engines = EngineFactory.withHeartbeat("preparing the GPU engine", log) {
                EngineFactory.buildGpuEngines(context, settings, choice, log)
            }
            val gpu = engines.first()
            val helper = engines.firstOrNull { it !== gpu }
            warmUp(gpu, log)
            val millis = System.currentTimeMillis() - start
            synchronized(lock) {
                building = false
                buildRequest = null
                closeLocked()
                holder = Holder(request, gpu, helper, choice.cpuThreads, millis)
            }
            log(
                "GPU ready in ${millis} ms · ${NcnnRifeEngine.shaderCacheStats()} · " +
                    "interpolations now start without building anything"
            )
            _status.value = Status(Phase.READY, "GPU ready", millis, gpu.label)
        } catch (t: Throwable) {
            val reason = t.message ?: t.javaClass.simpleName
            synchronized(lock) {
                building = false
                buildRequest = null
            }
            log("GPU: the start-up preparation failed ($reason): the first job builds its own engine")
            _status.value = Status(Phase.FAILED, reason)
        }
    }

    /** One inference, so the lazy utility pipelines are built here and not at the job's first frame. */
    private fun warmUp(engine: FrameEngine, log: (String) -> Unit) {
        val plane = WARM_WIDTH * WARM_HEIGHT
        val a = IntArray(plane) { 0xFF404040.toInt() }
        val b = IntArray(plane) { 0xFFA0A0A0.toInt() }
        val out = IntArray(plane)
        val t = System.currentTimeMillis()
        engine.interpolateArgb(a, b, WARM_WIDTH, WARM_HEIGHT, 0.5f, out)
        log("GPU: warm-up inference at ${WARM_WIDTH}x$WARM_HEIGHT in ${System.currentTimeMillis() - t} ms")
    }

    /**
     * Engines for a job, in the same shape [EngineFactory.create] would build them (one entry per
     * worker), or null when nothing usable is prepared. The pool that gets them does not own them.
     */
    fun borrow(settings: Settings, choice: EngineFactory.Choice, log: (String) -> Unit = {}): List<FrameEngine>? {
        val request = requestOf(settings) ?: return null
        awaitBuild(request, log)
        synchronized(lock) {
            val h = holder ?: return null
            if (h.request != request || h.retire) return null
            // Hybrid mode needs the CPU helper, built with the same thread count.
            if (choice.cpuWorkers > 0 && (h.cpuHelper == null || h.cpuThreads != choice.cpuThreads)) return null
            h.inUse = true
            val engines = ArrayList<FrameEngine>(choice.workers.coerceAtLeast(1) + choice.cpuWorkers)
            repeat(choice.workers.coerceAtLeast(1)) { engines += h.gpu }
            val helper = h.cpuHelper
            if (choice.cpuWorkers > 0 && helper != null) repeat(choice.cpuWorkers) { engines += helper }
            log("Engine reused from the start-up preparation (built in ${h.millis} ms): no build at job start")
            return engines
        }
    }

    /**
     * Waits for a preparation already running for the same settings: building a second engine in
     * parallel would hold two copies of the model, and the job would just be slower.
     */
    private fun awaitBuild(request: Request, log: (String) -> Unit) {
        val deadline = System.currentTimeMillis() + WAIT_FOR_BUILD_MS
        var announced = false
        while (true) {
            val waiting = synchronized(lock) { building && buildRequest == request }
            if (!waiting) return
            if (!announced) {
                log("GPU: the start-up preparation is still running, waiting for it…")
                announced = true
            }
            if (System.currentTimeMillis() > deadline) {
                log("GPU: preparation still running after ${WAIT_FOR_BUILD_MS / 1000} s, this job builds its own engine")
                return
            }
            runCatching { Thread.sleep(250) }
        }
    }

    /** The job using these engines rejected them (self-test, build failure): drop them. */
    fun discard(reason: String, log: (String) -> Unit = {}) {
        synchronized(lock) {
            val h = holder ?: return
            h.inUse = false
            h.retire = true
            closeLocked()
            log("GPU: the prepared engine was dropped ($reason), it is rebuilt in the background")
        }
        _status.value = Status(Phase.IDLE, "rebuilding")
    }

    /**
     * End of a job queue: the engines stay for the next one, stale ones are closed and the cache is
     * re-armed in the background (a no-op when they are still valid).
     */
    fun onQueueFinished(context: Context, settings: Settings, log: (String) -> Unit = {}) {
        synchronized(lock) {
            holder?.inUse = false
            closeLocked()
        }
        prepare(context, settings, log)
    }

    /** Closes the prepared engines if they are free; a job using them retires them instead. */
    private fun closeLocked() {
        val h = holder ?: return
        if (h.inUse) {
            h.retire = true
            return
        }
        holder = null
        listOfNotNull(h.gpu, h.cpuHelper).forEach { runCatching { it.close() } }
    }
}
