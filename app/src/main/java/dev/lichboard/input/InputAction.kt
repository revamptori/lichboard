package dev.lichboard.input

/** A captured key action; modifiers are sampled when pressed, never when a slow target replies. */
sealed interface InputAction {
    data class Text(val value: String, val meta: Int = 0) : InputAction
    data class Key(val code: Int, val meta: Int = 0) : InputAction
    data class Edit(val command: EditCommand) : InputAction
    data object CopyDraft : InputAction
    data object ClearDraft : InputAction
}
