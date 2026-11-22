package dev.lichboard.input

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import dev.lichboard.accessibility.AccessBridge
import dev.lichboard.data.InputMode
import dev.lichboard.ime.ImeBridge
import dev.lichboard.lichboard

/** Routes on every explicit key action; never assumes an overlay confers injection privileges. */
class InputRouter(private val context: Context, private val forceIme: Boolean = false) {
    private val app get() = context.lichboard
    private val clipboard get() = context.getSystemService(ClipboardManager::class.java)
    private fun route(): InputRoute {
        val settings = app.settings.current
        if (forceIme) return if (ImeBridge.hasEditor) InputRoute.IME else InputRoute.DRAFT
        val allowAssist = settings.accessibilityFallback && (settings.inputMode == InputMode.AUTO || settings.inputMode == InputMode.ACCESSIBILITY)
        return InputPolicy.choose(settings.inputMode, ImeBridge.hasEditor, allowAssist,
            allowAssist && AccessBridge.service?.hasEditableTarget() == true)
    }
    fun status(): String = when (route()) {
        InputRoute.IME -> "IME · focused editor"
        InputRoute.ACCESSIBILITY -> "ASSIST · focused text field"
        InputRoute.DRAFT -> "DRAFT · copy to paste elsewhere"
    }
    fun text(value: String, meta: Int): String {
        return when (route()) {
            InputRoute.IME -> if (ImeBridge.service?.text(value, meta) == true) status() else fallbackText(value, meta)
            InputRoute.ACCESSIBILITY -> if (AccessBridge.service?.text(value, meta) == true) status() else "Field rejected input · use clipboard draft"
            InputRoute.DRAFT -> draftText(value, meta)
        }
    }
    private fun fallbackText(value: String, meta: Int): String {
        val s = app.settings.current
        if (!forceIme && s.inputMode == InputMode.AUTO && s.accessibilityFallback &&
            AccessBridge.service?.text(value, meta) == true) return "ASSIST · focused text field"
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
    fun key(code: Int, meta: Int): String = when (route()) {
        InputRoute.IME -> if (ImeBridge.service?.key(code, meta) == true) status() else "Editor did not accept this key"
        InputRoute.ACCESSIBILITY -> if (AccessBridge.service?.key(code, meta) == true) status() else "This key needs an active IME connection"
        InputRoute.DRAFT -> draftKey(code, meta)
    }
    fun edit(command: EditCommand): String = when (route()) {
        InputRoute.IME -> if (ImeBridge.service?.edit(command) == true) status() else "Editor did not accept this action"
        InputRoute.ACCESSIBILITY -> if (AccessBridge.service?.edit(command) == true) status() else "Field did not accept this action"
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
                val text = app.draft.selectedText().ifEmpty { app.draft.snapshot.text }
                return copy(text)
            }
            EditCommand.CUT -> {
                val selected = app.draft.selectedText()
                if (selected.isEmpty()) return "Select draft text first (Ctrl+A selects all)"
                val result = copy(selected)
                app.draft.edit { insert("") }
                return result
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
