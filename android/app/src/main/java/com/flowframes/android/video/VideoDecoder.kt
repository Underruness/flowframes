package com.flowframes.android.video

import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.flowframes.android.core.FrameMath
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

    data class Frame(val ptsUs: Long)

    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val bufferInfo = MediaCodec.BufferInfo()

    private var spec: YuvConverter.Spec = initialSpec
    private var inputDone = false
    private var outputDone = false
    private var started = false
    private var codedWidth = 0
    private var codedHeight = 0
    private var coded: IntArray = IntArray(0)
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
        require(idx >= 0 && format != null) { "Aucune piste vidéo trouvée" }
        trackIndex = idx
        extractor.selectTrack(idx)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()
        started = true
        codedWidth = format.getInteger(MediaFormat.KEY_WIDTH)
        codedHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
        coded = IntArray(codedWidth * codedHeight)
        log("Décodeur: $mime ${codedWidth}x$codedHeight (rotation $rotation°, couleur ${spec.label})")
    }

    /** Decodes the next frame into [out] (targetWidth x targetHeight ARGB). */
    fun decodeNext(out: IntArray): Frame? {
        if (outputDone) return null
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (!inputDone) feedInput()
            val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    codedWidth = f.getInteger(MediaFormat.KEY_WIDTH)
                    codedHeight = f.getInteger(MediaFormat.KEY_HEIGHT)
                    coded = IntArray(codedWidth * codedHeight)
                    spec = VideoInfo.colorSpecFor(f, codedHeight)
                }

                outIndex >= 0 -> {
                    val isEos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val hasFrame = bufferInfo.size > 0
                    if (hasFrame) {
                        val image = codec.getOutputImage(outIndex)
                        if (image != null) {
                            image.use { copyImageToArgb(it, out) }
                        }
                        val pts = bufferInfo.presentationTimeUs
                        codec.releaseOutputBuffer(outIndex, false)
                        if (isEos) outputDone = true
                        lastPtsUs = pts
                        return Frame(pts)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (isEos) {
                        outputDone = true
                        return null
                    }
                }

                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (outputDone) return null
                    if (inputDone && bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return null
                }
            }
        }
        log("Décodeur: limite d'itérations atteinte")
        return null
    }

    private fun feedInput() {
        val inIndex = codec.dequeueInputBuffer(10_000)
        if (inIndex < 0) return
        val buffer = codec.getInputBuffer(inIndex) ?: return
        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) {
            codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
        } else {
            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime.coerceAtLeast(0L), 0)
            extractor.advance()
        }
    }

    private fun copyImageToArgb(image: Image, out: IntArray) {
        val w = image.width
        val h = image.height
        if (w != codedWidth || h != codedHeight || coded.size < w * h) {
            codedWidth = w
            codedHeight = h
            coded = IntArray(w * h)
        }
        val planes = image.planes
        val y = planes[0]
        val u = planes[1]
        val v = planes[2]
        YuvConverter.yuv420ToArgb(
            y.buffer, u.buffer, v.buffer,
            y.rowStride, u.rowStride, u.pixelStride,
            w, h, spec, coded
        )
        val rotated = FrameMath.rotateArgb(coded, w, h, rotation)
        val scaled = FrameMath.resizeArgb(rotated.pixels, rotated.width, rotated.height, targetWidth, targetHeight)
        if (scaled.size >= targetWidth * targetHeight) {
            System.arraycopy(scaled, 0, out, 0, targetWidth * targetHeight)
        }
    }

    val lastPts: Long get() = lastPtsUs

    override fun close() {
        runCatching { if (started) codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
    }
}
