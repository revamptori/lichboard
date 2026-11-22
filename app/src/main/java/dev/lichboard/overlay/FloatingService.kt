package dev.lichboard.overlay

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.lichboard.MainActivity
import dev.lichboard.R
import dev.lichboard.data.BoardSettings
import dev.lichboard.data.BoardTheme
import dev.lichboard.ime.ImeBridge
import dev.lichboard.input.InputRouter
import dev.lichboard.keyboard.BoardPalette
import dev.lichboard.keyboard.KeyboardPanel
import dev.lichboard.lichboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class FloatingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var wm: WindowManager
    private var bubble: BubbleView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var keyboard: KeyboardPanel? = null
    private var keyboardParams: WindowManager.LayoutParams? = null
    private var quick: View? = null
    private var locked = false
    private var receiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            locked = intent.action == Intent.ACTION_SCREEN_OFF || isLocked()
            if (locked) { hideKeyboard(); hideQuick() }
            updateVisibility()
        }
    }
    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        locked = isLocked()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Floating keyboard", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Keeps your user-enabled floating keyboard button available. Stop it here at any time."
            setShowBadge(false)
        })
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION, notification())
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        scope.launch { lichboard.settings.state.collect { render(it) } }
        scope.launch { lichboard.imeVisible.collect { updateVisibility() } }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            lichboard.settings.update { it.copy(floatingEnabled = false) }
            stopSelf(); return START_NOT_STICKY
        }
        if (!lichboard.settings.current.floatingEnabled || !Settings.canDrawOverlays(this)) {
            stopSelf(); return START_NOT_STICKY
        }
        render(lichboard.settings.current)
        when (intent?.action) {
            ACTION_TOGGLE -> if (keyboard == null) showKeyboard() else hideKeyboard()
            ACTION_SHOW -> showKeyboard()
            ACTION_HIDE -> hideKeyboard()
        }
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun command(action: String, code: Int) = PendingIntent.getForegroundService(this, code,
            Intent(this, FloatingService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("Lichboard is within reach")
            .setContentText("Tap the floating button, or open the keyboard here.")
            .setContentIntent(open).setOngoing(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Keyboard", command(ACTION_TOGGLE, 1))
            .addAction(0, "Hide keyboard", command(ACTION_HIDE, 2))
            .addAction(0, "Stop", command(ACTION_STOP, 3)).build()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun safeArea(): Rect {
        val metrics = wm.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        return Rect(insets.left + dp(6), insets.top + dp(6), metrics.bounds.width() - insets.right - dp(6),
            metrics.bounds.height() - insets.bottom - dp(6))
    }
    private fun params(width: Int, height: Int) = WindowManager.LayoutParams(width, height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.START
        setFitInsetsTypes(0)
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
    }
    private fun place(p: WindowManager.LayoutParams, x: Float, y: Float) {
        val area = safeArea()
        p.x = area.left + ((area.width() - p.width).coerceAtLeast(0) * x).roundToInt()
        p.y = area.top + ((area.height() - p.height).coerceAtLeast(0) * y).roundToInt()
    }
    private fun move(view: View, p: WindowManager.LayoutParams, dx: Float, dy: Float, save: Boolean, button: Boolean) {
        val area = safeArea()
        p.x = (p.x + dx.roundToInt()).coerceIn(area.left, maxOf(area.left, area.right - p.width))
        p.y = (p.y + dy.roundToInt()).coerceIn(area.top, maxOf(area.top, area.bottom - p.height))
        update(view, p)
        if (save) {
            val x = (p.x - area.left).toFloat() / (area.width() - p.width).coerceAtLeast(1)
            val y = (p.y - area.top).toFloat() / (area.height() - p.height).coerceAtLeast(1)
            lichboard.settings.update { if (button) it.copy(bubbleX = x, bubbleY = y) else it.copy(keyboardX = x, keyboardY = y) }
            view.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }
    }
    private fun render(s: BoardSettings) {
        if (!s.floatingEnabled || !Settings.canDrawOverlays(this)) { stopSelf(); return }
        if (bubble == null) {
            val p = params(dp(s.buttonSize), dp(s.buttonSize))
            val view = BubbleView(this, BoardPalette.from(s), tap = {
                hideQuick(); if (keyboard == null) showKeyboard() else hideKeyboard()
            }, longPress = { if (quick == null) showQuick() else hideQuick() }, move = { dx, dy, save ->
                bubble?.let { move(it, p, dx, dy, save, true) }
            })
            p.title = "Lichboard floating button"
            place(p, s.bubbleX, s.bubbleY); p.alpha = s.buttonOpacity
            if (!add(view, p)) { stopSelf(); return }
            bubble = view; bubbleParams = p
            lichboard.overlayRunning.value = true
        }
        bubble?.let { view -> bubbleParams?.let { p ->
            p.width = dp(s.buttonSize); p.height = dp(s.buttonSize); p.alpha = s.buttonOpacity
            place(p, s.bubbleX, s.bubbleY); view.palette = BoardPalette.from(s); view.invalidate(); update(view, p)
        } }
        if (!s.floatingKeyboard) hideKeyboard()
        keyboard?.let { view -> keyboardParams?.let { p ->
            keyboardDimensions(p, s); place(p, s.keyboardX, s.keyboardY); p.alpha = s.keyboardOpacity
            view.updateSettings(s); update(view, p)
        } }
        updateVisibility()
    }
    private fun keyboardDimensions(p: WindowManager.LayoutParams, s: BoardSettings) {
        val area = safeArea()
        p.width = (area.width() * s.keyboardWidth / 100f).roundToInt()
        p.height = minOf(dp(s.keyboardHeight), (area.height() * .78f).roundToInt())
    }
    private fun showKeyboard() {
        if (locked || isLocked()) return
        if (!lichboard.settings.current.floatingKeyboard) {
            Toast.makeText(this, "Enable floating keyboard in Lichboard → Controls", Toast.LENGTH_LONG).show(); return
        }
        hideQuick()
        keyboard?.let { it.refreshStatus(); return }
        ImeBridge.service?.requestHideSelf(0)
        val s = lichboard.settings.current
        val p = params(0, 0)
        keyboardDimensions(p, s); place(p, s.keyboardX, s.keyboardY); p.alpha = s.keyboardOpacity
        p.title = "Lichboard floating keyboard"
        val panel = KeyboardPanel(this, InputRouter(this), floating = true, onClose = { hideKeyboard() },
            onDrag = { dx, dy, save -> keyboard?.let { move(it, p, dx, dy, save, false) } })
        if (add(panel, p)) {
            keyboard = panel; keyboardParams = p
            panel.alpha = 0f; panel.animate().alpha(1f).setDuration(130).start()
            updateVisibility()
        } else panel.dispose()
    }
    private fun hideKeyboard() {
        keyboard?.let { it.dispose(); remove(it) }
        keyboard = null; keyboardParams = null
        updateVisibility()
    }
    private fun isLocked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked
    private fun updateVisibility() {
        val hide = locked || (lichboard.settings.current.autoHide && (keyboard != null || lichboard.imeVisible.value))
        bubble?.visibility = if (hide) View.GONE else View.VISIBLE
    }
    private fun showQuick() {
        if (locked) return
        val s = lichboard.settings.current
        val palette = BoardPalette.from(s)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(palette.background); cornerRadius = dp(18).toFloat(); setStroke(dp(1), palette.border)
            }
            contentDescription = "Lichboard quick settings"
        }
        fun label(value: String) = TextView(this).apply { text = value; textSize = 13f; setTextColor(palette.text) }
        fun button(value: String, action: () -> Unit) {
            box.addView(Button(this).apply {
                text = value; isAllCaps = false; setTextColor(palette.text)
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(-1, dp(44)))
        }
        box.addView(label("LICHBOARD  /  QUICK CONTROLS"))
        box.addView(label("Button opacity"))
        box.addView(SeekBar(this).apply {
            max = 70; progress = (s.buttonOpacity * 100 - 30).roundToInt()
            contentDescription = "Floating button opacity"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) lichboard.settings.update { it.copy(buttonOpacity = (progress + 30) / 100f) }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(-1, dp(34)))
        button(if (keyboard == null) "Open keyboard" else "Hide keyboard") { hideQuick(); if (keyboard == null) showKeyboard() else hideKeyboard() }
        button("Theme: ${s.theme.title} · next") {
            val themes = BoardTheme.entries
            lichboard.settings.update { it.copy(theme = themes[(it.theme.ordinal + 1) % themes.size]) }
            hideQuick(); showQuick()
        }
        button("All settings") { hideQuick(); startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        button("Stop floating button") { lichboard.settings.update { it.copy(floatingEnabled = false) }; stopSelf() }
        val area = safeArea()
        val width = minOf(dp(290), area.width())
        box.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(area.height(), View.MeasureSpec.AT_MOST))
        val p = params(width, box.measuredHeight).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            title = "Lichboard quick settings"
            x = (bubbleParams?.x ?: area.left).coerceIn(area.left, maxOf(area.left, area.right - width))
            y = (bubbleParams?.y ?: area.top).coerceIn(area.top, maxOf(area.top, area.bottom - box.measuredHeight))
        }
        box.setOnTouchListener { _, event -> if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) { hideQuick(); true } else false }
        if (add(box, p)) quick = box
    }
    private fun hideQuick() { quick?.let(::remove); quick = null }
    private fun add(view: View, p: WindowManager.LayoutParams): Boolean = try { wm.addView(view, p); true }
        catch (_: SecurityException) { false } catch (_: WindowManager.BadTokenException) { false }
    private fun update(view: View, p: WindowManager.LayoutParams) {
        try { if (view.isAttachedToWindow) wm.updateViewLayout(view, p) }
        catch (_: IllegalArgumentException) { stopSelf() } catch (_: SecurityException) { stopSelf() }
    }
    private fun remove(view: View) { try { wm.removeViewImmediate(view) } catch (_: IllegalArgumentException) { /* Already removed by Android. */ } }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); hideQuick(); render(lichboard.settings.current) }
    override fun onDestroy() {
        scope.cancel()
        if (receiverRegistered) unregisterReceiver(screenReceiver)
        hideQuick(); hideKeyboard(); bubble?.let(::remove)
        bubble = null; bubbleParams = null
        lichboard.overlayRunning.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object {
        const val ACTION_TOGGLE = "dev.lichboard.TOGGLE_KEYBOARD"
        const val ACTION_SHOW = "dev.lichboard.SHOW_KEYBOARD"
        const val ACTION_HIDE = "dev.lichboard.HIDE_KEYBOARD"
        const val ACTION_STOP = "dev.lichboard.STOP_FLOATING"
        private const val CHANNEL = "lichboard_floating"
        private const val NOTIFICATION = 17
        fun start(context: Context, action: String? = null): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, FloatingService::class.java).apply { this.action = action })
            true
        } catch (_: IllegalStateException) { false } catch (_: SecurityException) { false }
        fun stop(context: Context) { context.stopService(Intent(context, FloatingService::class.java)) }
    }
}
