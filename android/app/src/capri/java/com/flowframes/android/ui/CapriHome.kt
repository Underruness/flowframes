package com.flowframes.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
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
import kotlin.random.Random

/**
 * Capri's studio.
 *
 * The character this edition is named after is a black silhouette with a neon green eye mask, big
 * electric blue ringed eyes and a white zigzag mouth, standing on a carmine field full of white
 * dust, lit by violet rims. That is the whole layout language here: a near-black field with a
 * magenta glow and sparkles, magenta shards, green for everything that is alive, blue for what the
 * app measures, and one thin bar of colour under the wordmark instead of a big banner.
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
    Box(modifier = Modifier.fillMaxSize()) {
        CapriBackdrop()
        Column(modifier = Modifier.fillMaxSize()) {
            CapriHeader(
                settings = settings,
                warmup = warmup,
                onClearFinished = onClearFinished
            )
            CapriChips(settings = settings, warmup = warmup)
            SystemStrip(running = running)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (jobs.isEmpty()) {
                    CapriEmptyState(onAdd)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
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
}

/** Near-black field, a carmine glow behind the header and the white dust of the reference art. */
@Composable
private fun CapriBackdrop() {
    val dust = remember {
        val random = Random(0xC0FFEE)
        List(150) {
            Offset(random.nextFloat(), random.nextFloat()) to (1.2f + random.nextFloat() * 2.2f)
        }
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(CapriBlack)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(CapriHot.copy(alpha = 0.30f), Color.Transparent),
                center = Offset(size.width * 0.15f, -size.height * 0.05f),
                radius = size.width * 0.95f
            ),
            radius = size.width * 0.95f,
            center = Offset(size.width * 0.15f, -size.height * 0.05f)
        )
        dust.forEach { (position, radius) ->
            drawCircle(
                color = CapriWhite.copy(alpha = 0.20f + radius * 0.12f),
                radius = radius,
                center = Offset(position.x * size.width, position.y * size.height)
            )
        }
        // Two shards, one carmine one green, cut hard like the poster shapes.
        drawPath(
            Path().apply {
                moveTo(-10f, size.height * 0.86f)
                lineTo(size.width * 0.26f, size.height * 0.70f)
                lineTo(size.width * 0.04f, size.height * 0.93f)
                close()
            },
            brush = Brush.linearGradient(listOf(CapriDeep.copy(alpha = 0.45f), Color.Transparent))
        )
        drawPath(
            Path().apply {
                moveTo(size.width * 1.02f, size.height * 0.30f)
                lineTo(size.width * 0.82f, size.height * 0.46f)
                lineTo(size.width * 0.99f, size.height * 0.52f)
                close()
            },
            brush = Brush.linearGradient(listOf(CapriGreen.copy(alpha = 0.22f), Color.Transparent))
        )
    }
}

/**
 * The mark itself: the head, the mask and the eyes. Drawn each time rather than shipped as a bitmap,
 * so it stays sharp at any size, and so the launcher icon and the in-app logo cannot drift apart.
 */
