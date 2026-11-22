package dev.lichboard

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import dev.lichboard.ime.LichboardImeService
import dev.lichboard.overlay.FloatingService
import dev.lichboard.ui.LichboardScreen
import dev.lichboard.ui.PlatformState

class MainActivity : ComponentActivity() {
    private var platform by mutableStateOf(PlatformState())
    private var pendingFloating = false
    private var pendingShow = false
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPlatform() }
    private val overlayPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshPlatform()
        if (Settings.canDrawOverlays(this) && pendingFloating) startFloating(pendingShow)
        pendingFloating = false; pendingShow = false
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingFloating = savedInstanceState?.getBoolean("pending_float") ?: false
        pendingShow = savedInstanceState?.getBoolean("pending_show") ?: false
        WindowCompat.setDecorFitsSystemWindows(window, false)
        refreshPlatform()
        setContent {
            LichboardScreen(platform,
                enableKeyboard = { openSettings(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                chooseKeyboard = { getSystemService(InputMethodManager::class.java).showInputMethodPicker() },
                grantOverlay = { requestOverlay(false, false) },
                setFloating = { value ->
                    if (value) requestFloating(false) else {
                        lichboard.settings.update { it.copy(floatingEnabled = false) }; FloatingService.stop(this)
                    }
                },
                openFloating = { requestFloating(true) },
                openAccessibility = { openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                requestNotifications = { requestNotifications(force = true) })
        }
    }
    override fun onResume() {
        super.onResume(); refreshPlatform()
        if (lichboard.settings.current.floatingEnabled && platform.overlay && !lichboard.overlayRunning.value) FloatingService.start(this)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pending_float", pendingFloating); outState.putBoolean("pending_show", pendingShow)
        super.onSaveInstanceState(outState)
    }
    private fun refreshPlatform() {
        val component = ComponentName(this, LichboardImeService::class.java)
        val imm = getSystemService(InputMethodManager::class.java)
        platform = PlatformState(
            overlay = Settings.canDrawOverlays(this),
            imeEnabled = imm.enabledInputMethodList.any { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) == component },
            imeSelected = ComponentName.unflattenFromString(Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()) == component,
            notifications = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        )
    }
    private fun requestFloating(show: Boolean) {
        if (!Settings.canDrawOverlays(this)) requestOverlay(true, show) else startFloating(show)
    }
    private fun requestOverlay(enableAfter: Boolean, show: Boolean) {
        pendingFloating = enableAfter; pendingShow = show
        overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }
    private fun startFloating(show: Boolean) {
        lichboard.settings.update { it.copy(floatingEnabled = true, floatingKeyboard = if (show) true else it.floatingKeyboard) }
        if (!FloatingService.start(this, if (show) FloatingService.ACTION_SHOW else null)) {
            lichboard.settings.update { it.copy(floatingEnabled = false) }
            Toast.makeText(this, "Android could not start the overlay. Reopen Lichboard and try again.", Toast.LENGTH_LONG).show()
        } else requestNotifications()
    }
    private fun requestNotifications(force: Boolean = false) {
        val preferences = getSharedPreferences("permission_ui", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            (force || !preferences.getBoolean("notification_prompted", false))) {
            if (force && preferences.getBoolean("notification_prompted", false) &&
                !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                return
            }
            preferences.edit().putBoolean("notification_prompted", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    private fun openSettings(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, "Open Android Settings to configure this option.", Toast.LENGTH_LONG).show()
        }
    }
}
