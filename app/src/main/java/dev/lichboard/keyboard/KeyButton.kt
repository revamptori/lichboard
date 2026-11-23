package dev.lichboard.keyboard

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.TextView

/** Immediate color feedback and full-cell hit targets; timers exist only during a held key. */
class KeyButton(
    context: Context,
    label: String,
    description: String,
    private val activate: () -> Unit,
    private val hold: (() -> Unit)? = null,
    private val repeat: Boolean = false,
    private val activateOnDown: Boolean = false,
    private val beforePress: ((KeyButton) -> Unit)? = null,
    private val afterRelease: ((KeyButton) -> Unit)? = null,
    private val inputSession: (() -> Any)? = null
) : TextView(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var held = false
    private var down = false
    private var pointer = -1
    private var pressSession: Any? = null
    private var keyColor = 0
    private var pressedColor = 0
    private val shape = GradientDrawable().apply { cornerRadius = 8 * resources.displayMetrics.density }
    private val repeatTask = object : Runnable {
        override fun run() {
            if (!down) return
            if (!validPress()) { release(); return }
            held = true
            activate()
            if (down) handler.postDelayed(this, 40)
        }
    }
    private val holdTask = Runnable {
        if (down && !held && hold != null) {
            if (validPress()) { held = true; performLongClick() } else release()
        }
    }
    init {
        text = label
        contentDescription = description
        gravity = Gravity.CENTER
        textSize = if (label.length > 3) 11f else 16f
        typeface = KEY_TYPEFACE
        includeFontPadding = false
        isClickable = true
        isFocusable = true
        isLongClickable = hold != null || repeat
        val inset = (2 * resources.displayMetrics.density).toInt()
        background = InsetDrawable(shape, inset) // Visual gaps are touchable, not dead margins.
        setPadding(0, 0, 0, 0)
    }
    fun colors(background: Int, text: Int, pressed: Int) {
        keyColor = background; pressedColor = pressed
        shape.setColor(if (down) pressedColor else keyColor)
        setTextColor(text)
    }
    override fun performClick(): Boolean {
        activate() // Dispatch before accessibility bookkeeping, like a native click listener.
        super.performClick()
        return true
    }
    override fun performLongClick(): Boolean {
        if (hold != null) { hold.invoke(); return true }
        return super.performLongClick()
    }
    /** Preserve down-order when two fingers roll across keys before the first finger lifts. */
    fun commitPendingTap() {
        if (down && !held) {
            if (!validPress()) { release(); return }
            held = true
            handler.removeCallbacksAndMessages(null)
            performClick()
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointer = event.getPointerId(0)
                down = true; held = false
                pressSession = inputSession?.invoke()
                parent?.requestDisallowInterceptTouchEvent(true)
                shape.setColor(pressedColor)
                beforePress?.invoke(this)
                if (!down) return true // A preceding page key may have rebuilt this view.
                if (!validPress()) { release(); return true }
                if (repeat || (activateOnDown && hold == null)) {
                    held = true
                    performClick()
                }
                if (!down) return true
                if (repeat) handler.postDelayed(repeatTask, ViewConfiguration.getLongPressTimeout().toLong())
                else if (hold != null) handler.postDelayed(holdTask, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                if (event.getPointerId(index) == pointer) {
                    val click = down && !held && inside(event.getX(index), event.getY(index)) && validPress()
                    release()
                    if (click) performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> release()
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointer)
                if (index >= 0 && !inside(event.getX(index), event.getY(index))) release()
            }
        }
        return true
    }
    private fun validPress() = inputSession == null || pressSession == inputSession.invoke()
    private fun inside(x: Float, y: Float) = x >= -slop && x <= width + slop && y >= -slop && y <= height + slop
    private fun release() {
        down = false; pointer = -1; pressSession = null
        handler.removeCallbacksAndMessages(null)
        shape.setColor(keyColor)
        afterRelease?.invoke(this)
    }
    fun dispose() { release(); animate().cancel() }
    override fun onDetachedFromWindow() { dispose(); super.onDetachedFromWindow() }
    companion object { private val KEY_TYPEFACE = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
}
