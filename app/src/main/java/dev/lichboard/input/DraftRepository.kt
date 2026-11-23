package dev.lichboard.input

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class DraftRepository {
    private val buffer = DraftBuffer()
    private val mutable = MutableStateFlow(buffer.snapshot())
    val state = mutable.asStateFlow()
    val snapshot get() = mutable.value
    @Synchronized fun edit(action: DraftBuffer.() -> Unit) {
        buffer.action()
        mutable.value = buffer.snapshot()
    }
    /** A clipboard round trip must not delete newer text typed from another keyboard/UI. */
    @Synchronized fun editIfUnchanged(expected: DraftSnapshot, action: DraftBuffer.() -> Unit): Boolean {
        if (buffer.snapshot() != expected) return false
        edit(action)
        return true
    }
}
