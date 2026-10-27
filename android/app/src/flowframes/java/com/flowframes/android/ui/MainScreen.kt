package com.flowframes.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowframes.android.job.JobItem
import com.flowframes.android.job.JobStatus
import com.flowframes.android.job.Settings
import java.util.Locale
import com.flowframes.android.job.SystemMonitor
import com.flowframes.android.job.formatClock
import com.flowframes.android.job.estimateRemainingMs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    jobs: List<JobItem>,
    running: Boolean,
    logs: List<String>,
    settings: Settings,
    onAdd: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit,
    onClearFinished: () -> Unit,
    onCopyLogs: () -> Unit,
    onShareLogs: () -> Unit,
    onRemove: (String) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("FlowFrames", fontWeight = FontWeight.Bold)
                        Text(
                            "${settings.ncnnModel.label.substringBefore(" (")} · on-device interpolation",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onClearFinished) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear finished jobs")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onAdd,
                        modifier = Modifier.weight(1f),
                        enabled = !running
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add videos")
                    }
                    Button(
                        onClick = if (running) onCancel else onStart,
                        modifier = Modifier.weight(1f),
                        enabled = running || jobs.any { it.status == JobStatus.QUEUED }
                    ) {
                        Icon(
                            if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (running) "Cancel" else "Start")
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier
            .padding(padding)
            .fillMaxSize()) {
            SettingsSummary(settings)
            SystemPanel(running, settings.advanced)
            if (jobs.isEmpty()) {
                EmptyState(onAdd = onAdd)
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(jobs, key = { it.id }) { job ->
                        JobCard(job = job, onRemove = { onRemove(job.id) }, canRemove = !running)
                    }
                }
            }
            LogPanel(logs, onCopyLogs = onCopyLogs, onShareLogs = onShareLogs)
        }
    }
}

@Composable
private fun SettingsSummary(settings: Settings) {
    val res = when (settings.maxLongSide) {
        0 -> "source resolution"
        854 -> "480p"
        1280 -> "720p"
        1920 -> "1080p"
        2560 -> "1440p"
        3840 -> "4K"
        else -> "${settings.maxLongSide} px"
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (settings.targetFps > 0.0) String.format(Locale.US, "%.3f", settings.targetFps).trimEnd('0').trimEnd('.') + " fps"
                else "x${settings.factor}",
                color = Accent,
                fontWeight = FontWeight.Bold
            )
            Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(res, fontSize = 13.sp)
            Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (settings.quality == com.flowframes.android.job.AiQuality.HIGH) "high quality (CPU)"
                else settings.ncnnModel.label.substringBefore(" ("),
                fontSize = 13.sp
            )
            Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(settings.codec.label, fontSize = 13.sp)
        }
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.Movie,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = Accent
        )
        Spacer(Modifier.height(16.dp))
        Text("No video in the queue", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Add one or more videos, pick an interpolation multiplier and start the job. " +
                "RIFE generates the in-between frames on the device: no computer, no internet.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onAdd) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Choose videos")
        }
    }
}

