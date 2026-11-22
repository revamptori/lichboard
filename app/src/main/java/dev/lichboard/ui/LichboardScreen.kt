@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.lichboard.ui

import android.app.Activity
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lichboard.R
import dev.lichboard.accessibility.AccessBridge
import dev.lichboard.data.BoardSettings
import dev.lichboard.data.BoardTheme
import dev.lichboard.data.InputMode
import dev.lichboard.input.InputRouter
import dev.lichboard.keyboard.BoardPalette
import dev.lichboard.lichboard
import kotlin.math.roundToInt

data class PlatformState(val overlay: Boolean = false, val imeEnabled: Boolean = false, val imeSelected: Boolean = false, val notifications: Boolean = true)
private enum class Tab(val title: String) { BOARD("Board"), STYLE("Style"), CONTROLS("Controls") }

@Composable
fun LichboardScreen(
    platform: PlatformState,
    enableKeyboard: () -> Unit,
    chooseKeyboard: () -> Unit,
    grantOverlay: () -> Unit,
    setFloating: (Boolean) -> Unit,
    openFloating: () -> Unit,
    openAccessibility: () -> Unit,
    requestNotifications: () -> Unit
) {
    val context = LocalContext.current
    val app = context.lichboard
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val running by app.overlayRunning.collectAsStateWithLifecycle()
    val assist by AccessBridge.connected.collectAsStateWithLifecycle()
    val palette = BoardPalette.from(settings)
    val isLight = androidx.core.graphics.ColorUtils.calculateLuminance(palette.background) > .5
    SideEffect {
        (context as? Activity)?.window?.let {
            WindowCompat.getInsetsController(it, it.decorView).isAppearanceLightStatusBars = isLight
            WindowCompat.getInsetsController(it, it.decorView).isAppearanceLightNavigationBars = isLight
        }
    }
    val scheme = if (isLight) lightColorScheme(
        primary = Color(palette.accent), onPrimary = Color(palette.onAccent), background = Color(palette.background),
        surface = Color(palette.key), onSurface = Color(palette.text), onBackground = Color(palette.text),
        onSurfaceVariant = Color(palette.muted), surfaceVariant = Color(palette.special), outline = Color(palette.border)
    ) else darkColorScheme(
        primary = Color(palette.accent), onPrimary = Color(palette.onAccent), background = Color(palette.background),
        surface = Color(palette.special), onSurface = Color(palette.text), onBackground = Color(palette.text),
        onSurfaceVariant = Color(palette.muted), surfaceVariant = Color(palette.key), outline = Color(palette.border)
    )
    var tab by rememberSaveable { mutableStateOf(Tab.BOARD) }
    var testing by rememberSaveable { mutableStateOf(false) }
    var guide by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf(false) }
    var assistMode by remember { mutableStateOf<InputMode?>(null) }
    val update: ((BoardSettings) -> BoardSettings) -> Unit = { app.settings.update(it) }
    MaterialTheme(colorScheme = scheme, typography = Typography(
        headlineLarge = Typography().headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-1.2).sp),
        titleLarge = Typography().titleLarge.copy(fontWeight = FontWeight.SemiBold),
        bodyMedium = Typography().bodyMedium.copy(lineHeight = 22.sp)
    )) {
        Surface(Modifier.fillMaxSize(), color = scheme.background) {
            when {
                !settings.onboarded -> Onboarding(platform, enableKeyboard, chooseKeyboard, grantOverlay,
                    onFinish = { start -> update { it.copy(onboarded = true) }; if (start) setFloating(true) })
                testing -> TestScreen(platform, chooseKeyboard, openFloating) { testing = false }
                else -> Scaffold(containerColor = scheme.background, bottomBar = {
                    NavigationBar(containerColor = scheme.background, tonalElevation = 0.dp) {
                        Tab.entries.forEach { item ->
                            NavigationBarItem(selected = tab == item, onClick = { tab = item },
                                icon = { AppGlyph(item.ordinal, Modifier.size(23.dp), if (tab == item) scheme.primary else scheme.onSurfaceVariant) },
                                label = { Text(item.title, fontSize = 11.sp, fontWeight = FontWeight.Medium) },
                                colors = NavigationBarItemDefaults.colors(indicatorColor = scheme.primary.copy(alpha = .11f), selectedIconColor = scheme.primary, selectedTextColor = scheme.primary))
                        }
                    }
                }) { padding ->
                    Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
                        Spacer(Modifier.height(20.dp))
                        BrandHeader(running)
                        Spacer(Modifier.height(32.dp))
                        when (tab) {
                            Tab.BOARD -> BoardPage(settings, platform, running, openFloating, setFloating,
                                enableKeyboard, chooseKeyboard, grantOverlay, onTest = { testing = true }, onGuide = { guide = true })
                            Tab.STYLE -> StylePage(settings, update)
                            Tab.CONTROLS -> ControlsPage(settings, platform, assist, update, setFloating,
                                onMode = { mode -> if (mode == InputMode.ACCESSIBILITY && !settings.accessibilityFallback) {
                                    assistMode = mode; consent = true
                                } else update { it.copy(inputMode = mode) } },
                                onAssist = { value -> if (value) { assistMode = null; consent = true } else update { it.copy(accessibilityFallback = false) } },
                                openAccessibility = { assistMode = null; consent = true },
                                reset = { app.settings.resetPositions() }, requestNotifications, onGuide = { guide = true })
                        }
                        Spacer(Modifier.height(28.dp))
                        Text("LICHBOARD  /  1.0.0", fontSize = 10.sp, letterSpacing = 2.sp, color = scheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterHorizontally))
                        Spacer(Modifier.height(22.dp))
                    }
                }
            }
        }
        if (guide) AlertDialog(onDismissRequest = { guide = false }, title = { Text("How input really works") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("1. Active editor", fontWeight = FontWeight.SemiBold)
                Text("Enable and select Lichboard as your Android keyboard. With a focused editor, the floating keyboard can use its active InputConnection without stealing focus.")
                Text("2. Optional input assist", fontWeight = FontWeight.SemiBold)
                Text("With your explicit consent, accessibility can read and replace the selected text of a focused, non-password editable field. Custom editors may reject these actions. It cannot send arbitrary hardware keys.")
                Text("3. No input target? Use a draft.", fontWeight = FontWeight.SemiBold)
                Text("Type into an in-memory draft, open Clipboard, and tap Copy draft. Focus a field in the other app and use its Paste action. Android may block clipboard reads while an overlay is in the background.")
                Text("Terminals, remote desktops & games", fontWeight = FontWeight.SemiBold)
                Text("Ctrl, Alt, arrows and F1–F12 are genuine Android KeyEvents through an active IME connection. The receiving app must support them. Overlays cannot bypass secure screens, force-stops, or Android’s injection restrictions.")
                Text("Privacy", fontWeight = FontWeight.SemiBold)
                Text("No internet permission, accounts, analytics, typing history, or saved drafts. Clipboard reads happen only after your explicit action. Drafts disappear when the app process ends.")
            } }, confirmButton = { TextButton(onClick = { guide = false }) { Text("Got it") } })
        if (consent) AlertDialog(onDismissRequest = { consent = false }, title = { Text("Optional input assistance") },
            text = { Text("Lichboard can use Android Accessibility to read the text and selection in the currently focused editable field, and replace that text when you press a floating key.\n\nIt does not monitor your screen, store field contents, read password fields, or send data. Special hardware keys still require an IME connection.\n\nThis is optional. You can use the normal keyboard and clipboard draft without it. Enable “Lichboard input assist” on the next screen. You can revoke access in Android Settings at any time.") },
            confirmButton = { TextButton(onClick = {
                update { it.copy(accessibilityFallback = true, inputMode = assistMode ?: it.inputMode) }
                consent = false; openAccessibility()
            }) { Text("Agree & open settings") } }, dismissButton = { TextButton(onClick = { consent = false }) { Text("Not now") } })
    }
}

