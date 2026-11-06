package com.handy.android.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import kotlin.math.sqrt

const val SAMPLE_RATE = 16_000

/** Thread-safe, growable mono float PCM buffer at [SAMPLE_RATE]. */
class PcmBuffer(initialSeconds: Int = 30) {
    private var data = FloatArray(SAMPLE_RATE * initialSeconds)
    private var length = 0

    val size: Int @Synchronized get() = length

    @Synchronized
    fun append(src: FloatArray, count: Int = src.size) {
        if (length + count > data.size) {
            var cap = data.size * 2
            while (cap < length + count) cap *= 2
            data = data.copyOf(cap)
        }
        System.arraycopy(src, 0, data, length, count)
        length += count
    }

    @Synchronized
    fun copy(from: Int = 0, to: Int = length): FloatArray {
        val a = from.coerceIn(0, length)
        val b = to.coerceIn(a, length)
        return data.copyOfRange(a, b)
    }
}

/**
 * Captures 16 kHz mono audio from the microphone on a dedicated
 * high-priority thread and hands out float frames.
 *
 * Uses [MediaRecorder.AudioSource.VOICE_RECOGNITION], which on most devices
 * skips the aggressive AGC / noise processing tuned for calls: ASR models
 * are trained on (and prefer) clean, unprocessed audio.
 */
class AudioRecorder(
    private val onFrames: (FloatArray) -> Unit,
    private val onLevel: (Float) -> Unit,
    /** Debug builds only: replay this 16 kHz WAV in real time instead of using the mic. */
    private val debugReplay: java.io.File? = null,
) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    /** Native capture rate actually in use (16 kHz, or 48 kHz with 3:1 decimation). */
    var captureRate = SAMPLE_RATE
        private set

    @SuppressLint("MissingPermission") // Callers check RECORD_AUDIO first.
    fun start(): Boolean {
        if (running) return true
        if (com.handy.android.BuildConfig.DEBUG && debugReplay?.isFile == true) return startReplay(debugReplay)
        val rec = open(SAMPLE_RATE) ?: open(48_000) ?: return false
        record = rec
        captureRate = rec.sampleRate
        try {
            rec.startRecording()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "startRecording failed", e)
            rec.release(); record = null
            return false
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release(); record = null
            return false
        }
        running = true
        thread = Thread({ loop(rec) }, "handy-mic").also { it.start() }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun open(rate: Int): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null
        return try {
            val r = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, rate / 5 * 2), // >= 200 ms of headroom
            )
            if (r.state == AudioRecord.STATE_INITIALIZED) r else { r.release(); null }
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord @$rate failed", e)
            null
        }
    }

    private fun loop(rec: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val decimate = rec.sampleRate / SAMPLE_RATE // 1 or 3
        // ~32 ms per read at 16 kHz: low latency for the level meter and VAD.
        val shorts = ShortArray(512 * decimate)
        var levelAcc = 0.0
        var levelN = 0
        while (running) {
            val n = rec.read(shorts, 0, shorts.size)
            if (n <= 0) {
                if (n == AudioRecord.ERROR_DEAD_OBJECT) break
                continue
            }
            val outN = n / decimate
            val frame = FloatArray(outN)
            if (decimate == 1) {
                for (i in 0 until outN) frame[i] = shorts[i] / 32768f
            } else {
                // Box-filter decimation; adequate anti-aliasing for speech.
                for (i in 0 until outN) {
                    var s = 0
                    val base = i * decimate
                    for (k in 0 until decimate) s += shorts[base + k]
                    frame[i] = s / (32768f * decimate)
                }
            }
            for (v in frame) levelAcc += (v * v).toDouble()
            levelN += outN
            onFrames(frame)
            if (levelN >= 512) {
                val rms = sqrt(levelAcc / levelN).toFloat()
                // Map roughly -60..-10 dBFS onto 0..1 for the UI.
                val db = 20f * kotlin.math.log10(rms.coerceAtLeast(1e-6f))
                onLevel(((db + 60f) / 50f).coerceIn(0f, 1f))
                levelAcc = 0.0; levelN = 0
            }
        }
    }

    private fun startReplay(file: java.io.File): Boolean {
        val samples = WavFile.read(file)
        running = true
        thread = Thread({
            var i = 0
            while (running) {
                val frame = if (i < samples.size) samples.copyOfRange(i, minOf(i + 512, samples.size)) else FloatArray(512)
                i += 512
                onFrames(frame)
                var acc = 0.0; for (v in frame) acc += (v * v).toDouble()
                val db = 20f * kotlin.math.log10(sqrt(acc / frame.size).toFloat().coerceAtLeast(1e-6f))
                onLevel(((db + 60f) / 50f).coerceIn(0f, 1f))
                Thread.sleep(32)
            }
        }, "handy-mic-replay").also { it.start() }
        Log.w(TAG, "DEBUG: replaying ${file.name} instead of the microphone")
        return true
    }

    /** Stops capture and waits for the last frames to be delivered. */
    fun stop() {
        if (!running && record == null) return
        running = false
        try { record?.stop() } catch (_: IllegalStateException) {}
        thread?.join(500)
        thread = null
        record?.release()
        record = null
    }

    companion object { private const val TAG = "AudioRecorder" }
}
