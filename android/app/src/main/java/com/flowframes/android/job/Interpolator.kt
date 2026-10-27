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
import com.flowframes.android.core.FrameMath
import com.flowframes.android.core.InterpPlan
import com.flowframes.android.core.LatencyStats
import com.flowframes.android.core.Naming
import com.flowframes.android.core.RifeEngine
import com.flowframes.android.video.VideoDecoder
import com.flowframes.android.video.VideoEncoder
import com.flowframes.android.video.VideoInfo
import java.io.File
import java.util.Locale

class JobCancelledException : Exception("Job cancelled")

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

            val modelPath = ModelStore.prepare(context, settings.quality, log)
            val threads = if (settings.threads > 0) settings.threads else RifeEngine.defaultThreads()
            if (settings.useNnapi) {
                log("NNAPI requested: falls back to CPU silently on many drivers, compare with the benchmark")
            }

            RifeEngine(
                modelPath = modelPath,
                intraOpThreads = threads,
                useNnapi = settings.useNnapi,
                frameWidth = targetW,
                frameHeight = targetH,
                log = log
            ).use { engine ->
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

                    frames = runLoop(engine, decoder, encoder!!, info, targetW, targetH, factor, srcFps)
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
        engine: RifeEngine,
        decoder: VideoDecoder,
        encoder: VideoEncoder,
        info: VideoInfo,
        width: Int,
        height: Int,
        factor: Int,
        srcFps: Double
    ): Int {
        val pixels = width * height
        var prev = IntArray(pixels)
        var cur = IntArray(pixels)
        var prevPlanar = FloatArray(pixels * 3)
        var curPlanar = FloatArray(pixels * 3)
        val midPlanar = FloatArray(pixels * 3)

        var firstPlanar: FloatArray? = null
        var lastPts = 0L
        var firstPts = 0L
        var havePrev = false
        var decoded = 0
        var written = 0
        var skippedDuplicates = 0
        var cuts = 0
        val phases = InterpPlan.phases(factor)
        val planOptions = settings.planOptions()
        val timing = StageTiming()
        val wallStart = System.nanoTime()
        val expectedFrames = if (info.durationUs > 0) {
            (info.durationSeconds * srcFps).toInt().coerceAtLeast(1)
        } else 1000
        // A 576x576 RIFE pass costs ~380 ms on current phones, so 30 s in is enough to sample.
        val heatCheckAfter = 90

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
                System.arraycopy(curPlanar, 0, prevPlanar, 0, pixels * 3)
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
                    timing.encode { encoder.encodePlanar(curPlanar, frame.ptsUs) }
                    written++
                    adopt(cur, curPlanar, prev, prevPlanar, pixels)
                    lastPts = frame.ptsUs
                }

                InterpPlan.Decision.INTERPOLATE -> {
                    val span = frame.ptsUs - lastPts
                    for (t in phases) {
                        checkCancelled()
                        timing.infer {
                            engine.interpolate(prevPlanar, curPlanar, width, height, t.toFloat(), midPlanar)
                        }
                        val pts = lastPts + (span * t).toLong()
                        timing.encode { encoder.encodePlanar(midPlanar, pts) }
                        written++
                    }
                    timing.encode { encoder.encodePlanar(curPlanar, frame.ptsUs) }
                    written++
                    adopt(cur, curPlanar, prev, prevPlanar, pixels)
                    lastPts = frame.ptsUs
                }
            }

            onProgress(
                (0.98f * decoded / expectedFrames.toFloat()).coerceIn(0f, 0.98f),
                "Frame $decoded/$expectedFrames · $written out · ${timing.rate(decoded)} source fps"
            )
        }

        if (skippedDuplicates > 0) log("Deduplication: $skippedDuplicates duplicate frame(s) skipped")
        if (cuts > 0) log("Scene changes: $cuts cut(s) passed through without interpolation")

        val loopStart = firstPlanar
        if (settings.loop && decoded > 1 && loopStart != null) {
            val avgSpan = if (decoded > 1) (lastPts - firstPts) / (decoded - 1) else 33_333L
            log("Loop interpolation (last → first frame)")
            for (t in phases) {
                checkCancelled()
                timing.infer {
                    engine.interpolate(prevPlanar, loopStart, width, height, t.toFloat(), midPlanar)
                }
                timing.encode { encoder.encodePlanar(midPlanar, lastPts + (avgSpan * t).toLong()) }
                written++
            }
            timing.encode { encoder.encodePlanar(loopStart, lastPts + avgSpan) }
            written++
        }

        if (decoded == 0) throw IllegalStateException("No frame could be decoded from this video")
        log(timing.summary(decoded, written))
        log(timing.inferenceMs.summary("Inference latency"))
        log(timing.decodeMs.summary("Decode+convert latency"))
        val wallMs = (System.nanoTime() - wallStart) / 1_000_000
        val overheadMs = wallMs - timing.totalNs() / 1_000_000
        log("Wall time: $wallMs ms (pipeline ${timing.totalNs() / 1_000_000} ms, overhead $overheadMs ms)")
        log("Thermal status at end: ${thermal()}")
        return written
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
