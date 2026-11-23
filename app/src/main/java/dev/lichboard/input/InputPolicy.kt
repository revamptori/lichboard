package dev.lichboard.input

import dev.lichboard.data.InputMode

enum class InputRoute { IME, ACCESSIBILITY, DRAFT }
enum class EditCommand { COPY, CUT, PASTE, SELECT_ALL }

/** Chooses a candidate backend; Assist validates the focused non-password editor on execution. */
object InputPolicy {
    fun choose(mode: InputMode, ime: Boolean, assistOptIn: Boolean, assistBackendAvailable: Boolean): InputRoute = when {
        mode == InputMode.CLIPBOARD -> InputRoute.DRAFT
        (mode == InputMode.AUTO || mode == InputMode.IME) && ime -> InputRoute.IME
        (mode == InputMode.AUTO || mode == InputMode.ACCESSIBILITY) && assistOptIn && assistBackendAvailable -> InputRoute.ACCESSIBILITY
        else -> InputRoute.DRAFT
    }
}
