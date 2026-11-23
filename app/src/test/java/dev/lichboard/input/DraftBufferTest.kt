package dev.lichboard.input

import org.junit.Assert.*
import org.junit.Test

class DraftBufferTest {
    @Test fun insertionTracksCursor() {
        val b = DraftBuffer(); b.insert("abcd"); b.left(); b.left(); b.insert("XY")
        assertEquals("abXYcd", b.text); assertEquals(4, b.cursor)
    }
    @Test fun deletesWholeSurrogatePairBackward() {
        val b = DraftBuffer(); b.insert("a😀"); b.backspace()
        assertEquals("a", b.text); assertEquals(1, b.cursor)
    }
    @Test fun deletesWholeSurrogatePairForward() {
        val b = DraftBuffer(); b.insert("😀b"); b.home(); b.deleteForward()
        assertEquals("b", b.text); assertEquals(0, b.cursor)
    }
    @Test fun movementDoesNotSplitEmoji() {
        val b = DraftBuffer(); b.insert("a😀b"); b.left(); b.left()
        assertEquals(1, b.cursor); b.right(); assertEquals(3, b.cursor)
    }
    @Test fun shiftSelectAndReplace() {
        val b = DraftBuffer(); b.insert("abcd"); b.left(true); b.left(true)
        assertEquals("cd", b.selectedText); b.insert("!")
        assertEquals("ab!", b.text); assertEquals(b.cursor, b.anchor)
    }
    @Test fun selectAllAndDelete() {
        val b = DraftBuffer(); b.insert("hello 😀"); b.selectAll(); b.backspace()
        assertEquals("", b.text); assertEquals(0, b.cursor)
    }
    @Test fun selectionCollapsesInDirectionOfMovement() {
        val b = DraftBuffer(); b.insert("abcd"); b.selectAll(); b.left()
        assertEquals(0, b.cursor); b.selectAll(); b.right(); assertEquals(4, b.cursor)
    }
    @Test fun clampsAtStartAndEnd() {
        val b = DraftBuffer(); b.backspace(); b.deleteForward(); b.left(); b.right()
        assertEquals(0, b.cursor); b.insert("x"); b.right(); b.deleteForward()
        assertEquals("x", b.text); assertEquals(1, b.cursor)
    }
    @Test fun limitNeverCutsSurrogatePair() {
        val b = DraftBuffer(2); b.insert("a😀")
        assertEquals("a", b.text)
        b.selectAll(); b.insert("😀"); assertEquals("😀", b.text)
    }
    @Test fun replacingSelectionRespectsLimit() {
        val b = DraftBuffer(4); b.insert("abcd"); b.selectAll(); b.insert("uvwxyz")
        assertEquals("uvwx", b.text)
    }
    @Test fun clearErasesAllState() {
        val b = DraftBuffer(); b.insert("secret"); b.selectAll(); b.clear()
        assertEquals(DraftSnapshot(), b.snapshot())
    }
    @Test fun homeAndEndExtendSelection() {
        val b = DraftBuffer(); b.insert("abc"); b.home(true)
        assertEquals("abc", b.selectedText); b.end(); assertEquals("", b.selectedText)
    }
}
