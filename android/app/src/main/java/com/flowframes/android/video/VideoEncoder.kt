package com.flowframes.android.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import com.flowframes.android.core.YuvConverter

/**
 * Hardware encoder + MP4 muxer.
 *
 * Video samples are fed as ARGB frames converted to YUV 4:2:0; the source audio track is copied
 * through untouched (no re-encoding).
 */
class VideoEncoder(
    outputFd: java.io.FileDescriptor,
    private val width: Int,
    private val height: Int,
    mime: String,
    bitrate: Int,
    frameRate: Int,
    private val spec: YuvConverter.Spec,
    audioFormat: MediaFormat?,
    private val log: (String) -> Unit = {}
) : AutoCloseable {

    val mimeType: String = mime
    private val muxer = MediaMuxer(outputFd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val codec: MediaCodec
    private val bufferInfo = MediaCodec.BufferInfo()
    private val scratch = YuvConverter.EncScratch()
    private val audioTrackIndex: Int
    private var videoTrackIndex = -1
    private var muxerStarted = false
    private var codecStarted = false

    val hasAudio: Boolean get() = audioTrackIndex >= 0

    init {
        require(width > 1 && height > 1 && width % 2 == 0 && height % 2 == 0) {
            "Dimensions invalides pour l'encodeur: ${width}x$height"
        }
        // The audio track has to be registered before muxer.start().
        var audio = -1
        if (audioFormat != null) {
            audio = runCatching { muxer.addTrack(audioFormat) }.getOrElse {
                log("Audio not supported by the muxer, track skipped: ${it.message}")
                -1
            }
        }
        audioTrackIndex = audio

        codec = createEncoder(mime, frameRate, bitrate)
        codecStarted = true
        log("Encoder: $mime ${width}x$height, ${bitrate / 1000} kb/s, ${spec.label}")
    }

    private fun createEncoder(mime: String, frameRate: Int, bitrate: Int): MediaCodec {
        fun baseFormat(): MediaFormat = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate.coerceAtLeast(1))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        var lastError: Throwable? = null
        for (withExtras in booleanArrayOf(true, false)) {
            val c = MediaCodec.createEncoderByType(mime)
            val format = baseFormat()
            if (withExtras) {
                runCatching {
                    format.setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                    )
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    runCatching {
                        format.setInteger(
                            MediaFormat.KEY_COLOR_STANDARD,
                            if (spec.label.startsWith("bt709")) MediaFormat.COLOR_STANDARD_BT709
                            else MediaFormat.COLOR_STANDARD_BT601_NTSC
                        )
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    runCatching {
                        format.setInteger(
                            MediaFormat.KEY_COLOR_RANGE,
                            if (spec.label.endsWith("full")) MediaFormat.COLOR_RANGE_FULL
                            else MediaFormat.COLOR_RANGE_LIMITED
                        )
                    }
                }
            }
            try {
                c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                c.start()
                return c
            } catch (t: Throwable) {
                lastError = t
                runCatching { c.release() }
            }
        }
        throw IllegalStateException("Could not start the $mime encoder: ${lastError?.message}", lastError)
    }

    private var lastPtsUs = 0L

    /** Encodes one ARGB frame. [argb] must be width*height pixels. */
    fun encode(argb: IntArray, presentationTimeUs: Long) {
        lastPtsUs = presentationTimeUs
        val inIndex = dequeueInput()
        val image = codec.getInputImage(inIndex) ?: noInputImage()
        val frameSize = image.width * image.height * 3 / 2
        image.use { fillImage(it, argb) }
        codec.queueInputBuffer(inIndex, 0, frameSize, presentationTimeUs, 0)
        drain(false)
    }

    /**
     * Encodes one interpolated frame straight from planar RGB floats (CHW, [0,1]).
     * Skips the ARGB round trip the engine output used to go through.
     */
    fun encodePlanar(planar: FloatArray, presentationTimeUs: Long) {
        lastPtsUs = presentationTimeUs
        val inIndex = dequeueInput()
        val image = codec.getInputImage(inIndex) ?: noInputImage()
        val frameSize = image.width * image.height * 3 / 2
        image.use { fillImageFromPlanar(it, planar) }
        codec.queueInputBuffer(inIndex, 0, frameSize, presentationTimeUs, 0)
        drain(false)
    }

    private fun noInputImage(): Nothing = throw IllegalStateException(
        "Cet appareil n'expose pas d'entrée YUV flexible pour l'encodeur (getInputImage = null)"
    )

    /**
     * Queues the end-of-stream and flushes all remaining encoded samples.
     *
     * Everything here is time-boxed: some encoders never report END_OF_STREAM (or report it much
     * later than the dequeue timeout), and blocking the pipeline on that would leave the user
     * staring at a frozen progress bar at the end of every job.
     */
    fun finishVideo() {
        if (!codecStarted) return
        var queued = false
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val inIndex = codec.dequeueInputBuffer(50_000)
            if (inIndex >= 0) {
                codec.queueInputBuffer(inIndex, 0, 0, lastPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                queued = true
                break
            }
            drain(false)
        }
        if (!queued) log("Encoder: end-of-stream rejected, closing directly")
        drain(true)
        runCatching { codec.stop() }
        codecStarted = false
    }

    fun writeAudioSample(buffer: java.nio.ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!muxerStarted || audioTrackIndex < 0 || info.size <= 0) return
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        runCatching { muxer.writeSampleData(audioTrackIndex, buffer, info) }
    }

    private fun dequeueInput(): Int {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx >= 0) return idx
            drain(false)
        }
        throw IllegalStateException("Encoder stalled: no input buffer available")
    }

    private fun fillImage(image: android.media.Image, argb: IntArray) {
        val planes = image.planes
        val py = planes[0]
        val pu = planes[1]
        val pv = planes[2]
        val native = com.flowframes.android.core.NativeFrames.argbToYuv420(
            argb, width, height,
            py.buffer, pu.buffer, pv.buffer,
            py.rowStride, pu.rowStride, pu.pixelStride,
            spec
        )
        if (!native) {
            YuvConverter.argbToYuv420(
                argb, width, height,
                py.buffer, pu.buffer, pv.buffer,
                py.rowStride, pu.rowStride, pu.pixelStride,
                spec
            )
        }
        padImage(image)
    }

    private fun fillImageFromPlanar(image: android.media.Image, planar: FloatArray) {
        val planes = image.planes
        val py = planes[0]
        val pu = planes[1]
        val pv = planes[2]
        YuvConverter.planarToYuv420(
            planar, width, height,
            py.buffer, pu.buffer, pv.buffer,
            py.rowStride, pu.rowStride, pu.pixelStride,
            spec, scratch
        )
        padImage(image)
    }

    /** Replicate the last row/column into any alignment padding so no garbage is encoded. */
    private fun padImage(image: android.media.Image) {
        val planes = image.planes
        val py = planes[0]
        val pu = planes[1]
        val pv = planes[2]
        replicatePadding(py.buffer, image.width, image.height, py.rowStride, py.pixelStride)
        replicateChromaPadding(pu.buffer, image.width, image.height, pu.rowStride, pu.pixelStride)
        replicateChromaPadding(pv.buffer, image.width, image.height, pv.rowStride, pv.pixelStride)
    }

    private fun replicatePadding(buffer: java.nio.ByteBuffer, imgWidth: Int, imgHeight: Int, rowStride: Int, pixelStride: Int) {
        val rowsToFix = imgHeight - height
        if (rowsToFix <= 0 && imgWidth == width) return
        val lastRowStart = (height - 1) * rowStride
        for (row in height until imgHeight) {
            val target = row * rowStride
            for (col in 0 until width * pixelStride) {
                buffer.put(target + col, buffer.get(lastRowStart + col))
            }
        }
        if (imgWidth > width && pixelStride == 1) {
            for (row in 0 until imgHeight) {
                val base = row * rowStride
                val edge = buffer.get(base + width - 1)
                for (col in width until imgWidth) buffer.put(base + col, edge)
            }
        }
    }

    private fun replicateChromaPadding(buffer: java.nio.ByteBuffer, imgWidth: Int, imgHeight: Int, rowStride: Int, pixelStride: Int) {
        val chromaHeight = (imgHeight + 1) / 2
        val chromaWidth = (imgWidth + 1) / 2
        val usedHeight = (height + 1) / 2
        val usedWidth = (width + 1) / 2
        if (chromaHeight <= usedHeight && chromaWidth <= usedWidth) return
        val lastRowStart = (usedHeight - 1) * rowStride
        for (row in usedHeight until chromaHeight) {
            val target = row * rowStride
            for (col in 0 until usedWidth * pixelStride) {
                buffer.put(target + col, buffer.get(lastRowStart + col))
            }
        }
        for (row in 0 until chromaHeight) {
            val base = row * rowStride
            val edge = buffer.get(base + (usedWidth - 1) * pixelStride)
            for (col in usedWidth until chromaWidth) buffer.put(base + col * pixelStride, edge)
        }
    }

    private fun drain(endOfStream: Boolean) {
        val deadline = System.currentTimeMillis() + if (endOfStream) 8_000 else 1_500
        while (System.currentTimeMillis() < deadline) {
            val outIndex = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) 20_000L else 0L)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        videoTrackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                }

                outIndex >= 0 -> {
                    val buffer = codec.getOutputBuffer(outIndex)
                    val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buffer != null && bufferInfo.size > 0 && !isConfig && muxerStarted) {
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(videoTrackIndex, buffer, bufferInfo)
                    }
                    val eos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(outIndex, false)
                    if (eos) return
                }

                else -> {
                    if (!endOfStream) return
                }
            }
        }
    }

    override fun close() {
        runCatching { if (codecStarted) codec.stop() }
        runCatching { codec.release() }
        codecStarted = false
        if (muxerStarted) {
            runCatching { muxer.stop() }
        }
        runCatching { muxer.release() }
    }

    companion object {
        fun isEncoderAvailable(mime: String): Boolean = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
        }.getOrDefault(false)

        /** Bitrate heuristic (bits per pixel per frame) by quality preset. */
        fun bitrateFor(width: Int, height: Int, fps: Int, quality: Int): Int {
            val bpp = when (quality) {
                0 -> 0.045
                2 -> 0.10
                else -> 0.07
            }
            val raw = width.toDouble() * height * fps.coerceAtLeast(1) * bpp
            return raw.toInt().coerceIn(1_500_000, 80_000_000)
        }
    }
}
