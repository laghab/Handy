package com.handy.android.ui

import android.media.MediaPlayer
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.handy.android.audio.WavFile
import com.handy.android.graph
import com.handy.android.history.HistoryEntry
import com.handy.android.model.ModelCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HistoryScreen(activity: MainActivity, modifier: Modifier) {
    val g = activity.graph
    val entries by g.history.entries.collectAsStateWithLifecycle()
    var playingId by remember { mutableStateOf<Long?>(null) }
    var busyId by remember { mutableStateOf<Long?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val player = remember { arrayOfNulls<MediaPlayer>(1) }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) { onDispose { player[0]?.release(); player[0] = null } }

    fun stopPlayback() { player[0]?.release(); player[0] = null; playingId = null }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { ScreenTitle("History", "${entries.size} transcriptions") }
                if (entries.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Clear all") }
            }
        }
        if (entries.isEmpty()) {
            item {
                Text(
                    "Nothing yet. Everything you dictate shows up here, so you never lose a thought.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
        }
        items(entries, key = { it.id }) { e ->
            HistoryCard(
                e,
                playing = playingId == e.id,
                busy = busyId == e.id,
                onCopy = { activity.copyToClipboard(e.text) },
                onShare = { activity.shareText(e.text) },
                onDelete = { if (playingId == e.id) stopPlayback(); g.history.delete(e.id) },
                onPlay = {
                    if (playingId == e.id) stopPlayback()
                    else e.audioFile?.let { f ->
                        stopPlayback()
                        runCatching {
                            player[0] = MediaPlayer().apply {
                                setDataSource(f.path); prepare(); start()
                                setOnCompletionListener { stopPlayback() }
                            }
                            playingId = e.id
                        }
                    }
                },
                onRetranscribe = {
                    val f = e.audioFile ?: return@HistoryCard
                    busyId = e.id
                    scope.launch {
                        g.engine.acquire()
                        try {
                            val audio = withContext(Dispatchers.IO) { WavFile.read(f) }
                            val text = g.files.transcribeSamples(audio)
                            if (text.isNotBlank()) g.history.update(e.id, text)
                        } catch (_: Throwable) {
                        } finally {
                            g.engine.release(); busyId = null
                        }
                    }
                },
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("Deletes all transcriptions and saved recordings.") },
            confirmButton = { TextButton(onClick = { stopPlayback(); g.history.clear(); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun HistoryCard(
    e: HistoryEntry,
    playing: Boolean,
    busy: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onPlay: () -> Unit,
    onRetranscribe: () -> Unit,
) {
    SectionCard {
        val meta = buildString {
            append(DateUtils.getRelativeTimeSpanString(e.timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
            append(" · ").append(formatDuration(e.audioSeconds))
            ModelCatalog.byId(e.modelId)?.let { append(" · ").append(it.name) }
            if (e.source == "file") append(" · file")
        }
        Text(meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        SelectionContainer { Text(e.text, style = MaterialTheme.typography.bodyLarge) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCopy) { Icon(Icons.Default.ContentCopy, "Copy") }
            IconButton(onClick = onShare) { Icon(Icons.Default.Share, "Share") }
            if (e.audioFile != null) {
                IconButton(onClick = onPlay) { Icon(if (playing) Icons.Default.Stop else Icons.Default.PlayArrow, "Play recording") }
                if (busy) CircularProgressIndicator(Modifier.padding(12.dp).size(22.dp), strokeWidth = 2.dp)
                else IconButton(onClick = onRetranscribe) { Icon(Icons.Default.Refresh, "Transcribe again with current model") }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onDelete) { Icon(Icons.Default.DeleteOutline, "Delete") }
        }
    }
}

