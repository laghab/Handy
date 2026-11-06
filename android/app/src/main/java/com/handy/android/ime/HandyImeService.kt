package com.handy.android.ime

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.handy.android.graph
import com.handy.android.ui.MainActivity
import kotlinx.coroutines.launch

/**
 * Handy as a keyboard: the Android equivalent of "press a shortcut, speak,
 * text appears in whatever app you're using".
 */
class HandyImeService : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    private val dictation get() = graph.dictation
    private val engine get() = graph.engine
    private val settings get() = graph.settings

    /** Text we inserted last, so "Undo" can remove exactly that. */
    val lastInserted = mutableStateOf<String?>(null)
    val editorAction = mutableStateOf(EnterAction.NEWLINE)
    val isPasswordField = mutableStateOf(false)
    val micGranted = mutableStateOf(true)

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onCreateInputView(): View {
        window.window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(this)
            decor.setViewTreeSavedStateRegistryOwner(this)
            decor.setViewTreeViewModelStoreOwner(this)
        }
        return ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@HandyImeService)
            setViewTreeSavedStateRegistryOwner(this@HandyImeService)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { KeyboardScreen(this@HandyImeService) }
        }
    }

    // The keyboard is compact; never take over the screen in landscape.
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        editorAction.value = EnterAction.from(info)
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        isPasswordField.value = info.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
            (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        if (!restarting) lastInserted.value = null
        micGranted.value = dictation.hasMicPermission()

        // Warm the model now so the first word isn't waiting on a 1-3 s load.
        if (engine.activeModel() != null) engine.preload()
        if (!restarting && settings.current.autoStartOnKeyboardOpen && !dictation.isBusy) {
            dictation.start(handsFree = true)
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // The target field is gone; a dictation in flight has nowhere to land.
        if (dictation.isRecording) dictation.cancel()
        engine.scheduleUnload()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    }

    override fun onDestroy() {
        if (dictation.isRecording) dictation.cancel()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ actions

    fun startDictation(handsFree: Boolean): Boolean = dictation.start(handsFree)

    fun finishDictation() {
        lifecycleScope.launch {
            val t = dictation.stop(source = "keyboard") ?: return@launch
            insert(t.text)
        }
    }

    fun cancelDictation() = dictation.cancel()

    private fun insert(text: String) {
        val ic = currentInputConnection ?: return
        val s = settings.current
        var out = text
        if (s.smartSpacing) {
            val before = ic.getTextBeforeCursor(1, 0)
            val prev = before?.lastOrNull()
            if (prev != null && !prev.isWhitespace() && prev !in "([{\"'“‘/-@#" && out.first() !in ",.!?;:)]}'’”") {
                out = " $out"
            }
        }
        if (s.appendTrailingSpace) {
            val next = ic.getTextAfterCursor(1, 0)?.firstOrNull()
            if (next == null || !next.isWhitespace()) out += " "
        }
        ic.beginBatchEdit()
        ic.commitText(out, 1)
        ic.endBatchEdit()
        lastInserted.value = out

        if (s.autoSubmit) enter()
        if (s.switchBackAfterDictation) switchBack()
    }

    /** Removes the last dictation if the cursor is still right after it. */
    fun undoLast() {
        val ic = currentInputConnection ?: return
        val last = lastInserted.value ?: return
        val before = ic.getTextBeforeCursor(last.length, 0)?.toString()
        if (before == last) ic.deleteSurroundingText(last.length, 0)
        lastInserted.value = null
    }

    fun backspace() {
        val ic = currentInputConnection ?: return
        lastInserted.value = null
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) ic.commitText("", 1)
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    /** Deletes the previous word (used when backspace is held). */
    fun deleteWord() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(64, 0)?.toString() ?: return
        if (before.isEmpty()) return
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace()) i--
        while (i > 0 && !before[i - 1].isWhitespace()) i--
        ic.deleteSurroundingText(before.length - i, 0)
    }

    fun type(text: String) {
        currentInputConnection?.commitText(text, 1)
        lastInserted.value = null
    }

    fun enter() {
        val ic = currentInputConnection ?: return
        val action = editorAction.value
        if (action.imeAction != null) ic.performEditorAction(action.imeAction)
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
    }

    fun switchBack() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (!switchToPreviousInputMethod()) showPicker()
        } else {
            showPicker()
        }
    }

    fun showPicker() {
        getSystemService(InputMethodManager::class.java).showInputMethodPicker()
    }

    fun openApp(tab: String? = null, requestMic: Boolean = false) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .apply {
                    tab?.let { putExtra(MainActivity.EXTRA_TAB, it) }
                    if (requestMic) putExtra(MainActivity.EXTRA_REQUEST_MIC, true)
                },
        )
        requestHideSelf(0)
    }
}

enum class EnterAction(val imeAction: Int?) {
    NEWLINE(null), GO(EditorInfo.IME_ACTION_GO), SEARCH(EditorInfo.IME_ACTION_SEARCH),
    SEND(EditorInfo.IME_ACTION_SEND), NEXT(EditorInfo.IME_ACTION_NEXT), DONE(EditorInfo.IME_ACTION_DONE),
    PREVIOUS(EditorInfo.IME_ACTION_PREVIOUS);

    companion object {
        fun from(info: EditorInfo): EnterAction {
            if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return NEWLINE
            // Multi-line fields want a real newline.
            if (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0 &&
                info.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT
            ) return NEWLINE
            return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_GO -> GO
                EditorInfo.IME_ACTION_SEARCH -> SEARCH
                EditorInfo.IME_ACTION_SEND -> SEND
                EditorInfo.IME_ACTION_NEXT -> NEXT
                EditorInfo.IME_ACTION_DONE -> DONE
                EditorInfo.IME_ACTION_PREVIOUS -> PREVIOUS
                else -> NEWLINE
            }
        }
    }
}
