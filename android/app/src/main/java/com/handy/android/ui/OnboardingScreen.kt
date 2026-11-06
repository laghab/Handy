package com.handy.android.ui

import android.Manifest
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.handy.android.R
import com.handy.android.graph
import com.handy.android.model.ModelStatus

@Composable
fun OnboardingScreen(activity: MainActivity) {
    val g = activity.graph
    var step by rememberSaveable { mutableIntStateOf(0) }
    val tick by activity.refreshTick
    val status by g.models.status.collectAsStateWithLifecycle()
    val mic = remember(tick) { activity.hasMic() }
    val enabled = remember(tick) { KeyboardStatus.isEnabled(activity) }
    val selected = remember(tick) { KeyboardStatus.isSelected(activity) }
    val anyModel = status.values.any { it is ModelStatus.Installed || it is ModelStatus.Downloading || it is ModelStatus.Extracting }
    val finish = { g.settings.update { it.copy(onboardingCompleted = true) } }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp)) {
            // Progress dots
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                repeat(5) { i ->
                    Box(
                        Modifier.height(4.dp).width(if (i == step) 28.dp else 12.dp).clip(CircleShape)
                            .background(if (i <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (step < 4) TextButton(onClick = finish) { Text("Skip") }
            }
            AnimatedContent(
                step, modifier = Modifier.weight(1f),
                transitionSpec = { slideInHorizontally { it / 3 } togetherWith slideOutHorizontally { -it / 3 } }, label = "step",
            ) { s ->
                when (s) {
                    0 -> StepPage(
                        hero = { Image(painterResource(R.mipmap.ic_launcher_foreground), null, Modifier.size(180.dp)) },
                        title = "Handy",
                        body = "Speak, and your words appear in any app.\n\nFree, open source, and private: speech is turned into text right on your phone. No account, no cloud, works offline.",
                    ) { Button(onClick = { step = 1 }, Modifier.fillMaxWidth().height(54.dp)) { Text("Get started") } }

                    1 -> {
                        LaunchedEffect(mic) { if (mic) step = 2 }
                        StepPage(
                            hero = { HeroIcon { Icon(Icons.Default.Mic, null, Modifier.size(64.dp)) } },
                            title = "Microphone",
                            body = "Handy listens only while you hold or tap the mic button. Audio is processed on this device and never uploaded.",
                        ) {
                            Button(onClick = { activity.micPermission.launch(Manifest.permission.RECORD_AUDIO) }, Modifier.fillMaxWidth().height(54.dp)) {
                                Text("Allow microphone")
                            }
                        }
                    }

                    2 -> Column {
                        Text("Pick a speech model", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Download one to get going; you can add others any time. The recommended model is chosen for this phone.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.weight(1f)) { ModelsScreen(activity, Modifier, compact = true) }
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { step = 3 }, enabled = anyModel, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                            Text(if (anyModel) "Continue" else "Download a model to continue")
                        }
                    }

                    3 -> StepPage(
                        hero = { HeroIcon { Icon(Icons.Default.Keyboard, null, Modifier.size(64.dp)) } },
                        title = "Turn on the Handy keyboard",
                        body = "Handy works as a keyboard, so it can type into any app. Android will show a standard warning that keyboards can see what you type. Handy has no internet access except to download models, and stores nothing off your phone.",
                    ) {
                        CheckLine("Enable Handy in keyboard settings", enabled)
                        CheckLine("Switch to the Handy keyboard", selected)
                        Spacer(Modifier.height(12.dp))
                        when {
                            !enabled -> Button(onClick = { KeyboardStatus.openEnableSettings(activity) }, Modifier.fillMaxWidth().height(54.dp)) { Text("Open keyboard settings") }
                            !selected -> Button(onClick = { KeyboardStatus.showPicker(activity) }, Modifier.fillMaxWidth().height(54.dp)) { Text("Choose Handy keyboard") }
                            else -> Button(onClick = { step = 4 }, Modifier.fillMaxWidth().height(54.dp)) { Text("Continue") }
                        }
                        TextButton(onClick = { step = 4 }, Modifier.fillMaxWidth()) { Text("I'll do this later") }
                    }

                    else -> {
                        var text by rememberSaveable { mutableStateOf("") }
                        StepPage(
                            hero = { HeroIcon { Icon(Icons.Default.CheckCircle, null, Modifier.size(64.dp)) } },
                            title = "You're all set",
                            body = "Tap below, then hold the pink mic and speak. Let go and your words appear. Tap the mic instead to go hands-free.",
                        ) {
                            OutlinedTextField(
                                value = text, onValueChange = { text = it },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                                placeholder = { Text("Try it here") },
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = finish, Modifier.fillMaxWidth().height(54.dp)) { Text("Done") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepPage(
    hero: @Composable () -> Unit,
    title: String,
    body: String,
    actions: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(0.6f))
        hero()
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        actions()
    }
}

@Composable
private fun HeroIcon(content: @Composable () -> Unit) {
    Box(
        Modifier.size(132.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun CheckLine(text: String, done: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Default.CheckCircle, null,
            tint = if (done) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outlineVariant,
        )
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

