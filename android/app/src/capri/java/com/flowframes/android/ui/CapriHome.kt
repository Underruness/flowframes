package com.flowframes.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowframes.android.job.AiQuality
import com.flowframes.android.job.EngineWarmup
import com.flowframes.android.job.JobItem
import com.flowframes.android.job.JobStatus
import com.flowframes.android.job.Settings
import com.flowframes.android.job.SystemMonitor
import com.flowframes.android.job.estimateRemainingMs
import com.flowframes.android.job.formatClock
import java.util.Locale

/**
 * Capri's studio: the queue, the engine state, the monitor and the log, with a header that moves.
 *
 * Same job, colours and data as the historical edition; everything else (dynamic Material You
 * palette, animated gradient, progress rings, animated list and log panel) is Capri's.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CapriStudio(
    jobs: List<JobItem>,
    running: Boolean,
    logs: List<String>,
    settings: Settings,
    warmup: EngineWarmup.Status,
    onAdd: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onClearFinished: () -> Unit,
    onCopyLogs: () -> Unit,
    onShareLogs: () -> Unit,
    onRemove: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        CapriHero(settings = settings, warmup = warmup, onClearFinished = onClearFinished)
        SystemStrip(running = running)
        Box(modifier = Modifier
            .weight(1f)
            .fillMaxWidth()) {
            if (jobs.isEmpty()) {
                CapriEmptyState(onAdd)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(jobs, key = { it.id }) { job ->
                        JobCard(
                            job = job,
                            canRemove = !running && job.status != JobStatus.RUNNING,
                            onRemove = { onRemove(job.id) },
                            modifier = Modifier.animateItemPlacement()
                        )
                    }
                }
            }
        }
        ActionBar(
            running = running,
            canStart = jobs.any { it.status == JobStatus.QUEUED },
            onAdd = onAdd,
            onStart = onStart,
            onCancel = onCancel
        )
        LogPanel(logs = logs, onCopy = onCopyLogs, onShare = onShareLogs)
    }
}

/**
 * A thin animated gradient band: the mark, the wordmark, one line of settings and the GPU state.
 *
 * It used to be a 180 dp block with a row of pills; the queue needs that height far more, so the
 * header is now ~80 dp: two small lines instead of a banner.
 */
