package dev.lichboard

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lichboard.data.InputMode
import dev.lichboard.data.availableInputModes
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** A disabled toggle is insufficient: Standard must actually lack the sensitive service. */
@RunWith(AndroidJUnit4::class)
class StandardCapabilitiesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Suppress("DEPRECATION")
    @Test fun installedStandardPackageHasNoAccessibilityServiceOrImplementation() {
        assertFalse(BuildConfig.INPUT_ASSIST_AVAILABLE)
        assertEquals("dev.lichboard", context.packageName)
        val services = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SERVICES).services.orEmpty()
        assertFalse(services.any { it.permission == "android.permission.BIND_ACCESSIBILITY_SERVICE" })
        assertFalse(services.any { it.name.endsWith("LichboardAccessibilityService") })
        val implementation = runCatching {
            Class.forName("dev.lichboard.accessibility.LichboardAccessibilityService", false, context.classLoader)
        }
        assertTrue("The Assist implementation must not be compiled into Standard", implementation.exceptionOrNull() is ClassNotFoundException)
        assertFalse(availableInputModes.contains(InputMode.ACCESSIBILITY))
        assertEquals(3, availableInputModes.size)
    }

    @Test fun standardRejectsAssistInBothPreferenceWritesAndRestoredValues() {
        val settings = context.lichboard.settings
        val previous = settings.current
        try {
            instrumentation.runOnMainSync {
                settings.update { it.copy(inputMode = InputMode.ACCESSIBILITY, accessibilityFallback = true) }
                assertFalse(settings.current.accessibilityFallback)
                assertEquals(InputMode.AUTO, settings.current.inputMode)
                // Simulate preferences left by the pre-split APK, not just our update API.
                context.getSharedPreferences("lichboard_settings", 0).edit()
                    .putString("input_mode", "ACCESSIBILITY").putBoolean("assist", true).commit()
                assertFalse(settings.current.accessibilityFallback)
                assertEquals(InputMode.AUTO, settings.current.inputMode)
            }
        } finally { instrumentation.runOnMainSync { settings.update { previous } } }
    }
}
