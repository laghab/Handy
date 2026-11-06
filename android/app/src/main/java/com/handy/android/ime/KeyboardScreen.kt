package com.handy.android.ime

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.handy.android.engine.DictationState
import com.handy.android.engine.EngineState
import com.handy.android.graph
import com.handy.android.settings.MicMode
import com.handy.android.ui.theme.HandyTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun KeyboardScreen(ime: HandyImeService) {
    val g = ime.graph
    val settings by g.settings.state.collectAsStateWithLifecycle()
    val state by g.dictation.state.collectAsStateWithLifecycle()
    val engineState by g.engine.state.collectAsStateWithLifecycle()
    val modelStatus by g.models.status.collectAsStateWithLifecycle()
    val level by g.dictation.level.collectAsStateWithLifecycle()
    val view = LocalView.current
    val haptic: (Int) -> Unit = { if (settings.hapticFeedback) view.performHapticFeedback(it) }

    // Re-evaluated whenever install status changes.
    val activeModel = remember(modelStatus, settings.selectedModelId) { g.engine.activeModel() }

    HandyTheme(themeMode = settings.theme) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 12.dp),
            ) {
                StatusRow(
                    state = state,
                    engineState = engineState,
                    hasModel = activeModel != null,
                    modelName = activeModel?.name,
                    micMode = settings.micMode,
                    lastInserted = ime.lastInserted.value,
                    isPassword = ime.isPasswordField.value,
                    onUndo = { haptic(HapticFeedbackConstants.KEYBOARD_TAP); ime.undoLast() },
                    onSettings = { ime.openApp("settings") },
                    onFix = { kind ->
                        when (kind) {
                            DictationState.Error.Kind.NO_PERMISSION -> ime.openApp(requestMic = true)
                            DictationState.Error.Kind.NO_MODEL -> ime.openApp("models")
                            else -> g.dictation.clearError()
                        }
                    },
                    needsPermission = !ime.micGranted.value,
                )

                Row(
                    Modifier.fillMaxWidth().height(132.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        val recording = state is DictationState.Recording
                        AnimatedContent(recording, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "left") { rec ->
                            if (rec) {
                                RoundKey(Icons.Default.Close, "Cancel", tonal = true) {
                                    haptic(HapticFeedbackConstants.KEYBOARD_TAP); ime.cancelDictation()
                                }
                            } else {
                                RoundKey(Icons.Default.Language, "Switch keyboard", onLongPress = { ime.showPicker() }) {
                                    haptic(HapticFeedbackConstants.KEYBOARD_TAP); ime.switchBack()
                                }
                            }
                        }
                    }
                    MicButton(
                        state = state,
                        level = level,
                        mode = settings.micMode,
                        holdThresholdMs = settings.holdThresholdMs,
                        loading = engineState is EngineState.Loading,
                        haptic = haptic,
                        onStart = { ime.startDictation(handsFree = settings.micMode == MicMode.TAP_TO_TOGGLE) },
                        onStop = { ime.finishDictation() },
                        onHandsFree = { g.dictation.setHandsFree() },
                        onCancel = { ime.cancelDictation() },
                    )
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        RepeatKey(
                            onTap = { haptic(HapticFeedbackConstants.KEYBOARD_TAP); ime.backspace() },
                            onRepeat = { fast -> if (fast) ime.deleteWord() else ime.backspace() },
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Backspace, "Backspace", Modifier.size(26.dp))
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().height(52.dp).padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val tap = { s: String -> haptic(HapticFeedbackConstants.KEYBOARD_TAP); ime.type(s) }
                    Key(Modifier.weight(1f), onClick = { tap(",") }) { KeyText(",") }
                    Key(Modifier.weight(1f), onClick = { tap("?") }) { KeyText("?") }
                    Key(Modifier.weight(4f), onClick = { tap(" ") }) {
                        Text(
                            activeModel?.name ?: "space",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    Key(Modifier.weight(1f), onClick = { tap(".") }) { KeyText(".") }
                    Key(
                        Modifier.weight(1.5f),
                        accent = true,
                        onClick = { haptic(HapticFeedbackConstants.KEYBOARD_TAP); ime.enter() },
                    ) {
                        Icon(enterIcon(ime.editorAction.value), "Enter", Modifier.size(22.dp))
                    }
                }
            }
        }
    }
}

private fun enterIcon(a: EnterAction): ImageVector = when (a) {
    EnterAction.SEARCH -> Icons.Default.Search
    EnterAction.SEND -> Icons.AutoMirrored.Filled.Send
    EnterAction.GO, EnterAction.NEXT -> Icons.Default.ArrowForward
    EnterAction.DONE -> Icons.Default.Check
    else -> Icons.AutoMirrored.Filled.KeyboardReturn
}

