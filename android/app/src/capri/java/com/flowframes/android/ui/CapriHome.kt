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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
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
    onCopyLogs: () -> Unit,
    onShareLogs: () -> Unit,
    onRemove: (String) -> Unit
) {
    // Pure black behind everything, with an animated Milky Way on it: the OLED look the edition
    // asks for, and something for the eye to rest on between the bright bars.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        CosmicBackground()
        Column(modifier = Modifier.fillMaxSize()) {
        CapriHero(settings = settings)
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
}

/**
 * The wordmark: black behind it, the two lines of text, and nothing else. The colour is in the
 * letters themselves and in the bar underneath, not in a wall of paint.
 */
@Composable
private fun CapriHero(settings: Settings) {
    val transition = rememberInfiniteTransition(label = "capri-hero")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Reverse),
        label = "phase"
    )
    val textBrush = Brush.linearGradient(
        colors = listOf(
            lerp(CapriYellow, CapriCyan, phase),
            lerp(CapriMagenta, CapriYellow, phase),
            lerp(CapriCyan, CapriMagenta, phase)
        ),
        start = Offset.Zero,
        end = Offset(520f, 40f)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CapriLogo(modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "CAPRI",
                style = TextStyle(brush = textBrush, fontWeight = FontWeight.Black),
                fontSize = 20.sp,
                letterSpacing = 3.sp,
                maxLines = 1
            )
            Text(
                "${settings.ncnnModel.label.substringBefore(" (")} · " +
                    if (settings.quality == AiQuality.HIGH) "ensemble" else "on-device",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                // Two lines: the model names are long and used to end in "…" on a narrow phone.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * The background: a Milky Way instead of a field of dots.
 *
 * Three radial gradients fake the nebula, a tilted band carries the galactic dust, and three star
 * layers (built once as [Path]s in a 0..1 square, stretched to the screen) twinkle on a 90 s cycle
 * while three meteors cross the band every 30 s. Per frame that is three drawPath calls and three
 * lines: no bitmap, no per-star allocation.
 */
@Composable
private fun CosmicBackground(modifier: Modifier = Modifier) {
    val sky = remember { buildSky() }
    val transition = rememberInfiniteTransition(label = "cosmos")
    val twinkle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(90_000, easing = LinearEasing), RepeatMode.Restart),
        label = "twinkle"
    )
    val meteors by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(30_000, easing = LinearEasing), RepeatMode.Restart),
        label = "meteors"
    )
    Canvas(modifier = modifier.fillMaxSize()) {
        // Nebula: three soft pools of colour, just enough to keep the black from being flat.
        drawRect(
            Brush.radialGradient(
                colors = listOf(Color(0xFF1B1240).copy(alpha = 0.55f), Color.Transparent),
                center = Offset(size.width * 0.18f, size.height * 0.12f),
                radius = size.maxDimension * 0.55f
            )
        )
        drawRect(
            Brush.radialGradient(
                colors = listOf(Color(0xFF3A0F33).copy(alpha = 0.42f), Color.Transparent),
                center = Offset(size.width * 0.92f, size.height * 0.34f),
                radius = size.maxDimension * 0.50f
            )
        )
        drawRect(
            Brush.radialGradient(
                colors = listOf(Color(0xFF06263A).copy(alpha = 0.38f), Color.Transparent),
                center = Offset(size.width * 0.34f, size.height * 0.88f),
                radius = size.maxDimension * 0.60f
            )
        )

        // The band itself: a tilted stripe, brightest along its middle, over ~30 % of the height.
        rotate(degrees = -24f) {
            val bandHeight = size.height * 0.30f
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color(0xFF6E5AA8).copy(alpha = 0.08f),
                        Color(0xFFC0A6E8).copy(alpha = 0.14f),
                        Color.Transparent
                    ),
                    startY = center.y - bandHeight / 2f,
                    endY = center.y + bandHeight / 2f
                ),
                topLeft = Offset(-size.width * 0.30f, center.y - bandHeight / 2f),
                size = Size(size.width * 1.60f, bandHeight)
            )
        }

        // The stars: three prebuilt layers, one drawPath each. They are drawn in a 0..1 square
        // scaled to the screen, so the whole field costs one transform per layer.
        scale(scaleX = size.width, scaleY = size.height, pivot = Offset.Zero) {
            drawPath(sky.faint, Color.White.copy(alpha = twinkleAt(twinkle, 0.3f, 0.38f)))
            drawPath(sky.mid, Color.White.copy(alpha = twinkleAt(twinkle, 1.7f, 0.62f)))
            drawPath(sky.bright, Color.White.copy(alpha = twinkleAt(twinkle, 3.1f, 0.92f)))
        }

        // Meteors: three streaks on a 30 s cycle, faded in and out so none of them pops.
        sky.meteors.forEach { meteor ->
            val progress = (meteors + meteor.phase) % 1f
            val fade = kotlin.math.sin(progress * 3.1415927f)
            if (fade > 0.02f) {
                val head = Offset(
                    (meteor.x + progress * meteor.dx) * size.width,
                    (meteor.y + progress * meteor.dy) * size.height
                )
                val tail = Offset(
                    head.x - meteor.length * size.width,
                    head.y - meteor.length * size.width * 0.45f
                )
                drawLine(
                    brush = Brush.linearGradient(
                        colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.75f * fade)),
                        start = tail,
                        end = head
                    ),
                    start = tail,
                    end = head,
                    strokeWidth = 1.6.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

/** Twinkle of one layer: 0.74 + 0.26 × sin(...) around its own base alpha. */
private fun twinkleAt(time: Float, phase: Float, base: Float): Float =
    (base * (0.74f + 0.26f * kotlin.math.sin(time * 6.2831853f * 3f + phase))).coerceIn(0f, 1f)

/** The static part of the sky: three star layers, plus where each meteor starts and travels. */
private class Sky(
    val faint: Path,
    val mid: Path,
    val bright: Path,
    val meteors: List<Meteor>
)

/** One meteor: start and travel in 0..1, tail length as a fraction of the width, cycle offset. */
private class Meteor(
    val x: Float,
    val y: Float,
    val dx: Float,
    val dy: Float,
    val length: Float,
    val phase: Float
)

/**
 * Builds the sky once: the stars sit in a 0..1 square, so the canvas only has to scale them.
 *
 * Along the band the stars follow the -24° tilt, and their distance from the core is the sum of
 * three uniforms: that is denser in the middle and thins out towards the edges, like a galaxy seen
 * from inside. The field stars around them are uniformly spread.
 */
private fun buildSky(): Sky {
    val random = java.util.Random(20261028L)

    fun add(path: Path, x: Float, y: Float, radius: Float) {
        path.addOval(Rect(Offset(x - radius, y - radius), Offset(x + radius, y + radius)))
    }

    fun layer(band: Int, field: Int, radius: Float): Path {
        val path = Path()
        repeat(band) {
            val x = random.nextFloat() * 1.6f - 0.3f
            val spread = (random.nextFloat() + random.nextFloat() + random.nextFloat() - 1.5f) / 1.5f
            val y = 0.5f - (x - 0.5f) * 0.445f + spread * 0.13f
            add(path, x, y, radius * (0.65f + random.nextFloat() * 0.70f))
        }
        repeat(field) {
            add(path, random.nextFloat(), random.nextFloat(), radius * (0.55f + random.nextFloat() * 0.90f))
        }
        return path
    }

    return Sky(
        faint = layer(band = 620, field = 80, radius = 0.0011f),
        mid = layer(band = 240, field = 70, radius = 0.0018f),
        bright = layer(band = 70, field = 45, radius = 0.0028f),
        meteors = listOf(
            Meteor(x = 0.05f, y = 0.16f, dx = 0.95f, dy = 0.42f, length = 0.22f, phase = 0.10f),
            Meteor(x = -0.10f, y = 0.40f, dx = 1.05f, dy = 0.45f, length = 0.16f, phase = 0.48f),
            Meteor(x = 0.20f, y = 0.04f, dx = 0.85f, dy = 0.38f, length = 0.28f, phase = 0.77f)
        )
    )
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
            .background(Color.Black)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                shape = RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            settingsSummary(settings),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            // The full summary (model, resolution, rate, workers, codec) used to be clipped to one
            // line on a phone and lost its tail: it wraps now.
            maxLines = 2,
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
            maxLines = 2,
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
            color = CapriCyan,
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
        drawPath(path, color = CapriCyan)
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
    // A Column, not a Box: everything after the first row used to be drawn at the same origin as
    // the file name, which is exactly how "Total time" ended up on top of it.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(Color.Black)
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
        // The 46 dp ring ate the space the text needed; the wave bar at the bottom of the card
        // carries the same information on one thin line.
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
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            WaveProgressBar(
                fraction = job.progress,
                color = accent,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "${(job.progress.coerceIn(0f, 1f) * 100).toInt()} %",
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/**
 * The progress bar: a rounded track whose filled part carries a moving sine wave.
 *
 * Two sine lines slide over an accent → white → accent gradient, clipped to the filled rounded
 * rectangle. The fraction is animated, so a jump in the job does not jump on the screen.
 */
@Composable
private fun WaveProgressBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val target by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "wave-fraction"
    )
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
        label = "wave-phase"
    )
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(12.dp)
    ) {
        val radius = size.height / 2f
        val track = Path().apply {
            addRoundRect(RoundRect(rect = Rect(Offset.Zero, size), cornerRadius = CornerRadius(radius, radius)))
        }
        drawPath(track, Color.White.copy(alpha = 0.09f))
        val filled = target * size.width
        if (filled < 1f) return@Canvas
        val window = Path().apply {
            addRoundRect(
                RoundRect(
                    rect = Rect(0f, 0f, filled, size.height),
                    cornerRadius = CornerRadius(radius, radius)
                )
            )
        }
        clipPath(window) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(color, Color.White, color),
                    startX = 0f,
                    endX = size.width
                )
            )
            fun wave(offset: Float, amplitude: Float, alpha: Float) {
                val path = Path()
                val step = 4f
                var x = 0f
                while (x <= size.width + step) {
                    val angle = (x / size.width * 3.4f + offset + phase * 2f) * 3.1415927f
                    val y = size.height / 2f + amplitude * kotlin.math.sin(angle)
                    if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                    x += step
                }
                drawPath(
                    path,
                    Color.White.copy(alpha = alpha),
                    style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
                )
            }
            wave(offset = 0f, amplitude = size.height * 0.26f, alpha = 0.20f)
            wave(offset = 1.1f, amplitude = size.height * 0.18f, alpha = 0.10f)
        }
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

