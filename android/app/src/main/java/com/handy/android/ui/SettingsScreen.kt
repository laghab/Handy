package com.handy.android.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.handy.android.BuildConfig
import com.handy.android.engine.DeviceProfile
import com.handy.android.graph
import com.handy.android.model.ModelCatalog
import com.handy.android.model.formatBytes
import com.handy.android.settings.MicMode
import com.handy.android.settings.Settings
import com.handy.android.settings.ThemeMode
import java.util.Locale

@Composable
fun SettingsScreen(activity: MainActivity, modifier: Modifier, onOpenModels: () -> Unit) {
    val g = activity.graph
    val s by g.settings.state.collectAsStateWithLifecycle()
    val modelStatus by g.models.status.collectAsStateWithLifecycle()
    val active = remember(modelStatus, s.selectedModelId) { g.engine.activeModel() }
    val update: ((Settings) -> Settings) -> Unit = { g.settings.update(it) }
    // Changes that alter how the model is built need a reload.
    val updateReload: ((Settings) -> Settings) -> Unit = { g.settings.update(it); g.engine.invalidate() }
    var editingWords by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ScreenTitle("Settings")

        Section("Speech") {
            NavRow("Model", active?.name ?: "None installed", onOpenModels)
            if (active?.supportsLanguageSelection == true) {
                val options = listOf(ModelCatalog.AUTO) + active.selectableLanguages
                ChoiceRow(
                    "Language", s.language, options,
                    label = { if (it == ModelCatalog.AUTO) "Auto-detect" else languageName(it) },
                    onPick = { v -> updateReload { it.copy(language = v) } },
                )
            }
            SwitchRow(
                "Live transcription & silence trimming",
                "Uses voice-activity detection to skip silence and transcribe while you speak, so text is ready the moment you stop.",
                s.vadEnabled,
            ) { v -> update { it.copy(vadEnabled = v) } }
            SwitchRow("Remove filler words", "Drops \"um\", \"uh\", \"hmm\" and similar.", s.fillerWordRemoval) { v ->
                update { it.copy(fillerWordRemoval = v) }
            }
            NavRow(
                "Custom words",
                if (s.customWords.isEmpty()) "Names, brands, jargon the model gets wrong"
                else s.customWords.take(4).joinToString(", ") + if (s.customWords.size > 4) "…" else "",
            ) { editingWords = true }
        }

        Section("Keyboard") {
            ChoiceRow(
                "Mic button", s.micMode, MicMode.entries,
                label = {
                    when (it) {
                        MicMode.HOLD_OR_TAP -> "Hold to talk, or tap for hands-free"
                        MicMode.TAP_TO_TOGGLE -> "Tap to start / stop"
                        MicMode.HOLD_TO_TALK -> "Hold to talk only"
                    }
                },
                onPick = { v -> update { it.copy(micMode = v) } },
            )
            SwitchRow("Start listening when keyboard opens", "Hands-free: open the keyboard and just talk.", s.autoStartOnKeyboardOpen) { v ->
                update { it.copy(autoStartOnKeyboardOpen = v) }
            }
            SwitchRow("Switch back after dictating", "Returns to your regular keyboard after inserting text.", s.switchBackAfterDictation) { v ->
                update { it.copy(switchBackAfterDictation = v) }
            }
            SwitchRow("Auto-send", "Presses Enter / Send after inserting, like Handy's auto-submit.", s.autoSubmit) { v ->
                update { it.copy(autoSubmit = v) }
            }
            SwitchRow("Smart spacing", "Adds a space before the text when needed.", s.smartSpacing) { v -> update { it.copy(smartSpacing = v) } }
            SwitchRow("Trailing space", "Adds a space after each dictation.", s.appendTrailingSpace) { v -> update { it.copy(appendTrailingSpace = v) } }
            SwitchRow("Haptic feedback", null, s.hapticFeedback) { v -> update { it.copy(hapticFeedback = v) } }
            SwitchRow("Start / stop sounds", null, s.audioFeedback) { v -> update { it.copy(audioFeedback = v) } }
            SwitchRow("Pause media while dictating", "Pauses music and videos while the mic is on.", s.pauseMedia) { v -> update { it.copy(pauseMedia = v) } }
        }

        Section("Performance") {
            ChoiceRow(
                "Keep model in memory", s.keepModelLoadedMinutes, listOf(0, 1, 3, 10, 30, -1),
                label = {
                    when (it) {
                        0 -> "Unload right after use (saves memory)"
                        -1 -> "Always (fastest, uses the most memory)"
                        1 -> "1 minute after use"
                        else -> "$it minutes after use"
                    }
                },
                onPick = { v -> update { it.copy(keepModelLoadedMinutes = v) }; g.engine.scheduleUnload() },
            )
            val auto = DeviceProfile.recommendedThreads()
            ChoiceRow(
                "CPU threads", s.threads, listOf(0, 1, 2, 3, 4, 6, 8),
                label = { if (it == 0) "Auto ($auto performance cores)" else "$it" },
                onPick = { v -> updateReload { it.copy(threads = v) } },
            )
        }

        Section("History") {
            ChoiceRow(
                "Keep", s.historyLimit, listOf(0, 25, 100, 500, -1),
                label = {
                    when (it) { 0 -> "Don't save history"; -1 -> "Everything"; else -> "Last $it" }
                },
                onPick = { v -> update { it.copy(historyLimit = v) }; g.history.applyLimit(v) },
            )
            SwitchRow(
                "Save recordings",
                "Keeps the audio so you can replay or re-transcribe. Using ${formatBytes(remember(s) { g.history.totalAudioBytes() })}.",
                s.saveRecordings,
            ) { v -> update { it.copy(saveRecordings = v) } }
            NavRow("Clear history", null) { confirmClear = true }
        }

        Section("Appearance") {
            ChoiceRow(
                "Theme", s.theme, ThemeMode.entries,
                label = { when (it) { ThemeMode.SYSTEM -> "System default"; ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark" } },
                onPick = { v -> update { it.copy(theme = v) } },
            )
        }

        Section("About") {
            NavRow("Handy for Android", "Version ${BuildConfig.VERSION_NAME}", null)
            NavRow("Based on Handy by CJ Pais", "The free, open source, offline speech-to-text app · MIT license") {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/cjpais/Handy")))
            }
            NavRow("Speech engine", "sherpa-onnx by k2-fsa + ONNX Runtime · Apache-2.0") {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/k2-fsa/sherpa-onnx")))
            }
            NavRow("Privacy", "Audio never leaves your phone. The only network use is downloading models.", null)
            NavRow("Run setup again", null) { update { it.copy(onboardingCompleted = false) } }
        }
        Spacer(Modifier.padding(16.dp))
    }

    if (editingWords) {
        var text by remember { mutableStateOf(s.customWords.joinToString("\n")) }
        AlertDialog(
            onDismissRequest = { editingWords = false },
            title = { Text("Custom words") },
            text = {
                Column {
                    Text(
                        "One per line. Handy corrects close-sounding words in transcripts to these spellings.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.padding(4.dp))
                    OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                        placeholder = { Text("Kubernetes\nChargeBee\nAnanya") },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val words = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                    update { it.copy(customWords = words) }
                    editingWords = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingWords = false }) { Text("Cancel") } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("Deletes all transcriptions and saved recordings.") },
            confirmButton = { TextButton(onClick = { g.history.clear(); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

private fun languageName(code: String): String =
    Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault()).replaceFirstChar { it.uppercase() }
        .ifBlank { code } + " ($code)"

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(
        title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp, start = 4.dp),
    )
    SectionCard { content() }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .selectable(selected = checked, role = Role.Switch, onClick = { onChange(!checked) })
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun NavRow(title: String, subtitle: String?, onClick: (() -> Unit)?) {
    Column(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun <T> ChoiceRow(title: String, value: T, options: List<T>, label: (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    NavRow(title, label(value)) { open = true }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(options) { o ->
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(selected = o == value, role = Role.RadioButton, onClick = { onPick(o); open = false })
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RadioButton(selected = o == value, onClick = null)
                            Text(label(o), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
        )
    }
}