@Composable
private fun BrandHeader(running: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Logo(Modifier.size(36.dp))
        Spacer(Modifier.width(10.dp))
        Text("lichboard", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.6).sp)
        Spacer(Modifier.weight(1f))
        Row(Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(5.dp).background(if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
            Spacer(Modifier.width(6.dp)); Text(if (running) "LIVE" else "STANDBY", fontSize = 9.sp, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable
private fun Logo(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.ic_lichboard), contentDescription = "Lichboard logo", modifier = modifier)
}
@Composable
private fun AppGlyph(index: Int, modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.width; val h = size.height; val stroke = w * .065f
        when (index) {
            0 -> {
                drawRoundRect(color, Offset(w * .04f, h * .22f), Size(w * .92f, h * .62f), CornerRadius(w * .1f), style = Stroke(stroke))
                for (r in 0..1) for (c in 0..4) drawCircle(color, w * .027f, Offset(w * (.19f + c * .155f), h * (.39f + r * .16f)))
                drawLine(color, Offset(w * .34f, h * .72f), Offset(w * .66f, h * .72f), stroke, StrokeCap.Round)
            }
            1 -> {
                drawCircle(color, w * .38f, style = Stroke(stroke))
                drawCircle(color, w * .1f, Offset(w * .36f, h * .36f))
                drawCircle(color, w * .07f, Offset(w * .63f, h * .38f))
                drawCircle(color, w * .08f, Offset(w * .38f, h * .63f))
            }
            else -> for (r in 0..2) {
                val y = h * (.22f + r * .28f)
                drawLine(color, Offset(w * .12f, y), Offset(w * .88f, y), stroke, StrokeCap.Round)
                drawCircle(color, w * .09f, Offset(w * (if (r == 1) .67f else .35f), y))
            }
        }
    }
}
@Composable
private fun Eyebrow(text: String) { Text(text.uppercase(), fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary) }
@Composable
private fun SectionTitle(text: String) { Text(text.uppercase(), fontSize = 10.sp, letterSpacing = 1.7.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 26.dp, bottom = 12.dp, start = 2.dp)) }
@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .6f))) {
        Column(Modifier.padding(18.dp), content = content)
    }
}
@Composable
private fun Description(text: String, modifier: Modifier = Modifier) { Text(text, fontSize = 13.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier) }