@Composable
private fun CapriHero(settings: Settings, warmup: EngineWarmup.Status, onClearFinished: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "capri-hero")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Reverse),
        label = "phase"
    )
    val gradient = Brush.linearGradient(
        colors = listOf(
            lerp(CapriYellow, CapriCyan, phase),
            lerp(CapriMagenta, CapriYellow, phase),
            lerp(CapriCyan, CapriMagenta, phase)
        ),
        start = Offset.Zero,
        end = Offset(900f, 120f)
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp))
            .background(gradient)
            .padding(start = 16.dp, end = 6.dp, top = 9.dp, bottom = 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CapriLogo(modifier = Modifier.size(25.dp))
            Spacer(Modifier.width(9.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "CAPRI",
                    color = CapriInk,
                    fontWeight = FontWeight.Black,
                    fontSize = 17.sp,
                    letterSpacing = 3.sp
                )
                Text(
                    "${settings.ncnnModel.label.substringBefore(" (")} · " +
                        if (settings.quality == AiQuality.HIGH) "ensemble" else "on-device",
                    color = Color(0xD9120F1E),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onClearFinished, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.DeleteSweep,
                    contentDescription = "Clear finished jobs",
                    modifier = Modifier.size(18.dp),
                    tint = CapriInk
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            settingsSummary(settings),
            color = Color(0xE6120F1E),
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        WarmupLine(warmup)
    }
}

/** "RIFE 4.6 · 1080p · x2 · 2× parallel · H.264" on a single line, whatever the model is called. */
private fun settingsSummary(settings: Settings): String {
    val rate = if (settings.targetFps > 0.0) {
        fpsLabel(settings.targetFps) + " fps"
    } else "x${settings.factor}"
    val workers = settings.workers.coerceIn(Settings.MIN_WORKERS, Settings.MAX_WORKERS)
    return buildString {
        append(settings.ncnnModel.label.substringBefore(" ("))
        append(" · ")
        append(resolutionLabel(settings))
        append(" · ")
        append(rate)
        append(" · ")
        append(workers)
        append("× parallel · ")
        append(settings.codec.label)
    }
}

/** "GPU ready · 240 ms", with a dot that breathes while the engine is being prepared. */
@Composable
private fun WarmupLine(status: EngineWarmup.Status) {
    val (label, active) = when (status.phase) {
        EngineWarmup.Phase.READY -> "GPU ready · ${status.millis} ms · jobs start at once" to false
        EngineWarmup.Phase.BUILDING -> "Preparing the GPU engine… first launch only" to true
        EngineWarmup.Phase.FAILED -> "GPU preparation failed · CPU" to false
        EngineWarmup.Phase.NOT_APPLICABLE -> status.detail.ifEmpty { "GPU not used" } to false
        EngineWarmup.Phase.IDLE -> "GPU engine idle" to false
    }
    val transition = rememberInfiniteTransition(label = "warmup")
    val alpha by transition.animateFloat(
        initialValue = if (active) 0.2f else 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "dot"
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(7.dp)) {
            drawCircle(color = CapriInk, alpha = alpha, radius = size.minDimension / 2f)
        }
        Spacer(Modifier.width(7.dp))
        Text(
            label,
            color = Color(0xCC120F1E),
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** The Capri mark: an open ring and the frame RIFE creates between two others. */
@Composable
private fun CapriLogo(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val strokeWidth = size.minDimension * 0.12f
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        val inset = strokeWidth * 1.1f
        val box = Size(size.width - inset * 2, size.height - inset * 2)
        drawArc(
            color = CapriInk,
            startAngle = 40f,
            sweepAngle = 280f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = box,
            style = stroke
        )
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(size.width * 0.42f, size.height * 0.34f)
            lineTo(size.width * 0.70f, size.height * 0.50f)
            lineTo(size.width * 0.42f, size.height * 0.66f)
            close()
        }
        drawPath(path, color = CapriInk)
    }
}

@Composable
private fun CapriEmptyState(onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    Brush.linearGradient(listOf(CapriYellow.copy(alpha = 0.22f), CapriCyan.copy(alpha = 0.18f)))
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Movie, contentDescription = null, modifier = Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(20.dp))
        Text("No video in the queue", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "Add one or more videos, choose the multiplier and start: RIFE invents the missing frames on " +
                "the phone, without a computer and without the internet.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(22.dp))
        GradientButton(text = "Choose videos", icon = Icons.Filled.Add, onClick = onAdd, enabled = true)
    }
}

@Composable
private fun JobCard(job: JobItem, canRemove: Boolean, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val accent = statusColor(job.status)
    val running = job.status == JobStatus.RUNNING
    val sweep by animateFloatAsState(
        targetValue = job.progress.coerceIn(0f, 1f) * 360f,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "job-progress"
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                brush = if (running) {
                    Brush.linearGradient(listOf(CapriYellow.copy(alpha = 0.8f), CapriCyan.copy(alpha = 0.6f)))
                } else {
                    Brush.linearGradient(listOf(MaterialTheme.colorScheme.outline, MaterialTheme.colorScheme.outline))
                },
                shape = RoundedCornerShape(26.dp)
            )
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Canvas(modifier = Modifier.size(46.dp)) {
                val stroke = Stroke(width = size.minDimension * 0.13f, cap = StrokeCap.Round)
                val inset = stroke.width / 2f
                val box = Size(size.width - stroke.width, size.height - stroke.width)
                drawArc(
                    color = accent.copy(alpha = 0.18f),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = box,
                    style = stroke
                )
                drawArc(
                    color = accent,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = box,
                    style = stroke
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                // The name gets its own line (the remove button only competes with it), and the
                // badge moved to the line below with the metadata: nothing has to share a few dp
                // any more, which is what used to push the name under the badge on a small screen.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        job.displayName,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (canRemove) {
                        IconButton(onClick = onRemove, modifier = Modifier.size(26.dp)) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Remove",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusBadge(job.status, accent)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        metaLine(job),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        job.detail.takeIf { it.isNotEmpty() }?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        job.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 11.5.sp, color = CapriDanger, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (job.status == JobStatus.DONE && job.outputName != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "→ ${job.outputName}",
                fontSize = 11.5.sp,
                color = CapriMint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        JobTimer(job)
    }
}

/** The state of a job as a pill that never wraps and never takes more than one line. */
@Composable
private fun StatusBadge(status: JobStatus, accent: Color) {
    Text(
        statusLabel(status),
        color = accent,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(accent.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

/** "1920x1080 · 30 fps · 12s", on one line whatever the numbers are. */
private fun metaLine(job: JobItem): String {
    val info = job.info ?: return "metadata unavailable"
    return "${info.displayWidth}x${info.displayHeight} · " +
        String.format(Locale.US, "%.2f", info.fps).trimEnd('0').trimEnd('.') + " fps · " +
        formatDuration(info.durationSeconds)
}

/** Elapsed time, estimate and end time while running; total time once finished. */
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
                if (eta != null) {
                    " · ETA " + formatClock(eta) + " · ends ~" +
                        java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(now + eta))
                } else " · ETA computing…"
        }
        job.finishedAtMs > 0L -> "Total time " + formatClock(job.finishedAtMs - job.startedAtMs)
        else -> return
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

/** Add / start / cancel, always at hand. */
@Composable
private fun ActionBar(running: Boolean, canStart: Boolean, onAdd: () -> Unit, onStart: () -> Unit, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GhostButton(
            text = "Add videos",
            icon = Icons.Filled.Add,
            onClick = onAdd,
            enabled = !running,
            modifier = Modifier.weight(1f)
        )
        GradientButton(
            text = if (running) "Cancel" else "Interpolate",
            icon = if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
            onClick = if (running) onCancel else onStart,
            enabled = running || canStart,
            modifier = Modifier.weight(1f),
            danger = running
        )
    }
}

@Composable
private fun GradientButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    danger: Boolean = false
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.96f else 1f, label = "press")
    val brush = when {
        !enabled -> Brush.linearGradient(listOf(Color(0xFF35324A), Color(0xFF35324A)))
        danger -> Brush.linearGradient(listOf(CapriDanger, CapriMagenta))
        else -> Brush.linearGradient(listOf(CapriYellow, CapriCyan))
    }
    Row(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(22.dp))
            .background(brush)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = CapriInk)
        Spacer(Modifier.width(7.dp))
        Text(
            text,
            color = CapriInk,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun GhostButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.96f else 1f, label = "press")
    Row(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(22.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(22.dp))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            maxLines = 1
        )
    }
}

