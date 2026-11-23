package dev.lichboard

import android.app.UiAutomation
import android.content.Context
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lichboard.accessibility.AccessBridge
import dev.lichboard.data.BoardSettings
import dev.lichboard.data.InputMode
import dev.lichboard.input.InputRouter
import dev.lichboard.overlay.FloatingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run in a fresh instrumentation invocation; see scripts/device-test.sh. */
@RunWith(AndroidJUnit4::class)
class AccessibilityIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val app get() = context.lichboard
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private fun condition(message: String, predicate: () -> Boolean) {
        val end = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < end) { if (predicate()) return; Thread.sleep(80) }
        assertTrue(message, predicate())
    }

    @Test fun focusedInputAssistHonorsConsentHintsDeletionAndPasswords() {
        // Use UiAutomation only as the test device's shell, never as a second node-querying
        // accessibility client. Android 11 otherwise shares its node cache with the service
        // in this instrumented process and can deadlock when the target is this same process.
        val originalInfo = automation.serviceInfo
        val quietInfo = automation.serviceInfo.apply {
            eventTypes = 0
            flags = 0
            packageNames = arrayOf("dev.lichboard.test.silenced")
        }
        automation.serviceInfo = quietInfo
        val previousServices = shell("settings get secure enabled_accessibility_services").trim()
        val previousEnabled = shell("settings get secure accessibility_enabled").trim()
        var scenario: ActivityScenario<ProbeActivity>? = null
        try {
            shell("wm dismiss-keyguard")
            shell("appops set dev.lichboard SYSTEM_ALERT_WINDOW allow")
            shell("ime enable dev.lichboard/.ime.LichboardImeService")
            shell("ime set dev.lichboard/.ime.LichboardImeService")
            shell("settings put secure enabled_accessibility_services dev.lichboard/.accessibility.LichboardAccessibilityService")
            shell("settings put secure accessibility_enabled 1")
            condition("Real accessibility service connected") { AccessBridge.connected.value }
            instrumentation.runOnMainSync {
                app.settings.update { BoardSettings(onboarded = true, floatingEnabled = true, inputMode = InputMode.ACCESSIBILITY, autoHide = false, vibration = false) }
                app.draft.edit { clear() }
            }
            val host = ActivityScenario.launch(ProbeActivity::class.java)
            scenario = host
            condition("Test window focused") {
                var focused = false; host.onActivity { focused = it.hasWindowFocus() }; focused
            }
            host.onActivity { it.focusEditor() }
            condition("Focused editable accessibility node") { AccessBridge.service?.hasEditableTarget() == true }
            instrumentation.runOnMainSync { assertTrue(FloatingService.start(context, FloatingService.ACTION_SHOW)) }
            condition("Real overlay service started") { app.overlayRunning.value }
            val router = InputRouter(context)
            fun text(): String { var value = ""; host.onActivity { value = it.editor.text.toString() }; return value }

            val refused = runBlocking(Dispatchers.Default) { router.text("z", 0) }
            assertTrue(refused, refused.startsWith("DRAFT"))
            assertEquals("", text())
            assertEquals("z", app.draft.snapshot.text)
            instrumentation.runOnMainSync {
                app.draft.edit { clear() }
                app.settings.update { it.copy(accessibilityFallback = true) }
            }
            val first = runBlocking(Dispatchers.Default) { router.text("h", 0) }
            val second = runBlocking(Dispatchers.Default) { router.text("i", 0) }
            assertEquals("$first / $second; draft=${app.draft.snapshot}", "hi", text())
            val deletion = runBlocking(Dispatchers.Default) { router.key(KeyEvent.KEYCODE_DEL, 0) }
            assertEquals(deletion, "h", text())
            assertEquals("", app.draft.snapshot.text)

            host.onActivity {
                it.editor.setText("")
                it.editor.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                it.focusEditor()
            }
            condition("Password fields excluded from assist") { AccessBridge.service?.hasEditableTarget() == false }
            val passwordRefusal = runBlocking(Dispatchers.Default) { router.text("x", 0) }
            assertTrue(passwordRefusal, passwordRefusal.startsWith("DRAFT"))
            assertEquals("", text())
            assertEquals("x", app.draft.snapshot.text)
        } finally {
            instrumentation.runOnMainSync {
                app.settings.update { BoardSettings(onboarded = true, vibration = false) }
                FloatingService.stop(context)
            }
            scenario?.close()
            if (previousServices.isEmpty() || previousServices == "null") shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services $previousServices")
            if (previousEnabled == "null") shell("settings delete secure accessibility_enabled")
            else shell("settings put secure accessibility_enabled $previousEnabled")
            condition("Service disconnected after test") { !AccessBridge.connected.value }
            automation.serviceInfo = originalInfo
        }
    }
}
