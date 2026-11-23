package dev.lichboard.input

import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Main-thread confined. Ready IME/draft actions execute inline, without a worker-thread hop or
 * debounce timer. Only a genuinely suspending backend builds a backlog; adjacent unmodified text
 * in that backlog is combined on arrival, without crossing an edit, modifier or session boundary.
 */
class InputQueue(
    private val scope: CoroutineScope,
    private val currentSession: () -> Any,
    private val execute: suspend (InputAction) -> String,
    private val result: (String) -> Unit,
    private val capacity: Int = 256
) {
    private data class Request(var action: InputAction, val session: Any)
    private val requests = ArrayDeque<Request>()
    private var worker: Job? = null
    private var draining = false
    private var generation = 0L
    private var session: Any? = null

    init { require(capacity > 0) }

    fun offer(action: InputAction): Boolean {
        if (!scope.isActive) return false
        val now = currentSession()
        if (session != now) stop() // A slow old target must not delay/receive a new editor's keys.
        session = now
        val previous = requests.peekLast()
        val previousText = previous?.action as? InputAction.Text
        if (action is InputAction.Text && action.meta == 0 && previous != null && previous.session == now &&
            previousText != null && previousText.meta == 0 && previousText.value.length + action.value.length <= MAX_BATCH) {
            previous.action = InputAction.Text(previousText.value + action.value)
        } else {
            if (requests.size >= capacity) return false
            requests.addLast(Request(action, now))
        }
        if (!draining) drain()
        return true
    }

    private fun drain() {
        draining = true // Set before launching: synchronous/reentrant offers keep their order too.
        val run = generation
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                while (run == generation && requests.isNotEmpty()) {
                    ensureActive()
                    val request = requests.removeFirst()
                    if (request.session != currentSession()) continue
                    try {
                        val message = execute(request.action)
                        ensureActive()
                        if (run == generation && request.session == currentSession()) result(message)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        ensureActive()
                        if (run == generation && request.session == currentSession()) {
                            result("Input unavailable · refocus the editor or use clipboard draft")
                        }
                    }
                }
            } finally {
                if (run == generation) {
                    draining = false
                    worker = null
                    requests.clear()
                }
            }
        }
        // A ready backend may have finished the entire drain before launch() returns.
        if (draining && run == generation) worker = job
    }

    fun stop() {
        generation++
        val oldWorker = worker
        worker = null
        draining = false
        requests.clear() // Discard queued, unsent actions on detach, hide or editor changes.
        session = null
        oldWorker?.cancel()
    }

    private companion object { const val MAX_BATCH = 128 }
}
