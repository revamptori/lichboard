package dev.lichboard.accessibility

import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object AccessBridge {
    @Volatile private var reference = WeakReference<InputAssist>(null)
    private val mutable = MutableStateFlow(false)
    val connected = mutable.asStateFlow()
    val service get() = reference.get()
    fun attach(service: InputAssist) { reference = WeakReference(service); mutable.value = true }
    fun detach(service: InputAssist) {
        if (reference.get() === service) { reference.clear(); mutable.value = false }
    }
}