@Composable
private fun BoardPage(s: BoardSettings, p: PlatformState, running: Boolean, open: () -> Unit, floating: (Boolean) -> Unit,
                      enable: () -> Unit, choose: () -> Unit, overlay: () -> Unit, onTest: () -> Unit, onGuide: () -> Unit) {
    Eyebrow("YOUR KEYS. YOUR SPACE.")
    Spacer(Modifier.height(12.dp))
    Text("A keyboard.\nOn your terms.", style = MaterialTheme.typography.headlineLarge, fontSize = 39.sp, lineHeight = 44.sp)
    Spacer(Modifier.height(12.dp))
    Description("A familiar keyboard, one tap away.\nFloat above your apps. Stay in your flow.")
    Spacer(Modifier.height(24.dp))
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .5f), CircleShape).background(MaterialTheme.colorScheme.background, CircleShape), contentAlignment = Alignment.Center) { Logo(Modifier.size(32.dp)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Floating button", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.height(4.dp))
                Description(if (running) "Ready above your apps" else "Your keyboard’s home base")
            }
            Switch(checked = s.floatingEnabled && p.overlay, onCheckedChange = floating)
        }
        Spacer(Modifier.height(18.dp))
        Button(onClick = open, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(13.dp)) {
            AppGlyph(0, Modifier.size(20.dp), MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(10.dp)); Text("Bring up keyboard", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f)); Text("↗", fontSize = 22.sp)
        }
        Spacer(Modifier.height(12.dp))
        Description("Tap to open · drag to move · hold for controls", Modifier.align(Alignment.CenterHorizontally))
    }
    SectionTitle("Connection check")
    Panel {
        SetupRow("01", "Android keyboard", if (p.imeSelected) "Lichboard is selected" else if (p.imeEnabled) "Enabled · choose Lichboard" else "Enable for direct text input", p.imeSelected,
            if (p.imeEnabled) choose else enable)
        HorizontalDivider(Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = .4f))
        SetupRow("02", "Display over other apps", if (p.overlay) "Overlay access granted" else "Allow your floating workspace", p.overlay, overlay)
    }
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ActionTile("⌨", "Test keyboard", "Try your setup", Modifier.weight(1f), onTest)
        ActionTile("↗", "Input guide", "Know the limits", Modifier.weight(1f), onGuide)
    }
    Spacer(Modifier.height(18.dp))
    Row(verticalAlignment = Alignment.Top) {
        Text("◇", color = MaterialTheme.colorScheme.primary, fontSize = 18.sp)
        Spacer(Modifier.width(9.dp))
        Description("Private by design. No network access or typing history. Android controls where input and overlays are allowed.")
    }
}
@Composable
private fun SetupRow(number: String, title: String, subtitle: String, done: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Text(if (done) "✓" else number, color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp, modifier = Modifier.width(30.dp), fontFamily = FontFamily.Monospace)
        Column(Modifier.weight(1f)) { Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium); Spacer(Modifier.height(3.dp)); Description(subtitle) }
        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 23.sp)
    }
}
@Composable
private fun ActionTile(icon: String, title: String, subtitle: String, modifier: Modifier, click: () -> Unit) {
    Surface(onClick = click, modifier = modifier, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .5f))) {
        Column(Modifier.padding(16.dp)) { Text(icon, color = MaterialTheme.colorScheme.primary, fontSize = 24.sp); Spacer(Modifier.height(12.dp)); Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(4.dp)); Description(subtitle) }
    }
}