/** RAM and temperatures, refreshed every 2 s (5 s when idle). */
@Composable
private fun SystemStrip(running: Boolean) {
    val context = LocalContext.current
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
    val lowMemory = s.lowMemory
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            String.format(Locale.US, "%.1f/%.1f GB", (s.sysTotalMb - s.sysAvailMb) / 1024.0, s.sysTotalMb / 1024.0),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (lowMemory) CapriDanger else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "CPU ${t(s.cpuC)} · GPU ${t(s.gpuC)} · ${t(s.batteryC)}",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        val thermal = s.thermal
        if (thermal.isNotEmpty() && thermal != "none") {
            Text(thermal, fontSize = 11.sp, color = CapriWarn, maxLines = 1)
        }
    }
}

/** Collapsible log: the same lines the files get, colour-coded by kind. */
@Composable
private fun LogPanel(logs: List<String>, onCopy: () -> Unit, onShare: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size, expanded) {
        if (expanded && logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Divider(color = MaterialTheme.colorScheme.outline)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Log", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onCopy, enabled = logs.isNotEmpty()) { Text("Copy", fontSize = 12.sp) }
            TextButton(onClick = onShare, enabled = logs.isNotEmpty()) { Text("Share", fontSize = 12.sp) }
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide" else "Show", fontSize = 12.sp) }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(220)),
            exit = shrinkVertically(animationSpec = tween(180))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .background(Color(0xFF08070F))
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
                                color = lineColor(line)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun lineColor(line: String): Color = when {
    line.contains("✖") -> CapriDanger
    line.contains("⚠") -> CapriWarn
    line.contains("✔") -> CapriMint
    line.contains("▶") || line.contains("■") -> CapriCyan
    else -> Color(0xFFB9D9C8)
}

private fun statusColor(status: JobStatus): Color = when (status) {
    JobStatus.QUEUED -> Color(0xFF9A94B8)
    JobStatus.RUNNING -> CapriCyan
    JobStatus.DONE -> CapriMint
    JobStatus.FAILED -> CapriDanger
    JobStatus.CANCELLED -> CapriWarn
}

private fun statusLabel(status: JobStatus): String = when (status) {
    JobStatus.QUEUED -> "queued"
    JobStatus.RUNNING -> "running"
    JobStatus.DONE -> "done"
    JobStatus.FAILED -> "failed"
    JobStatus.CANCELLED -> "cancelled"
}

internal fun resolutionLabel(settings: Settings): String = when (settings.maxLongSide) {
    0 -> "source resolution"
    854 -> "480p"
    1280 -> "720p"
    1920 -> "1080p"
    2560 -> "1440p"
    3840 -> "4K"
    else -> "${settings.maxLongSide} px"
}

internal fun fpsLabel(fps: Double): String =
    if (fps % 1.0 == 0.0) String.format(Locale.US, "%.0f", fps)
    else String.format(Locale.US, "%.3f", fps).trimEnd('0').trimEnd('.')

private fun formatDuration(seconds: Double): String {
    if (seconds <= 0) return "0 s"
    val total = seconds.toInt()
    val m = total / 60
    val s = total % 60
    return if (m > 0) "${m}m ${s}s" else "${s}s"
}
