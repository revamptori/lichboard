package dev.lichboard.input

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InputQueueTest {
    @Test fun readyBackendExecutesEveryKeyBeforeOfferReturnsWithoutTheOld64KeyLimit() = runTest {
        val session = Any()
        val sent = mutableListOf<InputAction>()
        val messages = mutableListOf<String>()
        val queue = InputQueue(this, { session }, { sent += it; "ok" }, { messages += it })
        repeat(4_000) { index ->
            val action = InputAction.Text(('a' + index % 26).toString())
            assertTrue(queue.offer(action))
            assertEquals(index + 1, sent.size)
            assertEquals(action, sent.last())
        }
        assertEquals(4_000, messages.size)
    }

    @Test fun suspendedBackendCombines4096CharactersWithoutLosingOrReorderingEditsOrModifiers() = runTest {
        val session = Any()
        val gate = CompletableDeferred<Unit>()
        val sent = mutableListOf<InputAction>()
        var first = true
        val queue = InputQueue(this, { session }, {
            if (first) { first = false; gate.await() }
            sent += it
            "ok"
        }, {})
        val expected = mutableListOf<InputAction>()
        fun offer(action: InputAction) { expected += action; assertTrue(queue.offer(action)) }
        offer(InputAction.Text("start"))
        repeat(2_048) { offer(InputAction.Text(('a' + it % 26).toString())) }
        offer(InputAction.Key(7))
        offer(InputAction.Text("A", meta = 1))
        offer(InputAction.Edit(EditCommand.SELECT_ALL))
        offer(InputAction.Text("c", meta = 4))
        repeat(2_048) { offer(InputAction.Text(('a' + it % 26).toString())) }
        gate.complete(Unit)
        runCurrent()
        fun expand(actions: List<InputAction>) = actions.flatMap { action ->
            if (action is InputAction.Text && action.meta == 0) action.value.map { InputAction.Text(it.toString()) }
            else listOf(action)
        }
        assertEquals(expand(expected), expand(sent))
        assertTrue("Backlog was batched, not dispatched as thousands of IPC calls", sent.size < 45)
        assertTrue(sent.filterIsInstance<InputAction.Text>().all { it.value.length <= 128 })
    }

    @Test fun stopDiscardsPendingKeysAndCancelsAnUnsentSuspendedActionButAllowsFreshInput() = runTest {
        val session = Any()
        val gate = CompletableDeferred<Unit>()
        val sent = mutableListOf<InputAction>()
        val messages = mutableListOf<String>()
        var first = true
        val queue = InputQueue(this, { session }, {
            if (first) { first = false; gate.await() }
            sent += it
            "ok"
        }, { messages += it })
        assertTrue(queue.offer(InputAction.Text("waiting")))
        repeat(300) { assertTrue(queue.offer(InputAction.Text("old"))) }
        queue.stop()
        assertTrue(queue.offer(InputAction.Text("fresh")))
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(InputAction.Text("fresh")), sent)
        assertEquals(listOf("ok"), messages)
    }

    @Test fun newEditorDoesNotWaitForOrReceivePreviousEditorsPendingKeys() = runTest {
        var session = Any()
        val gate = CompletableDeferred<Unit>()
        val sent = mutableListOf<InputAction>()
        var first = true
        val queue = InputQueue(this, { session }, {
            if (first) { first = false; gate.await() }
            sent += it
            "ok"
        }, {})
        queue.offer(InputAction.Text("old in flight"))
        queue.offer(InputAction.Text("old pending"))
        session = Any()
        assertTrue(queue.offer(InputAction.Text("new editor")))
        assertEquals(listOf(InputAction.Text("new editor")), sent)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(InputAction.Text("new editor")), sent)
    }

    @Test fun aSessionChangeWithoutAnotherPressStillDiscardsTheBacklogAndStaleFeedback() = runTest {
        var session = Any()
        val gate = CompletableDeferred<Unit>()
        val began = mutableListOf<InputAction>()
        val messages = mutableListOf<String>()
        val queue = InputQueue(this, { session }, {
            began += it // An Android operation already sent cannot be retracted.
            gate.await()
            "old status"
        }, { messages += it })
        queue.offer(InputAction.Text("in flight"))
        queue.offer(InputAction.Text("not sent"))
        session = Any()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(InputAction.Text("in flight")), began)
        assertTrue(messages.isEmpty())
    }

    @Test fun fullSlowQueueExplicitlyRejectsActionsRatherThanReorderingOrGrowingWithoutBound() = runTest {
        val session = Any()
        val gate = CompletableDeferred<Unit>()
        val sent = mutableListOf<InputAction>()
        val queue = InputQueue(this, { session }, { gate.await(); sent += it; "ok" }, {}, capacity = 2)
        assertTrue(queue.offer(InputAction.Key(1)))
        assertTrue(queue.offer(InputAction.Key(2)))
        assertTrue(queue.offer(InputAction.Key(3)))
        assertFalse(queue.offer(InputAction.Key(4)))
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(1, 2, 3), sent.map { (it as InputAction.Key).code })
    }

    @Test fun reentrantOffersPreserveOrder() = runTest {
        val session = Any()
        val sent = mutableListOf<InputAction>()
        lateinit var queue: InputQueue
        queue = InputQueue(this, { session }, {
            sent += it
            if (it == InputAction.Text("a")) queue.offer(InputAction.Text("b"))
            "ok"
        }, {})
        queue.offer(InputAction.Text("a"))
        assertEquals(listOf(InputAction.Text("a"), InputAction.Text("b")), sent)
    }

    @Test fun reentrantStopDoesNotPublishOldStatusOrConsumeNewGenerationsRequests() = runTest {
        val session = Any()
        val messages = mutableListOf<String>()
        lateinit var queue: InputQueue
        queue = InputQueue(this, { session }, {
            val text = (it as InputAction.Text).value
            if (text == "old") { queue.stop(); queue.offer(InputAction.Text("new")) }
            text
        }, { messages += it })
        queue.offer(InputAction.Text("old"))
        assertEquals(listOf("new"), messages)
    }

    @Test fun aFailedActionDoesNotPoisonFollowingInput() = runTest {
        val session = Any()
        val messages = mutableListOf<String>()
        var first = true
        val queue = InputQueue(this, { session }, {
            if (first) { first = false; error("Editor refused") }
            "next accepted"
        }, { messages += it })
        assertTrue(queue.offer(InputAction.Key(1)))
        assertTrue(queue.offer(InputAction.Key(2)))
        assertTrue(messages.first().startsWith("Input unavailable"))
        assertEquals("next accepted", messages.last())
    }

    @Test fun disposedScopeCannotSendNewInput() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val session = Any()
        val queue = InputQueue(scope, { session }, { fail("Must not execute"); "" }, {})
        scope.cancel()
        assertFalse(queue.offer(InputAction.Text("stale")))
    }
}
