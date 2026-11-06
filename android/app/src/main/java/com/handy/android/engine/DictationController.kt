package com.handy.android.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import androidx.core.content.ContextCompat
import com.handy.android.history.HistoryStore
import com.handy.android.model.ModelCatalog
import com.handy.android.settings.AppSettings
import com.handy.android.text.TextProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DictationState {
    data object Idle : DictationState
    data class Recording(val partial: String, val handsFree: Boolean) : DictationState
    data class Transcribing(val partial: String) : DictationState
    data class Error(val kind: Kind, val message: String) : DictationState {
        enum class Kind { NO_PERMISSION, NO_MODEL, MIC_UNAVAILABLE, NO_SPEECH, FAILED }
    }
}

data class Transcript(val text: String, val result: DictationResult)

/**
 * Single entry point for dictation, shared by the keyboard and the app UI
 * (only one recording can exist at a time on a phone anyway).
 */
class DictationController(
    private val context: Context,
    private val settings: AppSettings,
    private val engine: TranscriptionEngine,
    private val history: HistoryStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<DictationState>(DictationState.Idle)
    val state: StateFlow<DictationState> = _state.asStateFlow()

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private var session: DictationSession? = null
    private var mirrorJob: Job? = null
    private var focusRequest: AudioFocusRequest? = null
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val tones by lazy { runCatching { ToneGenerator(AudioManager.STREAM_SYSTEM, 55) }.getOrNull() }

    val isRecording get() = _state.value is DictationState.Recording
    val isBusy get() = _state.value is DictationState.Recording || _state.value is DictationState.Transcribing

    fun hasMicPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Begins recording. Returns false (and sets an error state) if it can't. */
    fun start(handsFree: Boolean = false): Boolean {
        if (isBusy) return isRecording
        if (!hasMicPermission()) return fail(DictationState.Error.Kind.NO_PERMISSION, "Microphone permission needed")
        if (engine.activeModel() == null) return fail(DictationState.Error.Kind.NO_MODEL, "Download a speech model first")

        engine.acquire()
        engine.preload()
        val s = settings.current
        if (s.audioFeedback) tones?.startTone(ToneGenerator.TONE_PROP_BEEP, 60)
        if (s.pauseMedia) requestFocus()

        val replay = if (com.handy.android.BuildConfig.DEBUG) java.io.File(context.filesDir, "debug_mic.wav") else null
        val sess = DictationSession(engine, workScope, useVad = s.vadEnabled, debugReplay = replay)
        if (!sess.start()) {
            abandonFocus(); engine.release()
            return fail(DictationState.Error.Kind.MIC_UNAVAILABLE, "Microphone is in use by another app")
        }
        session = sess
        _state.value = DictationState.Recording("", handsFree)
        mirrorJob = scope.launch {
            launch { sess.level.collect { _level.value = it } }
            sess.partialText.collect { p ->
                when (val st = _state.value) {
                    is DictationState.Recording -> _state.value = st.copy(partial = p)
                    is DictationState.Transcribing -> _state.value = st.copy(partial = p)
                    else -> {}
                }
            }
        }
        return true
    }

    fun setHandsFree() {
        (_state.value as? DictationState.Recording)?.let { _state.value = it.copy(handsFree = true) }
    }

    /**
     * Stops and transcribes. Returns the post-processed transcript, or null if
     * nothing was said / it was cancelled.
     */
    suspend fun stop(source: String): Transcript? {
        val sess = session ?: return null
        session = null
        val partial = (state.value as? DictationState.Recording)?.partial.orEmpty()
        _state.value = DictationState.Transcribing(partial)
        _level.value = 0f
        val s = settings.current
        if (s.audioFeedback) tones?.startTone(ToneGenerator.TONE_PROP_ACK, 80)
        abandonFocus()
        return try {
            val result = sess.stop()
            val spec = engine.currentSpec()
            val lang = when {
                spec?.supportsLanguageSelection == true && s.language != ModelCatalog.AUTO -> s.language
                spec?.languages == "English" -> "en"
                else -> null
            }
            val text = TextProcessor.process(
                result.rawText, lang, s.fillerWordRemoval, s.customWords, s.wordCorrectionThreshold,
            )
            Log.i(TAG, "Transcribed %.1fs audio, finished %d ms after stop".format(result.audioSeconds, result.finishLatencyMs))
            if (text.isNotBlank()) {
                history.add(
                    text, result.rawText, result.modelId, result.audioSeconds, result.finishLatencyMs,
                    if (s.saveRecordings) result.audio else null, source, s.historyLimit,
                )
            }
            if (text.isBlank()) {
                fail(DictationState.Error.Kind.NO_SPEECH, "Didn't catch that. Try speaking a little closer.", 2500)
                null
            } else {
                _state.value = DictationState.Idle
                Transcript(text, result)
            }
        } catch (e: NoModelException) {
            fail(DictationState.Error.Kind.NO_MODEL, "Download a speech model first"); null
        } catch (e: Throwable) {
            Log.e(TAG, "Transcription failed", e)
            fail(DictationState.Error.Kind.FAILED, e.message ?: "Transcription failed"); null
        } finally {
            mirrorJob?.cancel()
            engine.release()
        }
    }

    fun cancel() {
        val sess = session ?: return
        session = null
        sess.cancel()
        mirrorJob?.cancel()
        abandonFocus()
        _level.value = 0f
        _state.value = DictationState.Idle
        engine.release()
    }

    fun clearError() {
        if (_state.value is DictationState.Error) _state.value = DictationState.Idle
    }

    private fun fail(kind: DictationState.Error.Kind, msg: String, showMs: Long = 4000): Boolean {
        _state.value = DictationState.Error(kind, msg)
        scope.launch {
            delay(showMs)
            if (_state.value is DictationState.Error) _state.value = DictationState.Idle
        }
        return false
    }

    private fun requestFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        if (audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focusRequest = req
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    companion object { private const val TAG = "Dictation" }
}
