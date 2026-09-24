package io.github.kaustubhowmick.plaintext.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoManagerTest {

    private var now = 0L
    private val undo = UndoManager { now }
    private val doc = StringBuilder()
    private val target = UndoManager.Target { s, e, t -> doc.replace(s, e, t) }

    private fun type(text: String) {
        for (c in text) {
            val at = doc.length
            doc.append(c)
            undo.record(at, "", c.toString(), at, composing = false)
            now += 100
        }
    }

    private fun backspace(n: Int) = repeat(n) {
        val at = doc.length - 1
        val removed = doc.substring(at)
        doc.deleteCharAt(at)
        undo.record(at, removed, "", at + 1, composing = false)
        now += 100
    }

    @Test
    fun burstOfTypingIsOneStep() {
        undo.reset()
        type("hello world")
        assertTrue(undo.isDirty)
        assertEquals(0, undo.undo(target))
        assertEquals("", doc.toString())
        assertFalse(undo.canUndo)
        assertFalse(undo.isDirty)
    }

    @Test
    fun pauseStartsANewStep() {
        undo.reset()
        type("abc")
        now += 3000
        type("def")
        undo.undo(target)
        assertEquals("abc", doc.toString())
        undo.undo(target)
        assertEquals("", doc.toString())
    }

    @Test
    fun newlineStartsANewStep() {
        undo.reset()
        type("abc\ndef")
        undo.undo(target)
        assertEquals("abc", doc.toString())
    }

    @Test
    fun backspacesMerge() {
        undo.reset()
        type("abcdef")
        now += 3000
        backspace(3)
        undo.undo(target)
        assertEquals("abcdef", doc.toString())
    }

    @Test
    fun breakGroupSplits() {
        undo.reset()
        type("abc")
        undo.breakGroup()
        type("def")
        undo.undo(target)
        assertEquals("abc", doc.toString())
    }

    @Test
    fun redoAndRedoClearedByNewEdit() {
        undo.reset()
        type("abc")
        undo.undo(target)
        assertTrue(undo.canRedo)
        assertEquals(3, undo.redo(target))
        assertEquals("abc", doc.toString())
        undo.undo(target)
        type("x")
        assertFalse(undo.canRedo)
    }

    @Test
    fun savePointTracksDirtyState() {
        undo.reset()
        type("abc")
        undo.markSaved()
        assertFalse(undo.isDirty)
        type("def") // after a save, typing starts a new group
        assertTrue(undo.isDirty)
        undo.undo(target)
        assertEquals("abc", doc.toString())
        assertFalse("back at the save point", undo.isDirty)
        undo.undo(target)
        assertTrue(undo.isDirty)
    }

    @Test
    fun compositionMerges() {
        undo.reset()
        // An IME rewriting its composing word: replace "hel" with "hell" with "hello".
        doc.append("hel")
        undo.record(0, "", "hel", 0, composing = true)
        doc.replace(0, 3, "hell")
        undo.record(0, "hel", "hell", 3, composing = true)
        doc.replace(0, 4, "hello")
        undo.record(0, "hell", "hello", 4, composing = true)
        undo.undo(target)
        assertEquals("", doc.toString())
    }

    @Test
    fun discardHistoryStaysDirty() {
        undo.reset()
        type("abc")
        undo.markSaved()
        undo.discardHistory()
        assertTrue(undo.isDirty)
        assertFalse(undo.canUndo)
    }

    @Test
    fun capDropsOldestButKeepsDirtyCorrect() {
        undo.reset()
        repeat(UndoManager.MAX_GROUPS + 5) {
            undo.breakGroup()
            type("x")
        }
        var steps = 0
        while (undo.undo(target) >= 0) steps++
        assertEquals(UndoManager.MAX_GROUPS, steps)
        assertTrue("oldest edits can no longer be undone, so the text differs from the clean state", undo.isDirty)
    }
}
