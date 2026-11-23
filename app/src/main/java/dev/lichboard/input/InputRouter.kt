package dev.lichboard.input

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import dev.lichboard.BuildConfig
import dev.lichboard.accessibility.AccessBridge
import dev.lichboard.data.InputMode
import dev.lichboard.ime.ImeBridge
import dev.lichboard.lichboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Routes on every explicit key action; never assumes an overlay confers injection privileges. */
class InputRouter(private val context: Context, private val forceIme: Boolean = false) {
    private val app get() = context.lichboard
    private val clipboard get() = context.getSystemService(ClipboardManager::class.java)
    private suspend fun route(): InputRoute {
        val settings = app.settings.current
        val hasIme = withContext(Dispatchers.Main.immediate) { ImeBridge.hasEditor }
        if (forceIme) return if (hasIme) InputRoute.IME else InputRoute.DRAFT
        val allowAssist = BuildConfig.INPUT_ASSIST_AVAILABLE && settings.accessibilityFallback && (settings.inputMode == InputMode.AUTO || settings.inputMode == InputMode.ACCESSIBILITY)
        val shouldProbeAssist = allowAssist && (settings.inputMode == InputMode.ACCESSIBILITY || !hasIme)
        val result = InputPolicy.choose(settings.inputMode, hasIme, allowAssist,
            shouldProbeAssist && AccessBridge.service?.hasEditableTarget() == true)
        // A hidden/destroyed panel may have been cancelled during a slow node query.
        currentCoroutineContext().ensureActive()
        return result
    }
    // Status never performs accessibility IPC on the rendering thread.
    fun status(): String {
        val s = app.settings.current
        return when {
            (forceIme || s.inputMode == InputMode.AUTO || s.inputMode == InputMode.IME) && ImeBridge.hasEditor -> "IME · focused editor"
            BuildConfig.INPUT_ASSIST_AVAILABLE && !forceIme && (s.inputMode == InputMode.AUTO || s.inputMode == InputMode.ACCESSIBILITY) &&
                s.accessibilityFallback && AccessBridge.connected.value -> "ASSIST · supported focused fields only"
            else -> "DRAFT · copy to paste elsewhere"
        }
    }
    suspend fun text(value: String, meta: Int): String {
        return when (route()) {
            InputRoute.IME -> if (withContext(Dispatchers.Main.immediate) { ImeBridge.service?.text(value, meta) == true }) "IME · focused editor" else fallbackText(value, meta)
            InputRoute.ACCESSIBILITY -> if (AccessBridge.service?.text(value, meta) == true) "ASSIST · focused text field" else "ASSIST · ${AccessBridge.service?.lastFailure ?: "Service disconnected"} · use draft"
            InputRoute.DRAFT -> draftText(value, meta)
        }
    }
    private suspend fun fallbackText(value: String, meta: Int): String {
        currentCoroutineContext().ensureActive()
        val s = app.settings.current
        if (BuildConfig.INPUT_ASSIST_AVAILABLE && !forceIme && s.inputMode == InputMode.AUTO && s.accessibilityFallback &&
            AccessBridge.service?.text(value, meta) == true) return "ASSIST · focused text field"
        currentCoroutineContext().ensureActive()
        return draftText(value, meta)
    }
    private fun draftText(value: String, meta: Int): String {
        if (meta and KeyEvent.META_ALT_ON != 0) return "Alt combinations need an active IME connection"
        if (meta and KeyEvent.META_CTRL_ON != 0) {
            return when (value.lowercase()) {
                "a" -> draftEdit(EditCommand.SELECT_ALL)
                "c" -> draftEdit(EditCommand.COPY)
                "x" -> draftEdit(EditCommand.CUT)
                "v" -> draftEdit(EditCommand.PASTE)
                else -> "This shortcut needs an active IME connection"
            }
        }
        app.draft.edit { insert(value) }
        return "DRAFT · saved in memory, not sent to an app"
    }
    suspend fun key(code: Int, meta: Int): String = when (route()) {
        InputRoute.IME -> if (withContext(Dispatchers.Main.immediate) { ImeBridge.service?.key(code, meta) == true }) "IME · focused editor" else "Editor did not accept this key"
        InputRoute.ACCESSIBILITY -> if (AccessBridge.service?.key(code, meta) == true) "ASSIST · focused text field" else "This key needs an active IME connection"
        InputRoute.DRAFT -> draftKey(code, meta)
    }
    suspend fun edit(command: EditCommand): String = when (route()) {
        InputRoute.IME -> if (withContext(Dispatchers.Main.immediate) { ImeBridge.service?.edit(command) == true }) "IME · focused editor" else "Editor did not accept this action"
        InputRoute.ACCESSIBILITY -> if (AccessBridge.service?.edit(command) == true) "ASSIST · focused text field" else "Field did not accept this action"
        InputRoute.DRAFT -> draftEdit(command)
    }
    private fun draftKey(code: Int, meta: Int): String {
        if (meta and (KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON) != 0) return "This key combination needs an active IME connection"
        val shift = meta and KeyEvent.META_SHIFT_ON != 0
        var handled = true
        app.draft.edit {
            when (code) {
                KeyEvent.KEYCODE_DEL -> backspace()
                KeyEvent.KEYCODE_FORWARD_DEL -> deleteForward()
                KeyEvent.KEYCODE_ENTER -> insert("\n")
                KeyEvent.KEYCODE_TAB -> insert("\t")
                KeyEvent.KEYCODE_DPAD_LEFT -> left(shift)
                KeyEvent.KEYCODE_DPAD_RIGHT -> right(shift)
                KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_UP -> home(shift)
                KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_DOWN -> end(shift)
                else -> handled = false
            }
        }
        return if (handled) "DRAFT · editing in memory" else "This key needs an active IME connection"
    }
    private fun draftEdit(command: EditCommand): String {
        when (command) {
            EditCommand.COPY -> {
                val draft = app.draft.snapshot
                return copy(draft.selectedText.ifEmpty { draft.text })
            }
            EditCommand.CUT -> {
                val draft = app.draft.snapshot
                if (draft.selectedText.isEmpty()) return "Select draft text first (Ctrl+A selects all)"
                val result = copy(draft.selectedText)
                if (!result.startsWith("Copied")) return result
                return if (app.draft.editIfUnchanged(draft) { insert("") }) result
                    else "Copied · draft changed, so nothing was cut"
            }
            EditCommand.PASTE -> {
                val value = clipboardText() ?: return "Clipboard is empty or unavailable"
                app.draft.edit { insert(value) }
            }
            EditCommand.SELECT_ALL -> app.draft.edit { selectAll() }
        }
        return "DRAFT · editing in memory"
    }
    fun clipboardText(): String? = runCatching {
        clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
    }.getOrNull()
    fun copyDraft(): String = copy(app.draft.snapshot.text)
    private fun copy(text: String): String {
        if (text.isEmpty()) return "Draft is empty"
        return runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText("Lichboard draft", text))
            "Copied · focus a field and use its Paste action"
        }.getOrDefault("Clipboard is unavailable in this context")
    }
    fun clearDraft(): String { app.draft.edit { clear() }; return "Draft cleared from memory" }
    fun draftPreview(): String = app.draft.snapshot.preview()
}
