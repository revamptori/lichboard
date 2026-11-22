package dev.lichboard.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import dev.lichboard.R
import dev.lichboard.keyboard.BoardPalette
import kotlin.math.hypot

class BubbleView(
    context: Context,
    var palette: BoardPalette,
    private val tap: () -> Unit,
    private val longPress: () -> Unit,
    private val move: (Float, Float, Boolean) -> Unit
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = requireNotNull(context.getDrawable(R.drawable.ic_lichboard))
    private val handler = Handler(Looper.getMainLooper())
    private var x = 0f; private var y = 0f
    private var startX = 0f; private var startY = 0f
    private var dragging = false; private var held = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val hold = Runnable { if (!dragging) { held = true; performLongClick() } }
    init {
        contentDescription = "Lichboard floating button"
        isClickable = true; isLongClickable = true; isFocusable = true
        elevation = 4 * resources.displayMetrics.density
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = minOf(width, height) / 2f - 2 * resources.displayMetrics.density
        paint.color = palette.background
        canvas.drawCircle(width / 2f, height / 2f, radius, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = resources.displayMetrics.density
        paint.color = palette.accent
        canvas.drawCircle(width / 2f, height / 2f, radius, paint)
        paint.style = Paint.Style.FILL
        val inset = (width * .22f).toInt()
        icon.setTint(palette.accent)
        icon.setBounds(inset, inset, width - inset, height - inset)
        icon.draw(canvas)
    }
    override fun performClick(): Boolean { super.performClick(); tap(); return true }
    override fun performLongClick(): Boolean { longPress(); return true }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x = event.rawX; y = event.rawY; startX = x; startY = y
                dragging = false; held = false
                handler.postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hypot(event.rawX - startX, event.rawY - startY) > slop) {
                    dragging = true; handler.removeCallbacks(hold)
                }
                if (dragging && !held) move(event.rawX - x, event.rawY - y, false)
                x = event.rawX; y = event.rawY
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(hold)
                if (dragging) move(0f, 0f, true) else if (!held) performClick()
            }
            MotionEvent.ACTION_CANCEL -> { handler.removeCallbacks(hold); if (dragging) move(0f, 0f, true) }
        }
        return true
    }
    override fun onDetachedFromWindow() { handler.removeCallbacksAndMessages(null); super.onDetachedFromWindow() }
}
