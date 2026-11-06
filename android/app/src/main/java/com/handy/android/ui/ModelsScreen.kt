package com.handy.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.handy.android.engine.DeviceProfile
import com.handy.android.graph
import com.handy.android.model.ModelCatalog
import com.handy.android.model.ModelSpec
import com.handy.android.model.ModelStatus
import com.handy.android.model.formatBytes

@Composable
fun ModelsScreen(activity: MainActivity, modifier: Modifier, compact: Boolean = false, onPicked: (() -> Unit)? = null) {
    val g = activity.graph
    val status by g.models.status.collectAsStateWithLifecycle()
    val settings by g.settings.state.collectAsStateWithLifecycle()
    val ramMb = remember { DeviceProfile.totalRamMb(activity) }
    val recommended = remember { ModelCatalog.recommendedFor(ramMb) }
    val free = remember(status) { g.models.freeBytes() }
    var confirmDelete by remember { mutableStateOf<ModelSpec?>(null) }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!compact) {
            item {
                ScreenTitle(
                    "Models",
                    "Models run entirely on your phone. Your device: ${"%.1f".format(ramMb / 1024f)} GB RAM, " +
                        "${DeviceProfile.performanceCores} performance cores · ${formatBytes(free)} free",
                )
            }
        }
        // During onboarding, lead with the model picked for this phone.
        val ordered = if (compact) listOf(recommended) + ModelCatalog.all.filter { it.id != recommended.id } else ModelCatalog.all
        items(ordered, key = { it.id }) { spec ->
            ModelCard(
                spec = spec,
                status = status[spec.id] ?: ModelStatus.NotInstalled,
                selected = settings.selectedModelId == spec.id,
                recommended = spec.id == recommended.id,
                heavy = spec.ramMb > ramMb * 0.35,
                onDownload = {
                    activity.requestNotificationsIfNeeded()
                    g.models.clearFailure(spec)
                    g.models.download(spec)
                    if (settings.selectedModelId == null || g.engine.activeModel() == null) {
                        g.settings.update { it.copy(selectedModelId = spec.id) }
                    }
                    onPicked?.invoke()
                },
                onCancel = { g.models.cancel(spec) },
                onSelect = {
                    g.settings.update { it.copy(selectedModelId = spec.id) }
                    g.engine.invalidate()
                    onPicked?.invoke()
                },
                onDelete = { confirmDelete = spec },
            )
        }
        if (!compact) {
            item {
                Text(
                    "Models come from the sherpa-onnx project (k2-fsa) and are int8-quantised for phones. " +
                        "Parakeet © NVIDIA (CC-BY-4.0), Whisper © OpenAI (MIT), Moonshine © Useful Sensors (MIT), " +
                        "SenseVoice © FunAudioLLM.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }
    }

    confirmDelete?.let { spec ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${spec.name}?") },
            text = { Text("Frees ${formatBytes(spec.installedBytes)}. You can download it again later.") },
            confirmButton = {
                TextButton(onClick = { g.models.delete(spec); g.engine.invalidate(); confirmDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun ModelCard(
    spec: ModelSpec,
    status: ModelStatus,
    selected: Boolean,
    recommended: Boolean,
    heavy: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    val installed = status is ModelStatus.Installed
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected && installed) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = if (selected && installed) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(spec.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (recommended) {
                    AssistChip(onClick = {}, label = { Text("Recommended") })
                }
            }
            Text(spec.languages, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text(spec.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Dots("Speed", spec.speed)
                Dots("Accuracy", spec.accuracy, MaterialTheme.colorScheme.tertiary)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "${formatBytes(spec.source.downloadBytes)} download · ~${spec.ramMb} MB RAM",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (heavy && !installed) {
                Text(
                    "May be slow or unstable on this phone's memory.",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(12.dp))
            when (status) {
                is ModelStatus.Downloading -> {
                    LinearProgressIndicator(progress = { status.fraction }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${formatBytes(status.downloaded)} of ${formatBytes(status.total)}" +
                                if (status.bytesPerSec > 0) " · ${formatBytes(status.bytesPerSec)}/s" else "",
                            style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onCancel) { Text("Pause") }
                    }
                }
                is ModelStatus.Extracting -> {
                    LinearProgressIndicator(progress = { status.fraction }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text("Unpacking…", style = MaterialTheme.typography.labelMedium)
                }
                is ModelStatus.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(status.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    FilledTonalButton(onClick = onDownload) { Text("Retry") }
                }
                ModelStatus.NotInstalled -> Button(onClick = onDownload) {
                    Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp)); Text("Download")
                }
                ModelStatus.Installed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selected) {
                        Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("In use", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    } else {
                        FilledTonalButton(onClick = onSelect) { Text("Use this model") }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete model") }
                }
            }
        }
    }
}
