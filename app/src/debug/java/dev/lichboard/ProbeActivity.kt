package dev.lichboard

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Debug-only real editor and raw-key target. Never packaged in the release APK. */
class ProbeActivity : Activity() {
    lateinit var editor: RecordingEditor
    lateinit var otherEditor: RecordingEditor
    lateinit var raw: RawTarget
    private lateinit var plain: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 48, 24, 0) }
        editor = RecordingEditor(this).apply {
            id = R.id.probe_editor; contentDescription = "Probe editor"; hint = "Real Android editor"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
        }
        otherEditor = RecordingEditor(this).apply {
            id = R.id.probe_other_editor; contentDescription = "Other probe editor"; hint = "Second Android editor"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        raw = RawTarget(this).apply { id = R.id.probe_raw; contentDescription = "Raw key target" }
        plain = TextView(this).apply { id = R.id.probe_plain; text = "No editor target"; isFocusableInTouchMode = true }
        root.addView(editor, LinearLayout.LayoutParams(-1, 240))
        root.addView(otherEditor, LinearLayout.LayoutParams(-1, 100))
        root.addView(raw, LinearLayout.LayoutParams(-1, 100))
        root.addView(plain, LinearLayout.LayoutParams(-1, 100))
        setContentView(root)
        editor.requestFocus()
    }
    fun focusEditor() = focusEditor(editor)
    fun focusOtherEditor() = focusEditor(otherEditor)
    private fun focusEditor(target: EditText) {
        target.requestFocus()
        val imm = getSystemService(InputMethodManager::class.java)
        imm.restartInput(target)
        target.post { imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT) }
    }
    fun noEditor() {
        plain.requestFocus()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(plain.windowToken, 0)
    }
    fun focusRaw() {
        raw.requestFocus()
        val imm = getSystemService(InputMethodManager::class.java)
        imm.restartInput(raw)
        raw.post { imm.showSoftInput(raw, InputMethodManager.SHOW_IMPLICIT) }
    }
    class RecordingEditor(context: android.content.Context) : EditText(context) {
        data class Commit(val value: String, val receivedNanos: Long)
        val commits = mutableListOf<Commit>() // Main-thread test evidence, never in a release APK.
        var selectedTextQueries = 0
        var codePointDeletes = 0
        var recording = false
        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
            val connection = super.onCreateInputConnection(outAttrs) ?: return null
            return object : InputConnectionWrapper(connection, false) {
                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    if (recording) commits += Commit(text?.toString().orEmpty(), SystemClock.elapsedRealtimeNanos())
                    return super.commitText(text, newCursorPosition)
                }
                override fun getSelectedText(flags: Int): CharSequence? {
                    if (recording) selectedTextQueries++
                    return super.getSelectedText(flags)
                }
                override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                    if (recording) codePointDeletes++
                    return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
                }
            }
        }
    }
    class RawTarget(context: android.content.Context) : View(context) {
        @Volatile var lastKey = -1
        @Volatile var lastMeta = 0
        init { isFocusableInTouchMode = true; setBackgroundColor(0xff283343.toInt()) }
        override fun onCheckIsTextEditor() = true
        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
            outAttrs.inputType = InputType.TYPE_NULL
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
            return BaseInputConnection(this, false)
        }
        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            if (!KeyEvent.isModifierKey(keyCode)) { lastKey = keyCode; lastMeta = event.metaState }
            return true
        }
    }
}
