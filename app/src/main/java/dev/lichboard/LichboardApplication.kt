package dev.lichboard

import android.app.Application
import android.content.Context
import dev.lichboard.data.SettingsStore
import dev.lichboard.input.DraftRepository
import kotlinx.coroutines.flow.MutableStateFlow

class LichboardApplication : Application() {
    val settings by lazy { SettingsStore(this) }
    val draft = DraftRepository()
    val imeVisible = MutableStateFlow(false)
    val overlayRunning = MutableStateFlow(false)
}

val Context.lichboard: LichboardApplication
    get() = applicationContext as LichboardApplication
