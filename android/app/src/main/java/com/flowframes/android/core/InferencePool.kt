package com.flowframes.android.core

import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray

/**
 * One interpolation engine, as seen by [InferencePool].
 *
 * [RifeEngine] is the real implementation. This is an abstract class rather than an interface so
 * that a test can substitute a fake engine and check the pool's scheduling without loading a
 * 21 MB model; [stopProfiling] keeps its default in that case.
 */
abstract class InferenceEngine : AutoCloseable {

    /** Interpolates the frame at phase [t] between [frame0] and [frame1] into [out]. */
    abstract fun interpolate(
        frame0: FloatArray,
        frame1: FloatArray,
        width: Int,
        height: Int,
        t: Float,
        out: FloatArray
    )

    /** Heaviest operators, bells and whistles first, when this engine was built with profiling. */
    open fun stopProfiling(top: Int = 8): List<String> = emptyList()
}

/**
 * Several RIFE sessions working at the same time.
 *
 * One inference cannot keep eight cores busy: on a Snapdragon 8 Elite, 4, 6 and 8 intra-op threads
 * cost 857, 814 and 885 ms per frame — the threads wait on memory, they do not compute faster.
 * Independent inferences do fill the machine. Measured at 576×576 with `tools/concurrency_probe.py`:
 *
 * | Configuration | ms per inference | Throughput |
 * |---|---|---|
 * | 1 session × 8 threads | 259 | 3.9 inf/s |
 * | 2 sessions × 4 threads | 147 | 6.8 inf/s |
 * | 3 sessions × 2 threads | 162 | 6.2 inf/s |
 * | 4 sessions × 2 threads | **125** | **8.0 inf/s** |
 *
 * That is 2.1× the throughput for the same output, pixel for pixel: this is scheduling, not a
 * quality trade-off. Each session owns its own arena, so memory grows with the number of sessions —
 * the caller chooses the count and the benchmark measures it on the device.
 *
 * Each session gets **its own single-thread executor**: the whole point of the design is that a
 * session runs one inference at a time (its intra-op pool is the parallelism inside that
 * inference). A shared worker pool with round-robin assignment cannot promise that, because the
 * task submitted second may start first and land on a session that is still busy; two concurrent
 * `run()` calls on one session share its intra-op threads and lose the isolation the throughput
 * numbers above were measured with. [peakConcurrencyPerSession] reports whether that contract held.
 */
