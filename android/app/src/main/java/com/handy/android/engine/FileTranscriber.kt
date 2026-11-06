package com.handy.android.engine

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import com.handy.android.audio.AudioDecoder
import com.handy.android.audio.SAMPLE_RATE
import com.handy.android.history.HistoryStore
import com.handy.android.model.ModelCatalog
import com.handy.android.settings.AppSettings
import com.handy.android.text.TextProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

sealed interface FileJobState {
    data object Idle : FileJobState
    data class Working(val fileName: String, val stage: String, val progress: Float, val partial: String) : FileJobState
    data class Done(val fileName: String, val text: String, val audioSeconds: Float, val elapsedMs: Long) : FileJobState
    data class Failed(val fileName: String, val message: String) : FileJobState
}

/** Transcribes audio files (shared voice notes, recordings, videos). */
class FileTranscriber(
    private val context: Context,
    private val settings: AppSettings,
    private val engine: TranscriptionEngine,
    private val history: HistoryStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val _state = MutableStateFlow<FileJobState>(FileJobState.Idle)
    val state: StateFlow<FileJobState> = _state.asStateFlow()

    fun transcribe(uri: Uri) {
        job?.cancel()
        val name = displayName(uri)
        job = scope.launch {
            engine.acquire()
            val t0 = SystemClock.elapsedRealtime()
            try {
                if (engine.activeModel() == null) throw NoModelException()
                engine.preload()
                _state.value = FileJobState.Working(name, "Decoding audio", 0f, "")
                val audio = AudioDecoder.decode(context, uri) { p ->
                    _state.value = FileJobState.Working(name, "Decoding audio", p * 0.15f, "")
                }
                if (audio.isEmpty()) throw IllegalStateException("The file contains no audio")
                val text = transcribeSamples(audio) { p, partial ->
                    _state.value = FileJobState.Working(name, "Transcribing", 0.15f + 0.85f * p, partial)
                }
                val secs = audio.size / SAMPLE_RATE.toFloat()
                val elapsed = SystemClock.elapsedRealtime() - t0
                if (text.isNotBlank()) {
                    history.add(text, text, engine.currentSpec()?.id, secs, elapsed, null, "file", settings.current.historyLimit)
                }
                _state.value = FileJobState.Done(name, text, secs, elapsed)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = FileJobState.Idle
                throw e
            } catch (e: NoModelException) {
                _state.value = FileJobState.Failed(name, "Download a speech model first")
            } catch (e: Throwable) {
                _state.value = FileJobState.Failed(name, e.message ?: "Could not transcribe this file")
            } finally {
                engine.release()
            }
        }
    }

    /** VAD-segments [audio] and transcribes each piece. Also used to re-run history items. */
    suspend fun transcribeSamples(audio: FloatArray, onProgress: (Float, String) -> Unit = { _, _ -> }): String {
        val s = settings.current
        val maxChunk = engine.maxChunkSeconds()
        val chunks = ArrayList<FloatArray>()
        val vad = engine.newVad(maxChunk)
        try {
            var lastEnd = 0
            val pre = SAMPLE_RATE / 4
            val post = (SAMPLE_RATE * 0.35f).toInt()
            fun drain() {
                while (!vad.empty()) {
                    val seg = vad.front(); vad.pop()
                    val start = maxOf(lastEnd, seg.start.toInt() - pre)
                    val end = minOf(audio.size, seg.start.toInt() + seg.samples.size + post)
                    if (end - start >= SAMPLE_RATE / 5) { chunks += audio.copyOfRange(start, end); lastEnd = end }
                }
            }
            var i = 0
            while (i + 512 <= audio.size) {
                vad.acceptWaveform(audio.copyOfRange(i, i + 512)); i += 512
                drain()
            }
            if (i < audio.size) vad.acceptWaveform(audio.copyOfRange(i, audio.size))
            vad.flush(); drain()
        } finally {
            vad.release()
        }
        val parts = ArrayList<String>()
        val totalSamples = chunks.sumOf { it.size }.coerceAtLeast(1)
        var done = 0
        for (c in chunks) {
            coroutineContext.ensureActive()
            parts += DictationSession.stripNonSpeech(engine.transcribe(c))
            done += c.size
            onProgress(done.toFloat() / totalSamples, TextProcessor.joinSegments(parts))
        }
        val spec = engine.currentSpec()
        val lang = if (spec?.supportsLanguageSelection == true && s.language != ModelCatalog.AUTO) s.language
        else if (spec?.languages == "English") "en" else null
        return TextProcessor.process(
            TextProcessor.joinSegments(parts), lang, s.fillerWordRemoval, s.customWords, s.wordCorrectionThreshold,
        )
    }

    fun cancel() { job?.cancel(); _state.value = FileJobState.Idle }
    fun dismiss() { if (_state.value !is FileJobState.Working) _state.value = FileJobState.Idle }

    private fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "Audio file"
}
