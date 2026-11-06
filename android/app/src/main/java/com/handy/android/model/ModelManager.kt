package com.handy.android.model

import android.content.Context
import android.content.Intent
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

sealed interface ModelStatus {
    data object NotInstalled : ModelStatus
    data class Downloading(val downloaded: Long, val total: Long, val bytesPerSec: Long) : ModelStatus {
        val fraction get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
    }
    data class Extracting(val fraction: Float) : ModelStatus
    data object Installed : ModelStatus
    data class Failed(val message: String) : ModelStatus
}

class ModelManager(private val context: Context) {
    private val root = File(context.noBackupFilesDir, "models").apply { mkdirs() }
    private val partials = File(context.noBackupFilesDir, "model-downloads").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val _status = MutableStateFlow(ModelCatalog.all.associate { it.id to initialStatus(it) })
    val status: StateFlow<Map<String, ModelStatus>> = _status.asStateFlow()

    /** Notified after a model finishes installing or is removed. */
    var onModelsChanged: (() -> Unit)? = null

    fun dirFor(spec: ModelSpec) = File(root, spec.id)

    fun isInstalled(spec: ModelSpec): Boolean {
        val dir = dirFor(spec)
        return File(dir, MARKER).exists() && spec.requiredFiles.all { File(dir, it).isFile }
    }

    private fun initialStatus(spec: ModelSpec): ModelStatus =
        if (isInstalled(spec)) ModelStatus.Installed else ModelStatus.NotInstalled

    fun installed(): List<ModelSpec> = ModelCatalog.all.filter { isInstalled(it) }

    val hasActiveDownloads: Boolean get() = jobs.isNotEmpty()

    private fun set(id: String, s: ModelStatus) = _status.update { it + (id to s) }

    /** Bytes free on the internal storage volume holding models. */
    fun freeBytes(): Long = StatFs(root.path).availableBytes

    /** Starts (or resumes) a download in the foreground service. */
    fun download(spec: ModelSpec) {
        if (jobs.containsKey(spec.id) || isInstalled(spec)) return
        val needed = spec.source.downloadBytes + spec.installedBytes - partialBytes(spec)
        if (freeBytes() < needed + 200L * 1024 * 1024) {
            set(spec.id, ModelStatus.Failed("Not enough storage: needs ${formatBytes(needed)} free"))
            return
        }
        set(spec.id, ModelStatus.Downloading(partialBytes(spec), spec.source.downloadBytes, 0))
        val job = scope.launch {
            try {
                install(spec)
                set(spec.id, ModelStatus.Installed)
                onModelsChanged?.invoke()
            } catch (e: CancellationException) {
                set(spec.id, ModelStatus.NotInstalled)
            } catch (e: Exception) {
                Log.e(TAG, "Install ${spec.id} failed", e)
                set(spec.id, ModelStatus.Failed(friendly(e)))
            } finally {
                jobs.remove(spec.id)
            }
        }
        jobs[spec.id] = job
        ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
    }

    fun cancel(spec: ModelSpec) {
        jobs[spec.id]?.cancel()
    }

    fun delete(spec: ModelSpec) {
        cancel(spec)
        dirFor(spec).deleteRecursively()
        File(partials, spec.id).deleteRecursively()
        set(spec.id, ModelStatus.NotInstalled)
        onModelsChanged?.invoke()
    }

    fun clearFailure(spec: ModelSpec) {
        if (_status.value[spec.id] is ModelStatus.Failed) set(spec.id, initialStatus(spec))
    }

    private fun partialBytes(spec: ModelSpec): Long =
        File(partials, spec.id).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // ------------------------------------------------------------------ install

    private suspend fun install(spec: ModelSpec) {
        val work = File(partials, spec.id).apply { mkdirs() }
        val total = spec.source.downloadBytes
        when (val src = spec.source) {
            is ModelSource.Files -> {
                var doneBefore = 0L
                for (f in src.files) {
                    val dst = File(work, f.name)
                    if (!(dst.isFile && dst.length() == f.sizeBytes)) {
                        fetch(spec, f.url, dst, f.sizeBytes, doneBefore, total)
                    }
                    doneBefore += f.sizeBytes
                }
                finalize(spec, work)
            }
            is ModelSource.Archive -> {
                val archive = File(work, "model.tar.bz2")
                if (!(archive.isFile && archive.length() == src.downloadBytes)) {
                    fetch(spec, src.url, archive, src.downloadBytes, 0, total)
                }
                val out = File(work, "extract").apply { deleteRecursively(); mkdirs() }
                extract(spec, archive, src.rootDir, out)
                archive.delete()
                finalize(spec, out)
                work.deleteRecursively()
            }
        }
    }

