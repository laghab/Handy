package com.handy.android.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavFile {
    /** Writes 16-bit mono PCM WAV. */
    fun write(file: File, samples: FloatArray, sampleRate: Int = SAMPLE_RATE) {
        val dataBytes = samples.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataBytes); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataBytes)
        }
        BufferedOutputStream(FileOutputStream(file), 64 * 1024).use { out ->
            out.write(header.array())
            val buf = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) {
                if (!buf.hasRemaining()) { out.write(buf.array(), 0, buf.position()); buf.clear() }
                buf.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            }
            out.write(buf.array(), 0, buf.position())
        }
    }

    /** Reads a 16-bit PCM WAV written by [write] (mono, 16 kHz). */
    fun read(file: File): FloatArray {
        RandomAccessFile(file, "r").use { raf ->
            val bytes = ByteArray((raf.length() - 44).toInt().coerceAtLeast(0))
            raf.seek(44); raf.readFully(bytes)
            val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            return FloatArray(sb.remaining()) { sb.get(it) / 32768f }
        }
    }
}

/**
 * Decodes any audio file Android can play (m4a, ogg/opus, mp3, wav, flac, aac,
 * amr, 3gp, video soundtracks…) into 16 kHz mono float PCM using the platform
 * hardware/software codecs.
 */
object AudioDecoder {
    class UnsupportedAudio(msg: String) : Exception(msg)

    fun decode(
        context: Context,
        uri: Uri,
        maxSeconds: Int = 60 * 60,
        onProgress: (Float) -> Unit = {},
    ): FloatArray {
        val extractor = MediaExtractor()
        try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                if (afd.declaredLength < 0) extractor.setDataSource(afd.fileDescriptor)
                else extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.declaredLength)
            } ?: throw UnsupportedAudio("Could not open file")

            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw UnsupportedAudio("No audio track found")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmFloat = false
            val resampler = StreamingResampler(srcRate, SAMPLE_RATE)
            val out = PcmBuffer(60)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val maxSamples = maxSeconds.toLong() * SAMPLE_RATE

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            if (durationUs > 0) onProgress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        srcRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                        resampler.reset(srcRate)
                    }
                    outIdx >= 0 -> {
                        val buf = codec.getOutputBuffer(outIdx)!!.order(ByteOrder.nativeOrder())
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        val mono = toMono(buf, channels, pcmFloat)
                        out.append(resampler.process(mono))
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (out.size >= maxSamples) outputDone = true
                    }
                }
            }
            codec.stop(); codec.release()
            return out.copy()
        } finally {
            extractor.release()
        }
    }

    private fun toMono(buf: ByteBuffer, channels: Int, isFloat: Boolean): FloatArray {
        val ch = channels.coerceAtLeast(1)
        return if (isFloat) {
            val fb = buf.asFloatBuffer()
            val frames = fb.remaining() / ch
            FloatArray(frames) { i -> var s = 0f; for (c in 0 until ch) s += fb.get(i * ch + c); s / ch }
        } else {
            val sb = buf.asShortBuffer()
            val frames = sb.remaining() / ch
            FloatArray(frames) { i -> var s = 0f; for (c in 0 until ch) s += sb.get(i * ch + c); s / (32768f * ch) }
        }
    }
}

/**
 * Linear-interpolation resampler with a one-pole low-pass ahead of it when
 * downsampling. Streaming, so arbitrarily long files don't need to be held
 * at their source rate.
 */
class StreamingResampler(private var srcRate: Int, private val dstRate: Int) {
    private var pos = 0.0
    private var last = 0f
    private var lp = 0f
    private var alpha = alphaFor(srcRate)

    private fun alphaFor(rate: Int): Float {
        if (rate <= dstRate) return 1f
        val cutoff = dstRate * 0.45
        val rc = 1.0 / (2 * Math.PI * cutoff)
        val dt = 1.0 / rate
        return (dt / (rc + dt)).toFloat()
    }

    fun reset(newSrcRate: Int) {
        srcRate = newSrcRate; alpha = alphaFor(newSrcRate); pos = 0.0
    }

    fun process(input: FloatArray): FloatArray {
        if (srcRate == dstRate) return input
        val step = srcRate.toDouble() / dstRate
        val out = FloatArray(((input.size - pos) / step).toInt().coerceAtLeast(0) + 1)
        var n = 0
        // Filter in place (one-pole LPF) to tame aliasing.
        val filtered = FloatArray(input.size)
        for (i in input.indices) { lp += alpha * (input[i] - lp); filtered[i] = lp }
        while (pos < filtered.size) {
            val i = pos.toInt()
            val frac = (pos - i).toFloat()
            val a = if (i == 0) last else filtered[i - 1]
            val b = filtered[i]
            // pos is measured so that index 0 sits between `last` and filtered[0].
            if (n < out.size) out[n++] = a + (b - a) * frac
            pos += step
        }
        pos -= filtered.size
        if (filtered.isNotEmpty()) last = filtered.last()
        return if (n == out.size) out else out.copyOf(n)
    }
}
