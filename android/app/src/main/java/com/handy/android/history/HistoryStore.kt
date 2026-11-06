package com.handy.android.history

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.handy.android.audio.WavFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class HistoryEntry(
    val id: Long,
    val timestamp: Long,
    val text: String,
    val rawText: String,
    val modelId: String?,
    val audioSeconds: Float,
    val latencyMs: Long,
    val audioFile: File?,
    val source: String,
)

/** Transcription history, newest first, with optional audio for re-listening / re-transcribing. */
class HistoryStore(context: Context) {
    private val audioDir = File(context.filesDir, "recordings").apply { mkdirs() }
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val db = object : SQLiteOpenHelper(context, "history.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE history(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts INTEGER NOT NULL,
                    text TEXT NOT NULL,
                    raw TEXT NOT NULL,
                    model TEXT,
                    seconds REAL NOT NULL,
                    latency INTEGER NOT NULL,
                    audio TEXT,
                    source TEXT NOT NULL)""",
            )
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    init { io.launch { reload() } }

    private fun reload() {
        val list = ArrayList<HistoryEntry>()
        db.readableDatabase.rawQuery(
            "SELECT id, ts, text, raw, model, seconds, latency, audio, source FROM history ORDER BY ts DESC", null,
        ).use { c ->
            while (c.moveToNext()) {
                list += HistoryEntry(
                    id = c.getLong(0), timestamp = c.getLong(1), text = c.getString(2), rawText = c.getString(3),
                    modelId = c.getString(4), audioSeconds = c.getFloat(5), latencyMs = c.getLong(6),
                    audioFile = c.getString(7)?.let { File(audioDir, it) }?.takeIf { it.exists() },
                    source = c.getString(8),
                )
            }
        }
        _entries.value = list
    }

    fun add(
        text: String, raw: String, modelId: String?, audioSeconds: Float, latencyMs: Long,
        audio: FloatArray?, source: String, limit: Int,
    ) {
        if (text.isBlank() || limit == 0) return
        io.launch {
            val ts = System.currentTimeMillis()
            val audioName = audio?.takeIf { it.isNotEmpty() }?.let {
                val name = "rec-$ts.wav"
                runCatching { WavFile.write(File(audioDir, name), it) }.map { name }.getOrNull()
            }
            db.writableDatabase.insert("history", null, ContentValues().apply {
                put("ts", ts); put("text", text); put("raw", raw); put("model", modelId)
                put("seconds", audioSeconds); put("latency", latencyMs); put("audio", audioName); put("source", source)
            })
            trim(limit)
            reload()
        }
    }

    fun update(id: Long, text: String) = io.launch {
        db.writableDatabase.update("history", ContentValues().apply { put("text", text) }, "id=?", arrayOf("$id"))
        reload()
    }

    fun delete(id: Long) = io.launch {
        _entries.value.firstOrNull { it.id == id }?.audioFile?.delete()
        db.writableDatabase.delete("history", "id=?", arrayOf("$id"))
        reload()
    }

    fun clear() = io.launch {
        db.writableDatabase.delete("history", null, null)
        audioDir.listFiles()?.forEach { it.delete() }
        reload()
    }

    fun applyLimit(limit: Int) = io.launch { trim(limit); reload() }

    private fun trim(limit: Int) {
        if (limit < 0) return
        val w = db.writableDatabase
        w.rawQuery("SELECT audio FROM history ORDER BY ts DESC LIMIT -1 OFFSET ?", arrayOf("$limit")).use { c ->
            while (c.moveToNext()) c.getString(0)?.let { File(audioDir, it).delete() }
        }
        w.execSQL("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY ts DESC LIMIT ?)", arrayOf(limit))
    }

    fun totalAudioBytes(): Long = audioDir.listFiles()?.sumOf { it.length() } ?: 0L
}
