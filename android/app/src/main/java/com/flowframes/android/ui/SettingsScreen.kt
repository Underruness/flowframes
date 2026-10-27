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
    onResetOutputFolder: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Réglages", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Retour")
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
                Text("Facteur", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2, 3, 4, 6, 8).forEach { f ->
                        FilterChip(
                            selected = settings.factor == f,
                            onClick = { onChange { s -> s.copy(factor = f) } },
                            label = { Text("x$f") }
                        )
                    }
                }
                Caption("Chaque facteur ajoute ${settings.factor - 1} image(s) interpolée(s) entre deux images source.")

                Spacer(Modifier.height(4.dp))
                Text("Résolution de traitement", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
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
                    "Les grandes résolutions sont plus belles mais beaucoup plus lentes et gourmandes en RAM. " +
                        "720p est un bon compromis sur mobile."
                )
            }

            Section("Intelligence artificielle") {
                Text("Qualité du modèle", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.quality == AiQuality.FAST,
                        onClick = { onChange { s -> s.copy(quality = AiQuality.FAST) } },
                        label = { Text("Rapide") }
                    )
                    FilterChip(
                        selected = settings.quality == AiQuality.HIGH,
                        onClick = { onChange { s -> s.copy(quality = AiQuality.HIGH) } },
                        label = { Text("Haute qualité") }
                    )
                }
                Caption("« Haute qualité » active le mode ensemble de RIFE : moins d'artefacts, environ deux fois plus lent.")

                SwitchRow(
                    title = "Accélération NNAPI",
                    description = "Utilise le processeur neuronal de l'appareil quand il est disponible (résultats variables selon le modèle).",
                    checked = settings.useNnapi,
                    onCheckedChange = { v -> onChange { s -> s.copy(useNnapi = v) } }
                )

                Text(
                    "Threads CPU : " + if (settings.threads == 0) "automatique" else settings.threads.toString(),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
                Slider(
                    value = settings.threads.toFloat(),
                    onValueChange = { v -> onChange { s -> s.copy(threads = v.toInt()) } },
                    valueRange = 0f..8f,
                    steps = 7
                )
            }

            Section("Vidéo de sortie") {
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

                Text("Débit", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Léger", "Équilibré", "Élevé").forEachIndexed { index, label ->
                        FilterChip(
                            selected = settings.bitratePreset == index,
                            onClick = { onChange { s -> s.copy(bitratePreset = index) } },
                            label = { Text(label) }
                        )
                    }
                }

                SwitchRow(
                    title = "Conserver l'audio",
                    description = "La piste audio d'origine est copiée telle quelle, sans réencodage.",
                    checked = settings.keepAudio,
                    onCheckedChange = { v -> onChange { s -> s.copy(keepAudio = v) } }
                )

                Spacer(Modifier.height(4.dp))
                Text("Modèle de nom", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                OutlinedTextField(
                    value = settings.namePattern,
                    onValueChange = { v -> onChange { s -> s.copy(namePattern = v) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall
                )
                Caption("Variables : {name} {factor} {fps} {codec} {date}")
                val preview = Naming.build(
                    settings.namePattern,
                    "clip.mp4",
                    settings.factor,
                    (30.0 * settings.factor),
                    settings.codec.label
                )
                Caption("Aperçu : $preview")

                Spacer(Modifier.height(4.dp))
                Text("Dossier de sortie", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        settings.outputTreeUri?.let { "Dossier personnalisé" }
                            ?: "Dossier de l'application (aucune permission requise)",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = onPickOutputFolder) { Text("Choisir", fontSize = 12.sp) }
                }
                if (settings.outputTreeUri != null) {
                    Row {
                        OutlinedButton(onClick = onResetOutputFolder) { Text("Réinitialiser", fontSize = 12.sp) }
                    }
                }
            }

            Section("Animation 2D et finitions") {
                SwitchRow(
                    title = "Dé-duplication des images",
                    description = "Ignore les images identiques. À activer pour l'animation 2D, à laisser désactivé pour de la vidéo réelle.",
                    checked = settings.dedupe,
                    onCheckedChange = { v -> onChange { s -> s.copy(dedupe = v) } }
                )
                if (settings.dedupe) {
                    Text(
                        "Seuil : " + String.format(Locale.US, "%.3f", settings.dedupeThreshold),
                        fontSize = 13.sp
                    )
                    Slider(
                        value = settings.dedupeThreshold.toFloat(),
                        onValueChange = { v -> onChange { s -> s.copy(dedupeThreshold = v.toDouble()) } },
                        valueRange = 0.0005f..0.02f
                    )
                }

                SwitchRow(
                    title = "Corriger les changements de scène",
                    description = "Évite de créer une image de transition (morphing) au milieu d'un changement de plan.",
                    checked = settings.fixSceneChanges,
                    onCheckedChange = { v -> onChange { s -> s.copy(fixSceneChanges = v) } }
                )
                if (settings.fixSceneChanges) {
                    Text(
                        "Sensibilité : " + String.format(Locale.US, "%.2f", settings.sceneThreshold),
                        fontSize = 13.sp
                    )
                    Slider(
                        value = settings.sceneThreshold.toFloat(),
                        onValueChange = { v -> onChange { s -> s.copy(sceneThreshold = v.toDouble()) } },
                        valueRange = 0.1f..0.9f
                    )
                }

                SwitchRow(
                    title = "Boucle parfaite",
                    description = "Interpole aussi de la dernière vers la première image pour les vidéos en boucle.",
                    checked = settings.loop,
                    onCheckedChange = { v -> onChange { s -> s.copy(loop = v) } }
                )

                Text(
                    "Limite de FPS en sortie : " +
                        if (settings.maxOutputFps <= 0.0) "aucune"
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

            Section("À propos") {
                Text(
                    "FlowFrames Android 1.0.0 — réimplémentation du pipeline de FlowFrames (Windows) " +
                        "pour Android : décodage MediaCodec, interpolation RIFE 4.9 via ONNX Runtime, " +
                        "encodage matériel et muxage MP4.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Modèles RIFE 4.9 (MIT) · exécution 100 % locale, aucune donnée envoyée.",
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