/**
 * Bar three, the readout: RAM and the battery temperature, refreshed every 2 s (5 s when idle).
 *
 * Only those two are always worth the space. The CPU, GPU and NPU zones, the app memory split, the
 * thermal state and the headroom are developer numbers: they show up in the advanced view.
 */
@Composable
private fun SystemStrip(running: Boolean, advanced: Boolean) {
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
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "RAM",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(6.dp))
            Text(
                String.format(
                    Locale.US,
                    "%.1f/%.1f GB",
                    (s.sysTotalMb - s.sysAvailMb) / 1024.0,
                    s.sysTotalMb / 1024.0
                ) + if (s.lowMemory) " · LOW" else "",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (s.lowMemory) CapriDanger else neutral
            )
            Spacer(Modifier.weight(1f))
            Text(
                "Battery ${t(s.batteryC)}",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                color = when {
                    s.batteryC == null -> neutral
                    s.batteryC >= 48 -> CapriDanger
                    s.batteryC >= 42 -> CapriWarn
                    else -> neutral
                }
            )
        }
        if (advanced) {
            Spacer(Modifier.height(5.dp))
            Text(
                buildString {
                    append("CPU ${t(s.cpuC)} · GPU ${t(s.gpuC)} · NPU ${t(s.npuC)}")
                    append(" · app ${s.appPssMb} MB (gpu ${s.graphicsMb})")
                    s.headroom?.let { append(String.format(Locale.US, " · headroom %.0f%%", it * 100)) }
                    s.thermal.takeIf { it.isNotEmpty() && it != "none" }?.let { append(" · $it") }
                },
                fontSize = 10.5.sp,
                color = neutral,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
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
                    .background(Color.Black)
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
