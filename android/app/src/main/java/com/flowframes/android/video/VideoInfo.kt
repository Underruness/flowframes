package com.flowframes.android.video

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import com.flowframes.android.core.YuvConverter

/** Metadata of an input video, in display orientation. */
data class VideoInfo(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val durationUs: Long,
    val fps: Double,
    val mime: String,
    val bitrate: Int,
    val hasAudio: Boolean,
    val colorSpec: YuvConverter.Spec
) {
    val colorSpecLabel: String get() = colorSpec.label

    val isPortrait: Boolean get() = rotation % 180 != 0

    /** Dimensions after applying the rotation metadata. */
    val displayWidth: Int get() = if (isPortrait) height else width
    val displayHeight: Int get() = if (isPortrait) width else height

    val durationSeconds: Double get() = durationUs / 1_000_000.0

    companion object {

        fun read(context: Context, uri: Uri): VideoInfo? {
            var extractor: MediaExtractor? = null
            var pfd: ParcelFileDescriptor? = null
            val retriever = MediaMetadataRetriever()
            try {
                // The descriptor must stay open until the extractor is released.
                pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
                extractor = MediaExtractor()
                extractor.setDataSource(pfd.fileDescriptor)

                val ex = extractor
                var videoTrack = -1
                var audioTrack = -1
                var videoFormat: MediaFormat? = null
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/") && videoTrack < 0) {
                        videoTrack = i
                        videoFormat = f
                    } else if (mime.startsWith("audio/") && audioTrack < 0) {
                        audioTrack = i
                    }
                }
                val format = videoFormat ?: return null
                val width = format.getInteger(MediaFormat.KEY_WIDTH)
                val height = format.getInteger(MediaFormat.KEY_HEIGHT)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: "video/avc"
                val fps = readFps(format)
                val bitrate = runCatching { format.getInteger(MediaFormat.KEY_BIT_RATE) }.getOrDefault(0)

                retriever.setDataSource(pfd.fileDescriptor)
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L

                val spec = colorSpecFor(format, height)

                return VideoInfo(
                    width = width,
                    height = height,
                    rotation = ((rotation % 360) + 360) % 360,
                    durationUs = durationMs * 1000L,
                    fps = fps,
                    mime = mime,
                    bitrate = bitrate,
                    hasAudio = audioTrack >= 0,
                    colorSpec = spec
                )
            } catch (t: Throwable) {
                return null
            } finally {
                runCatching { extractor?.release() }
                runCatching { retriever.release() }
                runCatching { pfd?.close() }
            }
        }

        fun readFps(format: MediaFormat): Double {
            runCatching {
                return format.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble()
            }
            runCatching {
                return format.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble()
            }
            return 30.0
        }

        /**
         * Picks the colour matrix/range used to convert between YUV and RGB.
         * Falls back to BT.709 for HD content and BT.601 for SD, which is what most
         * encoders assume when the container carries no explicit tagging.
         */
        @Suppress("DEPRECATION")
        fun colorSpecFor(format: MediaFormat?, height: Int): YuvConverter.Spec {
            var standard = -1
            var range = -1
            if (format != null) {
                runCatching { standard = format.getInteger(MediaFormat.KEY_COLOR_STANDARD) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    runCatching { range = format.getInteger(MediaFormat.KEY_COLOR_RANGE) }
                }
            }
            val full = range == MediaFormat.COLOR_RANGE_FULL
            val is601 = when (standard) {
                MediaFormat.COLOR_STANDARD_BT601_NTSC, MediaFormat.COLOR_STANDARD_BT601_PAL -> true
                MediaFormat.COLOR_STANDARD_BT709 -> false
                else -> height < 720
            }
            return when {
                is601 && full -> YuvConverter.BT601_FULL
                is601 -> YuvConverter.BT601_LIMITED
                full -> YuvConverter.BT709_FULL
                else -> YuvConverter.BT709_LIMITED
            }
        }
    }
}
