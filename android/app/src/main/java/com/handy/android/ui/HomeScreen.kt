package com.handy.android.ui

import android.Manifest
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.handy.android.engine.DictationState
import com.handy.android.engine.EngineState
import com.handy.android.engine.FileJobState
import com.handy.android.graph
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(activity: MainActivity, modifier: Modifier, onOpenModels: () -> Unit) {
    val g = activity.graph
    val tick by activity.refreshTick
    val modelStatus by g.models.status.collectAsStateWithLifecycle()
    val settings by g.settings.state.collectAsStateWithLifecycle()
    val engineState by g.engine.state.collectAsStateWithLifecycle()
    val fileState by g.files.state.collectAsStateWithLifecycle()

    val mic = remember(tick) { activity.hasMic() }
    val enabled = remember(tick) { KeyboardStatus.isEnabled(activity) }
    val selected = remember(tick) { KeyboardStatus.isSelected(activity) }
    val model = remember(modelStatus, settings.selectedModelId) { g.engine.activeModel() }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTitle("Handy", "Speak, and your words appear in any app. Everything stays on your phone.")

        if (!mic || model == null || !enabled || !selected) {
            SectionCard {
                Text("Finish setting up", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                SetupRow("Microphone access", mic, "Allow") { activity.micPermission.launch(Manifest.permission.RECORD_AUDIO) }
                SetupRow("Speech model", model != null, "Choose", onClick = onOpenModels)
                SetupRow("Enable Handy keyboard", enabled, "Enable") { KeyboardStatus.openEnableSettings(activity) }
                SetupRow("Switch to Handy keyboard", selected, "Switch", enabledAction = enabled) { KeyboardStatus.showPicker(activity) }
            }
        }

        FileJobCard(fileState, onCancel = { g.files.cancel() }, onDismiss = { g.files.dismiss() }, activity = activity)

        DictateCard(activity, ready = mic && model != null)

        SectionCard {
            Text("Try the keyboard", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Tap the field, hold the mic, speak, let go.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            var text by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                placeholder = { Text("Your words will appear here") },
            )
            if (enabled && !selected) {
                TextButton(onClick = { KeyboardStatus.showPicker(activity) }) {
                    Icon(Icons.Default.Keyboard, null); Spacer(Modifier.width(6.dp)); Text("Switch to Handy keyboard")
                }
            }
        }

        OutlinedButton(
            onClick = { activity.pickAudio.launch(arrayOf("audio/*", "video/*")) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Icon(Icons.Default.AudioFile, null); Spacer(Modifier.width(8.dp)); Text("Transcribe an audio file")
        }

        val engineLine = when (val e = engineState) {
            is EngineState.Ready -> "${e.model.name} loaded in ${e.loadMs} ms"
            is EngineState.Loading -> "Loading ${e.model.name}…"
            is EngineState.Failed -> "Model error: ${e.message}"
            EngineState.Unloaded -> model?.let { "${it.name} · loads when you start talking" } ?: ""
        }
        if (engineLine.isNotEmpty()) {
            Text(
                engineLine, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 18.dp),
            )
        }
    }
}

@Composable
private fun SetupRow(label: String, done: Boolean, action: String, enabledAction: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null,
            tint = if (done) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (!done) FilledTonalButton(onClick = onClick, enabled = enabledAction) { Text(action) }
    }
}

@Composable
private fun DictateCard(activity: MainActivity, ready: Boolean) {
    val g = activity.graph
    val state by g.dictation.state.collectAsStateWithLifecycle()
    val level by g.dictation.level.collectAsStateWithLifecycle()
    var result by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val recording = state is DictationState.Recording
    val busy = state is DictationState.Transcribing

    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Dictate here", style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        recording -> (state as DictationState.Recording).partial.ifBlank { "Listening… tap to finish" }
                        busy -> "Transcribing…"
                        state is DictationState.Error -> (state as DictationState.Error).message
                        else -> "Tap the mic, speak, tap again. Copy or share the text."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state is DictationState.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            val smooth by animateFloatAsState(if (recording) level else 0f, label = "lvl")
            val bg by animateColorAsState(
                if (recording) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer, label = "bg",
            )
            val ring = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
            Box(
                Modifier.size(72.dp)
                    .drawBehind { if (recording) drawCircle(ring, radius = size.minDimension / 2 * (1.05f + 0.3f * smooth)) }
                    .clip(CircleShape).background(bg)
                    .clickable(enabled = ready && !busy) {
                        if (recording) scope.launch { g.dictation.stop("app")?.let { result = it.text } }
                        else g.dictation.start(handsFree = true)
                    },
                contentAlignment = Alignment.Center,
            ) {
                when {
                    busy -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    recording -> Icon(Icons.Default.Stop, "Stop", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(32.dp))
                    else -> Icon(Icons.Default.Mic, "Dictate", tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(32.dp))
                }
            }
        }
        if (result.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            SelectionContainer {
                Text(
                    result, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp),
                )
            }
            Row {
                TextButton(onClick = { activity.copyToClipboard(result) }) { Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("Copy") }
                TextButton(onClick = { activity.shareText(result) }) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text("Share") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { result = "" }) { Icon(Icons.Default.Close, "Clear") }
            }
        }
    }
}

@Composable
private fun FileJobCard(state: FileJobState, onCancel: () -> Unit, onDismiss: () -> Unit, activity: MainActivity) {
    when (state) {
        FileJobState.Idle -> return
        is FileJobState.Working -> SectionCard {
            Text(state.fileName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text("${state.stage}… ${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            if (state.partial.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(state.partial.takeLast(300), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
        is FileJobState.Done -> SectionCard {
            Text(state.fileName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${formatDuration(state.audioSeconds)} of audio in ${formatDuration(state.elapsedMs / 1000f)}",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            SelectionContainer { Text(state.text.ifBlank { "No speech detected." }, style = MaterialTheme.typography.bodyLarge) }
            Row {
                if (state.text.isNotBlank()) {
                    TextButton(onClick = { activity.copyToClipboard(state.text) }) { Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("Copy") }
                    TextButton(onClick = { activity.shareText(state.text) }) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text("Share") }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Dismiss") }
            }
        }
        is FileJobState.Failed -> SectionCard {
            Text(state.fileName, style = MaterialTheme.typography.titleMedium)
            Text(state.message, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

