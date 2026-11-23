package dev.lichboard.accessibility

import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object AccessBridge {
    @Volatile private var reference = WeakReference<InputAssist>(null)
    private val mutable = MutableStateFlow(false)
    val connected = mutable.asStateFlow()
    val service get() = reference.get()
    @Volatile var inputSession: Any = Any()
        private set
    fun attach(service: InputAssist) {
        reference = WeakReference(service); inputSession = Any(); mutable.value = true
    }
    /** A focus/window notification invalidates pending input without retaining event contents. */
    fun focusChanged(service: InputAssist) {
        if (reference.get() === service) inputSession = Any()
    }
    fun detach(service: InputAssist) {
        if (reference.get() === service) { reference.clear(); inputSession = Any(); mutable.value = false }
    }
}
