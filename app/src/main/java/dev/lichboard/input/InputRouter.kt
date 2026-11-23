package dev.lichboard.input

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import dev.lichboard.BuildConfig
import dev.lichboard.accessibility.AccessBridge
import dev.lichboard.accessibility.AssistResult
import dev.lichboard.data.InputMode
import dev.lichboard.ime.ImeBridge
import dev.lichboard.ime.LichboardImeService
import dev.lichboard.lichboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** IME and memory editing stay on Main.immediate; only actual assist/clipboard IPC leaves it. */
class InputRouter(context: Context, private val forceIme: Boolean = false) {
    private val context = context.applicationContext
    private val app = context.lichboard
    private val clipboard by lazy { context.getSystemService(ClipboardManager::class.java) }
    private data class Session(val editor: Any?, val mode: InputMode, val assist: Any?, val force: Boolean)
    fun session(): Any {
        val settings = app.settings.current
        val usesIme = forceIme || settings.inputMode == InputMode.AUTO || settings.inputMode == InputMode.IME
        val usesAssist = !forceIme && BuildConfig.INPUT_ASSIST_AVAILABLE && settings.accessibilityFallback &&
            (settings.inputMode == InputMode.AUTO || settings.inputMode == InputMode.ACCESSIBILITY)
        return Session(if (usesIme) ImeBridge.service?.inputSession else null, settings.inputMode,
            if (usesAssist) AccessBridge.inputSession else null, forceIme)
    }
    // Status never probes nodes, reads an editor, or performs IPC during rendering.
    fun status(): String {
        val s = app.settings.current
        return when {
            (forceIme || s.inputMode == InputMode.AUTO || s.inputMode == InputMode.IME) && ImeBridge.hasEditor -> "IME · focused editor"
            BuildConfig.INPUT_ASSIST_AVAILABLE && !forceIme && (s.inputMode == InputMode.AUTO || s.inputMode == InputMode.ACCESSIBILITY) &&
                s.accessibilityFallback && AccessBridge.connected.value -> "ASSIST · supported focused fields only"
            s.inputMode == InputMode.CLIPBOARD -> "DRAFT · copy to paste elsewhere"
            else -> "DRAFT · select Lichboard and focus an editor to send input"
        }
    }
    suspend fun text(value: String, meta: Int): String = execute(InputAction.Text(value, meta))
    suspend fun key(code: Int, meta: Int): String = execute(InputAction.Key(code, meta))
    suspend fun edit(command: EditCommand): String = execute(InputAction.Edit(command))

    suspend fun execute(action: InputAction): String = withContext(Dispatchers.Main.immediate) {
        currentCoroutineContext().ensureActive()
        if (action == InputAction.CopyDraft) return@withContext withContext(Dispatchers.IO) { copyDraft() }
        if (action == InputAction.ClearDraft) return@withContext clearDraft()
        val settings = app.settings.current
        val ime = ImeBridge.service?.takeIf { it.hasEditor }
        val assistAllowed = BuildConfig.INPUT_ASSIST_AVAILABLE && !forceIme && settings.accessibilityFallback
        val assist = if (assistAllowed) AccessBridge.service else null
        val assistSession = AccessBridge.inputSession
        val route = if (forceIme) {
            if (ime != null) InputRoute.IME else InputRoute.DRAFT
        } else InputPolicy.choose(settings.inputMode, ime != null, assistAllowed, assist != null)
        when (route) {
            InputRoute.IME -> if (sendIme(requireNotNull(ime), action)) "IME · focused editor" else {
                // Do not replay a possibly accepted key through a second backend or another app.
                if (action is InputAction.Text && action.meta and (KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON) == 0) {
                    applyDraft(action)
                    "DRAFT · editor rejected input; text kept here, check the target"
                } else "IME · editor rejected this key/action; refocus the field"
            }
            InputRoute.ACCESSIBILITY -> when (val response = withContext(Dispatchers.IO) { requireNotNull(assist).execute(action, assistSession) }) {
                AssistResult.Applied -> "ASSIST · focused text field"
                AssistResult.NoTarget -> applyDraft(action)
                AssistResult.TargetChanged -> "ASSIST · editor changed; refocus and type again"
                is AssistResult.Rejected -> "ASSIST · ${response.reason} · use clipboard draft"
            }
            InputRoute.DRAFT -> applyDraft(action)
        }
    }
    private fun sendIme(ime: LichboardImeService, action: InputAction): Boolean = when (action) {
        is InputAction.Text -> ime.text(action.value, action.meta)
        is InputAction.Key -> ime.key(action.code, action.meta)
        is InputAction.Edit -> ime.edit(action.command)
        else -> false
    }
    private suspend fun applyDraft(action: InputAction): String {
        val clipboardAction = (action is InputAction.Edit && action.command != EditCommand.SELECT_ALL) ||
            (action is InputAction.Text && action.meta and KeyEvent.META_CTRL_ON != 0 && action.value.lowercase() in listOf("c", "x", "v"))
        val operation = {
            when (action) {
                is InputAction.Text -> draftText(action.value, action.meta)
                is InputAction.Key -> draftKey(action.code, action.meta)
                is InputAction.Edit -> draftEdit(action.command)
                InputAction.CopyDraft -> copyDraft()
                InputAction.ClearDraft -> clearDraft()
            }
        }
        return if (clipboardAction) withContext(Dispatchers.IO) { operation() } else operation()
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
