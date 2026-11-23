package dev.lichboard.data

import android.content.Context
import android.content.SharedPreferences
import dev.lichboard.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class InputMode(val title: String, val description: String) {
    AUTO("Automatic", "Active IME first, then optional input assist. Otherwise use a draft."),
    IME("IME only", "Use Lichboard’s active InputConnection. Draft when no editor is available."),
    ACCESSIBILITY("Input assist", "Use the optional accessibility service for supported, focused text fields."),
    CLIPBOARD("Clipboard draft", "Compose privately in memory, then explicitly copy and paste into an app.")
}

enum class BoardTheme(val title: String) {
    MIDNIGHT("Midnight"), AMOLED("AMOLED Black"), DRACULA("Dracula"), NORD("Nord"), LIGHT("Light"), CUSTOM("Custom")
}

data class BoardSettings(
    val onboarded: Boolean = false,
    val floatingEnabled: Boolean = false,
    val floatingKeyboard: Boolean = true,
    val startOnBoot: Boolean = false,
    val keyboardHeight: Int = 340,
    val keyboardWidth: Int = 100,
    val keyboardOpacity: Float = 0.98f,
    val buttonSize: Int = 56,
    val buttonOpacity: Float = 0.92f,
    val theme: BoardTheme = BoardTheme.MIDNIGHT,
    val customBackground: String = "151A24",
    val customAccent: String = "C6F58B",
    val vibration: Boolean = true,
    val sound: Boolean = false,
    val autoHide: Boolean = true,
    val inputMode: InputMode = InputMode.AUTO,
    val accessibilityFallback: Boolean = false,
    val bubbleX: Float = 1f,
    val bubbleY: Float = 0.42f,
    val keyboardX: Float = 0.5f,
    val keyboardY: Float = 1f
)

/** Filter both writes and restored preferences; Standard cannot opt into an absent feature. */
private fun BoardSettings.forEdition(): BoardSettings = if (BuildConfig.INPUT_ASSIST_AVAILABLE) this else copy(
    accessibilityFallback = false,
    inputMode = if (inputMode == InputMode.ACCESSIBILITY) InputMode.AUTO else inputMode
)

val availableInputModes: List<InputMode>
    get() = InputMode.entries.filter { BuildConfig.INPUT_ASSIST_AVAILABLE || it != InputMode.ACCESSIBILITY }

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("lichboard_settings", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()
    val current get() = mutable.value
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> mutable.value = read() }
    init { prefs.registerOnSharedPreferenceChangeListener(listener) }

    fun update(transform: (BoardSettings) -> BoardSettings) {
        val s = transform(current).forEdition()
        prefs.edit().apply {
            putBoolean("onboarded", s.onboarded)
            putBoolean("floating", s.floatingEnabled)
            putBoolean("floating_keyboard", s.floatingKeyboard)
            putBoolean("boot", s.startOnBoot)
            putInt("height", s.keyboardHeight.coerceIn(240, 480))
            putInt("width", s.keyboardWidth.coerceIn(70, 100))
            putFloat("keyboard_opacity", s.keyboardOpacity.coerceIn(.55f, 1f))
            putInt("button_size", s.buttonSize.coerceIn(40, 84))
            putFloat("button_opacity", s.buttonOpacity.coerceIn(.3f, 1f))
            putString("theme", s.theme.name)
            putString("custom_bg", s.customBackground)
            putString("custom_accent", s.customAccent)
            putBoolean("vibration", s.vibration)
            putBoolean("sound", s.sound)
            putBoolean("auto_hide", s.autoHide)
            putString("input_mode", s.inputMode.name)
            putBoolean("assist", s.accessibilityFallback)
            putFloat("bubble_x", s.bubbleX.coerceIn(0f, 1f))
            putFloat("bubble_y", s.bubbleY.coerceIn(0f, 1f))
            putFloat("keyboard_x", s.keyboardX.coerceIn(0f, 1f))
            putFloat("keyboard_y", s.keyboardY.coerceIn(0f, 1f))
        }.apply()
        mutable.value = read()
    }

    fun resetPositions() = update { it.copy(bubbleX = 1f, bubbleY = .42f, keyboardX = .5f, keyboardY = 1f) }

    private inline fun <reified T : Enum<T>> enumValue(key: String, default: T): T =
        enumValues<T>().firstOrNull { it.name == prefs.getString(key, null) } ?: default

    private fun read() = BoardSettings(
        onboarded = prefs.getBoolean("onboarded", false),
        floatingEnabled = prefs.getBoolean("floating", false),
        floatingKeyboard = prefs.getBoolean("floating_keyboard", true),
        startOnBoot = prefs.getBoolean("boot", false),
        keyboardHeight = prefs.getInt("height", 340).coerceIn(240, 480),
        keyboardWidth = prefs.getInt("width", 100).coerceIn(70, 100),
        keyboardOpacity = prefs.getFloat("keyboard_opacity", .98f).coerceIn(.55f, 1f),
        buttonSize = prefs.getInt("button_size", 56).coerceIn(40, 84),
        buttonOpacity = prefs.getFloat("button_opacity", .92f).coerceIn(.3f, 1f),
        theme = enumValue("theme", BoardTheme.MIDNIGHT),
        customBackground = prefs.getString("custom_bg", "151A24") ?: "151A24",
        customAccent = prefs.getString("custom_accent", "C6F58B") ?: "C6F58B",
        vibration = prefs.getBoolean("vibration", true),
        sound = prefs.getBoolean("sound", false),
        autoHide = prefs.getBoolean("auto_hide", true),
        inputMode = enumValue("input_mode", InputMode.AUTO),
        accessibilityFallback = prefs.getBoolean("assist", false),
        bubbleX = prefs.getFloat("bubble_x", 1f).coerceIn(0f, 1f),
        bubbleY = prefs.getFloat("bubble_y", .42f).coerceIn(0f, 1f),
        keyboardX = prefs.getFloat("keyboard_x", .5f).coerceIn(0f, 1f),
        keyboardY = prefs.getFloat("keyboard_y", 1f).coerceIn(0f, 1f)
    ).forEdition()
}
