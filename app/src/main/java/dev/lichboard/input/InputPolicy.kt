package dev.lichboard.input

import dev.lichboard.data.InputMode

enum class InputRoute { IME, ACCESSIBILITY, DRAFT }
enum class EditCommand { COPY, CUT, PASTE, SELECT_ALL }

object InputPolicy {
    fun choose(mode: InputMode, ime: Boolean, assistOptIn: Boolean, accessibleEditor: Boolean): InputRoute = when {
        mode == InputMode.CLIPBOARD -> InputRoute.DRAFT
        (mode == InputMode.AUTO || mode == InputMode.IME) && ime -> InputRoute.IME
        (mode == InputMode.AUTO || mode == InputMode.ACCESSIBILITY) && assistOptIn && accessibleEditor -> InputRoute.ACCESSIBILITY
        else -> InputRoute.DRAFT
    }
}
