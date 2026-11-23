package dev.lichboard

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lichboard.data.BoardSettings
import dev.lichboard.data.InputMode
import dev.lichboard.input.InputRouter
import dev.lichboard.keyboard.KeyButton
import dev.lichboard.keyboard.KeyboardPanel
import dev.lichboard.overlay.FloatingService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Native MotionEvents exercise hit targets, split pointers and hold/cancel, without UiAutomation. */
@RunWith(AndroidJUnit4::class)
class KeyboardTouchTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.lichboard
    private lateinit var scenario: ActivityScenario<ProbeActivity>
    private lateinit var host: FrameLayout
    private var panel: KeyboardPanel? = null

    @Before fun setup() {
        instrumentation.runOnMainSync {
            FloatingService.stop(instrumentation.targetContext)
            app.settings.update { BoardSettings(onboarded = true, inputMode = InputMode.CLIPBOARD, vibration = false) }
            app.draft.edit { clear() }
        }
        scenario = ActivityScenario.launch(ProbeActivity::class.java)
        scenario.onActivity { activity ->
            activity.noEditor()
            host = FrameLayout(activity)
            activity.setContentView(host)
        }
    }
    @After fun cleanup() {
        instrumentation.runOnMainSync {
            panel?.dispose()
            host.removeAllViews()
            app.settings.update { BoardSettings(onboarded = true, vibration = false) }
        }
        scenario.close()
    }
    private fun event(view: View, action: Int, x: Float = 40f, y: Float = 40f) {
        val now = SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, action, x, y, 0).let { e ->
            try { assertTrue(view.dispatchTouchEvent(e)) } finally { e.recycle() }
        }
    }
    @Test fun slightEdgeDriftIsAcceptedButMovingAwayAndCancellationDoNotType() {
        var clicks = 0
        scenario.onActivity { activity ->
            val key = KeyButton(activity, "a", "test", { clicks++ })
            host.addView(key, FrameLayout.LayoutParams(180, 100))
            key.layout(0, 0, 180, 100)
            val slop = ViewConfiguration.get(activity).scaledTouchSlop.toFloat()
            event(key, MotionEvent.ACTION_DOWN, 1f)
            event(key, MotionEvent.ACTION_UP, -slop / 2)
            assertEquals(1, clicks)
            event(key, MotionEvent.ACTION_DOWN, 1f)
            event(key, MotionEvent.ACTION_MOVE, -slop * 2)
            event(key, MotionEvent.ACTION_UP, 1f)
            assertEquals("A canceled drag must not later turn into a tap", 1, clicks)
            event(key, MotionEvent.ACTION_DOWN)
            event(key, MotionEvent.ACTION_CANCEL)
            event(key, MotionEvent.ACTION_UP)
            assertEquals(1, clicks)
        }
    }
    @Test fun unambiguousKeysActivateOnDownExactlyOnceAndAccessibilityClickStillWorks() {
        var clicks = 0
        scenario.onActivity { activity ->
            val key = KeyButton(activity, "q", "test", { clicks++ }, activateOnDown = true)
            host.addView(key, FrameLayout.LayoutParams(180, 100)); key.layout(0, 0, 180, 100)
            event(key, MotionEvent.ACTION_DOWN)
            assertEquals("No animation, release or worker wait before unambiguous input", 1, clicks)
            event(key, MotionEvent.ACTION_UP)
            assertEquals(1, clicks)
            key.performClick()
            assertEquals("Accessibility activation uses the same single action", 2, clicks)
        }
    }
    @Test fun longPressDoesNotInsertTheBaseKeyAndDisposalCancelsHoldTimers() {
        var clicks = 0
        var holds = 0
        lateinit var key: KeyButton
        scenario.onActivity { activity ->
            key = KeyButton(activity, "a", "test", { clicks++ }, hold = { holds++ })
            host.addView(key, FrameLayout.LayoutParams(180, 100)); key.layout(0, 0, 180, 100)
            event(key, MotionEvent.ACTION_DOWN)
        }
        Thread.sleep(ViewConfiguration.getLongPressTimeout() + 150L)
        instrumentation.runOnMainSync {
            event(key, MotionEvent.ACTION_UP)
            assertEquals(1, holds)
            assertEquals(0, clicks)
            event(key, MotionEvent.ACTION_DOWN)
            key.dispose()
        }
        Thread.sleep(ViewConfiguration.getLongPressTimeout() + 100L)
        instrumentation.runOnMainSync { assertEquals(1, holds); assertEquals(0, clicks) }
    }
    @Test fun repeatingDeleteStartsImmediatelyAndStopsOnRelease() {
        var clicks = 0
        lateinit var key: KeyButton
        scenario.onActivity { activity ->
            key = KeyButton(activity, "⌫", "test", { clicks++ }, repeat = true)
            host.addView(key, FrameLayout.LayoutParams(180, 100)); key.layout(0, 0, 180, 100)
            event(key, MotionEvent.ACTION_DOWN)
            assertEquals(1, clicks)
        }
        Thread.sleep(ViewConfiguration.getLongPressTimeout() + 180L)
        var released = 0
        instrumentation.runOnMainSync {
            event(key, MotionEvent.ACTION_UP)
            released = clicks
            assertTrue(clicks >= 3)
        }
        Thread.sleep(130)
        instrumentation.runOnMainSync { assertEquals("No extra delete after release", released, clicks) }
    }
    @Test fun editorSessionChangesDiscardHeldTapsAlternativesAndRepeatsButNotFreshClicks() {
        var session = Any()
        var taps = 0
        var holds = 0
        var repeats = 0
        lateinit var letter: KeyButton
        lateinit var delete: KeyButton
        scenario.onActivity { activity ->
            letter = KeyButton(activity, "a", "test", { taps++ }, hold = { holds++ }, inputSession = { session })
            delete = KeyButton(activity, "⌫", "test", { repeats++ }, repeat = true, inputSession = { session })
            host.addView(letter, FrameLayout.LayoutParams(180, 100)); letter.layout(0, 0, 180, 100)
            host.addView(delete, FrameLayout.LayoutParams(180, 100)); delete.layout(0, 0, 180, 100)
            event(letter, MotionEvent.ACTION_DOWN)
            session = Any()
            event(letter, MotionEvent.ACTION_UP)
            assertEquals("A release must not carry an old field's tap into its replacement", 0, taps)
            letter.performClick()
            assertEquals("Fresh accessibility clicks are not tied to an old finger", 1, taps)
            event(letter, MotionEvent.ACTION_DOWN)
            event(delete, MotionEvent.ACTION_DOWN)
            assertEquals(1, repeats)
            session = Any()
        }
        Thread.sleep(ViewConfiguration.getLongPressTimeout() + 150L)
        instrumentation.runOnMainSync {
            event(letter, MotionEvent.ACTION_UP); event(delete, MotionEvent.ACTION_UP)
            assertEquals(0, holds)
            assertEquals(1, taps)
            assertEquals("Only the first delete was sent, before the editor changed", 1, repeats)
        }
    }
    private fun descendants(root: View): List<View> = listOf(root) + if (root is ViewGroup) {
        (0 until root.childCount).flatMap { descendants(root.getChildAt(it)) }
    } else emptyList()

    @Test fun fullKeyCellsAndOverlappingFingersPreserveDownOrderAndOneShotShift() {
        scenario.onActivity { activity ->
            val board = KeyboardPanel(activity, InputRouter(activity), floating = true, onClose = {})
            panel = board
            host.addView(board, FrameLayout.LayoutParams(1_024, 800))
            board.measure(View.MeasureSpec.makeMeasureSpec(1_024, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            board.layout(0, 0, 1_024, 800)
            assertTrue(board.isAttachedToWindow)
            fun key(label: String) = descendants(board).first { it.contentDescription == "Key $label" }
            fun bounds(view: View): Rect = Rect(0, 0, view.width, view.height).also { board.offsetDescendantRectToMyCoords(view, it) }
            fun center(label: String): Pair<Float, Float> = bounds(key(label)).let { it.exactCenterX() to it.exactCenterY() }
            val q = bounds(key("q")); val w = bounds(key("w"))
            assertEquals("The visual gap belongs to a key, not a dead layout margin", q.right, w.left)
            var time = SystemClock.uptimeMillis()
            val downTime = time
            fun send(action: Int, points: List<Pair<Int, Pair<Float, Float>>>) {
                val properties = points.map { (id, _) -> MotionEvent.PointerProperties().apply {
                    this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER
                } }.toTypedArray()
                val coords = points.map { (_, point) -> MotionEvent.PointerCoords().apply {
                    x = point.first; y = point.second; pressure = 1f; size = 1f
                } }.toTypedArray()
                val e = MotionEvent.obtain(downTime, time++, action, points.size, properties, coords,
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                try { assertTrue(board.dispatchTouchEvent(e)) } finally { e.recycle() }
            }
            val seam = q.right - 1f to q.exactCenterY() // Inside the visual inset, outside the painted key.
            send(MotionEvent.ACTION_DOWN, listOf(0 to seam))
            send(MotionEvent.ACTION_UP, listOf(0 to seam))
            assertEquals("q", app.draft.snapshot.text)
            app.draft.edit { clear() }
            val a = center("a"); val b = center("b")
            send(MotionEvent.ACTION_DOWN, listOf(0 to a))
            send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(0 to a, 1 to b))
            send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(0 to a, 1 to b))
            send(MotionEvent.ACTION_UP, listOf(0 to a))
            assertEquals("The second finger lifting first must not reverse letters", "ab", app.draft.snapshot.text)
            app.draft.edit { clear() }
            val shift = center("Shift")
            send(MotionEvent.ACTION_DOWN, listOf(0 to shift))
            send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(0 to shift, 1 to a))
            send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(0 to shift, 1 to a))
            send(MotionEvent.ACTION_UP, listOf(0 to shift))
            val qCenter = center("q")
            send(MotionEvent.ACTION_DOWN, listOf(0 to qCenter))
            send(MotionEvent.ACTION_UP, listOf(0 to qCenter))
            assertEquals("Modifier is captured once, not delayed until a later key", "Aq", app.draft.snapshot.text)
            app.draft.edit { clear() }
            send(MotionEvent.ACTION_DOWN, listOf(0 to a))
            board.discardPendingInput() // E.g. IME hidden/restarted while a finger is still down.
            send(MotionEvent.ACTION_UP, listOf(0 to a))
            assertEquals("A hide/restart discards an uncommitted physical tap too", "", app.draft.snapshot.text)
            send(MotionEvent.ACTION_DOWN, listOf(0 to qCenter))
            send(MotionEvent.ACTION_UP, listOf(0 to qCenter))
            assertEquals("q", app.draft.snapshot.text)
        }
    }
}
