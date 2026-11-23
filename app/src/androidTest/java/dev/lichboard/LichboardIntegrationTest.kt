package dev.lichboard

import android.app.UiAutomation
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.ContentValues
import android.provider.MediaStore
import android.os.Environment
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import dev.lichboard.data.BoardSettings
import dev.lichboard.data.BoardTheme
import dev.lichboard.data.InputMode
import dev.lichboard.ime.ImeBridge
import dev.lichboard.ime.LichboardImeService
import dev.lichboard.input.InputRouter
import dev.lichboard.keyboard.KeyboardPanel
import dev.lichboard.overlay.FloatingService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LichboardIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val app get() = context.lichboard
    private val device = run {
        // UiAutomation otherwise suppresses the real service being tested.
        Configurator.getInstance().uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        UiDevice.getInstance(instrumentation)
    }
    private var scenario: ActivityScenario<ProbeActivity>? = null
    private val component get() = ComponentName(context, LichboardImeService::class.java).flattenToShortString()

    @Before fun setup() {
        device.executeShellCommand("wm dismiss-keyguard")
        device.executeShellCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        device.executeShellCommand("ime enable $component")
        device.executeShellCommand("ime set $component")
        assertEquals("Select the canonical Android IME ID before testing", component,
            device.executeShellCommand("settings get secure default_input_method").trim())
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
        instrumentation.runOnMainSync {
            FloatingService.stop(context)
            app.settings.update { BoardSettings(onboarded = true, autoHide = false, vibration = false) }
            app.draft.edit { clear() }
        }
        condition("Previous overlay service stopped") { !app.overlayRunning.value }
        scenario = ActivityScenario.launch(ProbeActivity::class.java)
        condition("Probe activity has window focus") {
            var focused = false; scenario!!.onActivity { focused = it.hasWindowFocus() }; focused
        }
    }
    @After fun cleanup() {
        instrumentation.runOnMainSync {
            app.settings.update { BoardSettings(onboarded = true, vibration = false) }
            FloatingService.stop(context)
        }
        scenario?.close()
    }
    private fun await(description: String): UiObject2 {
        device.wait(Until.findObject(By.desc(description)), 8_000)?.let { return it }
        val directory = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        device.takeScreenshot(File(directory, "failure-${description.hashCode()}.png"))
        device.dumpWindowHierarchy(File(directory, "failure-${description.hashCode()}.xml"))
        File(directory, "failure-window-state.txt").writeText(device.executeShellCommand("dumpsys window windows"))
        directory.listFiles()?.forEach(::exportEvidence)
        error("Missing accessible view: $description; foreground=${device.currentPackageName}; overlayRunning=${app.overlayRunning.value}; imeVisible=${app.imeVisible.value}; settings=${app.settings.current}")
    }
    private fun exportEvidence(file: File) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, if (file.extension == "png") "image/png" else "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/lichboard-tests-${BuildConfig.FLAVOR}")
        }
        context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.let { uri ->
            context.contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
        }
    }
    private fun click(description: String) {
        val floating = device.findObject(By.desc("Lichboard floating keyboard"))
        (floating?.findObject(By.desc(description)) ?: await(description)).click()
        instrumentation.waitForIdleSync()
    }
    private fun condition(message: String, test: () -> Boolean) {
        val until = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < until) { if (test()) return; Thread.sleep(80) }
        assertTrue(message, test())
    }
    private fun overlay(mode: InputMode = InputMode.AUTO) {
        instrumentation.runOnMainSync {
            app.settings.update { it.copy(floatingEnabled = true, inputMode = mode) }
            assertTrue(FloatingService.start(context, FloatingService.ACTION_SHOW))
        }
        await("Lichboard floating keyboard")
    }
    private fun editorText(): String {
        var result = ""
        scenario!!.onActivity { result = it.editor.text.toString() }
        return result
    }
    @Test fun normalImeTypesEditsAndCapitalizes() {
        scenario!!.onActivity { it.focusEditor() }
        condition("Real editor connection active") { ImeBridge.hasEditor }
        instrumentation.runOnMainSync { ImeBridge.service?.requestShowSelf(0) }
        await("Lichboard system keyboard")
        click("Key h"); click("Key i"); click("Key Shift"); click("Key q")
        condition("IME committed text") { editorText() == "hiQ" }
        click("Key ⌫")
        condition("Backspace works") { editorText() == "hi" }
        click("Key Space"); click("Key 1")
        condition("Number and space work") { editorText() == "hi 1" }
    }
    @Test fun overlayKeepsEditorFocusAndUsesInputConnection() {
        scenario!!.onActivity { it.focusEditor() }
        condition("Editor connection active") { ImeBridge.hasEditor }
        overlay()
        click("Key q"); click("Key w"); click("Key ←"); click("Key e")
        condition("Overlay typed through the active editor") { editorText() == "qew" }
        click("Key Ctrl"); click("Key a"); click("Key x")
        condition("Ctrl+A selected and replaced editor text") { editorText() == "x" }
        assertEquals("", app.draft.snapshot.text)
    }
    @Test fun automaticWithoutAnEditorUsesDraftNotInjection() {
        scenario!!.onActivity { it.noEditor() }
        condition("No active editor connection") { !ImeBridge.hasEditor }
        overlay()
        click("Key q")
        condition("Automatic route keeps no-target text in draft") { app.draft.snapshot.text == "q" }
        assertEquals("", editorText())
    }
    @Test fun independentOverlayHasHonestClipboardDraft() {
        scenario!!.onActivity { it.noEditor() }
        overlay(InputMode.CLIPBOARD)
        click("Key q"); click("Key w"); click("Key ←"); click("Key e")
        condition("Draft editing works without an editor") { app.draft.snapshot.text == "qew" }
        assertEquals("", editorText())
        click("Clipboard"); click("Key Copy draft")
        condition("Copy feedback shown") { device.hasObject(By.textContains("Copied")) }
        click("Key ABC")
        click("Emoji panel"); click("Emoji 😀")
        condition("Emoji committed to draft") { app.draft.snapshot.text.contains("😀") }
        click("Hide floating keyboard")
        assertTrue(device.wait(Until.gone(By.desc("Lichboard floating keyboard")), 8_000))
        assertTrue(app.overlayRunning.value)
    }
    @Test fun longPressAlternativesAndAllFunctionKeysExist() {
        overlay(InputMode.CLIPBOARD)
        await("Key e").longClick()
        click("Alternative é")
        condition("Long-press alternative was inserted") { app.draft.snapshot.text == "é" }
        click("Special keys")
        for (n in 1..12) assertNotNull(await("Key F$n"))
        listOf("Esc", "Tab", "Home", "End", "Ins", "Del", "PgUp", "PgDn").forEach { assertNotNull(await("Key $it")) }
        click("Key F12")
        condition("Unsupported draft special key is explained") { device.hasObject(By.textContains("needs an active IME")) }
    }
    @Test fun specialKeysReachRealRawInputTarget() {
        scenario!!.onActivity { it.focusRaw() }
        condition("Raw editor connection active") { ImeBridge.hasEditor }
        overlay()
        click("Special keys"); click("Key F12")
        condition("F12 dispatched as a real key event") {
            var code = -1; scenario!!.onActivity { code = it.raw.lastKey }; code == KeyEvent.KEYCODE_F12
        }
        click("Key Ctrl"); click("Key F1")
        condition("Ctrl metadata delivered") {
            var valid = false
            scenario!!.onActivity { valid = it.raw.lastKey == KeyEvent.KEYCODE_F1 && it.raw.lastMeta and KeyEvent.META_CTRL_ON != 0 }
            valid
        }
    }
    @Test fun bubbleDragsPersistsAndLongPressOpensControls() {
        instrumentation.runOnMainSync {
            app.settings.update { it.copy(floatingEnabled = true) }; FloatingService.start(context)
        }
        val bubble = await("Lichboard floating button")
        val before = app.settings.current.bubbleX
        val bounds = bubble.visibleBounds
        device.swipe(bounds.centerX(), bounds.centerY(), device.displayWidth / 3, device.displayHeight / 3, 35)
        condition("Position saved after drag") { app.settings.current.bubbleX != before }
        // Inject the hold at the drag endpoint rather than querying Android 11's stale
        // accessibility bounds for a just-moved overlay window.
        device.swipe(device.displayWidth / 3, device.displayHeight / 3,
            device.displayWidth / 3, device.displayHeight / 3, 140)
        await("Lichboard quick settings")
        click("Floating button opacity") // Seekbar receives a genuine user interaction.
    }
    @Test fun overlaySurvivesSwitchingToAnotherApplication() {
        instrumentation.runOnMainSync {
            app.settings.update { it.copy(floatingEnabled = true) }; FloatingService.start(context)
        }
        await("Lichboard floating button")
        device.pressHome()
        await("Lichboard floating button")
        // The launcher is another application. Do not assert overlays on protected Settings screens.
        click("Lichboard floating button")
        await("Lichboard floating keyboard")
        click("Hide floating keyboard")
        await("Lichboard floating button")
    }
    @Test fun composeSettingsNavigateAndApplyTheme() {
        instrumentation.runOnMainSync { ImeBridge.service?.requestHideSelf(0) }
        ActivityScenario.launch(MainActivity::class.java).use {
            assertNotNull(device.wait(Until.findObject(By.text("Bring up keyboard")), 8_000))
            val screenshot = File(context.cacheDir, "settings-board.png")
            device.takeScreenshot(screenshot); exportEvidence(screenshot)
            requireNotNull(device.findObject(By.text("Style"))).click()
            val dracula = device.wait(Until.findObject(By.text("Dracula")), 8_000)
            assertNotNull(dracula); requireNotNull(dracula).click()
            condition("Compose theme control updates real preferences") { app.settings.current.theme == BoardTheme.DRACULA }
            requireNotNull(device.findObject(By.text("Controls"))).click()
            assertNotNull(device.wait(Until.findObject(By.textContains("At your")), 8_000))
            val editionControl = if (BuildConfig.INPUT_ASSIST_AVAILABLE) "Input assist setup  ↗" else "Standard edition · no accessibility service"
            for (attempt in 0 until 6) {
                val candidate = device.findObject(By.text(editionControl))
                if (candidate != null && !candidate.visibleBounds.isEmpty) break
                requireNotNull(device.findObject(By.scrollable(true))).scroll(Direction.DOWN, .65f)
            }
            assertNotNull("Edition-specific controls are visible", device.findObject(By.text(editionControl)))
            if (BuildConfig.INPUT_ASSIST_AVAILABLE) {
                requireNotNull(device.findObject(By.text(editionControl))).click()
                assertNotNull(device.wait(Until.findObject(By.text("Optional input assistance")), 8_000))
                assertFalse("Consent is not granted by merely opening the dialog", app.settings.current.accessibilityFallback)
                requireNotNull(device.findObject(By.text("Not now"))).click()
            } else {
                assertFalse(device.hasObject(By.text("Allow input assist")))
                assertFalse(device.hasObject(By.text("Input assist")))
            }
            val controlsScreenshot = File(context.cacheDir, "settings-controls.png")
            device.takeScreenshot(controlsScreenshot); exportEvidence(controlsScreenshot)
        }
    }
    @Test fun pageRebuildsAndReattachmentDiscardStaleQueuedKeys() {
        lateinit var panel: KeyboardPanel
        lateinit var host: FrameLayout
        fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup) {
            (0 until root.childCount).flatMap { views(root.getChildAt(it)) }
        } else emptyList()
        fun press(description: String) {
            requireNotNull(views(panel).firstOrNull { it.contentDescription == description }).performClick()
        }
        scenario!!.onActivity { activity ->
            app.settings.update { it.copy(inputMode = InputMode.CLIPBOARD) }
            host = FrameLayout(activity)
            activity.setContentView(host)
            panel = KeyboardPanel(activity, InputRouter(activity), floating = true, onClose = {})
            host.addView(panel)
        }
        instrumentation.runOnMainSync {
            assertTrue(panel.isAttachedToWindow)
            val initialViews = views(panel).size
            repeat(24) {
                press("Special keys"); press("Key ABC")
                press("Emoji panel"); press("Key ABC")
                press("Key ?123"); press("Key ABC")
            }
            assertEquals("Only the current page belongs to the view hierarchy", initialViews, views(panel).size)
            // Keep Main busy while requests are queued. Their route cannot query the IME
            // until Main is released, so removing the panel must cancel all of them first.
            repeat(50) { press("Key q") }
            host.removeView(panel)
        }
        instrumentation.waitForIdleSync()
        assertEquals("Detached keys never reach the draft", "", app.draft.snapshot.text)
        scenario!!.onActivity {
            host.addView(panel)
            press("Key w")
        }
        condition("A reattached panel accepts fresh input only") { app.draft.snapshot.text == "w" }
        scenario!!.onActivity { panel.dispose(); host.removeView(panel) }
    }
    @Test fun everyThemeAndResizeRendersWithoutLosingDraft() {
        overlay(InputMode.CLIPBOARD)
        click("Key a")
        for (theme in BoardTheme.entries) {
            instrumentation.runOnMainSync { app.settings.update { it.copy(theme = theme, keyboardHeight = 360, keyboardWidth = 90, buttonSize = 64) } }
            click("Key b")
        }
        condition("Theme changes preserve the draft") { app.draft.snapshot.text == "abbbbbb" }
        File(context.getExternalFilesDir(null), "screenshots").mkdirs()
        val screenshot = File(context.getExternalFilesDir(null), "screenshots/floating-keyboard.png")
        device.takeScreenshot(screenshot)
        exportEvidence(screenshot)
    }
}
