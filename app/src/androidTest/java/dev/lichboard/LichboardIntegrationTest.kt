package dev.lichboard

import android.app.UiAutomation
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.ContentValues
import android.provider.MediaStore
import android.os.Environment
import android.os.Build
import android.os.SystemClock
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
import org.json.JSONObject

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
            put(MediaStore.Downloads.MIME_TYPE, when (file.extension) {
                "png" -> "image/png"
                "json" -> "application/json"
                "xml" -> "text/xml"
                else -> "text/plain"
            })
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
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup) {
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) }
    } else emptyList()
    private fun systemPanel(): KeyboardPanel = views(requireNotNull(ImeBridge.service?.window?.window?.decorView))
        .filterIsInstance<KeyboardPanel>().first()
    private fun key(root: View, description: String): View = requireNotNull(views(root).firstOrNull { it.contentDescription == description })
    private fun showIme() {
        scenario!!.onActivity { it.focusEditor() }
        condition("Real editor connection active") { ImeBridge.hasEditor }
        instrumentation.runOnMainSync { ImeBridge.service?.requestShowSelf(0) }
        await("Lichboard system keyboard")
    }
    private fun distribution(values: List<Double>): JSONObject {
        val sorted = values.sorted()
        fun percentile(p: Double) = sorted[kotlin.math.ceil(p * sorted.size).toInt().coerceIn(1, sorted.size) - 1]
        return JSONObject().put("p50", percentile(.50)).put("p95", percentile(.95)).put("max", sorted.last())
    }
    private fun performanceReport(name: String, report: JSONObject) {
        report.put("apiLevel", Build.VERSION.SDK_INT).put("edition", BuildConfig.FLAVOR)
        val file = File(context.cacheDir, name)
        file.writeText(report.toString(2))
        exportEvidence(file)
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
    @Test fun pageRebuildsAndReattachmentDoNotDropReadyInputOrSendDetachedKeys() {
        lateinit var panel: KeyboardPanel
        lateinit var host: FrameLayout
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
            // Ready actions now complete on this Main turn, rather than waiting on a CPU
            // worker to bounce back here. A detach must not retroactively discard sent input.
            repeat(512) { press("Key q") }
            assertEquals("q".repeat(512), app.draft.snapshot.text)
            host.removeView(panel)
            repeat(50) { press("Key x") } // Detached callbacks must not send anything.
        }
        instrumentation.waitForIdleSync()
        assertEquals("Detached keys never reach the draft", "q".repeat(512), app.draft.snapshot.text)
        scenario!!.onActivity {
            host.addView(panel)
            press("Key w")
        }
        condition("A reattached panel accepts fresh input only") { app.draft.snapshot.text == "q".repeat(512) + "w" }
        scenario!!.onActivity { panel.dispose(); host.removeView(panel) }
    }
    @Test fun realImeAcceptsA520CharacterBurstWithoutOverflowAndDeletesWithoutSelectionQueries() {
        showIme()
        val expected = "qwertyuiopasdfghjklzxcvbnm".repeat(20)
        val dispatchMs = mutableListOf<Double>()
        scenario!!.onActivity { it.editor.recording = true }
        instrumentation.runOnMainSync {
            val panel = systemPanel()
            val keys = expected.toSet().associateWith { key(panel, "Key $it") }
            expected.forEach { char ->
                val start = SystemClock.elapsedRealtimeNanos()
                keys.getValue(char).performClick()
                dispatchMs += (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
            }
        }
        condition("All 520 burst characters reach the real editor in order") { editorText() == expected }
        instrumentation.runOnMainSync {
            val backspace = key(systemPanel(), "Key ⌫")
            repeat(128) { backspace.performClick() }
        }
        condition("128 rapid backspaces are not dropped") { editorText() == expected.dropLast(128) }
        var queries = -1
        var deletes = 0
        scenario!!.onActivity { queries = it.editor.selectedTextQueries; deletes = it.editor.codePointDeletes }
        performanceReport("input-burst.json", JSONObject()
            .put("test", "actual-ime-burst").put("characters", expected.length).put("backspaces", 128)
            .put("loss", 0).put("orderMatches", true).put("selectedTextQueries", queries)
            .put("codePointDeletes", deletes).put("dispatchCallMs", distribution(dispatchMs))
            .put("scope", "Debug AOSP emulator. Direct key activation through actual IME to real EditText. Burst deliberately keeps Main busy; not touch-to-pixel latency."))
        assertEquals("Backspace must not perform synchronous selected-text IPC per key", 0, queries)
        assertTrue("Known collapsed cursor uses code-point-safe deletion", deletes > 0)
        assertEquals("", app.draft.snapshot.text)
    }

    @Test fun recordsActivationToRealInputConnectionLatencyAtRapidCadence() {
        showIme()
        // Warm the path before recording. The timing includes the actual target IC receiving
        // commitText, not just offer() returning, but excludes touch recognition and rendering.
        click("Key q")
        condition("Warm-up delivered") { editorText() == "q" }
        val expected = "thequickbrownfoxjumpsoverthelazydog".repeat(3)
        lateinit var keys: Map<Char, View>
        scenario!!.onActivity { it.editor.setText(""); it.editor.commits.clear(); it.editor.recording = true }
        instrumentation.runOnMainSync { keys = expected.toSet().associateWith { key(systemPanel(), "Key $it") } }
        val pressed = mutableListOf<Long>()
        val dispatchMs = mutableListOf<Double>()
        expected.forEach { char ->
            instrumentation.runOnMainSync {
                val start = SystemClock.elapsedRealtimeNanos()
                pressed += start
                keys.getValue(char).performClick()
                dispatchMs += (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
            }
            Thread.sleep(8) // Instrumentation thread only; no production debounce or timer.
        }
        condition("Every rapidly activated key reaches the target in order") { editorText() == expected }
        var commits = emptyList<ProbeActivity.RecordingEditor.Commit>()
        scenario!!.onActivity { commits = it.editor.commits.toList() }
        assertEquals(expected.map { it.toString() }, commits.map { it.value })
        val deliveryMs = commits.indices.map { (commits[it].receivedNanos - pressed[it]) / 1_000_000.0 }
        assertTrue(deliveryMs.all { it >= 0 })
        val delivery = distribution(deliveryMs)
        performanceReport("input-latency.json", JSONObject()
            .put("test", "actual-ime-activation-to-target-commit").put("samples", expected.length).put("cadenceMs", 8)
            .put("loss", 0).put("orderMatches", true).put("dispatchCallMs", distribution(dispatchMs))
            .put("activationToCommitMs", delivery).put("regressionBudgetP95Ms", 100)
            .put("scope", "Debug AOSP emulator, actual IME and real EditText. elapsedRealtimeNanos at direct key activation and target commitText entry. Not physical touch-to-display latency or a phone speed guarantee."))
        assertTrue("Gross dispatch/backlog regression: $delivery", delivery.getDouble("p95") < 100)
    }

    @Test fun imeUnicodeDeletionAndSelectionReplacementRemainCorrect() {
        showIme()
        var session: Any? = null
        instrumentation.runOnMainSync { session = ImeBridge.service?.inputSession }
        scenario!!.onActivity {
            it.editor.setText("a😀b"); it.editor.setSelection(it.editor.length()); it.focusEditor()
        }
        condition("New editor state delivered to IME") { ImeBridge.service?.inputSession !== session }
        click("Key ⌫"); condition("ASCII deleted") { editorText() == "a😀" }
        click("Key ⌫"); condition("Entire supplementary code point deleted") { editorText() == "a" }
        instrumentation.runOnMainSync { session = ImeBridge.service?.inputSession }
        scenario!!.onActivity {
            it.editor.setText("abcd"); it.editor.setSelection(1, 3); it.focusEditor()
        }
        condition("Selection delivered on restarted connection") { ImeBridge.service?.inputSession !== session }
        click("Key ⌫"); condition("Backspace deletes selected text, not the preceding character") { editorText() == "ad" }
        click("Key q"); condition("Typing after deletion uses the correct cursor") { editorText() == "aqd" }
    }

    @Test fun changingRealEditorsNeverReplaysOldKeysIntoTheNewField() {
        showIme()
        click("Key a"); condition("First field received input") { editorText() == "a" }
        var session: Any? = null
        instrumentation.runOnMainSync {
            session = ImeBridge.service?.inputSession
            // The already-sent q can only belong to the old InputConnection. It may be
            // accepted there or invalidated by Android; it must not be retried in field two.
            key(systemPanel(), "Key q").performClick()
        }
        scenario!!.onActivity { it.focusOtherEditor() }
        condition("IME started the second editor") { ImeBridge.hasEditor && ImeBridge.service?.inputSession !== session }
        await("Lichboard system keyboard")
        instrumentation.runOnMainSync {
            val panel = systemPanel()
            "new".forEach { key(panel, "Key $it").performClick() }
        }
        condition("Only fresh keys reach the second field") {
            var text = ""; scenario!!.onActivity { text = it.otherEditor.text.toString() }; text == "new"
        }
        assertEquals("No old input is replayed through a fallback backend", "", app.draft.snapshot.text)
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
