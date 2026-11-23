package dev.lichboard.input

import dev.lichboard.data.InputMode
import org.junit.Assert.assertEquals
import org.junit.Test

class InputPolicyTest {
    @Test fun automaticPrioritizesIme() = assertEquals(InputRoute.IME, InputPolicy.choose(InputMode.AUTO, true, true, true))
    @Test fun assistRequiresExplicitConsent() = assertEquals(InputRoute.DRAFT, InputPolicy.choose(InputMode.AUTO, false, false, true))
    @Test fun assistRequiresFocusedEditableTarget() = assertEquals(InputRoute.DRAFT, InputPolicy.choose(InputMode.AUTO, false, true, false))
    @Test fun automaticCanUseAssist() = assertEquals(InputRoute.ACCESSIBILITY, InputPolicy.choose(InputMode.AUTO, false, true, true))
    @Test fun imeOnlyNeverUsesAccessibility() = assertEquals(InputRoute.DRAFT, InputPolicy.choose(InputMode.IME, false, true, true))
    @Test fun clipboardNeverInjects() = assertEquals(InputRoute.DRAFT, InputPolicy.choose(InputMode.CLIPBOARD, true, true, true))
    @Test fun explicitAssistNeverUsesIme() = assertEquals(InputRoute.ACCESSIBILITY, InputPolicy.choose(InputMode.ACCESSIBILITY, true, true, true))
    @Test fun explicitAssistStillNeedsConsent() = assertEquals(InputRoute.DRAFT, InputPolicy.choose(InputMode.ACCESSIBILITY, true, false, true))
}
