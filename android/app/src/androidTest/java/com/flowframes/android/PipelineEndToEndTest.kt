package com.flowframes.android

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flowframes.android.core.YuvConverter
import com.flowframes.android.job.AiQuality
import com.flowframes.android.job.Interpolator
import com.flowframes.android.job.OutputCodec
import com.flowframes.android.job.Settings
import com.flowframes.android.video.VideoDecoder
import com.flowframes.android.video.VideoEncoder
import com.flowframes.android.video.VideoInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device validation of the full pipeline: synthetic clip → MediaCodec decode → RIFE on
 * ONNX Runtime → hardware encode → MP4 mux.
 *
 * Uses a tiny clip so it stays fast enough for software-emulated devices.
 */
@RunWith(AndroidJUnit4::class)
class PipelineEndToEndTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val log = mutableListOf<String>()
    private val logSink: (String) -> Unit = { line ->
        synchronized(log) { log.add(line) }
        println("PIPELINE: $line")
    }

    private fun makeTestClip(file: File, width: Int, height: Int, frames: Int): File {
        val pfd = ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_READ_WRITE or
                ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE
        )
        val encoder = VideoEncoder(
            outputFd = pfd.fileDescriptor,
            width = width,
            height = height,
            mime = "video/avc",
            bitrate = 1_000_000,
            frameRate = 30,
            spec = YuvConverter.BT709_LIMITED,
            audioFormat = null,
            log = logSink
        )
        val argb = IntArray(width * height)
        try {
            for (i in 0 until frames) {
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val checker = (((x / 8) + (y / 8)) % 2) * 40 + 90
                        argb[y * width + x] = (0xFF shl 24) or (checker shl 16) or (checker shl 8) or checker
                    }
                }
                val boxX = (i * (width - 24) / frames).coerceIn(0, width - 24)
                for (y in height / 3 until height / 3 + 20) {
                    for (x in boxX until boxX + 24) {
                        if (y < height && x < width) {
                            argb[y * width + x] = (0xFF shl 24) or (230 shl 16) or (40 shl 8) or 40
                        }
                    }
                }
                encoder.encode(argb, i * 33_333L)
                println("PIPELINE: clip frame $i written")
            }
            val finish = encoder.finishVideo()
            println("PIPELINE: clip finished with $finish")
        } finally {
            encoder.close()
            pfd.close()
        }
        return file
    }

    @Test
    fun fullPipelineProducesValidInterpolatedVideo() {
        val startedAt = System.currentTimeMillis()
        val dir = File(context.cacheDir, "e2e").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        // Defaults are cheap on real hardware; software-emulated devices can pass
        // -e width 32 -e height 32 to shrink the workload further.
        val args = InstrumentationRegistry.getArguments()
        val width = args.getString("width")?.toIntOrNull() ?: 96
        val height = args.getString("height")?.toIntOrNull() ?: 64
        val sourceFrames = args.getString("frames")?.toIntOrNull() ?: 4

        val input = makeTestClip(File(dir, "input.mp4"), width, height, sourceFrames)
        println("PIPELINE: clip ready in ${System.currentTimeMillis() - startedAt} ms (${input.length()} bytes)")
        assertTrue("test clip was not produced", input.length() > 2000)

        val inputInfo = VideoInfo.read(context, Uri.fromFile(input))
        assertNotNull("input metadata unreadable", inputInfo)
        assertEquals(width, inputInfo!!.displayWidth)
        assertEquals(height, inputInfo.displayHeight)

        val settings = Settings(
            factor = 2,
            quality = AiQuality.FAST,
            maxLongSide = width,
            keepAudio = false,
            fixSceneChanges = true,
            codec = OutputCodec.H264,
            bitratePreset = 0,
            outputTreeUri = null,
            threads = 2
        )

        var lastProgress = 0f
        val interpolator = Interpolator(
            context = context,
            settings = settings,
            log = logSink,
            onProgress = { p, detail ->
                lastProgress = p
                println("PIPELINE: progress ${(p * 100).toInt()}% — $detail")
            },
            isCancelled = { false }
        )
        val result = interpolator.process(Uri.fromFile(input), "input.mp4")
        println("PIPELINE: pipeline finished in ${System.currentTimeMillis() - startedAt} ms")

        assertNotNull("no output path", result.path)
        val output = File(result.path!!)
        assertTrue("empty output (${output.length()} bytes)", output.length() > 2000)
        assertEquals(1f, lastProgress, 0.001f)

        val outInfo = VideoInfo.read(context, Uri.fromFile(output))
        assertNotNull("output unreadable", outInfo)
        assertEquals(width, outInfo!!.displayWidth)
        assertEquals(height, outInfo.displayHeight)

        // n source frames at 30 fps → 2n-1 frames at 60 fps over the same duration.
        val expected = sourceFrames * 2 - 1
        assertTrue(
            "frames written: ${result.frames} (expected $expected)",
            result.frames in (expected - 1)..(expected + 1)
        )
        assertEquals((sourceFrames - 1) / 30.0, outInfo.durationSeconds, 0.2)
        assertEquals(60.0, outInfo.fps, 6.0)
        // A short output is the symptom of a codec that gave up early, and the pipeline now logs it.
        assertTrue(
            "pipeline reported a truncated output:\n${log.joinToString("\n")}",
            log.none { it.contains("Output covers") }
        )

        println("PIPELINE OK: ${result.label} — ${result.frames} frames, ${output.length()} bytes")
    }

    /**
     * The decoder used to report "no output for 15 s" as the end of the stream, which wrote a
     * truncated file with no error. This pins both halves of the fix: a normal clip must end with
     * [VideoDecoder.Result.EndOfStream], and every source frame must come out of it.
     */
    @Test
    fun decoderReachesTheEndOfStreamWithoutLosingFrames() {
        val dir = File(context.cacheDir, "e2e").apply { mkdirs() }
        val args = InstrumentationRegistry.getArguments()
        val width = args.getString("width")?.toIntOrNull() ?: 96
        val height = args.getString("height")?.toIntOrNull() ?: 64
        val sourceFrames = args.getString("frames")?.toIntOrNull() ?: 4

        val input = makeTestClip(File(dir, "decode.mp4"), width, height, sourceFrames)

        ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            VideoDecoder(
                fd = pfd.fileDescriptor,
                targetWidth = width,
                targetHeight = height,
                rotation = 0,
                initialSpec = YuvConverter.BT709_LIMITED,
                log = logSink
            ).use { decoder ->
                val argb = IntArray(width * height)
                var decoded = 0
                var last: VideoDecoder.Result = VideoDecoder.Result.Stalled
                while (true) {
                    last = decoder.decodeNext(argb)
                    if (last is VideoDecoder.Result.Frame) {
                        decoded++
                        continue
                    }
                    break
                }
                println("PIPELINE: decoder read $decoded frame(s), ended with $last")
                assertTrue(
                    "the decoder stalled instead of reaching the end of the stream: $last",
                    last is VideoDecoder.Result.EndOfStream
                )
                assertTrue("decoded $decoded frame(s) out of $sourceFrames", decoded >= sourceFrames - 1)
            }
        }
    }
}
