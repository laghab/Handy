package com.handy.android

import android.app.Application
import android.content.Context
import com.handy.android.engine.DictationController
import com.handy.android.engine.TranscriptionEngine
import com.handy.android.history.HistoryStore
import com.handy.android.model.ModelManager
import com.handy.android.settings.AppSettings

/** Process-wide singletons. The keyboard and the app share one process and one engine. */
class AppGraph(context: Context) {
    val settings = AppSettings(context)
    val models = ModelManager(context)
    val engine = TranscriptionEngine(context, settings, models)
    val history = HistoryStore(context)
    val dictation = DictationController(context, settings, engine, history)
    val files = com.handy.android.engine.FileTranscriber(context, settings, engine, history)

    init {
        reconcileSelection()
        models.onModelsChanged = {
            reconcileSelection()
            engine.invalidate()
        }
    }

    /** Keeps the selected model pointing at something installed (e.g. after the first download or a delete). */
    private fun reconcileSelection() {
        val selected = com.handy.android.model.ModelCatalog.byId(settings.current.selectedModelId)
        if (selected == null || !models.isInstalled(selected)) {
            models.installed().firstOrNull()?.let { m -> settings.update { it.copy(selectedModelId = m.id) } }
        }
    }
}

class HandyApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        graph.engine.onTrimMemory(level)
    }
}

val Context.graph: AppGraph get() = (applicationContext as HandyApp).graph