@Composable
private fun StylePage(s: BoardSettings, update: ((BoardSettings) -> BoardSettings) -> Unit) {
    Eyebrow("MAKE IT YOURS")
    Text("Less noise.\nMore you.", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 12.dp))
    Spacer(Modifier.height(18.dp))
    KeyboardPreview(s)
    SectionTitle("Color story")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BoardTheme.entries.chunked(2).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { theme ->
                val palette = BoardPalette.from(s.copy(theme = theme))
                val selected = s.theme == theme
                Surface(onClick = { update { it.copy(theme = theme) } }, modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(15.dp), color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = .5f))) {
                    Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(23.dp).clip(CircleShape).background(Color(palette.background)).border(2.dp, Color(palette.accent), CircleShape))
                        Spacer(Modifier.width(9.dp)); Text(theme.title, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        if (selected) Text("✓", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                    }
                }
            }
        } }
    }
    if (s.theme == BoardTheme.CUSTOM) {
        var background by remember(s.customBackground) { mutableStateOf(s.customBackground) }
        var accent by remember(s.customAccent) { mutableStateOf(s.customAccent) }
        val valid = background.matches(Regex("[A-Fa-f0-9]{6}")) && accent.matches(Regex("[A-Fa-f0-9]{6}"))
        Spacer(Modifier.height(14.dp))
        Panel {
            Description("Six-digit hex colors, without #")
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(background, { background = it.take(6) }, label = { Text("Background") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(accent, { accent = it.take(6) }, label = { Text("Accent") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
            TextButton(onClick = { update { it.copy(customBackground = background.uppercase(), customAccent = accent.uppercase()) } }, enabled = valid) { Text("Apply custom colors") }
        }
    }
    SectionTitle("Keyboard geometry")
    Panel {
        SliderSetting("Keyboard height", "${s.keyboardHeight} dp", s.keyboardHeight.toFloat(), 240f..480f) { value -> update { it.copy(keyboardHeight = value.roundToInt()) } }
        SliderSetting("Floating width", "${s.keyboardWidth}%", s.keyboardWidth.toFloat(), 70f..100f) { value -> update { it.copy(keyboardWidth = value.roundToInt()) } }
        SliderSetting("Floating opacity", "${(s.keyboardOpacity * 100).roundToInt()}%", s.keyboardOpacity, .55f..1f) { value -> update { it.copy(keyboardOpacity = value) } }
        Description("Height applies to both keyboards. Width and opacity apply only to the floating keyboard. Landscape height is clamped to keep the board on-screen.")
    }
    SectionTitle("Floating button")
    Panel {
        SliderSetting("Button size", "${s.buttonSize} dp", s.buttonSize.toFloat(), 40f..84f) { value -> update { it.copy(buttonSize = value.roundToInt()) } }
        SliderSetting("Button opacity", "${(s.buttonOpacity * 100).roundToInt()}%", s.buttonOpacity, .3f..1f) { value -> update { it.copy(buttonOpacity = value) } }
    }
}
@Composable
private fun KeyboardPreview(s: BoardSettings) {
    val p = BoardPalette.from(s)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(p.background)).border(1.dp, Color(p.border), RoundedCornerShape(20.dp)).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("lichboard", color = Color(p.accent), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f)); Text("THEME PREVIEW", color = Color(p.muted), fontSize = 8.sp, letterSpacing = 1.sp)
        }
        Spacer(Modifier.height(12.dp))
        listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").forEach { line ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
                line.forEach { char -> Box(Modifier.weight(1f).height(28.dp).clip(RoundedCornerShape(5.dp)).background(Color(p.key)), contentAlignment = Alignment.Center) { Text(char.toString(), color = Color(p.text), fontSize = 11.sp) } }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("Ctrl", "Alt", "space", "↵").forEachIndexed { i, label -> Box(Modifier.weight(if (i == 2) 4f else 1f).height(27.dp).clip(RoundedCornerShape(5.dp)).background(Color(if (i == 3) p.accent else p.special)), contentAlignment = Alignment.Center) {
                Text(label, color = Color(if (i == 3) p.onAccent else p.muted), fontSize = 10.sp)
            } }
        }
    }
}
@Composable
private fun SliderSetting(title: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, change: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(title, fontSize = 14.sp, modifier = Modifier.weight(1f)); Text(valueText, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace) }
    Slider(value = value.coerceIn(range), onValueChange = change, valueRange = range, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun ControlsPage(s: BoardSettings, p: PlatformState, assist: Boolean, update: ((BoardSettings) -> BoardSettings) -> Unit,
    floating: (Boolean) -> Unit, onMode: (InputMode) -> Unit, onAssist: (Boolean) -> Unit, openAccessibility: () -> Unit,
    reset: () -> Unit, notifications: () -> Unit, onGuide: () -> Unit) {
    var resetDone by remember { mutableStateOf(false) }
    Eyebrow("FINE-TUNE YOUR FLOW")
    Text("At your\ncommand.", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 12.dp))
    SectionTitle("Floating workspace")
    Panel {
        ToggleSetting("Floating button", "Keep a movable button above other apps", s.floatingEnabled && p.overlay, floating)
        SettingDivider()
        ToggleSetting("Floating keyboard", "Allow a keyboard without a text field", s.floatingKeyboard) { value -> update { it.copy(floatingKeyboard = value) } }
        SettingDivider()
        ToggleSetting("Start on boot", "Restore your enabled button after unlocking. Android/OEM restrictions may apply.", s.startOnBoot) { value -> update { it.copy(startOnBoot = value) } }
        SettingDivider()
        ToggleSetting("Auto-hide button", "Hide the button while a Lichboard keyboard is visible", s.autoHide) { value -> update { it.copy(autoHide = value) } }
    }
    SectionTitle("Input routing")
    Panel {
        InputMode.entries.forEachIndexed { index, mode ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onMode(mode) }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = s.inputMode == mode, onClick = null)
                Spacer(Modifier.width(13.dp)); Column { Text(mode.title, fontSize = 14.sp, fontWeight = FontWeight.Medium); Spacer(Modifier.height(3.dp)); Description(mode.description) }
            }
            if (index < InputMode.entries.lastIndex) SettingDivider()
        }
    }
    Spacer(Modifier.height(12.dp))
    Panel {
        ToggleSetting("Allow input assist", "Optional accessibility fallback for supported text fields. Does not inject arbitrary keys.", s.accessibilityFallback, onAssist)
        Spacer(Modifier.height(10.dp))
        Description(if (assist) "Android service connected${if (!s.accessibilityFallback) " · disabled inside Lichboard" else ""}" else "Android service not connected · optional")
        TextButton(onClick = openAccessibility) { Text("Input assist setup  ↗") }
    }
    SectionTitle("Key feedback")
    Panel {
        ToggleSetting("Haptic feedback", "Respect Android’s keyboard haptics setting", s.vibration) { value -> update { it.copy(vibration = value) } }
        SettingDivider()
        ToggleSetting("Keypress sounds", "Use Android’s key click sound effects", s.sound) { value -> update { it.copy(sound = value) } }
    }
    SectionTitle("Workspace & privacy")
    Panel {
        TextButton(onClick = { reset(); resetDone = true }, contentPadding = PaddingValues(0.dp)) { Text(if (resetDone) "✓ Positions reset" else "Reset keyboard & button positions") }
        if (!p.notifications) TextButton(onClick = notifications, contentPadding = PaddingValues(0.dp)) { Text("Allow service notifications") }
        TextButton(onClick = onGuide, contentPadding = PaddingValues(0.dp)) { Text("Input limitations & privacy  ↗") }
        Description("No hidden APIs. No root. No network permission. Secure apps can hide overlays; Android may stop background services after a force-stop or under battery restrictions.")
    }
}
@Composable
private fun ToggleSetting(title: String, subtitle: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium); Spacer(Modifier.height(4.dp)); Description(subtitle) }
        Spacer(Modifier.width(10.dp)); Switch(checked = value, onCheckedChange = change)
    }
}
@Composable
private fun SettingDivider() { HorizontalDivider(Modifier.padding(vertical = 13.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = .35f)) }

