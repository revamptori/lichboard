package dev.lichboard.keyboard

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import dev.lichboard.data.BoardSettings
import dev.lichboard.input.EditCommand
import dev.lichboard.input.InputRouter
import dev.lichboard.lichboard
import java.util.Locale
import kotlin.math.roundToInt

/** Shared, fully functional key surface used by the IME and the independent overlay. */
class KeyboardPanel(
    context: Context,
    private val router: InputRouter,
    private val floating: Boolean,
    private val onClose: () -> Unit,
    private val onDrag: ((Float, Float, Boolean) -> Unit)? = null
) : LinearLayout(context) {
    private enum class Page { LETTERS, SYMBOLS, SYMBOLS_MORE, SPECIAL, EMOJI, CLIPBOARD }
    private var settings = context.lichboard.settings.current
    private var palette = BoardPalette.from(settings)
    private var page = Page.LETTERS
    private var shift = 0 // 0: off, 1: one shot, 2: caps lock
    private var ctrl = false
    private var alt = false
    private var ctrlLock = false
    private var altLock = false
    private var lastShift = 0L
    private var emojiCategory = 0
    private var clipboardPreview: String? = null
    private val buttons = mutableListOf<KeyButton>()
    private val letters = mutableMapOf<KeyButton, String>()
    private val modifiers = mutableMapOf<KeyButton, String>()
    private lateinit var toolbar: LinearLayout
    private lateinit var status: TextView
    private lateinit var body: LinearLayout
    private var alternatives: List<String>? = null

    init {
        orientation = VERTICAL
        contentDescription = if (floating) "Lichboard floating keyboard" else "Lichboard system keyboard"
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        setPadding(dp(5), dp(4), dp(5), dp(5))
        build()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun rounded(color: Int, radius: Int = 12) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), palette.border)
    }
    fun updateSettings(value: BoardSettings) {
        val next = BoardPalette.from(value)
        settings = value
        if (palette != next) { palette = next; build() }
    }
    private fun build() {
        disposeButtons()
        removeAllViews()
        background = rounded(palette.background, if (floating) 18 else 0)
        toolbar = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        addView(toolbar, LayoutParams(LayoutParams.MATCH_PARENT, dp(36)))
        buildToolbar()
        status = TextView(context).apply {
            textSize = 10f
            setTextColor(palette.muted)
            maxLines = 2
            setPadding(dp(6), 0, dp(6), 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, dp(32)))
        body = LinearLayout(context).apply { orientation = VERTICAL }
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        buildBody()
        refreshStatus()
    }
    private fun buildToolbar() {
        toolbar.removeAllViews()
        buttons.removeAll { it.parent == null }
        val alternate = alternatives
        if (alternate != null) {
            alternate.forEach { char ->
                toolbar.addView(key(char, "Alternative $char") {
                    sendText(char); alternatives = null; buildToolbar()
                }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            }
            toolbar.addView(key("×", "Close alternatives", special = true) { alternatives = null; buildToolbar() }, LayoutParams(dp(34), LayoutParams.MATCH_PARENT))
            return
        }
        val title = TextView(context).apply {
            text = if (floating) "⠿  lichboard" else "♜  lichboard"
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(palette.accent)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), 0, 0, 0)
            contentDescription = if (floating) "Drag keyboard" else "Lichboard"
        }
        if (floating) {
            var x = 0f; var y = 0f
            title.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { x = event.rawX; y = event.rawY }
                    MotionEvent.ACTION_MOVE -> { onDrag?.invoke(event.rawX - x, event.rawY - y, false); x = event.rawX; y = event.rawY }
                    MotionEvent.ACTION_UP -> { onDrag?.invoke(0f, 0f, true); view.performClick() }
                    MotionEvent.ACTION_CANCEL -> onDrag?.invoke(0f, 0f, true)
                }; true
            }
        }
        toolbar.addView(title, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        toolbar.addView(key("▤", "Clipboard", special = true) {
            feedback(); clipboardPreview = router.clipboardText(); setPage(if (page == Page.CLIPBOARD) Page.LETTERS else Page.CLIPBOARD)
        }, LayoutParams(dp(38), dp(32)))
        toolbar.addView(key("☺", "Emoji panel", special = true) { feedback(); setPage(if (page == Page.EMOJI) Page.LETTERS else Page.EMOJI) }, LayoutParams(dp(38), dp(32)))
        toolbar.addView(key("Fn", "Special keys", special = true) { feedback(); setPage(if (page == Page.SPECIAL) Page.LETTERS else Page.SPECIAL) }, LayoutParams(dp(38), dp(32)))
        toolbar.addView(key("⌄", "Hide keyboard", special = true) { feedback(); onClose() }, LayoutParams(dp(38), dp(32)))
    }
    private fun setPage(value: Page) { alternatives = null; page = value; buildBody(); buildToolbar(); refreshStatus() }
    private fun buildBody() {
        // Remove only body-owned keys; detached key views cancel their own callbacks.
        body.removeAllViews()
        buttons.removeAll { it.parent == null }
        letters.clear(); modifiers.clear()
        when (page) {
            Page.LETTERS -> lettersPage()
            Page.SYMBOLS, Page.SYMBOLS_MORE -> symbolsPage()
            Page.SPECIAL -> specialPage()
            Page.EMOJI -> emojiPage()
            Page.CLIPBOARD -> clipboardPage()
        }
        updateModifiers()
    }
    private fun row(): LinearLayout = LinearLayout(context).also { line ->
        line.gravity = Gravity.CENTER
        body.addView(line, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }
    private fun LinearLayout.addKey(view: KeyButton, weight: Float = 1f) {
        addView(view, LayoutParams(0, LayoutParams.MATCH_PARENT, weight).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
    }
    private fun key(label: String, description: String = "Key $label", special: Boolean = false,
                    accent: Boolean = false, repeat: Boolean = false, hold: (() -> Unit)? = null,
                    action: () -> Unit): KeyButton {
        return KeyButton(context, label, description, action, hold, repeat).also {
            it.colors(if (accent) palette.accent else if (special) palette.special else palette.key,
                if (accent) palette.onAccent else palette.text, ColorUtils.blendARGB(palette.key, palette.accent, .35f))
            buttons.add(it)
        }
    }
    private fun character(value: String): KeyButton {
        val choices = ALTERNATIVES[value]
        val button = key(value, hold = if (choices == null) null else ({
            feedback(); alternatives = choices.map { if (shift > 0) it.uppercase(Locale.ROOT) else it }; buildToolbar()
        })) { sendText(if (shift > 0) value.uppercase(Locale.ROOT) else value) }
        if (value.firstOrNull()?.isLetter() == true) letters[button] = value
        return button
    }
    private fun keyCode(label: String, code: Int, weight: Float = 1f, line: LinearLayout) {
        line.addKey(key(label, special = true, repeat = code == KeyEvent.KEYCODE_DEL || code == KeyEvent.KEYCODE_FORWARD_DEL) {
            feedback(); refreshStatus(router.key(code, meta())); consumeModifiers()
        }, weight)
    }
    private fun modifier(label: String): KeyButton = key(label, special = true, hold = {
        feedback()
        when (label) {
            "Shift" -> shift = if (shift == 2) 0 else 2
            "Ctrl" -> { ctrlLock = !ctrlLock; ctrl = ctrlLock }
            "Alt" -> { altLock = !altLock; alt = altLock }
        }
        updateModifiers()
    }) {
        feedback()
        when (label) {
            "Shift" -> {
                val now = SystemClock.uptimeMillis()
                shift = if (shift == 1 && now - lastShift < 350) 2 else if (shift > 0) 0 else 1
                lastShift = now
            }
            "Ctrl" -> { ctrl = !ctrl; ctrlLock = false }
            "Alt" -> { alt = !alt; altLock = false }
        }
        updateModifiers()
    }.also { modifiers[it] = label }

    private fun lettersPage() {
        row().apply { "1234567890".forEach { addKey(character(it.toString())) } }
        row().apply { "qwertyuiop".forEach { addKey(character(it.toString())) } }
        row().apply {
            addView(View(context), LayoutParams(0, 1, .45f))
            "asdfghjkl".forEach { addKey(character(it.toString())) }
            addView(View(context), LayoutParams(0, 1, .45f))
        }
        row().apply {
            addKey(modifier("Shift"), 1.35f)
            "zxcvbnm".forEach { addKey(character(it.toString())) }
            keyCode("⌫", KeyEvent.KEYCODE_DEL, 1.35f, this)
        }
        bottomRow()
        navigationRow()
    }
    private fun symbolsPage() {
        row().apply { "1234567890".forEach { addKey(character(it.toString())) } }
        val first = if (page == Page.SYMBOLS) "@#$%&*()-+" else "[]{}<>^`|~"
        val second = if (page == Page.SYMBOLS) "!\"':;/?=_" else "\\€£¥¢©®™°"
        row().apply { first.forEach { addKey(character(it.toString())) } }
        row().apply { second.forEach { addKey(character(it.toString())) } }
        row().apply {
            addKey(key(if (page == Page.SYMBOLS) "#+=" else "123", special = true) { setPage(if (page == Page.SYMBOLS) Page.SYMBOLS_MORE else Page.SYMBOLS) }, 1.4f)
            listOf(",", ".", "…", "—", "_", ":").forEach { addKey(character(it)) }
            keyCode("⌫", KeyEvent.KEYCODE_DEL, 1.4f, this)
        }
        bottomRow()
        navigationRow()
    }
    private fun bottomRow() {
        row().apply {
            addKey(key(if (page == Page.LETTERS) "?123" else "ABC", special = true) {
                feedback(); setPage(if (page == Page.LETTERS) Page.SYMBOLS else Page.LETTERS)
            }, 1.25f)
            addKey(modifier("Ctrl"), .9f)
            addKey(modifier("Alt"), .9f)
            addKey(key("space", "Key Space") { sendText(" ") }, 3.3f)
            addKey(character("."), .7f)
            addKey(key("↵", "Key Enter", accent = true) { feedback(); refreshStatus(router.key(KeyEvent.KEYCODE_ENTER, meta())); consumeModifiers() }, 1.35f)
        }
    }
    private fun navigationRow() {
        row().apply {
            keyCode("Esc", KeyEvent.KEYCODE_ESCAPE, line = this)
            keyCode("Tab", KeyEvent.KEYCODE_TAB, line = this)
            keyCode("←", KeyEvent.KEYCODE_DPAD_LEFT, line = this)
            keyCode("↓", KeyEvent.KEYCODE_DPAD_DOWN, line = this)
            keyCode("↑", KeyEvent.KEYCODE_DPAD_UP, line = this)
            keyCode("→", KeyEvent.KEYCODE_DPAD_RIGHT, line = this)
            addKey(key("Fn", "Open function keys", special = true) { setPage(Page.SPECIAL) })
        }
    }
    private fun specialPage() {
        for (group in 0..1) row().apply {
            for (n in 1..6) {
                val number = group * 6 + n
                keyCode("F$number", KeyEvent.KEYCODE_F1 + number - 1, line = this)
            }
        }
        row().apply {
            keyCode("Esc", KeyEvent.KEYCODE_ESCAPE, line = this); keyCode("Tab", KeyEvent.KEYCODE_TAB, line = this)
            keyCode("Home", KeyEvent.KEYCODE_MOVE_HOME, line = this); keyCode("End", KeyEvent.KEYCODE_MOVE_END, line = this)
            keyCode("Ins", KeyEvent.KEYCODE_INSERT, line = this); keyCode("Del", KeyEvent.KEYCODE_FORWARD_DEL, line = this)
        }
        row().apply {
            keyCode("PgUp", KeyEvent.KEYCODE_PAGE_UP, line = this); keyCode("←", KeyEvent.KEYCODE_DPAD_LEFT, line = this)
            keyCode("↓", KeyEvent.KEYCODE_DPAD_DOWN, line = this); keyCode("↑", KeyEvent.KEYCODE_DPAD_UP, line = this)
            keyCode("→", KeyEvent.KEYCODE_DPAD_RIGHT, line = this); keyCode("PgDn", KeyEvent.KEYCODE_PAGE_DOWN, line = this)
        }
        row().apply {
            listOf("Copy" to EditCommand.COPY, "Cut" to EditCommand.CUT, "Paste" to EditCommand.PASTE, "Select all" to EditCommand.SELECT_ALL).forEach { (label, command) ->
                addKey(key(label, special = true) { feedback(); refreshStatus(router.edit(command)) })
            }
            keyCode("⌫", KeyEvent.KEYCODE_DEL, line = this)
        }
        row().apply {
            addKey(key("ABC", special = true) { setPage(Page.LETTERS) })
            addKey(modifier("Ctrl")); addKey(modifier("Alt")); addKey(modifier("Shift"))
            addKey(key("space", "Key Space") { sendText(" ") }, 1.5f)
            keyCode("↵", KeyEvent.KEYCODE_ENTER, line = this)
        }
    }
    private fun emojiPage() {
        row().apply {
            listOf("Faces", "Nature", "Things").forEachIndexed { i, label ->
                addKey(key(label, accent = emojiCategory == i) { emojiCategory = i; buildBody() })
            }
        }
        EMOJI[emojiCategory].chunked(8).forEach { values -> row().apply {
            values.forEach { emoji -> addKey(key(emoji, "Emoji $emoji") { sendText(emoji) }) }
        } }
        row().apply {
            addKey(key("ABC", special = true) { setPage(Page.LETTERS) })
            addKey(key("space", "Key Space") { sendText(" ") }, 2f)
            keyCode("⌫", KeyEvent.KEYCODE_DEL, line = this)
            keyCode("↵", KeyEvent.KEYCODE_ENTER, line = this)
        }
    }
    private fun clipboardPage() {
        val preview = TextView(context).apply {
            text = "CLIPBOARD\n${clipboardPreview?.take(180) ?: "Empty or unavailable. Android restricts background clipboard access."}\n\nDRAFT (MEMORY ONLY)\n${router.draftPreview().take(160)}"
            textSize = 12f; setTextColor(palette.text)
            setPadding(dp(10), dp(6), dp(10), dp(4))
            maxLines = 8
            setTextIsSelectable(false)
        }
        body.addView(preview, LayoutParams(LayoutParams.MATCH_PARENT, 0, 3f))
        row().apply {
            addKey(key("Paste", "Paste clipboard to target", accent = true) { feedback(); refreshStatus(router.edit(EditCommand.PASTE)) })
            addKey(key("Copy draft", special = true) { feedback(); refreshStatus(router.copyDraft()) })
            addKey(key("Clear draft", special = true) { refreshStatus(router.clearDraft()); buildBody() })
        }
        row().apply {
            addKey(key("ABC", special = true) { setPage(Page.LETTERS) })
            addKey(key("Copy", special = true) { refreshStatus(router.edit(EditCommand.COPY)) })
            addKey(key("Cut", special = true) { refreshStatus(router.edit(EditCommand.CUT)) })
            addKey(key("Select all", special = true) { refreshStatus(router.edit(EditCommand.SELECT_ALL)) })
        }
    }
    private fun meta(): Int = (if (shift > 0) KeyEvent.META_SHIFT_ON else 0) or
        (if (ctrl) KeyEvent.META_CTRL_ON else 0) or (if (alt) KeyEvent.META_ALT_ON else 0)
    private fun sendText(value: String) { feedback(); refreshStatus(router.text(value, meta())); consumeModifiers() }
    private fun consumeModifiers() {
        if (shift == 1) shift = 0
        if (!ctrlLock) ctrl = false
        if (!altLock) alt = false
        updateModifiers()
    }
    fun resetModifiers() { shift = 0; ctrl = false; alt = false; ctrlLock = false; altLock = false; updateModifiers() }
    private fun updateModifiers() {
        letters.forEach { (view, char) -> view.text = if (shift > 0) char.uppercase(Locale.ROOT) else char }
        modifiers.forEach { (view, label) ->
            val active = when (label) { "Shift" -> shift > 0; "Ctrl" -> ctrl; else -> alt }
            val locked = when (label) { "Shift" -> shift == 2; "Ctrl" -> ctrlLock; else -> altLock }
            view.stateDescription = if (locked) "Locked" else if (active) "On" else "Off"
            view.colors(if (active) palette.accent else palette.special, if (active) palette.onAccent else palette.text,
                ColorUtils.blendARGB(palette.key, palette.accent, .35f))
        }
    }
    fun refreshStatus(message: String = router.status()) {
        if (!::status.isInitialized) return
        status.text = if (message.startsWith("DRAFT")) "$message\n${router.draftPreview().replace('\n', ' ').takeLast(90)}" else message
    }
    private fun feedback() {
        if (settings.vibration) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (settings.sound) context.getSystemService(AudioManager::class.java)?.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, .3f)
    }
    private fun disposeButtons() { buttons.forEach { it.dispose() }; buttons.clear(); letters.clear(); modifiers.clear() }
    fun dispose() { disposeButtons(); animate().cancel() }
    override fun onDetachedFromWindow() { buttons.forEach { it.dispose() }; super.onDetachedFromWindow() }

    companion object {
        private val ALTERNATIVES = mapOf(
            "a" to listOf("à", "á", "â", "ä", "æ", "ã", "å"), "c" to listOf("ç", "ć", "č"),
            "e" to listOf("è", "é", "ê", "ë", "ē"), "i" to listOf("ì", "í", "î", "ï", "ī"),
            "n" to listOf("ñ", "ń"), "o" to listOf("ò", "ó", "ô", "ö", "œ", "ø"),
            "s" to listOf("ß", "ś", "š"), "u" to listOf("ù", "ú", "û", "ü", "ū"),
            "y" to listOf("ý", "ÿ"), "z" to listOf("ž", "ź", "ż"), "." to listOf("…", ",", "·"),
            "1" to listOf("¹", "½", "⅓"), "2" to listOf("²", "⅔"), "3" to listOf("³", "¾"),
            "0" to listOf("°", "⁰"), "-" to listOf("—", "–", "_"), "$" to listOf("€", "£", "¥", "₹", "₨")
        )
        private val EMOJI = listOf(
            listOf("😀", "😃", "😄", "😁", "😆", "😅", "😂", "🙂", "🙃", "😉", "😊", "🥰", "😍", "🤩", "😘", "😎", "🤔", "🤨", "😐", "😴", "🤯", "🥳", "😢", "😭", "😤", "😡", "🥺", "😇", "👍", "👎", "👏", "🙏"),
            listOf("🌱", "🌿", "☘️", "🌵", "🌴", "🌲", "🌳", "🌻", "🌸", "🌷", "🌹", "🍀", "🍁", "🍄", "🌾", "🌍", "🌞", "🌝", "🌙", "⭐", "🌟", "✨", "⚡", "🔥", "🌈", "☁️", "🌧️", "❄️", "🐈", "🐕", "🦊", "🦋"),
            listOf("❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "💻", "⌨️", "🖱️", "📱", "🎮", "🎧", "📷", "💡", "🔑", "🔒", "🛠️", "⚙️", "📌", "✅", "❌", "❗", "🎉", "🎈", "🎁", "🏆", "🚀", "✈️", "☕", "🍕")
        )
    }
}