@Composable
private fun StatusRow(
    state: DictationState,
    engineState: EngineState,
    hasModel: Boolean,
    modelName: String?,
    micMode: MicMode,
    lastInserted: String?,
    isPassword: Boolean,
    needsPermission: Boolean,
    onUndo: () -> Unit,
    onSettings: () -> Unit,
    onFix: (DictationState.Error.Kind) -> Unit,
) {
    var startedAt by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(0L) }
    val recording = state is DictationState.Recording
    LaunchedEffect(recording) {
        if (recording) {
            startedAt = System.currentTimeMillis()
            while (true) { now = System.currentTimeMillis(); delay(200) }
        }
    }

    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).padding(start = 8.dp)) {
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            when {
                state is DictationState.Error -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.message,
                        color = if (state.kind == DictationState.Error.Kind.NO_SPEECH) muted else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium, maxLines = 2, modifier = Modifier.weight(1f, false),
                    )
                    val label = when (state.kind) {
                        DictationState.Error.Kind.NO_PERMISSION -> "Grant"
                        DictationState.Error.Kind.NO_MODEL -> "Get model"
                        DictationState.Error.Kind.NO_SPEECH -> null
                        else -> "OK"
                    }
                    if (label != null) TextButton(onClick = { onFix(state.kind) }) { Text(label) }
                }
                needsPermission -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Handy needs microphone access", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, false))
                    TextButton(onClick = { onFix(DictationState.Error.Kind.NO_PERMISSION) }) { Text("Grant") }
                }
                !hasModel -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("No speech model yet", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, false))
                    TextButton(onClick = { onFix(DictationState.Error.Kind.NO_MODEL) }) { Text("Get model") }
                }
                state is DictationState.Recording -> {
                    val secs = ((now - startedAt).coerceAtLeast(0) / 1000).toInt()
                    val timer = "%d:%02d".format(secs / 60, secs % 60)
                    val hint = if (state.handsFree) "tap mic to finish" else "release to insert · slide to cancel"
                    Column {
                        Text(
                            // Show the newest words: long dictations scroll off the front.
                            state.partial.ifBlank { "Listening…" }.let { if (it.length > 90) "…" + it.takeLast(90).substringAfter(' ') else it },
                            style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        Text("● $timer  ·  $hint", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                state is DictationState.Transcribing -> Column {
                    if (state.partial.isNotBlank()) {
                        Text(state.partial.takeLast(140), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("Transcribing…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                lastInserted != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Inserted", style = MaterialTheme.typography.bodyMedium, color = muted)
                    TextButton(onClick = onUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp)); Text("Undo")
                    }
                }
                engineState is EngineState.Loading -> Text("Loading ${engineState.model.name}…", color = muted, style = MaterialTheme.typography.bodyMedium)
                engineState is EngineState.Failed -> Text(engineState.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                else -> Text(
                    (if (isPassword) "Password field · " else "") + when (micMode) {
                        MicMode.HOLD_OR_TAP -> "Hold to talk, or tap for hands-free"
                        MicMode.TAP_TO_TOGGLE -> "Tap to start, tap again to finish"
                        MicMode.HOLD_TO_TALK -> "Hold the mic while you speak"
                    },
                    color = muted, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                )
            }
        }
        IconTap(Icons.Default.Settings, "Handy settings", onSettings)
    }
}

/**
 * The mic key. Recording starts on touch-down (zero perceived latency for
 * push-to-talk). How it ends depends on [mode]; sliding far sideways while
 * holding cancels, like voice notes in messaging apps.
 */
@Composable
private fun MicButton(
    state: DictationState,
    level: Float,
    mode: MicMode,
    holdThresholdMs: Long,
    loading: Boolean,
    haptic: (Int) -> Unit,
    onStart: () -> Boolean,
    onStop: () -> Unit,
    onHandsFree: () -> Unit,
    onCancel: () -> Unit,
) {
    val recording = state is DictationState.Recording
    val transcribing = state is DictationState.Transcribing
    val currentRecording by rememberUpdatedState(recording)
    val currentBusy by rememberUpdatedState(recording || transcribing)
    var cancelArmed by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val cancelDistancePx = with(density) { 110.dp.toPx() }

    val smoothLevel by animateFloatAsState(if (recording) level else 0f, spring(stiffness = 600f), label = "level")
    val pulse = rememberInfiniteTransition(label = "pulse")
    val breathe by pulse.animateFloat(
        1f, 1.06f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe",
    )
    val colors = MaterialTheme.colorScheme
    val bg by animateColorAsState(
        when {
            cancelArmed -> colors.errorContainer
            recording -> colors.primary
            else -> colors.primaryContainer
        }, label = "bg",
    )
    val fg = when {
        cancelArmed -> colors.onErrorContainer
        recording -> colors.onPrimary
        else -> colors.onPrimaryContainer
    }
    val ring = colors.primary.copy(alpha = 0.22f)

    Box(
        Modifier
            .size(124.dp)
            .semantics { contentDescription = if (recording) "Stop dictation" else "Start dictation" }
            .pointerInput(mode, holdThresholdMs) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (currentBusy) {
                        // A tap while hands-free (or while finishing) ends it.
                        if (currentRecording) { haptic(HapticFeedbackConstants.KEYBOARD_TAP); onStop() }
                        do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
                        return@awaitEachGesture
                    }
                    if (!onStart()) {
                        do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
                        return@awaitEachGesture
                    }
                    haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                    val t0 = down.uptimeMillis
                    var t1 = t0
                    var armed = false
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: e.changes.first()
                        val dx = c.position.x - down.position.x
                        val dy = c.position.y - down.position.y
                        val nowArmed = abs(dx) > cancelDistancePx || dy < -cancelDistancePx
                        if (nowArmed != armed) {
                            armed = nowArmed; cancelArmed = armed
                            if (armed) haptic(HapticFeedbackConstants.LONG_PRESS)
                        }
                        if (!c.pressed) { t1 = c.uptimeMillis; break }
                    }
                    cancelArmed = false
                    val held = t1 - t0
                    when {
                        armed -> { haptic(HapticFeedbackConstants.KEYBOARD_TAP); onCancel() }
                        mode == MicMode.HOLD_TO_TALK -> { haptic(HapticFeedbackConstants.KEYBOARD_TAP); onStop() }
                        mode == MicMode.TAP_TO_TOGGLE -> onHandsFree()
                        held >= holdThresholdMs -> { haptic(HapticFeedbackConstants.KEYBOARD_TAP); onStop() }
                        else -> onHandsFree()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(88.dp)
                .drawBehind {
                    if (recording) {
                        val r = size.minDimension / 2
                        drawCircle(ring, radius = r * (1.08f + 0.32f * smoothLevel))
                        drawCircle(ring, radius = r * (1.0f + 0.16f * smoothLevel))
                    }
                }
                .scale(if (recording && !cancelArmed) breathe else 1f)
                .clip(CircleShape)
                .background(bg),
            contentAlignment = Alignment.Center,
        ) {
            when {
                transcribing || loading && !recording -> CircularProgressIndicator(
                    color = fg, strokeWidth = 3.dp, modifier = Modifier.size(36.dp),
                )
                cancelArmed -> Icon(Icons.Default.Close, null, tint = fg, modifier = Modifier.size(38.dp))
                recording && (state as DictationState.Recording).handsFree ->
                    Icon(Icons.Default.Stop, null, tint = fg, modifier = Modifier.size(38.dp))
                else -> Icon(Icons.Default.Mic, null, tint = fg, modifier = Modifier.size(38.dp))
            }
        }
    }
}

