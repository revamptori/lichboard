package dev.lichboard.ime

import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import dev.lichboard.input.EditCommand
import dev.lichboard.input.InputRouter
import dev.lichboard.keyboard.BoardPalette
import dev.lichboard.keyboard.KeyboardPanel
import dev.lichboard.lichboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class LichboardImeService : InputMethodService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var panel: KeyboardPanel? = null
    private var editorStarted = false
    var inputSession: Any = Any()
        private set
    private var selectionState = 0 // 0 unknown, 1 collapsed, 2 selected; never query text per key.
    private val characterMap by lazy { KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD) }
    val hasEditor: Boolean get() {
        if (!editorStarted) return false
        val connection = currentInputConnection ?: return false
        // Android exposes the window's fallback binding when no View supplied an editor IC.
        // Do not mistake that fallback for a writable editor (TYPE_NULL can also be a real terminal).
        return connection !== currentInputBinding?.connection
    }

    override fun onCreate() {
        super.onCreate()
        ImeBridge.attach(this)
        scope.launch {
            lichboard.settings.state.collect { settings ->
                panel?.updateSettings(settings)
                panel?.layoutParams?.let { params ->
                    params.height = keyboardHeight()
                    panel?.layoutParams = params
                }
                window?.window?.let { imeWindow ->
                    val background = BoardPalette.from(settings).background
                    @Suppress("DEPRECATION")
                    run { imeWindow.navigationBarColor = background }
                    androidx.core.view.WindowCompat.getInsetsController(imeWindow, imeWindow.decorView)
                        .isAppearanceLightNavigationBars = androidx.core.graphics.ColorUtils.calculateLuminance(background) > .5
                }
            }
        }
    }
    private fun keyboardHeight(): Int {
        val desired = (lichboard.settings.current.keyboardHeight * resources.displayMetrics.density).toInt()
        return minOf(desired, (resources.displayMetrics.heightPixels * .7f).toInt())
    }
    override fun onCreateInputView(): View {
        panel?.dispose()
        return KeyboardPanel(this, InputRouter(this, forceIme = true), floating = false,
            onClose = { requestHideSelf(0) }).also {
            panel = it
            it.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, keyboardHeight())
        }
    }
    override fun onEvaluateFullscreenMode() = false
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        editorStarted = true
        inputSession = Any()
        selectionState = when {
            attribute == null || attribute.initialSelStart < 0 || attribute.initialSelEnd < 0 -> 0
            attribute.initialSelStart == attribute.initialSelEnd -> 1
            else -> 2
        }
        panel?.discardPendingInput()
        panel?.resetModifiers()
    }
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lichboard.imeVisible.value = true
        panel?.refreshStatus()
    }
    override fun onFinishInputView(finishingInput: Boolean) {
        panel?.discardPendingInput()
        lichboard.imeVisible.value = false
        super.onFinishInputView(finishingInput)
    }
    override fun onFinishInput() {
        inputSession = Any()
        selectionState = 0
        panel?.discardPendingInput()
        editorStarted = false
        lichboard.imeVisible.value = false
        super.onFinishInput()
    }
    override fun onWindowHidden() {
        panel?.discardPendingInput()
        lichboard.imeVisible.value = false
        super.onWindowHidden()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
                                   candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selectionState = when {
            newSelStart < 0 || newSelEnd < 0 -> 0
            newSelStart == newSelEnd -> 1
            else -> 2
        }
    }

    private val rawEditor get() = currentInputEditorInfo?.inputType == InputType.TYPE_NULL
    fun text(value: String, meta: Int): Boolean {
        val ic = if (hasEditor) currentInputConnection else null
        ic ?: return false
        return runCatching {
            if (rawEditor || meta and (KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON) != 0) {
                selectionState = 0
                val events = characterMap.getEvents(value.toCharArray())
                    ?: return false
                withModifiers(meta and KeyEvent.META_SHIFT_ON.inv()) {
                    var ok = true
                    for (event in events) {
                        ok = ic.sendKeyEvent(KeyEvent(event.downTime, event.eventTime, event.action, event.keyCode,
                            event.repeatCount, event.metaState or meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                            KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE, InputDevice.SOURCE_KEYBOARD)) && ok
                    }
                    ok
                }
            } else ic.commitText(value, 1).also { if (it) selectionState = 1 }
        }.getOrDefault(false)
    }
    fun key(code: Int, meta: Int): Boolean {
        val ic = if (hasEditor) currentInputConnection else null
        ic ?: return false
        return runCatching {
            if (meta == 0 && !rawEditor) {
                when (code) {
                    KeyEvent.KEYCODE_DEL -> {
                        if (selectionState == 2) return ic.commitText("", 1).also { if (it) selectionState = 1 }
                        if (selectionState == 1 && ic.deleteSurroundingTextInCodePoints(1, 0)) return true
                    }
                    KeyEvent.KEYCODE_FORWARD_DEL -> {
                        if (selectionState == 2) return ic.commitText("", 1).also { if (it) selectionState = 1 }
                        if (selectionState == 1 && ic.deleteSurroundingTextInCodePoints(0, 1)) return true
                    }
                    KeyEvent.KEYCODE_ENTER -> {
                        val info = currentInputEditorInfo
                        val multiline = info != null && info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
                        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
                        if (!multiline && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                            if (ic.performEditorAction(action)) return true
                        }
                    }
                }
            }
            selectionState = 0
            withModifiers(meta) { send(code, KeyEvent.ACTION_DOWN, meta) and send(code, KeyEvent.ACTION_UP, meta) }
        }.getOrDefault(false)
    }
    fun edit(command: EditCommand): Boolean {
        if (!hasEditor) return false
        val id = when (command) {
            EditCommand.COPY -> android.R.id.copy
            EditCommand.CUT -> android.R.id.cut
            EditCommand.PASTE -> android.R.id.paste
            EditCommand.SELECT_ALL -> android.R.id.selectAll
        }
        if (command != EditCommand.COPY) selectionState = 0
        return runCatching { currentInputConnection?.performContextMenuAction(id) == true }.getOrDefault(false)
    }
    private fun send(code: Int, action: Int, meta: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        return currentInputConnection?.sendKeyEvent(KeyEvent(now, now, action, code, 0, meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE, InputDevice.SOURCE_KEYBOARD)) == true
    }
    private fun withModifiers(meta: Int, action: () -> Boolean): Boolean {
        val modifiers = listOf(KeyEvent.META_CTRL_ON to KeyEvent.KEYCODE_CTRL_LEFT,
            KeyEvent.META_ALT_ON to KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.META_SHIFT_ON to KeyEvent.KEYCODE_SHIFT_LEFT)
            .filter { meta and it.first != 0 }
        var pressedMeta = 0
        for ((bit, code) in modifiers) { pressedMeta = pressedMeta or bit; send(code, KeyEvent.ACTION_DOWN, pressedMeta) }
        return try { action() } finally {
            for ((bit, code) in modifiers.reversed()) {
                pressedMeta = pressedMeta and bit.inv()
                send(code, KeyEvent.ACTION_UP, pressedMeta)
            }
        }
    }
    override fun onDestroy() {
        ImeBridge.detach(this)
        lichboard.imeVisible.value = false
        panel?.dispose()
        panel = null
        scope.cancel()
        super.onDestroy()
    }
}