    private fun finalize(spec: ModelSpec, from: File) {
        for (name in spec.requiredFiles) {
            if (!File(from, name).isFile) throw IOException("Model is missing $name")
        }
        val dir = dirFor(spec)
        dir.deleteRecursively()
        if (!from.renameTo(dir)) {
            from.copyRecursively(dir, overwrite = true)
            from.deleteRecursively()
        }
        File(dir, MARKER).writeText(spec.id)
        File(partials, spec.id).deleteRecursively()
    }

    /** Resumable HTTP download into [dst] using a Range request. */
    private suspend fun fetch(spec: ModelSpec, url: String, dst: File, expected: Long, base: Long, total: Long) {
        val part = File(dst.path + ".part")
        var attempt = 0
        while (true) {
            coroutineContext.ensureActive()
            val have = if (part.exists()) part.length() else 0L
            if (have == expected) break
            if (have > expected) part.delete()
            try {
                downloadOnce(spec, url, part, have, base, total)
                if (part.length() == expected) break
                if (part.length() > expected) { part.delete(); throw IOException("Unexpected file size") }
            } catch (e: IOException) {
                if (++attempt >= 6) throw e
                Log.w(TAG, "Download interrupted (attempt $attempt), resuming", e)
                kotlinx.coroutines.delay(1000L * attempt)
            }
        }
        if (!part.renameTo(dst)) throw IOException("Could not save ${dst.name}")
    }

    private suspend fun downloadOnce(spec: ModelSpec, url: String, part: File, offset: Long, base: Long, total: Long) {
        val req = Request.Builder().url(url).apply {
            if (offset > 0) header("Range", "bytes=$offset-")
            header("User-Agent", "HandyAndroid/1.0")
        }.build()
        val call = http.newCall(req)
        val job = coroutineContext[Job]
        val handle = job?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val append = offset > 0 && resp.code == 206
                val body = resp.body ?: throw IOException("Empty response")
                var written = if (append) offset else 0L
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(256 * 1024)
                    val input = body.byteStream()
                    var lastEmit = 0L
                    var windowStart = SystemClock.elapsedRealtime()
                    var windowBytes = 0L
                    var speed = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n; windowBytes += n
                        val now = SystemClock.elapsedRealtime()
                        if (now - windowStart >= 1000) {
                            speed = windowBytes * 1000 / (now - windowStart)
                            windowStart = now; windowBytes = 0
                        }
                        if (now - lastEmit > 250) {
                            lastEmit = now
                            set(spec.id, ModelStatus.Downloading(base + written, total, speed))
                        }
                    }
                }
            }
        } finally {
            handle?.dispose()
        }
    }

    private suspend fun extract(spec: ModelSpec, archive: File, rootDir: String, out: File) {
        val wanted = spec.requiredFiles.toSet() + "LICENSE"
        val size = archive.length().toFloat()
        val counting = CountingInputStream(FileInputStream(archive))
        set(spec.id, ModelStatus.Extracting(0f))
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(counting, 1 shl 20))).use { tar ->
            var entry = tar.nextEntry
            val buf = ByteArray(256 * 1024)
            var lastEmit = 0L
            while (entry != null) {
                coroutineContext.ensureActive()
                val name = entry.name.removePrefix("./").removePrefix("$rootDir/")
                if (!entry.isDirectory && name in wanted) {
                    FileOutputStream(File(out, name)).use { o ->
                        while (true) {
                            val n = tar.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastEmit > 250) {
                                lastEmit = now
                                set(spec.id, ModelStatus.Extracting(counting.count / size))
                            }
                        }
                    }
                }
                entry = tar.nextEntry
            }
        }
    }

    private class CountingInputStream(private val inner: InputStream) : InputStream() {
        @Volatile var count = 0L
        override fun read(): Int = inner.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) count += it }
        override fun close() = inner.close()
    }

    private fun friendly(e: Exception): String = when {
        e is java.net.UnknownHostException -> "No internet connection"
        e is java.net.SocketTimeoutException -> "Connection timed out, tap to resume"
        e.message?.startsWith("HTTP") == true -> "Server error (${e.message})"
        e.message?.contains("ENOSPC") == true -> "Storage full"
        else -> e.message ?: "Download failed"
    }

    companion object {
        private const val TAG = "ModelManager"
        private const val MARKER = ".installed"
    }
}

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> String.format("%.1f GB", b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> String.format("%.0f MB", b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> String.format("%.0f KB", b / (1L shl 10).toDouble())
    else -> "$b B"
}