@Composable
private fun Onboarding(p: PlatformState, enable: () -> Unit, choose: () -> Unit, overlay: () -> Unit, onFinish: (Boolean) -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var startFloating by rememberSaveable { mutableStateOf(true) }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(26.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Logo(Modifier.size(36.dp)); Spacer(Modifier.width(10.dp)); Text("lichboard", fontSize = 21.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.weight(1f)); Text("0${step + 1} / 04", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
        Spacer(Modifier.height(32.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            AnimatedContent(targetState = step, label = "Setup step") { current -> Column {
                when (current) {
                    0 -> {
                        Box(Modifier.fillMaxWidth().height(130.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(112.dp).border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .28f), CircleShape), contentAlignment = Alignment.Center) {
                                Box(Modifier.size(88.dp).background(MaterialTheme.colorScheme.surface, CircleShape), contentAlignment = Alignment.Center) { Logo(Modifier.size(58.dp)) }
                            }
                        }
                        Spacer(Modifier.height(28.dp)); Eyebrow("WELCOME TO LICHBOARD")
                        Text("Your keyboard.\nUnbound.", style = MaterialTheme.typography.headlineLarge, fontSize = 42.sp, lineHeight = 47.sp, modifier = Modifier.padding(vertical = 16.dp))
                        Description("Bring up a full keyboard above your apps—even when there’s no text field. Move it, make it yours, and keep your flow.")
                        Spacer(Modifier.height(24.dp))
                        Panel { Text("Built for real Android", fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(8.dp)); Description("Overlays control visibility, not input permission. Type through a focused editor, optional input assist, or an explicit clipboard draft.") }
                        Spacer(Modifier.height(16.dp)); Description("Local only  ·  No accounts  ·  No typing history")
                    }
                    1 -> {
                        Eyebrow("01 / CONNECT YOUR KEYS")
                        Text("Make yourself\nat home.", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(vertical = 16.dp))
                        Description("Enable Lichboard in Android’s keyboard settings, then select it as your current keyboard. This allows normal text input and special keys in compatible editors.")
                        Spacer(Modifier.height(24.dp))
                        Panel {
                            SetupRow("1", "Enable Lichboard", if (p.imeEnabled) "Enabled in Android" else "Open keyboard settings", p.imeEnabled, enable)
                            Spacer(Modifier.height(20.dp))
                            SetupRow("2", "Choose Lichboard", if (p.imeSelected) "Your current keyboard" else "Open keyboard picker", p.imeSelected, choose)
                        }
                        Spacer(Modifier.height(20.dp)); Description("Android shows a standard warning for every third-party keyboard. Lichboard has no internet permission and does not save what you type.")
                    }
                    2 -> {
                        Eyebrow("02 / FLOAT ABOVE YOUR APPS")
                        Text("Always\nwithin reach.", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(vertical = 16.dp))
                        Description("Allow “Display over other apps” so the movable Lichboard button and keyboard can remain available as you switch apps.")
                        Spacer(Modifier.height(24.dp))
                        Panel { Text(if (p.overlay) "✓ Overlay access granted" else "One Android permission", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary); Spacer(Modifier.height(10.dp)); Description("Tap below and allow Lichboard. A persistent service notification lets you stop the overlay at any time."); Spacer(Modifier.height(10.dp)); OutlinedButton(onClick = overlay) { Text(if (p.overlay) "Review permission" else "Allow display over apps  ↗") } }
                        Spacer(Modifier.height(20.dp)); Description("Secure screens and apps that block overlays remain protected. No accessibility permission is required for the floating button or normal keyboard.")
                    }
                    3 -> {
                        Eyebrow("03 / TAKE IT FOR A SPIN")
                        Text("Ready when\nyou are.", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(vertical = 16.dp))
                        Description("Try your keys below. The floating button stays available when you leave Lichboard.")
                        Spacer(Modifier.height(18.dp)); EditorTest(p, choose)
                        Spacer(Modifier.height(22.dp))
                        Panel { ToggleSetting("Start floating button", if (p.overlay) "Begin with a movable button" else "Overlay access is needed first", startFloating && p.overlay) { startFloating = it; if (it && !p.overlay) overlay() } }
                        Spacer(Modifier.height(16.dp)); Description("Tap the button to open. Drag it to move. Hold it for quick controls. You can change all of this later.")
                    }
                }
            } }
            Spacer(Modifier.height(20.dp))
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.align(Alignment.CenterHorizontally)) { repeat(4) { Box(Modifier.width(if (it == step) 22.dp else 6.dp).height(5.dp).clip(CircleShape).background(if (it == step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)) } }
        Spacer(Modifier.height(20.dp))
        Button(onClick = { if (step < 3) step++ else onFinish(startFloating && p.overlay) }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(15.dp)) { Text(if (step == 0) "Let’s set things up  →" else if (step == 3) "Open Lichboard  →" else "Continue  →", fontWeight = FontWeight.SemiBold) }
        TextButton(onClick = { if (step == 0) onFinish(false) else step-- }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(if (step == 0) "I’ll set up later" else "Back", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
@Composable
private fun EditorTest(p: PlatformState, choose: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Your test space") }, placeholder = { Text("Type something good…") },
        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).focusRequester(focus), shape = RoundedCornerShape(16.dp))
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = { if (p.imeSelected) { focus.requestFocus(); keyboard?.show() } else choose() }) { Text(if (p.imeSelected) "Show system keyboard" else "Choose Lichboard") }
}
@Composable
private fun TestScreen(p: PlatformState, choose: () -> Unit, openFloating: () -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val draft by context.lichboard.draft.state.collectAsStateWithLifecycle()
    var result by remember { mutableStateOf("") }
    androidx.activity.compose.BackHandler(onBack = back)
    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
        TextButton(onClick = back, contentPadding = PaddingValues(0.dp)) { Text("← Back to board") }
        Spacer(Modifier.height(20.dp)); Eyebrow("YOUR TEST SPACE")
        Text("Meet your keys.", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(vertical = 16.dp))
        Description("Focus this field to test IME input. Open the floating keyboard to test the same keys without switching apps. Shortcuts work only when the receiving editor supports them.")
        Spacer(Modifier.height(22.dp)); EditorTest(p, choose)
        Spacer(Modifier.height(14.dp)); Button(onClick = openFloating, modifier = Modifier.fillMaxWidth()) { Text("Open floating keyboard") }
        SectionTitle("In-memory draft")
        Panel {
            Text(draft.preview().takeLast(1000), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            Spacer(Modifier.height(12.dp)); Description("Draft mode never injects text into another app. Copy it, focus a field, then use that app’s Paste action. Nothing here is saved to disk.")
            Row {
                TextButton(onClick = { result = InputRouter(context).copyDraft() }) { Text("Copy draft") }
                TextButton(onClick = { result = InputRouter(context).clearDraft() }) { Text("Clear") }
            }
            if (result.isNotEmpty()) Description(result)
        }
    }
}
