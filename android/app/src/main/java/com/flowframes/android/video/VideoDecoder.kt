package com.flowframes.android.video

import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.flowframes.android.core.YuvConverter

/**
 * Hardware video decoder: MediaExtractor + MediaCodec (ByteBuffer output) producing ARGB frames
 * already rotated and scaled to the target size.
 */
class VideoDecoder(
    private val fd: java.io.FileDescriptor,
    private val targetWidth: Int,
    private val targetHeight: Int,
    private val rotation: Int,
    initialSpec: YuvConverter.Spec,
    private val log: (String) -> Unit = {}
) : AutoCloseable {

    /**
     * What one [decodeNext] call produced.
     *
     * The end of a file and a decoder that stopped answering are reported separately on purpose: a
     * caller that treats a stall as the end of the video writes a truncated file without any error.
     */
    sealed class Result {
        /** A frame was written into the caller's buffer. */
        data class Frame(val ptsUs: Long) : Result()

        /** No more frames: the input ran out and the codec stopped asking for work. */
        object EndOfStream : Result()

        /** The decoder produced nothing for [STALL_TIMEOUT_MS] while input was still pending. */
        object Stalled : Result()
    }

    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val bufferInfo = MediaCodec.BufferInfo()

    private var spec: YuvConverter.Spec = initialSpec
    private val scratch = YuvConverter.PlaneScratch()
    private var inputDone = false
    private var outputDone = false
    private var started = false
    private var codedWidth = 0
    private var codedHeight = 0
    private var lastPtsUs = -1L

    private val trackIndex: Int

    init {
        extractor.setDataSource(fd)
        var idx = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                idx = i
                format = f
                break
            }
        }
        require(idx >= 0 && format != null) { "No video track found" }
        trackIndex = idx
        extractor.selectTrack(idx)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()
        started = true
        codedWidth = format.getInteger(MediaFormat.KEY_WIDTH)
        codedHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
        log("Decoder: $mime ${codedWidth}x$codedHeight (rotation $rotation°, colour ${spec.label})")
    }

    /** Decodes the next frame into [out] (targetWidth x targetHeight ARGB). */
    fun decodeNext(out: IntArray): Result {
        if (outputDone) return Result.EndOfStream
        // There is no fixed per-frame deadline any more: a slow or throttled device must make the
        // job take longer, never make it end the video early. The timer below only fires when the
        // codec stops making progress at all — feeding input, dequeuing output or a format change.
        var lastProgressAt = System.currentTimeMillis()
        while (true) {
            var progress = false
            if (!inputDone && feedInput()) progress = true

            val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    codedWidth = f.getInteger(MediaFormat.KEY_WIDTH)
                    codedHeight = f.getInteger(MediaFormat.KEY_HEIGHT)
                    spec = VideoInfo.colorSpecFor(f, codedHeight)
                    progress = true
                }

                outIndex >= 0 -> {
                    progress = true
                    val isEos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val hasFrame = bufferInfo.size > 0
                    val pts = bufferInfo.presentationTimeUs
                    if (hasFrame) {
                        codec.getOutputImage(outIndex)?.use { copyImageToArgb(it, out) }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (isEos) outputDone = true
                    if (hasFrame) {
                        lastPtsUs = pts
                        return Result.Frame(pts)
                    }
                    if (outputDone) return Result.EndOfStream
                }
            }

            val now = System.currentTimeMillis()
            if (progress) lastProgressAt = now
            val idleMs = now - lastProgressAt
            if (idleMs < STALL_TIMEOUT_MS) continue
            if (inputDone) {
                // Every sample has been queued and the codec is quiet: plenty of decoders never
                // flag the end of the stream, so this is the end of the file, and it is logged.
                log("Decoder: no output for ${idleMs / 1000} s after the end of the input, treating it as the end of the stream")
                outputDone = true
                return Result.EndOfStream
            }
            log("Decoder: no output for ${idleMs / 1000} s while input was still pending")
            return Result.Stalled
        }
    }

    /** Queues input until the codec input buffer is full. Returns true when it queued something. */
    private fun feedInput(): Boolean {
        val inIndex = codec.dequeueInputBuffer(10_000)
        if (inIndex < 0) return false
        val buffer = codec.getInputBuffer(inIndex) ?: return false
        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) {
            codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
        } else {
            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime.coerceAtLeast(0L), 0)
            extractor.advance()
        }
        return true
    }

    /**
     * One pass: YUV planes -> rotated + scaled ARGB, written straight into [out].
     * The previous code converted at full source size, rotated into a fresh array and then resized
     * into another one, which cost ~70 ms per 1080p frame.
     */
    private fun copyImageToArgb(image: Image, out: IntArray) {
        val planes = image.planes
        val y = planes[0]
        val u = planes[1]
        val v = planes[2]
        YuvConverter.yuv420ToArgbScaled(
            y.buffer, u.buffer, v.buffer,
            y.rowStride, u.rowStride, u.pixelStride,
            image.width, image.height,
            targetWidth, targetHeight,
            rotation, spec, out, scratch
        )
    }

    val lastPts: Long get() = lastPtsUs

    override fun close() {
        runCatching { if (started) codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
    }

    private companion object {
        /** How long the decoder may produce nothing before it is considered stuck or finished. */
        const val STALL_TIMEOUT_MS = 60_000L
    }
}
