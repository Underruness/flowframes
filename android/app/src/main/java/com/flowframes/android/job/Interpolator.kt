package com.flowframes.android.job

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.documentfile.provider.DocumentFile
import com.flowframes.android.core.DeviceInfo
import com.flowframes.android.core.FloatArrayPool
import com.flowframes.android.core.FrameMath
import com.flowframes.android.core.InferencePool
import com.flowframes.android.core.InterpPlan
import com.flowframes.android.core.LatencyStats
import com.flowframes.android.core.Naming
import com.flowframes.android.core.OrderedWindow
import com.flowframes.android.video.VideoDecoder
import com.flowframes.android.video.VideoEncoder
import com.flowframes.android.video.VideoInfo
import java.io.File
import java.util.Locale

class JobCancelledException : Exception("Job cancelled")

/**
 * One source pair in flight: its two frames (owned copies, so the decoder can move on), the
 * interpolated frames being produced by the pool, and the futures that fill them.
 */
private class PendingPair(
    val startPts: Long,
    val endPts: Long,
    val first: FloatArray,
    val second: FloatArray,
    val frames: List<FloatArray>,
    val futures: List<java.util.concurrent.Future<*>>
)

/**
 * Per-stage wall-clock accounting. Kept in the job log so a slow run can be diagnosed instead of
 * guessed at: the model, the pixel conversions and the codecs have very different costs.
 */
private class StageTiming {
    var decodeNs = 0L
    var inferNs = 0L
    var encodeNs = 0L
    var inferCalls = 0
    val inferenceMs = LatencyStats()
    val decodeMs = LatencyStats()

    fun <T> decode(block: () -> T): T {
        val t = System.nanoTime()
        try {
            return block()
        } finally {
            val elapsed = System.nanoTime() - t
            decodeNs += elapsed
            decodeMs.add((elapsed / 1_000_000).toInt())
        }
    }

    /** Records an inference measured on a worker thread. */
    @Synchronized
    fun recordInference(ns: Long) {
        inferNs += ns
        inferCalls++
        inferenceMs.add((ns / 1_000_000).toInt())
    }

    fun <T> infer(block: () -> T): T {
        val t = System.nanoTime()
        try {
            return block()
        } finally {
            val elapsed = System.nanoTime() - t
            inferNs += elapsed
            inferCalls++
            inferenceMs.add((elapsed / 1_000_000).toInt())
        }
    }

    fun <T> encode(block: () -> T): T {
        val t = System.nanoTime()
        try {
            return block()
        } finally {
            encodeNs += System.nanoTime() - t
        }
    }

    /** Mean of the most recent inference samples, for the per-minute heartbeat. */
    fun recentInferenceMs(): Double = inferenceMs.recentMeanMs()

    /** Throughput so far, ignoring inference still running. */
    fun rate(frames: Int): String {
        val totalNs = decodeNs + inferNs + encodeNs
        if (totalNs <= 0 || frames <= 0) return "–"
        return String.format(Locale.US, "%.2f", frames / (totalNs / 1_000_000_000.0))
    }

    fun totalNs(): Long = decodeNs + inferNs + encodeNs

    fun summary(frames: Int, outFrames: Int): String {
        fun ms(v: Long) = String.format(Locale.US, "%.0f", v / 1_000_000.0)
        val perInfer = if (inferCalls > 0) inferNs / 1_000_000.0 / inferCalls else 0.0
        val total = totalNs()
        val fps = if (total > 0) frames / (total / 1_000_000_000.0) else 0.0
        val share = if (total > 0) inferNs * 100.0 / total else 0.0
        return "Stage timing: decode ${ms(decodeNs)} ms · inference ${ms(inferNs)} ms " +
            "(${String.format(Locale.US, "%.0f", perInfer)} ms/frame, $inferCalls inferences, " +
            "${String.format(Locale.US, "%.0f", share)}% of wall time) · encode ${ms(encodeNs)} ms " +
            "→ ${String.format(Locale.US, "%.2f", fps)} source fps ($outFrames output frames)"
    }
}


/** One interpolation never takes this long on a working engine (CPU 1080p: ~2 s). */
private const val INFERENCE_TIMEOUT_S = 120L

/**
 * Full FlowFrames pipeline on device:
 * decode (MediaCodec) → RIFE interpolation (ONNX Runtime) → encode (MediaCodec) → mux (MediaMuxer).
 */

