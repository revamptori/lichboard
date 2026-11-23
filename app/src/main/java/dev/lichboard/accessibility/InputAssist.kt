package dev.lichboard.accessibility

import dev.lichboard.input.EditCommand

/** Optional input backend. Its Android service implementation is compiled into Assist only. */
interface InputAssist {
    val lastFailure: String
    fun hasEditableTarget(): Boolean
    suspend fun text(value: String, meta: Int): Boolean
    suspend fun key(code: Int, meta: Int): Boolean
    suspend fun edit(command: EditCommand): Boolean
}
