package dev.lichboard.accessibility

import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object AccessBridge {
    private var reference = WeakReference<LichboardAccessibilityService>(null)
    private val mutable = MutableStateFlow(false)
    val connected = mutable.asStateFlow()
    val service get() = reference.get()
    fun attach(service: LichboardAccessibilityService) { reference = WeakReference(service); mutable.value = true }
    fun detach(service: LichboardAccessibilityService) {
        if (reference.get() === service) { reference.clear(); mutable.value = false }
    }
}