class InferencePool private constructor(
    private val engines: List<InferenceEngine>,
    val threadsPerWorker: Int,
    private val log: (String) -> Unit
) : AutoCloseable {

    /**
     * Builds [workers] independent RIFE sessions from one model file.
     */
    constructor(
        modelPath: String,
        workers: Int,
        threadsPerWorker: Int,
        useNnapi: Boolean = false,
        useXnnpack: Boolean = false,
        parallelOps: Boolean = false,
        frameWidth: Int = 0,
        frameHeight: Int = 0,
        profileDir: File? = null,
        log: (String) -> Unit = {}
    ) : this(
        engines = createEngines(
            modelPath, workers, threadsPerWorker, useNnapi, useXnnpack, parallelOps,
            frameWidth, frameHeight, profileDir, log
        ),
        threadsPerWorker = threadsPerWorker,
        log = log
    )

    /** Used by the tests, which supply fake engines and their own thread count. */
    internal constructor(engines: List<InferenceEngine>, threadsPerWorker: Int) :
        this(engines, threadsPerWorker, {})

    /** Number of sessions, i.e. how many inferences can run at the same time. */
    val workers: Int get() = engines.size

    private val executors: List<ExecutorService> = engines.indices.map { index ->
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "rife-$index").apply { isDaemon = true }
        }
    }

    private val next = AtomicInteger()
    private val inFlight = AtomicIntegerArray(engines.size)
    private val peakInFlight = AtomicIntegerArray(engines.size)

    /** Called on the worker thread after every interpolation, with its duration in nanoseconds. */
    @Volatile
    var onInference: ((Long) -> Unit)? = null

    /** One of the sessions, for the rare call that must block the calling thread. */
    val primary: InferenceEngine get() = engines[0]

    /**
     * Highest number of inferences one session ever ran at the same time. It has to stay at 1:
     * anything above means two `run()` calls shared a session's intra-op threads, which is the
     * contention this pool exists to avoid.
     */
    val peakConcurrencyPerSession: Int
        get() = (0 until engines.size).maxOf { peakInFlight.get(it) }

    init {
        require(engines.isNotEmpty()) { "at least one inference engine is required" }
        require(threadsPerWorker >= 1) { "threadsPerWorker must be >= 1" }
        log(
            "Inference pool: ${engines.size} sessions × $threadsPerWorker threads " +
                "(${totalThreads} threads, one inference per session at a time)"
        )
    }

    /** Total inference threads across every session. */
    val totalThreads: Int get() = engines.size * threadsPerWorker

    /**
     * Queues one interpolation. The array [out] belongs to the caller and is written by a worker
     * thread; the returned future completes when it is safe to encode it.
     *
     * Sessions are used round-robin so that consecutive phases of the same pair, which is how the
     * pipeline submits them, start on different sessions.
     */
    fun submit(
        frame0: FloatArray,
        frame1: FloatArray,
        width: Int,
        height: Int,
        t: Float,
        out: FloatArray
    ): Future<*> = submitTo(Math.floorMod(next.getAndIncrement(), engines.size), frame0, frame1, width, height, t, out)

    private fun submitTo(
        index: Int,
        frame0: FloatArray,
        frame1: FloatArray,
        width: Int,
        height: Int,
        t: Float,
        out: FloatArray
    ): Future<*> {
        val engine = engines[index]
        return executors[index].submit {
            val busy = inFlight.incrementAndGet(index)
            while (true) {
                val seen = peakInFlight.get(index)
                if (busy <= seen || peakInFlight.compareAndSet(index, seen, busy)) break
            }
            val start = System.nanoTime()
            try {
                engine.interpolate(frame0, frame1, width, height, t, out)
            } finally {
                inFlight.decrementAndGet(index)
                onInference?.invoke(System.nanoTime() - start)
            }
        }
    }

    /** Runs every session once so the first real frame does not pay for lazy setup. */
    fun warmUp(width: Int, height: Int) {
        val futures = ArrayList<Future<*>>(engines.size)
        for (index in engines.indices) {
            val a = FloatArray(width * height * 3) { 0.5f }
            val b = FloatArray(width * height * 3) { 0.5f }
            val o = FloatArray(width * height * 3)
            futures += submitTo(index, a, b, width, height, 0.5f, o)
        }
        futures.forEach { runCatching { it.get() } }
    }

    override fun close() {
        executors.forEach { it.shutdown() }
        // One shared deadline: awaiting each executor in turn could otherwise wait workers × 60 s.
        val deadline = System.currentTimeMillis() + 60_000
        var quiet = true
        for (executor in executors) {
            val remaining = deadline - System.currentTimeMillis()
            val done = remaining > 0 &&
                runCatching { executor.awaitTermination(remaining, TimeUnit.MILLISECONDS) }.getOrDefault(false)
            if (!done) quiet = false
        }
        if (!quiet) {
            // A cancelled job can leave an inference running; closing the session under it would
            // crash in native code, so let the process reclaim it instead.
            executors.forEach { runCatching { it.shutdownNow() } }
            log("Inference pool: a session was still busy at shutdown, it is left to the process")
            return
        }
        engines.forEach { runCatching { it.close() } }
    }

    companion object {

        private fun createEngines(
            modelPath: String,
            workers: Int,
            threadsPerWorker: Int,
            useNnapi: Boolean,
            useXnnpack: Boolean,
            parallelOps: Boolean,
            frameWidth: Int,
            frameHeight: Int,
            profileDir: File?,
            log: (String) -> Unit
        ): List<InferenceEngine> {
            require(workers >= 1) { "workers must be >= 1" }
            val built = ArrayList<InferenceEngine>(workers)
            try {
                for (index in 0 until workers) {
                    built += RifeEngine(
                        modelPath = modelPath,
                        intraOpThreads = threadsPerWorker,
                        useNnapi = useNnapi,
                        useXnnpack = useXnnpack,
                        parallelOps = parallelOps,
                        frameWidth = frameWidth,
                        frameHeight = frameHeight,
                        profileDir = if (index == 0) profileDir else null,
                        log = if (index == 0) log else ({ _: String -> })
                    )
                }
            } catch (t: Throwable) {
                built.forEach { runCatching { it.close() } }
                throw t
            }
            return built
        }

        /**
         * Sessions to run in parallel. Two threads per session keeps a core pair per session and
         * leaves two cores for the decoder, the encoder and the pixel conversions.
         */
        fun defaultWorkers(): Int = Runtime.getRuntime().availableProcessors().let { cores ->
            when {
                cores >= 8 -> 3
                cores >= 6 -> 2
                else -> 2
            }
        }

        /** Threads per session so that the pool never occupies more than `cores - 2` cores. */
        fun defaultThreadsPerWorker(workers: Int): Int =
            Runtime.getRuntime().availableProcessors().let { cores ->
                val usable = if (cores <= 2) 1 else cores - 2
                (usable / workers.coerceAtLeast(1)).coerceAtLeast(1)
            }
    }
}

/**
 * Reuses frame buffers instead of allocating 4 MB arrays per frame: at 576×576 the job moves
 * roughly 20 MB per pair, and the collector should not see any of it.
 */
class FloatArrayPool(private val size: Int) {

    private val free = ArrayDeque<FloatArray>()

    @Synchronized
    fun acquire(): FloatArray = free.removeLastOrNull() ?: FloatArray(size)

    @Synchronized
    fun release(array: FloatArray) {
        if (array.size == size && free.size < 12) free.addLast(array)
    }
}
