package dev.lichboard.accessibility

import dev.lichboard.input.InputAction

sealed interface AssistResult {
    data object Applied : AssistResult
    data object NoTarget : AssistResult
    data object TargetChanged : AssistResult
    data class Rejected(val reason: String) : AssistResult
}

/** The Android implementation is compiled into Assist only. No node is retained between actions. */
interface InputAssist {
    val lastFailure: String
    fun hasEditableTarget(): Boolean
    suspend fun execute(action: InputAction, session: Any): AssistResult
}
