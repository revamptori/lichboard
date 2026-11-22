package dev.lichboard.input

/** In-memory, UTF-16-indexed editor. Cursor movement/deletion never splits a surrogate pair. */
class DraftBuffer(private val limit: Int = 20_000) {
    var text: String = ""
        private set
    var cursor: Int = 0
        private set
    var anchor: Int = 0
        private set
    val selectionStart get() = minOf(cursor, anchor)
    val selectionEnd get() = maxOf(cursor, anchor)
    val selectedText get() = text.substring(selectionStart, selectionEnd)

    fun insert(value: String) {
        val available = (limit - text.length + selectionEnd - selectionStart).coerceAtLeast(0)
        var end = minOf(value.length, available)
        if (end > 0 && end < value.length && Character.isHighSurrogate(value[end - 1])) end--
        val clipped = value.substring(0, end)
        val start = selectionStart
        text = text.replaceRange(start, selectionEnd, clipped)
        cursor = start + clipped.length
        anchor = cursor
    }
    fun backspace() {
        if (cursor != anchor) return insert("")
        if (cursor == 0) return
        val previous = Character.offsetByCodePoints(text, cursor, -1)
        text = text.removeRange(previous, cursor)
        cursor = previous
        anchor = cursor
    }
    fun deleteForward() {
        if (cursor != anchor) return insert("")
        if (cursor == text.length) return
        text = text.removeRange(cursor, Character.offsetByCodePoints(text, cursor, 1))
    }
    fun left(extend: Boolean = false) {
        val next = if (!extend && cursor != anchor) selectionStart
        else if (cursor > 0) Character.offsetByCodePoints(text, cursor, -1) else 0
        move(next, extend)
    }
    fun right(extend: Boolean = false) {
        val next = if (!extend && cursor != anchor) selectionEnd
        else if (cursor < text.length) Character.offsetByCodePoints(text, cursor, 1) else text.length
        move(next, extend)
    }
    fun home(extend: Boolean = false) = move(0, extend)
    fun end(extend: Boolean = false) = move(text.length, extend)
    private fun move(position: Int, extend: Boolean) { cursor = position; if (!extend) anchor = cursor }
    fun selectAll() { anchor = 0; cursor = text.length }
    fun clear() { text = ""; cursor = 0; anchor = 0 }
    fun snapshot() = DraftSnapshot(text, cursor, anchor)
}

data class DraftSnapshot(val text: String = "", val cursor: Int = 0, val anchor: Int = 0) {
    fun preview(): String = text.take(cursor) + "│" + text.drop(cursor)
}
