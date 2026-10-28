package com.flowframes.android.ui

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowframes.android.core.CrashLog
import com.flowframes.android.core.Naming
import com.flowframes.android.core.NcnnRifeEngine
import com.flowframes.android.job.AiQuality
import com.flowframes.android.job.EngineKind
import com.flowframes.android.job.NcnnModel
import com.flowframes.android.job.OutputCodec
import com.flowframes.android.job.Settings
import java.util.Locale

/**
 * Capri's settings: the same options as the historical edition, grouped into rounded cards with
 * animated switches, segmented controls and one slider for the parallel inferences.
 */
@Composable
fun CapriSettingsScreen(
    settings: Settings,
    onChange: ((Settings) -> Settings) -> Unit,
    onPickOutputFolder: () -> Unit,
    onResetOutputFolder: () -> Unit,
    onRunBenchmark: () -> Unit,
    benchmarkRunning: Boolean,
    onCopyLogs: () -> Unit,
    onShareLogs: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(4.dp))
        Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.Black)
        Text(
            "RIFE runs entirely on this phone. Nothing is uploaded, and the output video is written " +
                "next to the source unless another folder is chosen.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        CapriSection(icon = Icons.Filled.Speed, title = "Output rate") {
            val targetMode = settings.targetFps > 0.0
            Segmented(
                options = listOf(false to "Multiplier", true to "Exact fps"),
                selected = targetMode,
                onSelect = { target -> onChange { s -> s.copy(targetFps = if (target) 60.0 else 0.0) } }
            )
            if (!targetMode) {
                Segmented(
                    options = listOf(2, 3, 4, 6, 8).map { it to "x$it" },
                    selected = settings.factor,
                    onSelect = { f -> onChange { s -> s.copy(factor = f) } }
                )
                Caption("Each step adds ${settings.factor - 1} generated frame(s) between two source frames.")
            } else {
                Segmented(
                    options = listOf(30.0, 48.0, 60.0, 90.0, 120.0, 144.0).map { it to fpsLabel(it) },
                    selected = settings.targetFps,
                    onSelect = { f -> onChange { s -> s.copy(targetFps = f) } }
                )
                var fpsText by remember(targetMode) { mutableStateOf(fpsLabel(settings.targetFps)) }
                OutlinedTextField(
                    value = fpsText,
                    onValueChange = { v ->
                        fpsText = v
                        v.replace(',', '.').toDoubleOrNull()?.takeIf { it in 1.0..480.0 }?.let { fps ->
                            onChange { s -> s.copy(targetFps = fps) }
                        }
                    },
                    label = { Text("Output fps (1-480, e.g. 59.94)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Caption(
                    "The output gets exactly this rate whatever the source is: 24 to 60 fps interpolates at " +
                        "fractional positions (x2.5), and frames landing on a source frame are reused for free."
                )
            }
            Text("Processing resolution", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Segmented(
                options = listOf(854, 1280, 1920).map { it to resolutionLabel(Settings(maxLongSide = it)) },
                selected = settings.maxLongSide,
                onSelect = { v -> onChange { s -> s.copy(maxLongSide = v) } }
            )
            Segmented(
                options = listOf(2560, 3840, 0).map { it to resolutionLabel(Settings(maxLongSide = it)) },
                selected = settings.maxLongSide,
                onSelect = { v -> onChange { s -> s.copy(maxLongSide = v) } }
            )
            Caption(
                "Higher resolutions look better and cost time and RAM roughly with the pixel count " +
                    "(1440p = 1.8x, 4K = 4x the 1080p time)."
            )
        }

        CapriSection(icon = Icons.Filled.Bolt, title = "Engine") {
            Text("ncnn model", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Segmented(
                options = NcnnModel.values().map { it to it.label.substringBefore(" (") },
                selected = settings.ncnnModel,
                onSelect = { m -> onChange { s -> s.copy(ncnnModel = m) } }
            )
            Caption("RIFE 4.6 is about 20% faster on the GPU; 4.9 gives slightly different (often cleaner) results.")

            Text(
                "Parallel inferences: ${settings.workers.coerceIn(Settings.MIN_WORKERS, Settings.MAX_WORKERS)} at a time",
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )
            Slider(
                value = settings.workers.coerceIn(Settings.MIN_WORKERS, Settings.MAX_WORKERS).toFloat(),
                onValueChange = { v -> onChange { s -> s.copy(workers = v.toInt()) } },
                valueRange = Settings.MIN_WORKERS.toFloat()..Settings.MAX_WORKERS.toFloat(),
                steps = Settings.MAX_WORKERS - Settings.MIN_WORKERS - 1
            )
            Caption(
                "2 by default: one inference computes while the other copies its frames in and out, which " +
                    "keeps the GPU busy. Each one holds its own copy of the model, so more costs RAM."
            )

            CapriSwitch(
                title = "fp16 arithmetic",
                description = "On by default in Capri: half-precision math in the network, about 50 dB from the " +
                    "fp32 result (invisible). Turn it off if a job hangs — that is the safe fallback.",
                checked = settings.fp16,
                onCheckedChange = { v -> onChange { s -> s.copy(fp16 = v) } }
            )
            CapriSwitch(
                title = "Cooperative matrix",
                description = "On by default in Capri; off is the safe fallback. ncnn's tensor-core kernels for " +
                    "fp16 math (Adreno 8xx), used only together with fp16.",
                checked = settings.coopMatrix,
                onCheckedChange = { v -> onChange { s -> s.copy(coopMatrix = v) } }
            )
            Caption(
                "The GPU engine is built when the app starts: the first launch compiles the shaders in the " +
                    "background and every interpolation afterwards starts immediately."
            )

            if (settings.advanced) {
                Text("Runtime", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Segmented(
                    options = listOf(
                        EngineKind.NCNN_GPU to "GPU",
                        EngineKind.NCNN_CPU to "CPU (ncnn)",
                        EngineKind.ORT_CPU to "CPU (ONNX)"
                    ),
                    selected = settings.engine,
                    onSelect = { k -> onChange { s -> s.copy(engine = k) } }
                )
                Capsule(
                    options = listOf(AiQuality.FAST to "Fast", AiQuality.HIGH to "High quality"),
                    selected = settings.quality,
                    onSelect = { q -> onChange { s -> s.copy(quality = q) } }
                )
                Caption(
                    "The GPU path is self-tested at the job's resolution and falls back to the CPU if the driver " +
                        "fails it. High quality runs the ensemble model on ONNX Runtime (CPU, about twice slower)."
                )
                CapriSwitch(
                    title = "CPU assist (hybrid GPU + CPU)",
                    description = "CPU workers take interpolations from the same queue as the GPU. Useful when the " +
                        "CPU is idle, can lose when the phone throttles.",
                    checked = settings.cpuAssist,
                    onCheckedChange = { v -> onChange { s -> s.copy(cpuAssist = v) } }
                )
            }
        }

        CapriSection(icon = Icons.Filled.Folder, title = "Output video") {
            Text("Codec", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Segmented(
                options = OutputCodec.values().map { it to it.label },
                selected = settings.codec,
                onSelect = { c -> onChange { s -> s.copy(codec = c) } }
            )
            Text("Bitrate", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Segmented(
                options = listOf(0 to "Small", 1 to "Balanced", 2 to "High"),
                selected = settings.bitratePreset,
                onSelect = { v -> onChange { s -> s.copy(bitratePreset = v) } }
            )
            CapriSwitch(
                title = "Keep audio",
                description = "The original audio track is copied as-is, without re-encoding.",
                checked = settings.keepAudio,
                onCheckedChange = { v -> onChange { s -> s.copy(keepAudio = v) } }
            )
            Text("Filename pattern", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            OutlinedTextField(
                value = settings.namePattern,
                onValueChange = { v -> onChange { s -> s.copy(namePattern = v) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall
            )
            Caption("Variables: {name} {factor} {fps} {codec} {date}")
            Caption(
                "Preview: " + Naming.build(
                    settings.namePattern,
                    "clip.mp4",
                    settings.factor,
                    (30.0 * settings.factor),
                    settings.codec.label
                )
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (settings.outputTreeUri != null) {
                        "Custom folder"
                    } else {
                        "Downloads/capri (created automatically)"
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = onPickOutputFolder) { Text("Choose", fontSize = 12.sp) }
                if (settings.outputTreeUri != null) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = onResetOutputFolder) { Text("Reset", fontSize = 12.sp) }
                }
            }
        }

        if (settings.advanced) {
            CapriSection(icon = Icons.Filled.Palette, title = "2D animation and finishing") {
                CapriSwitch(
                    title = "Frame deduplication",
                    description = "Skips identical frames. Enable for 2D animation, leave off for live action.",
                    checked = settings.dedupe,
                    onCheckedChange = { v -> onChange { s -> s.copy(dedupe = v) } }
                )
                if (settings.dedupe) {
                    SliderLabel("Threshold", String.format(Locale.US, "%.3f", settings.dedupeThreshold))
                    Slider(
                        value = settings.dedupeThreshold.toFloat(),
                        onValueChange = { v -> onChange { s -> s.copy(dedupeThreshold = v.toDouble()) } },
                        valueRange = 0.0005f..0.02f
                    )
                }
                CapriSwitch(
                    title = "Fix scene changes",
                    description = "Avoids generating a morphed frame in the middle of a cut.",
                    checked = settings.fixSceneChanges,
                    onCheckedChange = { v -> onChange { s -> s.copy(fixSceneChanges = v) } }
                )
                if (settings.fixSceneChanges) {
                    SliderLabel("Sensitivity", String.format(Locale.US, "%.2f", settings.sceneThreshold))
                    Slider(
                        value = settings.sceneThreshold.toFloat(),
                        onValueChange = { v -> onChange { s -> s.copy(sceneThreshold = v.toDouble()) } },
                        valueRange = 0.1f..0.9f
                    )
                }
                CapriSwitch(
                    title = "Perfect loop",
                    description = "Also interpolates from the last frame back to the first, for looping videos.",
                    checked = settings.loop,
                    onCheckedChange = { v -> onChange { s -> s.copy(loop = v) } }
                )
                SliderLabel(
                    "Output FPS limit",
                    if (settings.maxOutputFps <= 0.0) {
                        "none"
                    } else {
                        String.format(Locale.US, "%.0f fps", settings.maxOutputFps)
                    }
                )
                Slider(
                    value = settings.maxOutputFps.toFloat(),
                    onValueChange = { v -> onChange { s -> s.copy(maxOutputFps = v.toDouble()) } },
                    valueRange = 0f..240f,
                    steps = 7
                )
                CapriSwitch(
                    title = "XNNPACK provider",
                    description = "Off by default: RIFE's warping is not covered by XNNPACK, which then only adds " +
                        "layout conversions (the plain CPU path measured 8% faster).",
                    checked = settings.useXnnpack,
                    onCheckedChange = { v -> onChange { s -> s.copy(useXnnpack = v) } }
                )
                CapriSwitch(
                    title = "Parallel operators",
                    description = "Runs independent branches of the graph at the same time instead of one operator " +
                        "after another. Measure it: it helps only when the graph has parallel branches.",
                    checked = settings.parallelOps,
                    onCheckedChange = { v -> onChange { s -> s.copy(parallelOps = v) } }
                )
                CapriSwitch(
                    title = "NNAPI acceleration",
                    description = "Not recommended: 6x slower than the CPU here (5.4 s/frame against 0.8 s), and most " +
                        "drivers fall back to the CPU silently.",
                    checked = settings.useNnapi,
                    onCheckedChange = { v -> onChange { s -> s.copy(useNnapi = v) } }
                )
                SliderLabel(
                    "CPU threads",
                    if (settings.threads == 0) "automatic (cores - 2, at most 6)" else "${settings.threads} (manual)"
                )
                Slider(
                    value = settings.threads.toFloat(),
                    onValueChange = { v -> onChange { s -> s.copy(threads = v.toInt()) } },
                    valueRange = 0f..8f,
                    steps = 7
                )
                Caption(
                    "Automatic leaves two cores for the decoder, the encoder and the pixel conversion: using every " +
                        "core measured 25-30% slower on a real job."
                )
            }
        }

        CapriSection(icon = Icons.Filled.BugReport, title = "Diagnostics and display") {
            CapriSwitch(
                title = "Advanced view",
                description = "Shows the CPU and ONNX Runtime options, the detailed RAM split and every temperature.",
                checked = settings.advanced,
                onCheckedChange = { v -> onChange { s -> s.copy(advanced = v) } }
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onRunBenchmark, enabled = !benchmarkRunning) {
                    Text(if (benchmarkRunning) "Benchmark running..." else "Run engine benchmark", fontSize = 12.sp)
                }
                if (benchmarkRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
            Caption(
                "The benchmark loads the model several ways (parallel sessions, thread count, execution provider, " +
                    "fp16), adds a sustained pass to expose throttling, and reports the real cost per frame. About " +
                    "two minutes; the report goes to the log and can be applied as the new settings."
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onCopyLogs) { Text("Copy logs", fontSize = 12.sp) }
                OutlinedButton(onClick = onShareLogs) { Text("Share logs", fontSize = 12.sp) }
            }
            val context = LocalContext.current
            OutlinedButton(onClick = {
                Thread {
                    NcnnRifeEngine.clearShaderCaches()
                    CrashLog.resetGpuSafety(context)
                    NcnnRifeEngine.setPipelineDiskCache(true)
                }.start()
                Toast.makeText(context, "GPU shader cache cleared: the next GPU run rebuilds it", Toast.LENGTH_SHORT)
                    .show()
            }) { Text("Clear GPU shader cache", fontSize = 12.sp) }
            Spacer(Modifier.height(2.dp))
            Text(
                "Capri 1.8.0 - RIFE 4.9 / 4.6 models (MIT) - ncnn (BSD-3) - stable-diffusion.cpp (MIT) - MediaCodec for decoding and encoding.",
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CapriSection(icon: ImageVector, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        content()
    }
}

/** Pill-shaped segmented control with an animated selection. */
@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            val background by animateColorAsState(
                targetValue = if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                animationSpec = tween(220),
                label = "segment"
            )
            val foreground by animateColorAsState(
                targetValue = if (active) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = tween(220),
                label = "segment-text"
            )
            Text(
                label,
                color = foreground,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(background)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 13.dp, vertical = 8.dp)
            )
        }
    }
}

/** Same control for a wider choice: it wraps onto several lines of chips. */
@Composable
private fun <T> Capsule(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (value, label) ->
                    val active = value == selected
                    val background by animateColorAsState(
                        targetValue = if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                        animationSpec = tween(220),
                        label = "chip"
                    )
                    Text(
                        label,
                        color = if (active) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(background)
                            .clickable { onSelect(value) }
                            .padding(horizontal = 13.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CapriSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Caption(description)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SliderLabel(title: String, value: String) {
    Text("$title: $value", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
}

@Composable
private fun Caption(text: String) {
    Text(text, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
