package dev.lichboard.keyboard

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.TextView

/** Small native key view: no composition, timer, or animation runs while idle. */
class KeyButton(
    context: Context,
    label: String,
    description: String,
    private val activate: () -> Unit,
    private val hold: (() -> Unit)? = null,
    private val repeat: Boolean = false
) : TextView(context) {
    private val handler = Handler(Looper.getMainLooper())
    private var held = false
    private var down = false
    private var keyColor = 0
    private var pressedColor = 0
    private val shape = GradientDrawable().apply { cornerRadius = 8 * resources.displayMetrics.density }
    private val repeatTask = object : Runnable {
        override fun run() {
            if (!down) return
            held = true
            activate()
            handler.postDelayed(this, 55)
        }
    }
    private val holdTask = Runnable {
        if (down && hold != null) { held = true; performLongClick() }
    }
    init {
        text = label
        contentDescription = description
        gravity = Gravity.CENTER
        textSize = if (label.length > 3) 11f else 16f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
        isClickable = true
        isFocusable = true
        isLongClickable = hold != null || repeat
        background = shape
        setPadding(0, 0, 0, 0)
    }
    fun colors(background: Int, text: Int, pressed: Int) {
        keyColor = background; pressedColor = pressed
        shape.setColor(if (down) pressedColor else keyColor)
        setTextColor(text)
    }
    override fun performClick(): Boolean { super.performClick(); activate(); return true }
    override fun performLongClick(): Boolean {
        if (hold != null) { hold.invoke(); return true }
        return super.performLongClick()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                down = true; held = false
                shape.setColor(pressedColor)
                animate().scaleX(.95f).scaleY(.95f).setDuration(60).start()
                if (repeat) handler.postDelayed(repeatTask, ViewConfiguration.getLongPressTimeout().toLong())
                else if (hold != null) handler.postDelayed(holdTask, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_UP -> {
                val click = down && !held && event.x >= 0 && event.x <= width && event.y >= 0 && event.y <= height
                release()
                if (click) performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> { release(); return true }
            MotionEvent.ACTION_MOVE -> {
                if (event.x < -20 || event.x > width + 20 || event.y < -20 || event.y > height + 20) release()
                return true
            }
        }
        return true
    }
    private fun release() {
        down = false
        handler.removeCallbacksAndMessages(null)
        shape.setColor(keyColor)
        animate().scaleX(1f).scaleY(1f).setDuration(80).start()
    }
    fun dispose() { down = false; handler.removeCallbacksAndMessages(null); animate().cancel() }
    override fun onDetachedFromWindow() { dispose(); super.onDetachedFromWindow() }
}
