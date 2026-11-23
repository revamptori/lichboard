package dev.lichboard.input

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class DraftRepositoryTest {
    @Test fun concurrentEditsAreSerialized() {
        val repository = DraftRepository()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val workers = (1..4).map {
                executor.submit { repeat(250) { repository.edit { insert("x") } } }
            }
            workers.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1_000, repository.snapshot.text.length)
            assertEquals(1_000, repository.snapshot.cursor)
            assertEquals(repository.snapshot, repository.state.value)
        } finally { executor.shutdownNow() }
    }
    @Test fun conditionalCutRemovesTheCopiedSelection() {
        val repository = DraftRepository()
        repository.edit { insert("hello 😀"); home(true) }
        val copied = repository.snapshot
        assertEquals("hello 😀", copied.selectedText)
        assertTrue(repository.editIfUnchanged(copied) { insert("") })
        assertEquals(DraftSnapshot(), repository.snapshot)
    }
    @Test fun clipboardRoundTripCannotDeleteNewerDraftText() {
        val repository = DraftRepository()
        repository.edit { insert("copied"); selectAll() }
        val copied = repository.snapshot
        repository.edit { clear(); insert("newer text") }
        assertFalse(repository.editIfUnchanged(copied) { insert("") })
        assertEquals("newer text", repository.snapshot.text)
    }
    @Test fun snapshotsStayImmutableAfterLaterEdits() {
        val repository = DraftRepository()
        repository.edit { insert("old"); selectAll() }
        val previous = repository.snapshot
        repository.edit { insert("new") }
        assertEquals("old", previous.text)
        assertEquals("old", previous.selectedText)
        assertEquals("new", repository.snapshot.text)
        assertEquals("", repository.snapshot.selectedText)
    }
}
