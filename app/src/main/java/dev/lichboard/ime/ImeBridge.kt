package dev.lichboard.ime

import java.lang.ref.WeakReference

object ImeBridge {
    @Volatile private var reference = WeakReference<LichboardImeService>(null)
    val service: LichboardImeService? get() = reference.get()
    val hasEditor get() = service?.hasEditor == true
    fun attach(service: LichboardImeService) { reference = WeakReference(service) }
    fun detach(service: LichboardImeService) { if (reference.get() === service) reference.clear() }
}
