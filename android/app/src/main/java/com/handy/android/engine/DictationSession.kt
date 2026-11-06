package com.handy.android.engine

import android.os.SystemClock
import android.util.Log
import com.handy.android.audio.AudioRecorder
import com.handy.android.audio.PcmBuffer
import com.handy.android.audio.SAMPLE_RATE
import com.k2fsa.sherpa.onnx.Vad
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Result of one dictation. */
data class DictationResult(
    val rawText: String,
    val audio: FloatArray,
    val audioSeconds: Float,
    /** Time from "stop" to text available. The number users actually feel. */
    val finishLatencyMs: Long,
    val modelId: String?,
)

/**
 * One press-to-talk recording.
 *
 * Audio flows mic thread → channel → VAD worker. Each time the VAD closes a
 * speech segment (the speaker paused), that segment goes straight to the
 * engine while recording continues. On [stop] only the tail segment is left
 * to decode, so long dictations finish almost as fast as short ones.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DictationSession(
    private val engine: TranscriptionEngine,
    private val scope: CoroutineScope,
    private val useVad: Boolean,
    private val debugReplay: java.io.File? = null,
) {
    private val pcm = PcmBuffer()
    private val frames = Channel<FloatArray>(Channel.UNLIMITED)
    private val segments = ArrayList<Deferred<String>>()
    private val partial = StringBuilder()

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level
    private val _partialText = MutableStateFlow("")
    /** Text of segments already transcribed while still recording. */
    val partialText: StateFlow<String> = _partialText

    private var recorder: AudioRecorder? = null
    private var vadJob: Job? = null
    private var vad: Vad? = null
    private val vadDone = CompletableDeferred<Unit>()
    private var lastSegmentEnd = 0
    private var startedAt = 0L
    @Volatile private var cancelled = false

    val elapsedMs: Long get() = if (startedAt == 0L) 0 else SystemClock.elapsedRealtime() - startedAt

    /** Starts the microphone. Returns false if the mic could not be opened. */
    fun start(): Boolean {
        val maxChunk = engine.maxChunkSeconds()
        if (useVad) vad = runCatching { engine.newVad(maxChunk) }.onFailure { Log.e(TAG, "VAD init", it) }.getOrNull()
        val rec = AudioRecorder(
            onFrames = { f -> pcm.append(f); frames.trySend(f) },
            onLevel = { _level.value = it },
            debugReplay = debugReplay,
        )
        if (!rec.start()) {
            vad?.release(); vad = null
            return false
        }
        recorder = rec
        startedAt = SystemClock.elapsedRealtime()
        vadJob = scope.launch(Dispatchers.Default) { runVad() }
        return true
    }

    private suspend fun runVad() {
        val v = vad
        try {
            if (v == null) {
                for (f in frames) { /* drain; transcribe everything at stop */ }
                return
            }
            val window = FloatArray(512)
            var filled = 0
            for (f in frames) {
                var i = 0
                while (i < f.size) {
                    val n = minOf(512 - filled, f.size - i)
                    System.arraycopy(f, i, window, filled, n)
                    filled += n; i += n
                    if (filled == 512) {
                        v.acceptWaveform(window.copyOf())
                        filled = 0
                        drainVad(v)
                    }
                }
            }
            if (filled > 0) v.acceptWaveform(window.copyOf(filled))
            v.flush()
            drainVad(v)
        } finally {
            vadDone.complete(Unit)
        }
    }

    private fun drainVad(v: Vad) {
        while (!v.empty()) {
            val seg = v.front()
            v.pop()
            if (cancelled) continue
            // Pad a little on both sides: VAD onsets/offsets clip consonants.
            val pre = (0.25f * SAMPLE_RATE).toInt()
            val post = (0.35f * SAMPLE_RATE).toInt()
            val start = maxOf(lastSegmentEnd, seg.start.toInt() - pre)
            val end = minOf(pcm.size, seg.start.toInt() + seg.samples.size + post)
            if (end - start < SAMPLE_RATE / 5) continue
            lastSegmentEnd = end
            enqueue(pcm.copy(start, end))
        }
    }

    private fun enqueue(chunk: FloatArray) {
        val d = scope.async {
            val text = runCatching { engine.transcribe(chunk) }
                .onFailure { Log.e(TAG, "segment failed", it) }
                .getOrThrow()
                .let(::stripNonSpeech)
            synchronized(partial) {
                if (text.isNotBlank()) {
                    if (partial.isNotEmpty()) partial.append(' ')
                    partial.append(text.trim())
                    _partialText.value = partial.toString()
                }
            }
            text
        }
        synchronized(segments) { segments += d }
    }

    /** Stops recording and returns the full transcript (raw, unprocessed). */
    suspend fun stop(): DictationResult {
        val stopAt = SystemClock.elapsedRealtime()
        withContext(Dispatchers.Default) { recorder?.stop() }
        recorder = null
        frames.close()
        vadDone.await()
        val usedVad = vad != null
        vad?.release(); vad = null

        val audio = pcm.copy()
        // Without VAD, cut the whole recording into model-sized chunks. With
        // VAD, no segments means no speech: return empty instead of letting
        // the model hallucinate words out of silence.
        if (!usedVad) chunkWithoutVad(audio).forEach(::enqueue)
        val parts = synchronized(segments) { segments.toList() }.map { d ->
            runCatching { d.await() }.getOrElse { if (it is NoModelException) throw it; "" }
        }
        val text = com.handy.android.text.TextProcessor.joinSegments(parts)
        return DictationResult(
            rawText = text,
            audio = audio,
            audioSeconds = audio.size / SAMPLE_RATE.toFloat(),
            finishLatencyMs = SystemClock.elapsedRealtime() - stopAt,
            modelId = engine.currentSpec()?.id,
        )
    }

    fun cancel() {
        cancelled = true
        recorder?.stop(); recorder = null
        frames.close()
        scope.launch { vadDone.await(); vad?.release(); vad = null }
    }

    /**
     * Splits audio into chunks no longer than the model allows, cutting at the
     * quietest 100 ms window near each boundary so words aren't sliced.
     */
    private fun chunkWithoutVad(audio: FloatArray): List<FloatArray> {
        val max = (engine.maxChunkSeconds() * SAMPLE_RATE).toInt()
        if (audio.size <= max) return listOf(audio)
        val out = ArrayList<FloatArray>()
        var start = 0
        val win = SAMPLE_RATE / 10
        while (start < audio.size) {
            if (audio.size - start <= max) { out += audio.copyOfRange(start, audio.size); break }
            val searchFrom = start + (max * 0.7).toInt()
            val searchTo = start + max - win
            var bestAt = searchTo
            var bestEnergy = Double.MAX_VALUE
            var p = searchFrom
            while (p < searchTo) {
                var e = 0.0
                for (k in p until p + win) e += audio[k] * audio[k]
                if (e < bestEnergy) { bestEnergy = e; bestAt = p + win / 2 }
                p += win / 2
            }
            out += audio.copyOfRange(start, bestAt)
            start = bestAt
        }
        return out
    }

    companion object {
        private const val TAG = "DictationSession"
        private val NON_SPEECH = Regex("""\[(?:[A-Z_ ]+|blank_audio|music|silence)\]|\((?:music|silence|inaudible|blank audio)\)|<\|[^|>]*\|>""", RegexOption.IGNORE_CASE)

        /** Removes Whisper-style non-speech annotations like [BLANK_AUDIO]. */
        fun stripNonSpeech(text: String): String = NON_SPEECH.replace(text, "").trim()
    }
}
