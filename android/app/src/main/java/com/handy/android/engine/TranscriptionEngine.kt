package com.handy.android.engine

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.handy.android.audio.SAMPLE_RATE
import com.handy.android.model.ModelCatalog
import com.handy.android.model.ModelManager
import com.handy.android.model.ModelSpec
import com.handy.android.settings.AppSettings
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

sealed interface EngineState {
    data object Unloaded : EngineState
    data class Loading(val model: ModelSpec) : EngineState
    data class Ready(val model: ModelSpec, val loadMs: Long) : EngineState
    data class Failed(val model: ModelSpec?, val message: String) : EngineState
}

class NoModelException : Exception("No speech model installed yet")

/**
 * Owns the loaded speech recognizer.
 *
 * - All native calls run on one dedicated thread, so loading, decoding and
 *   unloading can never race, and the UI thread is never blocked.
 * - The model is preloaded as soon as the keyboard opens, kept warm while in
 *   use, and unloaded after an idle timeout or when the system is low on
 *   memory, because big models hold hundreds of MB.
 */
class TranscriptionEngine(
    private val context: Context,
    private val settings: AppSettings,
    private val models: ModelManager,
) {
    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
            r.run()
        }, "handy-engine")
    }.asCoroutineDispatcher()

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _state = MutableStateFlow<EngineState>(EngineState.Unloaded)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private var recognizer: OfflineRecognizer? = null
    private var loadedKey: String? = null
    private var loadedSpec: ModelSpec? = null

    private val activeUsers = AtomicInteger(0)
    private var unloadJob: Job? = null

    /** The model the user picked, if it's installed (falls back to any installed one). */
    fun activeModel(): ModelSpec? {
        val chosen = ModelCatalog.byId(settings.current.selectedModelId)
        if (chosen != null && models.isInstalled(chosen)) return chosen
        return ModelCatalog.all.firstOrNull { models.isInstalled(it) }
    }

    private fun keyFor(spec: ModelSpec): String {
        val s = settings.current
        val lang = if (spec.supportsLanguageSelection) s.language else ModelCatalog.AUTO
        return "${spec.id}|$lang|${threads()}"
    }

    private fun threads(): Int = settings.current.threads.takeIf { it > 0 } ?: DeviceProfile.recommendedThreads()

    /** Warm the model in the background (e.g. when the keyboard appears). */
    fun preload() {
        cancelUnload()
        scope.launch { runCatching { ensureLoadedLocked() } }
    }

    /** Must be called on [dispatcher]. */
    private fun ensureLoadedLocked(): OfflineRecognizer {
        val spec = activeModel() ?: throw NoModelException()
        val key = keyFor(spec)
        recognizer?.let { if (key == loadedKey) return it }

        releaseLocked()
        _state.value = EngineState.Loading(spec)
        val t0 = SystemClock.elapsedRealtime()
        val dir = models.dirFor(spec)
        val lang = if (spec.supportsLanguageSelection) settings.current.language else ModelCatalog.AUTO
        val modelConfig = spec.buildConfig(dir, lang).apply {
            numThreads = threads()
            provider = "cpu"
            debug = false
        }
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = modelConfig,
            decodingMethod = "greedy_search",
        )
        try {
            val r = OfflineRecognizer(assetManager = null, config = config)
            recognizer = r
            loadedKey = key
            loadedSpec = spec
            val ms = SystemClock.elapsedRealtime() - t0
            Log.i(TAG, "Loaded ${spec.id} in ${ms}ms with ${modelConfig.numThreads} threads")
            _state.value = EngineState.Ready(spec, ms)
            return r
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load ${spec.id}", t)
            _state.value = EngineState.Failed(spec, t.message ?: "Could not load model")
            throw t
        }
    }

    private fun releaseLocked() {
        recognizer?.release()
        recognizer = null
        loadedKey = null
        loadedSpec = null
        if (_state.value !is EngineState.Failed) _state.value = EngineState.Unloaded
    }

    /** Transcribes one chunk of 16 kHz mono audio. Returns raw model text. */
    suspend fun transcribe(samples: FloatArray): String = withContext(dispatcher) {
        if (samples.size < SAMPLE_RATE / 10) return@withContext ""
        val r = ensureLoadedLocked()
        val stream = r.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            r.decode(stream)
            r.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    /** Max chunk length for the active model. */
    fun maxChunkSeconds(): Float = (loadedSpec ?: activeModel())?.maxChunkSeconds ?: 28f

    fun currentSpec(): ModelSpec? = loadedSpec ?: activeModel()

    /**
     * A Silero VAD instance tuned for dictation. VAD runs on the caller's
     * thread (it's ~0.1 ms per 32 ms window), so each session gets its own.
     */
    fun newVad(maxSpeechSeconds: Float): Vad = Vad(
        assetManager = context.assets,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "silero_vad.onnx",
                threshold = 0.45f,
                // Natural pauses mid-sentence shouldn't split a thought.
                minSilenceDuration = 0.6f,
                minSpeechDuration = 0.2f,
                windowSize = 512,
                maxSpeechDuration = maxSpeechSeconds,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
            provider = "cpu",
        ),
    )

    // ----------------------------------------------------------------- lifecycle

    /** Marks the start of a dictation; keeps the model from unloading. */
    fun acquire() {
        activeUsers.incrementAndGet()
        cancelUnload()
    }

    fun release() {
        if (activeUsers.decrementAndGet() <= 0) {
            activeUsers.set(0)
            scheduleUnload()
        }
    }

    /** Called when the keyboard hides or the app goes to background. */
    fun scheduleUnload() {
        cancelUnload()
        val minutes = settings.current.keepModelLoadedMinutes
        if (minutes < 0) return // keep forever
        unloadJob = scope.launch {
            delay(minutes * 60_000L)
            if (activeUsers.get() == 0) releaseLocked()
        }
    }

    private fun cancelUnload() {
        unloadJob?.cancel(); unloadJob = null
    }

    fun onTrimMemory(level: Int) {
        // TRIM_MEMORY_BACKGROUND (40) and up: we're a likely kill candidate,
        // and a loaded model is most of our footprint.
        if (level >= 40 && activeUsers.get() == 0) scope.launch { releaseLocked() }
    }

    /** Drop the current model (e.g. it was deleted, or settings changed). */
    fun invalidate() {
        scope.launch { releaseLocked(); if (_state.value is EngineState.Failed) _state.value = EngineState.Unloaded }
    }

    companion object { private const val TAG = "HandyEngine" }
}
