package dev.lichboard.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.lichboard.input.EditCommand

/** Explicit user-triggered focused text editing only. Never caches nodes, reads passwords, or polls. */
class LichboardAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { super.onServiceConnected(); AccessBridge.attach(this) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* No screen monitoring. */ }
    override fun onInterrupt() = Unit // No ongoing work or speech to interrupt.
    override fun onDestroy() { AccessBridge.detach(this); super.onDestroy() }

    @Suppress("DEPRECATION")
    private fun focusedApplicationRoot(): AccessibilityNodeInfo? {
        // A touched non-focusable overlay can become the accessibility "active" window.
        // Keep targeting the input-focused APPLICATION window, never the overlay itself.
        val interactive = windows
        return try {
            interactive.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }?.root
                ?: rootInActiveWindow
        } finally { interactive.forEach { it.recycle() } }
    }

    @Suppress("DEPRECATION")
    private inline fun <T> withEditor(default: T, action: (AccessibilityNodeInfo) -> T): T {
        val root = focusedApplicationRoot() ?: return default
        var node: AccessibilityNodeInfo? = null
        return try {
            node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val target = node ?: return default
            if (!target.refresh() || !target.isEditable || target.isPassword || !target.isEnabled) default else action(target)
        } catch (_: IllegalStateException) { default }
        finally { node?.recycle(); root.recycle() }
    }
    fun hasEditableTarget(): Boolean = withEditor(false) { true }
    fun text(value: String, meta: Int): Boolean {
        if (meta and KeyEvent.META_ALT_ON != 0) return false
        if (meta and KeyEvent.META_CTRL_ON != 0) {
            val command = when (value.lowercase()) {
                "a" -> EditCommand.SELECT_ALL; "c" -> EditCommand.COPY
                "x" -> EditCommand.CUT; "v" -> EditCommand.PASTE; else -> return false
            }
            return edit(command)
        }
        return withEditor(false) { replaceSelection(it, value) }
    }
    fun edit(command: EditCommand): Boolean = withEditor(false) { node ->
        when (command) {
            EditCommand.COPY -> node.performAction(AccessibilityNodeInfo.ACTION_COPY)
            EditCommand.CUT -> node.performAction(AccessibilityNodeInfo.ACTION_CUT)
            EditCommand.PASTE -> node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            EditCommand.SELECT_ALL -> setSelection(node, 0, editableText(node).length)
        }
    }
    fun key(code: Int, meta: Int): Boolean = withEditor(false) { node ->
        if (meta and (KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON) != 0) return@withEditor false
        val text = editableText(node)
        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        if (start !in 0..text.length || end !in 0..text.length) return@withEditor false
        val low = minOf(start, end)
        val high = maxOf(start, end)
        val extend = meta and KeyEvent.META_SHIFT_ON != 0
        when (code) {
            KeyEvent.KEYCODE_DEL -> if (low != high) replaceSelection(node, "") else if (low > 0) {
                replaceRange(node, text, Character.offsetByCodePoints(text, low, -1), high, "")
            } else true
            KeyEvent.KEYCODE_FORWARD_DEL -> if (low != high) replaceSelection(node, "") else if (high < text.length) {
                replaceRange(node, text, low, Character.offsetByCodePoints(text, high, 1), "")
            } else true
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val position = if (!extend && low != high) low else if (end > 0) Character.offsetByCodePoints(text, end, -1) else 0
                setSelection(node, if (extend) start else position, position)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val position = if (!extend && low != high) high else if (end < text.length) Character.offsetByCodePoints(text, end, 1) else end
                setSelection(node, if (extend) start else position, position)
            }
            KeyEvent.KEYCODE_MOVE_HOME -> setSelection(node, if (extend) start else 0, 0)
            KeyEvent.KEYCODE_MOVE_END -> setSelection(node, if (extend) start else text.length, text.length)
            KeyEvent.KEYCODE_ENTER -> {
                if (node.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER)) {
                    node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                } else if (node.isMultiLine) replaceSelection(node, "\n") else false
            }
            // Accessibility is not a key-injection API. Unsupported special keys are explicitly rejected.
            else -> false
        }
    }
    private fun editableText(node: AccessibilityNodeInfo): String =
        if (node.isShowingHintText) "" else node.text?.toString().orEmpty()

    private fun replaceSelection(node: AccessibilityNodeInfo, value: String): Boolean {
        val old = editableText(node)
        val a = node.textSelectionStart
        val b = node.textSelectionEnd
        if (a !in 0..old.length || b !in 0..old.length) return false
        return replaceRange(node, old, minOf(a, b), maxOf(a, b), value)
    }
    private fun replaceRange(node: AccessibilityNodeInfo, old: String, start: Int, end: Int, value: String): Boolean {
        if (!node.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT)) return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, old.replaceRange(start, end, value)) }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        node.refresh()
        setSelection(node, start + value.length, start + value.length)
        return true
    }
    private fun setSelection(node: AccessibilityNodeInfo, start: Int, end: Int): Boolean =
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        })
}