@Composable
private fun CapriMask(modifier: Modifier = Modifier, field: Boolean = true) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (field) {
            drawRect(CapriHot)
            val random = Random(7)
            repeat(26) {
                drawCircle(
                    color = CapriWhite.copy(alpha = 0.5f),
                    radius = w * 0.008f,
                    center = Offset(random.nextFloat() * w, random.nextFloat() * h)
                )
            }
        }
        // Black spiky head.
        val head = Path().apply {
            fun p(x: Float, y: Float) = Offset(x * w, y * h)
            moveTo(p(0.50f, 0.12f).x, p(0.50f, 0.12f).y)
            listOf(
                0.62f to 0.26f, 0.78f to 0.16f, 0.72f to 0.32f, 0.92f to 0.38f, 0.74f to 0.48f,
                0.94f to 0.62f, 0.70f to 0.64f, 0.74f to 0.90f, 0.50f to 0.74f, 0.26f to 0.90f,
                0.30f to 0.64f, 0.06f to 0.62f, 0.26f to 0.48f, 0.08f to 0.38f, 0.28f to 0.32f,
                0.22f to 0.16f, 0.38f to 0.26f
            ).forEach { (x, y) -> lineTo(x * w, y * h) }
            close()
        }
        drawPath(head, CapriBlack)
        // Green mask band across the eyes.
        drawPath(
            Path().apply {
                moveTo(0.16f * w, 0.47f * h)
                lineTo(0.88f * w, 0.51f * h)
                lineTo(0.83f * w, 0.68f * h)
                lineTo(0.20f * w, 0.65f * h)
                close()
            },
            CapriGreen
        )
        // Left eye: white, blue, black, then the glint.
        val left = Offset(0.38f * w, 0.58f * h)
        drawCircle(CapriWhite, radius = 0.19f * w, center = left)
        drawCircle(CapriBlue, radius = 0.13f * w, center = left)
        drawCircle(CapriInk, radius = 0.07f * w, center = left)
        drawCircle(CapriWhite, radius = 0.026f * w, center = Offset(0.355f * w, 0.545f * h))
        // Right eye, smaller, as on the reference.
        val right = Offset(0.69f * w, 0.60f * h)
        drawCircle(CapriWhite, radius = 0.135f * w, center = right)
        drawCircle(CapriBlue, radius = 0.088f * w, center = right)
        drawCircle(CapriInk, radius = 0.045f * w, center = right)
        // White zigzag mouth.
        drawPath(
            Path().apply {
                moveTo(0.40f * w, 0.79f * h)
                lineTo(0.46f * w, 0.73f * h)
                lineTo(0.52f * w, 0.82f * h)
                lineTo(0.58f * w, 0.75f * h)
                lineTo(0.64f * w, 0.80f * h)
            },
            CapriWhite,
            style = Stroke(width = 0.035f * w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

/**
 * The header: 64 dp tall, one wordmark, one hairline of colour under it and the current state. The
 * big gradient banner that used to sit here is gone: the sparkle field behind the whole screen does
 * the work now, without eating a third of a small display.
 */
@Composable
private fun CapriHeader(settings: Settings, warmup: EngineWarmup.Status, onClearFinished: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CapriMask(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "CAPRI",
                        color = CapriWhite,
                        fontFamily = CapriDisplay,
                        fontStyle = FontStyle.Italic,
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp,
                        letterSpacing = 4.sp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "GOOD-BOY",
                        color = CapriHot,
                        fontFamily = CapriDisplay,
                        fontStyle = FontStyle.Italic,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        letterSpacing = 1.5.sp,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.height(3.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Brush.horizontalGradient(listOf(CapriHot, CapriPurple, CapriGreen)))
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${settings.ncnnModel.label.substringBefore(" (")} · " +
                        if (settings.quality == AiQuality.HIGH) "ensemble" else "on-device interpolation",
                    color = CapriDim,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onClearFinished) {
                Icon(
                    Icons.Filled.DeleteSweep,
                    contentDescription = "Clear finished jobs",
                    tint = CapriHot
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        WarmupLine(warmup)
    }
}

/** One scrollable line of chips: they never wrap, so they never push the queue around. */
@Composable
private fun CapriChips(settings: Settings, warmup: EngineWarmup.Status) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Chip(resolutionLabel(settings), CapriBlue)
        Chip(
            if (settings.targetFps > 0.0) fpsLabel(settings.targetFps) + " fps" else "x${settings.factor}",
            CapriHot
        )
        Chip(
            "${settings.workers.coerceIn(Settings.MIN_WORKERS, Settings.MAX_WORKERS)}x parallel",
            CapriPurple
        )
        Chip(settings.codec.label, CapriGreen)
        if (warmup.phase == EngineWarmup.Phase.BUILDING) Chip("compiling shaders", CapriWarn)
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontFamily = CapriFont,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

/** "GPU ready", "compiling shaders", with a dot that breathes while the engine is being built. */
@Composable
private fun WarmupLine(status: EngineWarmup.Status) {
    val (label, tone, active) = when (status.phase) {
        EngineWarmup.Phase.READY -> Triple(
            "GPU ready · ${status.millis} ms · jobs start immediately", CapriGreen, false
        )
        EngineWarmup.Phase.BUILDING -> Triple(
            "Compiling the GPU engine — first launch only, jobs start right after", CapriWarn, true
        )
        EngineWarmup.Phase.FAILED -> Triple("GPU preparation failed · ${status.detail}", CapriDanger, false)
        EngineWarmup.Phase.NOT_APPLICABLE -> Triple(status.detail.ifEmpty { "GPU not used" }, CapriDim, false)
        EngineWarmup.Phase.IDLE -> Triple("GPU engine idle", CapriDim, false)
    }
    val transition = rememberInfiniteTransition(label = "warmup")
    val alpha by transition.animateFloat(
        initialValue = if (active) 0.25f else 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "dot"
    )
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp)) {
        Canvas(modifier = Modifier.size(8.dp)) {
            drawCircle(color = tone, alpha = alpha, radius = size.minDimension / 2f)
        }
        Spacer(Modifier.width(7.dp))
        Text(
            label,
            color = tone,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CapriEmptyState(onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CapriMask(
            modifier = Modifier
                .size(104.dp)
                .clip(RoundedCornerShape(18.dp))
                .border(2.dp, CapriHot, RoundedCornerShape(18.dp))
        )
        Spacer(Modifier.height(18.dp))
        Text(
            "No video in the queue",
            color = CapriWhite,
            fontFamily = CapriDisplay,
            fontStyle = FontStyle.Italic,
            fontWeight = FontWeight.Bold,
            fontSize = 19.sp,
            maxLines = 1
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Add one or more videos, choose the multiplier and start: RIFE invents the missing frames " +
                "on the phone, without a computer and without the internet.",
            fontSize = 12.5.sp,
            color = CapriDim,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(10.dp))
        CapriTag("good-boy")
        Spacer(Modifier.height(18.dp))
        CapriButton(text = "Add videos", icon = Icons.Filled.Add, onClick = onAdd, enabled = true)
    }
}

/** A rotated, letter-spaced tag, the way the reference art stamps words on the character. */
@Composable
private fun CapriTag(text: String) {
    Text(
        text.uppercase(Locale.US),
        color = CapriInk,
        fontFamily = CapriDisplay,
        fontStyle = FontStyle.Italic,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        letterSpacing = 2.sp,
        modifier = Modifier
            .rotate(-3f)
            .clip(RoundedCornerShape(3.dp))
            .background(CapriHot)
            .padding(horizontal = 9.dp, vertical = 3.dp)
    )
}

/**
 * One job. Every line owns its row: the name and the status share the first one, the metadata gets a
 * full line of its own that is truncated instead of squeezed, and the progress is a flat bar rather
 * than a ring that used to steal 46 dp of width and push the text over itself on small phones.
 */
@Composable
private fun JobCard(job: JobItem, canRemove: Boolean, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val accent = statusColor(job.status)
    val running = job.status == JobStatus.RUNNING
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CapriPanel)
            .border(1.dp, if (running) CapriHot.copy(alpha = 0.85f) else CapriPurple.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
    ) {
        // Shard on the left edge, in the state's colour.
        Box(
            modifier = Modifier
                .padding(top = 12.dp, bottom = 12.dp)
                .width(4.dp)
                .heightIn(min = 34.dp)
                .background(accent)
        )
        Column(modifier = Modifier.padding(start = 12.dp, end = 10.dp, top = 11.dp, bottom = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    job.displayName,
                    color = CapriWhite,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    statusLabel(job.status).uppercase(Locale.US),
                    color = accent,
                    fontFamily = CapriDisplay,
                    fontStyle = FontStyle.Italic,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(accent.copy(alpha = 0.14f))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                )
                if (canRemove) {
                    IconButton(onClick = onRemove, modifier = Modifier.size(30.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Remove",
                            modifier = Modifier.size(15.dp),
                            tint = CapriDim
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                metaLine(job),
                fontSize = 11.5.sp,
                color = CapriDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(9.dp))
            CapriBar(progress = job.progress, active = running)
            job.detail.takeIf { it.isNotEmpty() }?.let {
                Spacer(Modifier.height(7.dp))
                Text(it, fontSize = 11.5.sp, color = CapriBlueLight, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            job.error?.let {
                Spacer(Modifier.height(7.dp))
                Text(it, fontSize = 11.5.sp, color = CapriDanger, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (job.status == JobStatus.DONE && job.outputName != null) {
                Spacer(Modifier.height(7.dp))
                Text(
                    "→ ${job.outputName}",
                    fontSize = 11.5.sp,
                    color = CapriGreen,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            JobTimer(job)
        }
    }
}

private fun metaLine(job: JobItem): String {
    val info = job.info ?: return "metadata unavailable"
    return "${info.displayWidth}x${info.displayHeight} · " +
        String.format(Locale.US, "%.2f", info.fps).trimEnd('0').trimEnd('.') + " fps · " +
        formatDuration(info.durationSeconds)
}

/** Flat progress bar with notch marks: a shard of colour, not a soft Material ring. */
@Composable
private fun CapriBar(progress: Float, active: Boolean) {
    val sweep by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "job-progress"
    )
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
    ) {
        drawRect(CapriPanelHigh)
        drawRect(
            brush = if (active) {
                Brush.horizontalGradient(listOf(CapriGreen, CapriBlue))
            } else {
                Brush.horizontalGradient(listOf(CapriHot, CapriPurple))
            },
            size = Size(size.width * sweep, size.height)
        )
        for (i in 1..7) {
            val x = size.width * i / 8f
            drawLine(CapriBlack, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
        }
    }
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
    Spacer(Modifier.height(7.dp))
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = CapriBlueLight,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/** Add / start / cancel, always at hand. */
@Composable
private fun ActionBar(running: Boolean, canStart: Boolean, onAdd: () -> Unit, onStart: () -> Unit, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GhostButton(text = "Add videos", icon = Icons.Filled.Add, onClick = onAdd, enabled = !running)
        CapriButton(
            text = if (running) "Cancel" else "Interpolate",
            icon = if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
            onClick = if (running) onCancel else onStart,
            enabled = running || canStart,
            danger = running,
            modifier = Modifier.weight(1f)
        )
    }
}

/** The call to action: the mask's green, black text, hard corners. */
@Composable
private fun CapriButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean,
    danger: Boolean = false,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.97f else 1f, label = "press")
    val fill = when {
        !enabled -> CapriPanelHigh
        danger -> CapriHot
        else -> CapriGreen
    }
    val ink = when {
        !enabled -> CapriDim
        danger -> CapriWhite
        else -> CapriInk
    }
    Row(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .background(fill)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = ink)
        Spacer(Modifier.width(7.dp))
        Text(
            text.uppercase(Locale.US),
            color = ink,
            fontFamily = CapriDisplay,
            fontStyle = FontStyle.Italic,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            letterSpacing = 1.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun GhostButton(text: String, icon: ImageVector, onClick: () -> Unit, enabled: Boolean) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.97f else 1f, label = "press")
    val tone = if (enabled) CapriGreen else CapriDim.copy(alpha = 0.5f)
    Row(
        modifier = Modifier
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .border(1.5.dp, tone, RoundedCornerShape(8.dp))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = tone)
        Spacer(Modifier.width(7.dp))
        Text(
            text.uppercase(Locale.US),
            color = tone,
            fontFamily = CapriDisplay,
            fontStyle = FontStyle.Italic,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = 0.8.sp,
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            String.format(Locale.US, "%.1f/%.1f GB", (s.sysTotalMb - s.sysAvailMb) / 1024.0, s.sysTotalMb / 1024.0),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = CapriFont,
            color = if (s.lowMemory) CapriDanger else CapriDim,
            maxLines = 1
        )
        Text(
            "CPU ${t(s.cpuC)} · GPU ${t(s.gpuC)} · ${t(s.batteryC)}",
            fontSize = 11.sp,
            fontFamily = CapriFont,
            color = CapriDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        val thermal = s.thermal
        if (thermal.isNotEmpty() && thermal != "none") {
            Text(thermal, fontSize = 11.sp, fontFamily = CapriFont, color = CapriWarn, maxLines = 1)
        }
    }
    Divider(color = CapriPurple.copy(alpha = 0.25f))
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
        Divider(color = CapriPurple.copy(alpha = 0.25f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "LOG",
                color = CapriHot,
                fontFamily = CapriDisplay,
                fontStyle = FontStyle.Italic,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 1.5.sp,
                modifier = Modifier.weight(1f)
            )
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
                    .background(CapriBlack)
            ) {
                if (logs.isEmpty()) {
                    Text(
                        "No events yet",
                        fontSize = 11.sp,
                        modifier = Modifier.padding(12.dp),
                        color = CapriDim
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
    line.contains("✔") -> CapriGreen
    line.contains("▶") || line.contains("■") -> CapriBlueLight
    else -> CapriDim
}

private fun statusColor(status: JobStatus): Color = when (status) {
    JobStatus.QUEUED -> CapriBlue
    JobStatus.RUNNING -> CapriGreen
    JobStatus.DONE -> CapriGreen
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
