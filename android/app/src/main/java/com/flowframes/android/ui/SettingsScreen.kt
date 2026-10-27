package com.flowframes.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowframes.android.core.Naming
import com.flowframes.android.job.AiQuality
import com.flowframes.android.job.OutputCodec
import com.flowframes.android.job.Settings
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    onChange: ((Settings) -> Settings) -> Unit,
    onBack: () -> Unit,
    onPickOutputFolder: () -> Unit,
    onResetOutputFolder: () -> Unit,
    onRunBenchmark: () -> Unit,
    benchmarkRunning: Boolean,
    onCopyLogs: () -> Unit,
    onShareLogs: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Section("Interpolation") {
                Text("Multiplier", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2, 3, 4, 6, 8).forEach { f ->
                        FilterChip(
                            selected = settings.factor == f,
                            onClick = { onChange { s -> s.copy(factor = f) } },
                            label = { Text("x$f") }
                        )
                    }
                }
                Caption("Each step adds ${settings.factor - 1} interpolated frame(s) between two source frames.")

                Spacer(Modifier.height(4.dp))
                Text("Processing resolution", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(854 to "480p", 1280 to "720p", 1920 to "1080p").forEach { (v, label) ->
                        FilterChip(
                            selected = settings.maxLongSide == v,
                            onClick = { onChange { s -> s.copy(maxLongSide = v) } },
                            label = { Text(label) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2560 to "1440p", 0 to "Source").forEach { (v, label) ->
                        FilterChip(
                            selected = settings.maxLongSide == v,
                            onClick = { onChange { s -> s.copy(maxLongSide = v) } },
                            label = { Text(label) }
                        )
                    }
                }
                Caption(
                    "Higher resolutions look better but cost time and RAM roughly with the pixel count. " +
                        "Each frame costs about (width × height) / 330 000 × the per-frame time shown by the benchmark."
                )
            }

            Section("AI engine") {
                Text("Model quality", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.quality == AiQuality.FAST,
                        onClick = { onChange { s -> s.copy(quality = AiQuality.FAST) } },
                        label = { Text("Fast") }
                    )
                    FilterChip(
                        selected = settings.quality == AiQuality.HIGH,
                        onClick = { onChange { s -> s.copy(quality = AiQuality.HIGH) } },
                        label = { Text("High quality") }
                    )
                }
                Caption("\"High quality\" runs RIFE in ensemble mode: fewer artefacts, roughly twice as slow.")

                SwitchRow(
                    title = "XNNPACK provider",
                    description = "Off by default: RIFE's GridSample warping is not supported by XNNPACK, so it only adds " +
                        "layout conversions. The benchmark measured the plain CPU path ~8% faster on a Snapdragon 8 Elite.",
                    checked = settings.useXnnpack,
                    onCheckedChange = { v -> onChange { s -> s.copy(useXnnpack = v) } }
                )

                SwitchRow(
                    title = "Parallel operators",
                    description = "Runs independent branches of the network at the same time instead of one operator " +
                        "after another. Helps when the graph has parallel branches; measure it.",
                    checked = settings.parallelOps,
                    onCheckedChange = { v -> onChange { s -> s.copy(parallelOps = v) } }
                )

                SwitchRow(
                    title = "NNAPI acceleration",
                    description = "Not recommended: it was 6x slower than the CPU here (5.4 s/frame against 0.8 s), " +
                        "and most drivers fall back to the CPU without saying so. Only enable it if the benchmark wins.",
                    checked = settings.useNnapi,
                    onCheckedChange = { v -> onChange { s -> s.copy(useNnapi = v) } }
                )

                Text(
                    "CPU threads: " +
                        if (settings.threads == 0) "automatic (cores - 2, at most 6)" else "${settings.threads} (manual)",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
                Slider(
                    value = settings.threads.toFloat(),
                    onValueChange = { v -> onChange { s -> s.copy(threads = v.toInt()) } },
                    valueRange = 0f..8f,
                    steps = 7
                )
                Caption(
                    "Automatic leaves two cores for the decoder, the encoder and the pixel conversion. Using every " +
                        "core measured 25-30% slower on a real job, even though it looks slightly faster in a short test."
                )
            }

            Section("Output video") {
                Text("Codec", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutputCodec.entries.forEach { codec ->
                        FilterChip(
                            selected = settings.codec == codec,
                            onClick = { onChange { s -> s.copy(codec = codec) } },
                            label = { Text(codec.label) }
                        )
                    }
                }

                Text("Bitrate", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Small", "Balanced", "High").forEachIndexed { index, label ->
                        FilterChip(
                            selected = settings.bitratePreset == index,
                            onClick = { onChange { s -> s.copy(bitratePreset = index) } },
                            label = { Text(label) }
                        )
                    }
                }

                SwitchRow(
                    title = "Keep audio",
                    description = "The original audio track is copied as-is, without re-encoding.",
                    checked = settings.keepAudio,
                    onCheckedChange = { v -> onChange { s -> s.copy(keepAudio = v) } }
                )

                Spacer(Modifier.height(4.dp))
                Text("Filename pattern", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                OutlinedTextField(
                    value = settings.namePattern,
                    onValueChange = { v -> onChange { s -> s.copy(namePattern = v) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall
                )
                Caption("Variables: {name} {factor} {fps} {codec} {date}")
                val preview = Naming.build(
                    settings.namePattern,
                    "clip.mp4",
                    settings.factor,
                    (30.0 * settings.factor),
                    settings.codec.label
                )
                Caption("Preview: $preview")

                Spacer(Modifier.height(4.dp))
                Text("Output folder", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        settings.outputTreeUri?.let { "Custom folder" }
                            ?: "App folder (no permission required)",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = onPickOutputFolder) { Text("Choose", fontSize = 12.sp) }
                }
                if (settings.outputTreeUri != null) {
                    Row {
                        OutlinedButton(onClick = onResetOutputFolder) { Text("Reset", fontSize = 12.sp) }
                    }
                }
            }

            Section("2D animation and finishing") {
                SwitchRow(
                    title = "Frame deduplication",
                    description = "Skips identical frames. Enable for 2D animation, leave off for live-action video.",
                    checked = settings.dedupe,
                    onCheckedChange = { v -> onChange { s -> s.copy(dedupe = v) } }
                )
                if (settings.dedupe) {
                    Text(
                        "Threshold: " + String.format(Locale.US, "%.3f", settings.dedupeThreshold),
                        fontSize = 13.sp
                    )
                    Slider(
                        value = settings.dedupeThreshold.toFloat(),
                        onValueChange = { v -> onChange { s -> s.copy(dedupeThreshold = v.toDouble()) } },
                        valueRange = 0.0005f..0.02f
                    )
                }

                SwitchRow(
                    title = "Fix scene changes",
                    description = "Avoids generating a morphed transition frame in the middle of a cut.",
                    checked = settings.fixSceneChanges,
                    onCheckedChange = { v -> onChange { s -> s.copy(fixSceneChanges = v) } }
                )
                if (settings.fixSceneChanges) {
                    Text(
                        "Sensitivity: " + String.format(Locale.US, "%.2f", settings.sceneThreshold),
                        fontSize = 13.sp
                    )
                    Slider(
                        value = settings.sceneThreshold.toFloat(),
                        onValueChange = { v -> onChange { s -> s.copy(sceneThreshold = v.toDouble()) } },
                        valueRange = 0.1f..0.9f
                    )
                }

                SwitchRow(
                    title = "Perfect loop",
                    description = "Also interpolates from the last frame back to the first, for looping videos.",
                    checked = settings.loop,
                    onCheckedChange = { v -> onChange { s -> s.copy(loop = v) } }
                )

                Text(
                    "Output FPS limit: " +
                        if (settings.maxOutputFps <= 0.0) "none"
                        else String.format(Locale.US, "%.0f fps", settings.maxOutputFps),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
                Slider(
                    value = settings.maxOutputFps.toFloat(),
                    onValueChange = { v -> onChange { s -> s.copy(maxOutputFps = v.toDouble()) } },
                    valueRange = 0f..240f,
                    steps = 7
                )
            }

            Section("Diagnostics") {
                Text(
                    "The engine benchmark loads the model several ways (execution provider, thread count, " +
                        "execution mode, static shapes), adds a sustained pass to expose throttling, and reports " +
                        "the real cost per frame on this device. It takes about a minute and writes the result " +
                        "to the log, which stays copyable.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onRunBenchmark, enabled = !benchmarkRunning) {
                        Text(if (benchmarkRunning) "Benchmark running…" else "Run engine benchmark", fontSize = 12.sp)
                    }
                    if (benchmarkRunning) {
                        CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onCopyLogs) { Text("Copy logs", fontSize = 12.sp) }
                    OutlinedButton(onClick = onShareLogs) { Text("Share logs", fontSize = 12.sp) }
                }
                Caption(
                    "Logs include device model, core count, thermal status, engine configuration, per-stage " +
                        "timings, inference latency percentiles with their drift, per-minute progress and a " +
                        "diagnosis line naming the bottleneck."
                )
            }

            Section("About") {
                Text(
                    "FlowFrames Android 1.3.0 — a rewrite of the FlowFrames (Windows) pipeline for Android: " +
                        "MediaCodec decoding, RIFE 4.9 interpolation through ONNX Runtime, hardware encoding " +
                        "and MP4 muxing.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "RIFE 4.9 models (MIT) · everything runs locally, nothing is uploaded.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = Accent)
            content()
        }
    }
}

@Composable
private fun Caption(text: String) {
    Text(text, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Caption(description)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
