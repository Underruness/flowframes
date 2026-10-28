package com.flowframes.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowframes.android.gen.GenSize
import com.flowframes.android.gen.GenStatus
import com.flowframes.android.gen.GenUiState
import com.flowframes.android.gen.LocalModel
import com.flowframes.android.gen.ModelLibrary
import com.flowframes.android.gen.Sampler
import java.util.Locale

/**
 * Create: local image generation. Prompt in, picture out, nothing leaves the phone.
 *
 * The checkpoint is the user's: imported from storage into files/models, never downloaded by the
 * app. The layout is the Studio's: black canvas, one card per decision, the same wave bar for
 * progress.
 */
@Composable
fun CapriCreate(
    state: GenUiState,
    engineAvailable: Boolean,
    engineReason: String?,
    importProgress: Pair<Long, Long>?,
    onPrompt: (String) -> Unit,
    onNegative: (String) -> Unit,
    onSize: (GenSize) -> Unit,
    onSteps: (Int) -> Unit,
    onCfg: (Float) -> Unit,
    onSeed: (String) -> Unit,
    onSampler: (Sampler) -> Unit,
    onGenerate: () -> Unit,
    onCancel: () -> Unit,
    onImport: () -> Unit,
    onSelectModel: (LocalModel) -> Unit,
    onDeleteModel: (LocalModel) -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onUseSeed: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        CosmicBackground()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 14.dp, 16.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column {
                    Text(
                        "CREATE",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        letterSpacing = 2.sp
                    )
                    Text(
                        "Local diffusion · the picture is made on this phone",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (!engineAvailable) {
                item {
                    SectionCard {
                        Text("Generation engine unavailable", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            engineReason ?: "libcapri_diffusion.so is missing from this APK.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item { ModelCard(state, importProgress, onImport, onSelectModel, onDeleteModel) }

            item {
                SectionCard {
                    FieldLabel("Prompt")
                    OutlinedTextField(
                        value = state.prompt,
                        onValueChange = onPrompt,
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 5,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        colors = capriFieldColors()
                    )
                    Spacer(Modifier.height(10.dp))
                    FieldLabel("Negative prompt")
                    OutlinedTextField(
                        value = state.negative,
                        onValueChange = onNegative,
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 1,
                        maxLines = 3,
                        textStyle = MaterialTheme.typography.bodySmall,
                        colors = capriFieldColors()
                    )
                }
            }

            item {
                SectionCard {
                    FieldLabel("Size")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GenSize.entries.forEach { size ->
                            Pill(
                                text = size.label,
                                selected = state.size == size,
                                modifier = Modifier.weight(1f),
                                onClick = { onSize(size) }
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    ControlSlider(
                        label = "Steps",
                        value = "${state.steps}",
                        fraction = (state.steps - 1) / 39f,
                        onChange = { fraction -> onSteps((1 + fraction * 39).toInt().coerceIn(1, 40)) }
                    )
                    Spacer(Modifier.height(6.dp))
                    ControlSlider(
                        label = "Guidance (CFG)",
                        value = String.format(Locale.US, "%.1f", state.cfg),
                        fraction = (state.cfg - 1f) / 14f,
                        onChange = { fraction -> onCfg((1f + fraction * 14f).coerceIn(1f, 15f)) }
                    )
                    Spacer(Modifier.height(14.dp))
                    FieldLabel("Sampler")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Sampler.entries.take(4).forEach { sampler ->
                            Pill(
                                text = sampler.label,
                                selected = state.sampler == sampler,
                                modifier = Modifier.weight(1f),
                                onClick = { onSampler(sampler) }
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            FieldLabel("Seed")
                            OutlinedTextField(
                                value = state.seedText,
                                onValueChange = onSeed,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                placeholder = { Text("random", fontSize = 12.sp) },
                                textStyle = MaterialTheme.typography.bodySmall,
                                colors = capriFieldColors()
                            )
                        }
                        IconButton(onClick = {
                            onSeed((0 until 9).joinToString("") { (0..9).random().toString() })
                        }) {
                            Icon(
                                Icons.Filled.Casino,
                                contentDescription = "Random seed",
                                tint = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }

            item {
                if (state.busy) {
                    SectionCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                state.message ?: "Denoising ${state.step.coerceAtLeast(1)} / ${state.total}",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(onClick = onCancel) {
                                Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Cancel")
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        val fraction = if (state.total > 0) state.step.toFloat() / state.total else 0f
                        WaveProgressBar(
                            fraction = fraction,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(12.dp)
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${(fraction * 100).toInt()} %",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(26.dp))
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable(enabled = state.model != null) { onGenerate() }
                                .padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.AutoAwesome,
                                contentDescription = null,
                                tint = CapriInk,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Generate",
                                color = CapriInk,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                        state.error?.let { error ->
                            Spacer(Modifier.height(10.dp))
                            Text(
                                error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            state.result?.let { bitmap ->
                item {
                    SectionCard {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Generated picture",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                        )
                        Spacer(Modifier.height(10.dp))
                        val details = buildString {
                            append("${state.size.width} × ${state.size.height} · ${state.steps} steps")
                            state.resultSeed?.let { append(" · seed $it") }
                            if (state.elapsedMs > 0) {
                                append(" · ${String.format(Locale.US, "%.1f", state.elapsedMs / 1000.0)} s")
                            }
                        }
                        Text(
                            details,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ActionPill(
                                text = state.savedName?.let { "Saved" } ?: "Save",
                                icon = { Icon(Icons.Filled.Download, null, modifier = Modifier.size(16.dp)) },
                                modifier = Modifier.weight(1f),
                                onClick = onSave
                            )
                            ActionPill(
                                text = "Share",
                                icon = { Icon(Icons.Filled.Share, null, modifier = Modifier.size(16.dp)) },
                                modifier = Modifier.weight(1f),
                                onClick = onShare
                            )
                        }
                        if (state.resultSeed != null) {
                            Spacer(Modifier.height(4.dp))
                            TextButton(onClick = onUseSeed, modifier = Modifier.fillMaxWidth()) {
                                Text("Reuse seed ${state.resultSeed}", fontSize = 12.sp)
                            }
                        }
                        state.savedName?.let { name ->
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Download/capri/$name",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    "Models are not included: import a .safetensors or .gguf checkpoint (SD 1.5, SDXL, " +
                        "SD-Turbo, LCM) from this phone's storage. A fine-tune with 4–8 steps and " +
                        "LCM/DPM++ gives the quickest pictures on a phone CPU.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ModelCard(
    state: GenUiState,
    importProgress: Pair<Long, Long>?,
    onImport: () -> Unit,
    onSelect: (LocalModel) -> Unit,
    onDelete: (LocalModel) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                FieldLabel("Model")
                Text(
                    state.model?.name ?: "No checkpoint imported",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                state.model?.let { model ->
                    Text(
                        ModelLibrary.formatBytes(model.bytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Box {
                TextButton(onClick = { expanded = true }, enabled = state.models.isNotEmpty()) {
                    Text(if (state.models.size > 1) "Change" else "Models")
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    state.models.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model.label, fontSize = 12.sp) },
                            onClick = {
                                expanded = false
                                onSelect(model)
                            }
                        )
                    }
                }
            }
            state.model?.let { model ->
                IconButton(onClick = { onDelete(model) }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete model",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (importProgress != null) {
            val (copied, total) = importProgress
            val fraction = if (total > 0) (copied.toFloat() / total).coerceIn(0f, 1f) else 0f
            Text(
                "Importing… ${ModelLibrary.formatBytes(copied)}" +
                    if (total > 0) " / ${ModelLibrary.formatBytes(total)}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            WaveProgressBar(
                fraction = fraction,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
            )
        } else {
            ActionPill(
                text = if (state.models.isEmpty()) "Import a checkpoint" else "Import another",
                icon = { Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp)) },
                modifier = Modifier.fillMaxWidth(),
                onClick = onImport
            )
        }
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xFF0B0B0B))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(22.dp))
            .padding(16.dp)
    ) {
        content()
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text.uppercase(Locale.US),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.sp
    )
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Pill(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val background = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val content = if (selected) CapriInk else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .border(1.dp, if (selected) background else MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = content, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun ActionPill(
    text: String,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ControlSlider(
    label: String,
    value: String,
    fraction: Float,
    onChange: (Float) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
    Slider(
        value = fraction.coerceIn(0f, 1f),
        onValueChange = onChange,
        colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    )
}

@Composable
private fun capriFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    cursorColor = MaterialTheme.colorScheme.primary
)
