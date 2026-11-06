package com.handy.android.settings

import android.content.Context
import android.content.SharedPreferences
import com.handy.android.model.ModelCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the mic key reacts to touches (mirrors Handy's shortcut activation modes). */
enum class MicMode { HOLD_OR_TAP, TAP_TO_TOGGLE, HOLD_TO_TALK }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class Settings(
    val selectedModelId: String? = null,
    val language: String = ModelCatalog.AUTO,
    val micMode: MicMode = MicMode.HOLD_OR_TAP,
    val holdThresholdMs: Long = 300,
    val autoStartOnKeyboardOpen: Boolean = false,
    val switchBackAfterDictation: Boolean = false,
    val autoSubmit: Boolean = false,
    val appendTrailingSpace: Boolean = true,
    val smartSpacing: Boolean = true,
    val vadEnabled: Boolean = true,
    val fillerWordRemoval: Boolean = true,
    val customWords: List<String> = emptyList(),
    val wordCorrectionThreshold: Double = 0.18,
    val hapticFeedback: Boolean = true,
    val audioFeedback: Boolean = false,
    val pauseMedia: Boolean = true,
    val keepModelLoadedMinutes: Int = 3,
    val historyLimit: Int = 100,
    val saveRecordings: Boolean = true,
    val threads: Int = 0, // 0 = auto
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val onboardingCompleted: Boolean = false,
)

/**
 * SharedPreferences-backed settings exposed as a [StateFlow].
 *
 * SharedPreferences (rather than DataStore) because the keyboard needs a
 * synchronous, already-in-memory read every time it opens.
 */
class AppSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("handy_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state.asStateFlow()
    val current: Settings get() = _state.value

    fun update(transform: (Settings) -> Settings) {
        synchronized(this) {
            val next = transform(_state.value)
            if (next == _state.value) return
            write(next)
            _state.value = next
        }
    }

    private fun read(): Settings {
        val d = Settings()
        return Settings(
            selectedModelId = prefs.getString("selected_model", d.selectedModelId),
            language = prefs.getString("language", d.language) ?: d.language,
            micMode = enumOr(prefs.getString("mic_mode", null), d.micMode),
            holdThresholdMs = prefs.getLong("hold_threshold_ms", d.holdThresholdMs),
            autoStartOnKeyboardOpen = prefs.getBoolean("auto_start", d.autoStartOnKeyboardOpen),
            switchBackAfterDictation = prefs.getBoolean("switch_back", d.switchBackAfterDictation),
            autoSubmit = prefs.getBoolean("auto_submit", d.autoSubmit),
            appendTrailingSpace = prefs.getBoolean("trailing_space", d.appendTrailingSpace),
            smartSpacing = prefs.getBoolean("smart_spacing", d.smartSpacing),
            vadEnabled = prefs.getBoolean("vad", d.vadEnabled),
            fillerWordRemoval = prefs.getBoolean("filler_removal", d.fillerWordRemoval),
            customWords = prefs.getString("custom_words", null)
                ?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: d.customWords,
            wordCorrectionThreshold = prefs.getFloat("word_threshold", d.wordCorrectionThreshold.toFloat()).toDouble(),
            hapticFeedback = prefs.getBoolean("haptics", d.hapticFeedback),
            audioFeedback = prefs.getBoolean("audio_feedback", d.audioFeedback),
            pauseMedia = prefs.getBoolean("pause_media", d.pauseMedia),
            keepModelLoadedMinutes = prefs.getInt("keep_loaded_min", d.keepModelLoadedMinutes),
            historyLimit = prefs.getInt("history_limit", d.historyLimit),
            saveRecordings = prefs.getBoolean("save_recordings", d.saveRecordings),
            threads = prefs.getInt("threads", d.threads),
            theme = enumOr(prefs.getString("theme", null), d.theme),
            onboardingCompleted = prefs.getBoolean("onboarding_done", d.onboardingCompleted),
        )
    }

    private fun write(s: Settings) {
        prefs.edit()
            .putString("selected_model", s.selectedModelId)
            .putString("language", s.language)
            .putString("mic_mode", s.micMode.name)
            .putLong("hold_threshold_ms", s.holdThresholdMs)
            .putBoolean("auto_start", s.autoStartOnKeyboardOpen)
            .putBoolean("switch_back", s.switchBackAfterDictation)
            .putBoolean("auto_submit", s.autoSubmit)
            .putBoolean("trailing_space", s.appendTrailingSpace)
            .putBoolean("smart_spacing", s.smartSpacing)
            .putBoolean("vad", s.vadEnabled)
            .putBoolean("filler_removal", s.fillerWordRemoval)
            .putString("custom_words", s.customWords.joinToString("\n"))
            .putFloat("word_threshold", s.wordCorrectionThreshold.toFloat())
            .putBoolean("haptics", s.hapticFeedback)
            .putBoolean("audio_feedback", s.audioFeedback)
            .putBoolean("pause_media", s.pauseMedia)
            .putInt("keep_loaded_min", s.keepModelLoadedMinutes)
            .putInt("history_limit", s.historyLimit)
            .putBoolean("save_recordings", s.saveRecordings)
            .putInt("threads", s.threads)
            .putString("theme", s.theme.name)
            .putBoolean("onboarding_done", s.onboardingCompleted)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