class Interpolator(
    private val context: Context,
    private val settings: Settings,
    private val log: (String) -> Unit,
    private val onProgress: (Float, String) -> Unit,
    private val isCancelled: () -> Boolean
) {

    data class Output(val uri: Uri, val label: String, val frames: Int, val path: String?)

    fun process(inputUri: Uri, displayName: String): Output {
        val info = VideoInfo.read(context, inputUri)
            ?: throw IllegalStateException("Cannot read this video (unsupported format)")

        log(DeviceInfo.headline(context))
        log(
            "Source: ${info.width}x${info.height}" +
                (if (info.rotation != 0) " (rotation ${info.rotation}°)" else "") +
                " · ${fmt(info.fps)} fps · ${fmt(info.durationSeconds)} s · ${info.mime.substringAfter('/')}"
        )

        val cap = settings.maxLongSide
        val (targetW, targetH) = FrameMath.fitWithin(info.displayWidth, info.displayHeight, cap, cap)
        val srcFps = if (info.fps > 0.5) info.fps else 30.0
        val factor = InterpPlan.effectiveFactor(settings.factor, srcFps, settings.maxOutputFps)
        val outFps = srcFps * factor
        if (factor != settings.factor) {
            log("Multiplier reduced from x${settings.factor} to x$factor to stay under ${settings.maxOutputFps.toInt()} fps")
        }
        log("Processing at ${targetW}x$targetH, output ${fmt(outFps)} fps (x$factor)")

        val codec = if (VideoEncoder.isEncoderAvailable(settings.codec.mime)) settings.codec else {
            log("${settings.codec.label} encoder unavailable on this device → H.264")
            OutputCodec.H264
        }
        val bitrate = VideoEncoder.bitrateFor(targetW, targetH, outFps.toInt(), settings.bitratePreset)

        val baseName = Naming.build(settings.namePattern, displayName, factor, outFps, codec.label)
        val outTarget = createOutput(baseName)
        var success = false

        try {
            val audioFormat = if (settings.keepAudio && info.hasAudio) readAudioFormat(inputUri) else null
            if (settings.keepAudio && info.hasAudio && audioFormat == null) {
                log("Audio track unreadable, output will be silent")
            }

            log("${settings.factor - 1} inference(s) per source frame · engine setting: ${settings.engine.label}")
            if (settings.useNnapi && EngineFactory.chain(settings).first() == EngineKind.ORT_CPU) {
                log("NNAPI requested: it was 6x slower than the CPU on a Snapdragon 8 Elite. Run the benchmark to compare on this device.")
            }

            EngineFactory.build(
                context = context,
                settings = settings,
                width = targetW,
                height = targetH,
                log = log
            ).use { pool ->
                val outPfd = outTarget.fd
                val inPfd = context.contentResolver.openFileDescriptor(inputUri, "r")
                    ?: throw IllegalStateException("Cannot open the source video")

                var decoder: VideoDecoder? = null
                var encoder: VideoEncoder? = null
                var frames = 0
                try {
                    encoder = VideoEncoder(
                        outputFd = outPfd.fileDescriptor,
                        width = targetW,
                        height = targetH,
                        mime = codec.mime,
                        bitrate = bitrate,
                        frameRate = outFps.toInt().coerceIn(1, 480),
                        spec = info.colorSpec,
                        audioFormat = audioFormat,
                        log = log
                    )
                    decoder = VideoDecoder(
                        fd = inPfd.fileDescriptor,
                        targetWidth = targetW,
                        targetHeight = targetH,
                        rotation = info.rotation,
                        initialSpec = info.colorSpec,
                        log = log
                    )

                    frames = runLoop(pool, decoder, encoder!!, info, targetW, targetH, factor, srcFps)
                    encoder.finishVideo()
                    if (encoder.hasAudio) copyAudio(inputUri, encoder, log)
                } finally {
                    runCatching { decoder?.close() }
                    runCatching { encoder?.close() }
                    runCatching { inPfd.close() }
                    runCatching { outPfd.close() }
                }

                success = true
                onProgress(1f, "Done")
                log("Done → ${outTarget.label} ($frames frames)")
                runCatching { outTarget.fd.close() }
                return Output(outTarget.uri, outTarget.label, frames, outTarget.path)
            }
        } catch (t: Throwable) {
            runCatching { outTarget.fd.close() }
            if (!success) deleteOutput(outTarget)
            throw t
        }
    }

    private class OutputTarget(
        val uri: Uri,
        val label: String,
        val path: String?,
        val fd: ParcelFileDescriptor
    )

    private fun readAudioFormat(uri: Uri): MediaFormat? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val ex = MediaExtractor()
            try {
                ex.setDataSource(pfd.fileDescriptor)
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) return@use f
                }
                null
            } finally {
                runCatching { ex.release() }
            }
        }
    }.getOrNull()

    private fun runLoop(
        pool: InferencePool,
        decoder: VideoDecoder,
        encoder: VideoEncoder,
        info: VideoInfo,
        width: Int,
        height: Int,
        factor: Int,
        srcFps: Double
    ): Int {
        val pixels = width * height
        val planeSize = pixels * 3
        val buffers = FloatArrayPool(planeSize)
        var prev = IntArray(pixels)
        var cur = IntArray(pixels)
        var prevPlanar = FloatArray(planeSize)
        var curPlanar = FloatArray(planeSize)

        var firstPlanar: FloatArray? = null
        var lastPts = 0L
        var firstPts = 0L
        var havePrev = false
        var decoded = 0
        var written = 0
        var skippedDuplicates = 0
        var cuts = 0
        var interpolatedPairs = 0
        val phases = InterpPlan.phases(factor)
        val planOptions = settings.planOptions()
        val timing = StageTiming()
        val wallStart = System.nanoTime()
        val expectedFrames = if (info.durationUs > 0) {
            (info.durationSeconds * srcFps).toInt().coerceAtLeast(1)
        } else 1000
        // A 576x576 RIFE pass costs a few hundred ms on current phones, so 30 s in is enough.
        val heatCheckAfter = 90
        // A long job is where throttling shows up, so the log gets a heartbeat every minute.
        val heartbeatEveryNs = 60_000_000_000L
        var lastBeatNs = System.nanoTime()
        var lastBeatDecoded = 0

        pool.onInference = { ns -> timing.recordInference(ns) }

        // Interpolated frames are produced out of order by the pool and encoded in order here.
        // The window bounds how far the decoder runs ahead: one pair in flight holds its two source
        // frames plus one output per phase, so a 1080p job has to keep the window short.
        val bytesPerPair = (2L + phases.size) * planeSize * 4
        // Frames are Java-heap FloatArrays, so the window is budgeted against the heap this app was
        // given (largeHeap), never against the phone's RAM: at 1080p one pair is already ~130 MB.
        val heapBudget = Runtime.getRuntime().maxMemory() / 2
        // Hybrid GPU + CPU: a CPU phase takes several GPU phases' time and the encoder waits for it
        // in order, so the GPU needs more pairs ahead to stay busy meanwhile.
        val hybrid = pool.distinctEngines.size > 1
        val capacity = (heapBudget / bytesPerPair.coerceAtLeast(1L)).toInt()
            .coerceIn(1, if (hybrid) maxOf(4, pool.workers * 2) else maxOf(2, pool.workers))
        val window = OrderedWindow<PendingPair>(capacity) { pair ->
            written += encodePair(encoder, pair, phases, timing)
            buffers.release(pair.first)
            buffers.release(pair.second)
            pair.frames.forEach { buffers.release(it) }
        }
        log(
            "Pipeline: ${pool.workers} inference session(s) × ${pool.threadsPerWorker} threads " +
                "(${pool.totalThreads} total) · window of $capacity pair(s) · " +
                "${bytesPerPair * capacity / (1024 * 1024)} MB of frame buffers"
        )

        while (true) {
            checkCancelled()
            val frame = timing.decode {
                val f = decoder.decodeNext(cur) ?: return@decode null
                FrameMath.argbToPlanar(cur, curPlanar)
                f
            } ?: break
            decoded++

            if (decoded == heatCheckAfter) {
                log("Thermal status after $decoded frames: ${thermal()}")
            }

            if (!havePrev) {
                timing.encode { encoder.encodePlanar(curPlanar, frame.ptsUs) }
                written++
                System.arraycopy(cur, 0, prev, 0, pixels)
                System.arraycopy(curPlanar, 0, prevPlanar, 0, planeSize)
                firstPts = frame.ptsUs
                lastPts = frame.ptsUs
                havePrev = true
                if (settings.loop) {
                    firstPlanar = curPlanar.copyOf()
                }
                onProgress(
                    (0.98f * decoded / expectedFrames.toFloat()).coerceIn(0f, 0.5f),
                    "Frame $decoded/$expectedFrames"
                )
                continue
            }

            // One pass computes both metrics the dedupe/scene logic needs.
            val (lumaDiff, changed) = FrameMath.lumaStats(prev, cur, 0.08)
            when (InterpPlan.decide(prev, cur, planOptions, lumaDiff, changed)) {
                InterpPlan.Decision.SKIP_DUPLICATE -> {
                    skippedDuplicates++
                    // keep the previous frame, drop this duplicate
                }

                InterpPlan.Decision.CUT -> {
                    cuts++
                    // Everything queued must be written before the cut frame, or the output is
                    // out of order around the cut.
                    window.drain()
                    timing.encode { encoder.encodePlanar(curPlanar, frame.ptsUs) }
                    written++
                    adopt(cur, curPlanar, prev, prevPlanar, pixels)
                    lastPts = frame.ptsUs
                }

                InterpPlan.Decision.INTERPOLATE -> {
                    interpolatedPairs++
                    val first = buffers.acquire().also { System.arraycopy(prevPlanar, 0, it, 0, planeSize) }
                    val second = buffers.acquire().also { System.arraycopy(curPlanar, 0, it, 0, planeSize) }
                    val frames = ArrayList<FloatArray>(phases.size)
                    val futures = ArrayList<java.util.concurrent.Future<*>>(phases.size)
                    for (t in phases) {
                        checkCancelled()
                        val out = buffers.acquire()
                        frames += out
                        futures += pool.submit(first, second, width, height, t.toFloat(), out)
                    }
                    window.offer(PendingPair(lastPts, frame.ptsUs, first, second, frames, futures))
                    adopt(cur, curPlanar, prev, prevPlanar, pixels)
                    lastPts = frame.ptsUs
                }
            }

            onProgress(
                (0.98f * decoded / expectedFrames.toFloat()).coerceIn(0f, 0.98f),
                "Frame $decoded/$expectedFrames · $written out · ${timing.rate(decoded)} source fps"
            )

            val now = System.nanoTime()
            if (now - lastBeatNs >= heartbeatEveryNs) {
                val beatSeconds = (now - lastBeatNs) / 1_000_000_000.0
                val instant = if (beatSeconds > 0) (decoded - lastBeatDecoded) / beatSeconds else 0.0
                log(
                    String.format(
                        Locale.US,
                        "Progress: %d frames (%d out) · %.2f source fps overall · %.2f fps over the last %.0f s · " +
                            "inference %.0f ms/frame over the last minute · thermal %s · heap %d/%d MB",
                        decoded, written, timing.rate(decoded).toDoubleOrNull() ?: 0.0, instant, beatSeconds,
                        timing.recentInferenceMs(), thermal(), heapUsedMb(), heapMaxMb()
                    )
                )
                lastBeatNs = now
                lastBeatDecoded = decoded
            }
        }

        window.drain()
        pool.onInference = null
        log("Work split: ${pool.engineStats()}")

        if (skippedDuplicates > 0) log("Deduplication: $skippedDuplicates duplicate frame(s) skipped")
        if (cuts > 0) log("Scene changes: $cuts cut(s) passed through without interpolation")

        val loopStart = firstPlanar
        if (settings.loop && decoded > 1 && loopStart != null) {
            val avgSpan = if (decoded > 1) (lastPts - firstPts) / (decoded - 1) else 33_333L
            log("Loop interpolation (last → first frame)")
            val first = buffers.acquire().also { System.arraycopy(prevPlanar, 0, it, 0, planeSize) }
            val second = buffers.acquire().also { System.arraycopy(loopStart, 0, it, 0, planeSize) }
            val frames = ArrayList<FloatArray>(phases.size)
            val futures = ArrayList<java.util.concurrent.Future<*>>(phases.size)
            pool.onInference = { ns -> timing.recordInference(ns) }
            for (t in phases) {
                checkCancelled()
                val out = buffers.acquire()
                frames += out
                futures += pool.submit(first, second, width, height, t.toFloat(), out)
            }
            val tail = OrderedWindow<PendingPair>(1) { pair ->
                written += encodePair(encoder, pair, phases, timing)
                buffers.release(pair.first)
                buffers.release(pair.second)
                pair.frames.forEach { buffers.release(it) }
            }
            tail.offer(PendingPair(lastPts, lastPts + avgSpan, first, second, frames, futures))
            tail.drain()
            pool.onInference = null
        }

        if (decoded == 0) throw IllegalStateException("No frame could be decoded from this video")
        log(timing.summary(decoded, written))
        log(timing.inferenceMs.summary("Inference latency"))
        log(timing.decodeMs.summary("Decode+convert latency"))
        val wallMs = (System.nanoTime() - wallStart) / 1_000_000
        val overheadMs = wallMs - timing.totalNs() / 1_000_000
        log("Wall time: $wallMs ms (pipeline ${timing.totalNs() / 1_000_000} ms, overhead $overheadMs ms)")
        log("Frames: $decoded decoded · $interpolatedPairs pairs interpolated (x$factor) · " +
            "$cuts scene cut(s) · $skippedDuplicates duplicate(s) skipped · $written written")
        log("Thermal status at end: ${thermal()}")
        log(diagnosis(decoded, timing, wallMs.toDouble(), width, height, factor))
        return written
    }

    /** Waits for one pair's interpolations and encodes its frames in order. */
    private fun encodePair(
        encoder: VideoEncoder,
        pair: PendingPair,
        phases: DoubleArray,
        timing: StageTiming
    ): Int {
        var produced = 0
        val span = pair.endPts - pair.startPts
        for (i in phases.indices) {
            checkCancelled()
            awaitInference(pair.futures[i])
            val pts = pair.startPts + (span * phases[i]).toLong()
            timing.encode { encoder.encodePlanar(pair.frames[i], pts) }
            produced++
        }
        timing.encode { encoder.encodePlanar(pair.second, pair.endPts) }
        return produced + 1
    }

    private fun awaitInference(future: java.util.concurrent.Future<*>) {
        try {
            // Poll so that a cancel is honoured, and give up on a call that never returns (a GPU
            // driver hang) instead of leaving the job stuck forever.
            val deadline = System.nanoTime() + INFERENCE_TIMEOUT_S * 1_000_000_000L
            while (true) {
                try {
                    future.get(1, java.util.concurrent.TimeUnit.SECONDS)
                    break
                } catch (e: java.util.concurrent.TimeoutException) {
                    checkCancelled()
                    if (System.nanoTime() > deadline) {
                        EngineFactory.markGpuHung()
                        throw IllegalStateException(
                            "An inference did not finish in $INFERENCE_TIMEOUT_S s (GPU driver hang?). " +
                                "Restart the app; the next job will use the CPU, or turn fp16 math off."
                        )
                    }
                }
            }
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw JobCancelledException()
        }
    }

    private fun heapUsedMb(): Long {
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
    }

    private fun heapMaxMb(): Long = Runtime.getRuntime().maxMemory() / (1024 * 1024)

    /**
     * Turns the stage timings into one actionable sentence. The numbers decide the advice: the fix
     * for a model-bound job (lower the resolution) is the opposite of the fix for a codec-bound one.
     */
    private fun diagnosis(
        decoded: Int,
        timing: StageTiming,
        wallMs: Double,
        width: Int,
        height: Int,
        factor: Int
    ): String {
        val total = timing.totalNs().coerceAtLeast(1)
        val infer = timing.inferNs * 100.0 / total
        val decode = timing.decodeNs * 100.0 / total
        val encode = timing.encodeNs * 100.0 / total
        val perInfer = if (timing.inferCalls > 0) timing.inferNs / 1_000_000.0 / timing.inferCalls else 0.0
        var line = String.format(
            Locale.US,
            "Diagnosis: inference %.0f%% · decode+convert %.0f%% · encode %.0f%% of wall time. ",
            infer, decode, encode
        )
        line += when {
            infer >= 85 -> {
                val areaNow = (width.toDouble() * height).coerceAtLeast(1.0)
                val saving = ((1.0 - (854.0 * 480.0) / areaNow).coerceIn(0.0, 0.9) * 100).toInt()
                String.format(
                    Locale.US,
                    "The RIFE model is the limit (%.0f ms/frame): the codecs and pixel conversions are not worth " +
                        "optimising further. Cost scales with the pixel count, so 480p instead of %dx%d would cut it " +
                        "by about %d%%, and every extra multiplier step adds one more inference per pair " +
                        "(x%d costs %d times x2).",
                    perInfer, width, height, saving, factor, factor - 1
                )
            }
            decode >= 30 ->
                "Decoding is the limit, which usually means a codec without hardware support or a very high source resolution. " +
                    "Re-encode the source once with a hardware-friendly codec before interpolating."
            encode >= 30 ->
                "Encoding is the limit: the bitrate or the output resolution is heavy for this device. " +
                    "Try a lower bitrate preset or HEVC, which most phones encode in hardware."
            else -> "No single stage dominates; the job is balanced."
        }
        if (timing.inferenceMs.hasDrift()) {
            val drift = timing.inferenceMs.driftPercent()
            if (drift > 10.0) {
                line += String.format(
                    Locale.US,
                    " Inference got %.0f%% slower between the first and the last frames: the phone is throttling. " +
                        "Splitting long videos into shorter runs, or dropping the resolution, keeps the average up.",
                    drift
                )
            }
        }
        if (wallMs > 0 && decoded > 0) {
            line += String.format(Locale.US, " End-to-end: %.2f s per source frame.", wallMs / 1000.0 / decoded)
        }
        return line
    }

    private fun thermal(): String = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        DeviceInfo.thermalStatus(pm)
    }.getOrDefault("unknown")

    private fun adopt(
        cur: IntArray, curPlanar: FloatArray,
        prev: IntArray, prevPlanar: FloatArray,
        pixels: Int
    ) {
        System.arraycopy(cur, 0, prev, 0, pixels)
        System.arraycopy(curPlanar, 0, prevPlanar, 0, pixels * 3)
    }

    private fun copyAudio(inputUri: Uri, encoder: VideoEncoder, log: (String) -> Unit) {
        var copied = 0L
        try {
            context.contentResolver.openFileDescriptor(inputUri, "r")?.use { pfd ->
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(pfd.fileDescriptor)
                    var track = -1
                    for (i in 0 until ex.trackCount) {
                        val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                        if (mime.startsWith("audio/")) {
                            track = i
                            break
                        }
                    }
                    if (track < 0) return@use
                    ex.selectTrack(track)
                    val maxSize = runCatching {
                        ex.getTrackFormat(track).getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                    }.getOrDefault(256 * 1024).coerceAtLeast(64 * 1024)
                    val buffer = java.nio.ByteBuffer.allocateDirect(maxSize)
                    val info = android.media.MediaCodec.BufferInfo()
                    while (true) {
                        if (isCancelled()) throw JobCancelledException()
                        val size = ex.readSampleData(buffer, 0)
                        if (size < 0) break
                        info.set(0, size, ex.sampleTime, 0)
                        encoder.writeAudioSample(buffer, info)
                        copied++
                        ex.advance()
                    }
                } finally {
                    runCatching { ex.release() }
                }
            }
        } catch (c: JobCancelledException) {
            throw c
        } catch (t: Throwable) {
            log("Audio copy skipped: ${t.message}")
        }
        if (copied > 0) log("Audio copied: $copied samples")
    }

    private fun createOutput(baseName: String): OutputTarget {
        val treeUri = settings.outputTreeUri
        if (!treeUri.isNullOrBlank()) {
            val tree = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) }.getOrNull()
            if (tree != null && tree.canWrite()) {
                val existing = tree.listFiles().mapNotNull { it.name }.toSet()
                val name = Naming.deduplicate(baseName) { it in existing }
                val doc = tree.createFile("video/mp4", name)
                if (doc != null) {
                    val fd = context.contentResolver.openFileDescriptor(doc.uri, "rw")
                    if (fd != null) return OutputTarget(doc.uri, name, null, fd)
                    runCatching { doc.delete() }
                }
                log("Could not create the file in the chosen folder, falling back to the app folder")
            } else {
                log("Output folder not writable, falling back to the app folder")
            }
        }
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "FlowFrames").apply { mkdirs() }
        val existing = dir.list()?.toSet() ?: emptySet()
        val name = Naming.deduplicate(baseName) { it in existing }
        val file = File(dir, name)
        val fd = ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
        )
        return OutputTarget(Uri.fromFile(file), name, file.absolutePath, fd)
    }

    private fun deleteOutput(target: OutputTarget) {
        runCatching {
            if (target.path != null) {
                File(target.path).delete()
            } else {
                DocumentFile.fromSingleUri(context, target.uri)?.delete()
            }
        }
    }

    private fun checkCancelled() {
        if (isCancelled()) throw JobCancelledException()
    }

    private fun fmt(value: Double): String =
        String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
}