@Composable
private fun RowScope.KeyText(s: String) =
    Text(s, fontSize = 20.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface)

@Composable
private fun Key(
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var pressed by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxSize()
            .graphicsLayer { val s = if (pressed) 0.94f else 1f; scaleX = s; scaleY = s }
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    accent -> colors.secondaryContainer
                    pressed -> colors.surfaceContainerHighest
                    else -> colors.surfaceContainerHigh
                },
            )
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    var up = false
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.changes.none { it.pressed }) { up = true; break }
                    }
                    pressed = false
                    if (up) onClick()
                }
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun RoundKey(
    icon: ImageVector,
    description: String,
    tonal: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(60.dp)
            .clip(CircleShape)
            .background(if (tonal) colors.errorContainer else colors.surfaceContainerHigh)
            .semantics { contentDescription = description }
            .pointerInput(onLongPress) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var longFired = false
                    val longAt = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.first()
                        if (!longFired && onLongPress != null && c.uptimeMillis >= longAt && c.pressed) {
                            longFired = true; onLongPress()
                        }
                        if (!c.pressed) break
                    }
                    if (!longFired) onClick()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (tonal) colors.onErrorContainer else colors.onSurfaceVariant)
    }
}

/** Backspace: tap deletes a char; hold repeats, accelerating to whole words. */
@Composable
private fun RepeatKey(
    onTap: () -> Unit,
    onRepeat: (fast: Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    var pressed by remember { mutableStateOf(false) }
    val view = LocalView.current
    Box(
        Modifier
            .size(width = 76.dp, height = 60.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (pressed) colors.surfaceContainerHighest else colors.surfaceContainerHigh)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    onTap()
                    val job: Job = scope.launch {
                        delay(400)
                        var n = 0
                        while (true) {
                            val fast = n > 12
                            onRepeat(fast)
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK, View.HAPTIC_FEEDBACK_ENABLED)
                            n++
                            delay(if (fast) 160 else 55)
                        }
                    }
                    do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
                    job.cancel()
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun IconTap(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .semantics { contentDescription = description }
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed }); onClick() } },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.Unspecified.takeOrElse(MaterialTheme.colorScheme.onSurfaceVariant))
    }
}

private fun Color.takeOrElse(other: Color) = if (this == Color.Unspecified) other else this
