package dev.lichboard

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Debug-only real editor and raw-key target. Never packaged in the release APK. */
class ProbeActivity : Activity() {
    lateinit var editor: EditText
    lateinit var raw: RawTarget
    private lateinit var plain: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 48, 24, 0) }
        editor = EditText(this).apply {
            id = R.id.probe_editor; contentDescription = "Probe editor"; hint = "Real Android editor"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
        }
        raw = RawTarget(this).apply { id = R.id.probe_raw; contentDescription = "Raw key target" }
        plain = TextView(this).apply { id = R.id.probe_plain; text = "No editor target"; isFocusableInTouchMode = true }
        root.addView(editor, LinearLayout.LayoutParams(-1, 240))
        root.addView(raw, LinearLayout.LayoutParams(-1, 100))
        root.addView(plain, LinearLayout.LayoutParams(-1, 100))
        setContentView(root)
        editor.requestFocus()
    }
    fun focusEditor() {
        editor.requestFocus()
        val imm = getSystemService(InputMethodManager::class.java)
        imm.restartInput(editor)
        editor.post { imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }
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
