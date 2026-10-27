package com.flowframes.android.job

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import com.flowframes.android.core.FrameMath
import com.flowframes.android.core.InterpPlan
import com.flowframes.android.core.Naming
import com.flowframes.android.core.RifeEngine
import com.flowframes.android.video.VideoDecoder
import com.flowframes.android.video.VideoEncoder
import com.flowframes.android.video.VideoInfo
import java.io.File
import java.util.Locale

class JobCancelledException : Exception("Tâche annulée")

/**
 * Per-stage wall-clock accounting. Kept in the job log so a slow run can be diagnosed instead of
 * guessed at: the model, the pixel conversions and the codecs have very different costs.
 */
private class StageTiming {
    var decodeNs = 0L
    var inferNs = 0L
    var encodeNs = 0L
    var inferCalls = 0

    fun <T> decode(block: () -> T): T {
        val t = System.nanoTime()
        try {
            return block()
        } finally {
            decodeNs += System.nanoTime() - t
        }
    }

    fun <T> infer(block: () -> T): T {
        val t = System.nanoTime()
        try {
            return block()
        } finally {
            inferNs += System.nanoTime() - t
            inferCalls++
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

    fun summary(frames: Int, outFrames: Int): String {
        fun ms(v: Long) = String.format(Locale.US, "%.0f", v / 1_000_000.0)
        val perInfer = if (inferCalls > 0) inferNs / 1_000_000.0 / inferCalls else 0.0
        val totalNs = decodeNs + inferNs + encodeNs
        val fps = if (totalNs > 0) frames / (totalNs / 1_000_000_000.0) else 0.0
        return "Temps par étage: décodage ${ms(decodeNs)} ms · inférence ${ms(inferNs)} ms " +
            "(${String.format(Locale.US, "%.0f", perInfer)} ms/image, $inferCalls images) · " +
            "encodage ${ms(encodeNs)} ms → ${String.format(Locale.US, "%.2f", fps)} img source/s " +
            "($outFrames images en sortie)"
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
            ?: throw IllegalStateException("Impossible de lire cette vidéo (format non supporté)")

        log(
            "Source: ${info.width}x${info.height} · ${fmt(info.fps)} fps · " +
                "${fmt(info.durationSeconds)} s · ${info.mime.substringAfter('/')}" +
                if (info.rotation != 0) " · rotation ${info.rotation}°" else ""
        )

        val cap = settings.maxLongSide
        val (targetW, targetH) = FrameMath.fitWithin(info.displayWidth, info.displayHeight, cap, cap)
        val srcFps = if (info.fps > 0.5) info.fps else 30.0
        val factor = InterpPlan.effectiveFactor(settings.factor, srcFps, settings.maxOutputFps)
        val outFps = srcFps * factor
        if (factor != settings.factor) {
            log("Facteur réduit de x${settings.factor} à x$factor pour respecter ${settings.maxOutputFps.toInt()} fps max")
        }
        log("Traitement en ${targetW}x$targetH, sortie ${fmt(outFps)} fps (x$factor)")

        val codec = if (VideoEncoder.isEncoderAvailable(settings.codec.mime)) settings.codec else {
            log("Encodeur ${settings.codec.label} indisponible sur cet appareil → H.264")
            OutputCodec.H264
        }
        val bitrate = VideoEncoder.bitrateFor(targetW, targetH, outFps.toInt(), settings.bitratePreset)

        val baseName = Naming.build(settings.namePattern, displayName, factor, outFps, codec.label)
        val outTarget = createOutput(baseName)
        var success = false

        try {
            val audioFormat = if (settings.keepAudio && info.hasAudio) readAudioFormat(inputUri) else null
            if (settings.keepAudio && info.hasAudio && audioFormat == null) {
                log("Piste audio illisible, sortie sans audio")
            }

            val modelPath = ModelStore.prepare(context, settings.quality, log)
            val threads = if (settings.threads > 0) settings.threads else RifeEngine.defaultThreads()

            RifeEngine(
                modelPath = modelPath,
                intraOpThreads = threads,
                useNnapi = settings.useNnapi,
                log = log
            ).use { engine ->
                val outPfd = outTarget.fd
                val inPfd = context.contentResolver.openFileDescriptor(inputUri, "r")
                    ?: throw IllegalStateException("Impossible d'ouvrir la vidéo source")

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
                onProgress(1f, "Terminé")
                log("Terminé → ${outTarget.label} ($frames images)")
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
        val expectedFrames = if (info.durationUs > 0) {
            (info.durationSeconds * srcFps).toInt().coerceAtLeast(1)
        } else 1000

        while (true) {
            checkCancelled()
            val frame = timing.decode {
                val f = decoder.decodeNext(cur) ?: return@decode null
                FrameMath.argbToPlanar(cur, curPlanar)
                f
            } ?: break
            decoded++

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
                    "Image $decoded/$expectedFrames"
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
                "Image $decoded/$expectedFrames · $written en sortie · ${timing.rate(decoded)} img/s"
            )
        }

        if (skippedDuplicates > 0) log("Dé-duplication: $skippedDuplicates image(s) ignorée(s)")
        if (cuts > 0) log("Changements de scène: $cuts coupure(s) non interpolée(s)")

        val loopStart = firstPlanar
        if (settings.loop && decoded > 1 && loopStart != null) {
            val avgSpan = if (decoded > 1) (lastPts - firstPts) / (decoded - 1) else 33_333L
            log("Interpolation de boucle (dernière → première image)")
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

        if (decoded == 0) throw IllegalStateException("Aucune image décodée depuis cette vidéo")
        log(timing.summary(decoded, written))
        return written
    }

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
            log("Copie audio ignorée: ${t.message}")
        }
        if (copied > 0) log("Audio copié: $copied échantillons")
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
                log("Création du fichier impossible dans le dossier choisi, repli sur le dossier de l'application")
            } else {
                log("Dossier de sortie inaccessible, repli sur le dossier de l'application")
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