@Composable
private fun JobCard(job: JobItem, onRemove: () -> Unit, canRemove: Boolean) {
    val statusColor = when (job.status) {
        JobStatus.QUEUED -> MaterialTheme.colorScheme.onSurfaceVariant
        JobStatus.RUNNING -> Accent
        JobStatus.DONE -> Mint
        JobStatus.FAILED -> Danger
        JobStatus.CANCELLED -> Warn
    }
    val statusLabel = when (job.status) {
        JobStatus.QUEUED -> "queued"
        JobStatus.RUNNING -> "running"
        JobStatus.DONE -> "done"
        JobStatus.FAILED -> "failed"
        JobStatus.CANCELLED -> "cancelled"
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        job.displayName,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val meta = buildString {
                        job.info?.let { info ->
                            append("${info.displayWidth}x${info.displayHeight}")
                            append(" · ")
                            append(String.format(Locale.US, "%.2f", info.fps).trimEnd('0').trimEnd('.'))
                            append(" fps · ")
                            append(formatDuration(info.durationSeconds))
                        } ?: append("metadata unavailable")
                    }
                    Text(meta, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(statusLabel, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                if (canRemove && job.status != JobStatus.RUNNING) {
                    IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Remove",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (job.status == JobStatus.RUNNING || job.status == JobStatus.DONE) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = job.progress.coerceIn(0f, 1f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = statusColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
            JobTimer(job)
            if (job.detail.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(job.detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            job.error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 12.sp, color = Danger)
            }
            if (job.status == JobStatus.DONE && job.outputName != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "→ ${job.outputName}",
                    fontSize = 12.sp,
                    color = Mint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun LogPanel(logs: List<String>, onCopyLogs: () -> Unit, onShareLogs: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size, expanded) {
        if (expanded && logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Divider(color = MaterialTheme.colorScheme.outline)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Log", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onCopyLogs, enabled = logs.isNotEmpty()) {
                    Text("Copy", fontSize = 12.sp)
                }
                TextButton(onClick = onShareLogs, enabled = logs.isNotEmpty()) {
                    Text("Share", fontSize = 12.sp)
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide" else "Show", fontSize = 12.sp)
                }
            }
            if (expanded) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 170.dp)
                        .background(Color(0xFF0A0912))
                ) {
                    if (logs.isEmpty()) {
                        Text(
                            "No events yet",
                            fontSize = 11.sp,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(state = listState, contentPadding = PaddingValues(12.dp)) {
                            items(logs) { line ->
                                Text(
                                    line,
                                    fontSize = 10.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFFB9D9C8)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatDuration(seconds: Double): String {
    if (seconds <= 0) return "0 s"
    val total = seconds.toInt()
    val m = total / 60
    val s = total % 60
    return if (m > 0) "${m}m ${s}s" else "${s}s"
}

/** Elapsed time and ETA while running, total time once finished. */
@Composable
private fun JobTimer(job: JobItem) {
    if (job.startedAtMs <= 0L) return
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var etaFrom by remember(job.id, job.startedAtMs) { mutableStateOf<Pair<Long, Float>?>(null) }
    if (job.status == JobStatus.RUNNING) {
        LaunchedEffect(job.id, job.startedAtMs) {
            while (true) {
                now = System.currentTimeMillis()
                kotlinx.coroutines.delay(1000)
            }
        }
        if (etaFrom == null && job.progress > 0f) etaFrom = System.currentTimeMillis() to job.progress
    }
    val text = when {
        job.status == JobStatus.RUNNING -> {
            val eta = etaFrom?.let { estimateRemainingMs(now, it.first, it.second, job.progress) }
            "Elapsed " + formatClock(now - job.startedAtMs) +
                (if (eta != null) " · ETA " + formatClock(eta) + " · ends ~" +
                    java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(now + eta))
                else " · ETA computing…")
        }
        job.finishedAtMs > 0L -> "Total time " + formatClock(job.finishedAtMs - job.startedAtMs)
        else -> return
    }
    Spacer(Modifier.height(6.dp))
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Accent)
}

/** RAM and temperatures, refreshed every 2 s (every 5 s when idle). */
@Composable
private fun SystemPanel(running: Boolean, advanced: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var snap by remember { mutableStateOf<SystemMonitor.Snapshot?>(null) }
    LaunchedEffect(running) {
        while (true) {
            snap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { SystemMonitor.sample(context) }.getOrNull()
            }
            kotlinx.coroutines.delay(if (running) 2000 else 5000)
        }
    }
    val s = snap ?: return
    fun t(v: Double?) = if (v == null) "n/a" else String.format(Locale.US, "%.0f°", v)
    fun tempColor(v: Double?) = when {
        v == null -> null
        v >= 75 -> Danger
        v >= 60 -> Warn
        else -> null
    }
    fun battColor(v: Double?) = when {
        v == null -> null
        v >= 48 -> Danger
        v >= 42 -> Warn
        else -> null
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("RAM", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Accent)
            val phone = String.format(Locale.US, "%.1f/%.1f GB", (s.sysTotalMb - s.sysAvailMb) / 1024.0, s.sysTotalMb / 1024.0)
            Text(
                (if (advanced) "app ${s.appPssMb} MB (gpu ${s.graphicsMb}) · phone $phone" else phone) +
                    if (s.lowMemory) " · LOW" else "",
                fontSize = 11.sp,
                color = if (s.lowMemory) Danger else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("TEMP", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Accent)
            val temps = if (advanced) listOf("CPU" to s.cpuC, "GPU" to s.gpuC, "NPU" to s.npuC, "Batt" to s.batteryC)
            else listOf("Battery" to s.batteryC)
            temps.forEach { (label, v) ->
                Text(
                    "$label ${t(v)}",
                    fontSize = 11.sp,
                    color = (if (label.startsWith("Batt")) battColor(v) else tempColor(v))
                        ?: MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (advanced) s.headroom?.let {
                Text(
                    String.format(Locale.US, "headroom %.0f%%", it * 100),
                    fontSize = 11.sp,
                    color = if (it >= 0.9f) Warn else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
