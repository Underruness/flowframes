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
        CapriHero(settings = settings, onClearFinished = onClearFinished)
        EngineBar(settings = settings, warmup = warmup)
        SystemStrip(running = running, advanced = settings.advanced)
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
 * The bright band, wrapped around the mark and the wordmark only.
 *
 * The settings and the engine state used to sit inside this gradient; they now have their own
 * Material You bar underneath, so the colour is a signature instead of a wall of light.
 */
@Composable
private fun CapriHero(settings: Settings, onClearFinished: () -> Unit) {
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
            .background(gradient)
            .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CapriLogo(modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "CAPRI",
                color = CapriInk,
                fontWeight = FontWeight.Black,
                fontSize = 18.sp,
                letterSpacing = 3.sp
            )
            Text(
                "${settings.ncnnModel.label.substringBefore(" (")} · " +
                    if (settings.quality == AiQuality.HIGH) "ensemble" else "on-device",
                color = Color(0xD9120F1E),
                fontSize = 10.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onClearFinished, modifier = Modifier.size(38.dp)) {
            Icon(
                Icons.Filled.DeleteSweep,
                contentDescription = "Clear finished jobs",
                modifier = Modifier.size(18.dp),
                tint = CapriInk
            )
        }
    }
}

/**
 * Bar two, in Material You: what the engine will do, and whether the GPU is already prepared.
 */
@Composable
private fun EngineBar(settings: Settings, warmup: EngineWarmup.Status) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                shape = RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            settingsSummary(settings),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(5.dp))
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

/** "GPU ready · 240 ms · jobs start at once", with a dot that breathes while the GPU is prepared. */
@Composable
private fun WarmupLine(status: EngineWarmup.Status) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val (label, active, tone) = when (status.phase) {
        EngineWarmup.Phase.READY -> Triple("GPU ready · ${status.millis} ms · jobs start at once", false, CapriMint)
        EngineWarmup.Phase.BUILDING -> Triple("Preparing the GPU engine… first launch only", true, CapriWarn)
        EngineWarmup.Phase.FAILED -> Triple("GPU preparation failed · CPU", false, CapriDanger)
        EngineWarmup.Phase.NOT_APPLICABLE -> Triple(status.detail.ifEmpty { "GPU not used" }, false, neutral)
        EngineWarmup.Phase.IDLE -> Triple("GPU engine idle", false, neutral)
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
            drawCircle(color = tone, alpha = alpha, radius = size.minDimension / 2f)
        }
        Spacer(Modifier.width(7.dp))
        Text(
            label,
            color = neutral,
            fontSize = 10.5.sp,
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
