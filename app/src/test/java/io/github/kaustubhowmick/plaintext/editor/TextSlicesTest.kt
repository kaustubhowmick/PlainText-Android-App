package io.github.kaustubhowmick.plaintext.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSlicesTest {

    private fun slices(text: String, maxLines: Int, maxChars: Int = Int.MAX_VALUE): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val end = TextSlices.end(text, start, maxLines, maxChars)
            out.add(text.substring(start, end))
            start = end
        }
        return out
    }

    @Test
    fun slicesEndAfterWholeLines() {
        assertEquals(listOf("a\nb\n", "c\nd\n", "e"), slices("a\nb\nc\nd\ne", 2))
        assertEquals(listOf("a\nb\n", "c\nd\n"), slices("a\nb\nc\nd\n", 2))
        assertEquals(listOf("\n\n", "\n"), slices("\n\n\n", 2))
    }

    @Test
    fun aLineIsNeverSplit() {
        val long = "x".repeat(10_000)
        assertEquals(listOf(long), slices(long, 1))
        assertEquals(listOf("$long\n", long), slices("$long\n$long", 1))
    }

    @Test
    fun charLimitEndsAfterTheLineThatReachesIt() {
        assertEquals(listOf("abc\n", "de\n", "f\ngh"), slices("abc\nde\nf\ngh", 100, 3))
        assertEquals(listOf("abcdef\n", "g"), slices("abcdef\ng", 100, 2))
    }

    @Test
    fun slicesJoinBackToTheText() {
        val text = (0 until 1000).joinToString("\n") { "line $it" }
        val parts = slices(text, 64)
        assertEquals(text, parts.joinToString(""))
        assertEquals(16, parts.size)
        parts.dropLast(1).forEach { assertEquals(64, it.count { c -> c == '\n' }) }
    }

    @Test
    fun emptyText() {
        assertEquals(0, TextSlices.end("", 0, 10, 10))
        assertFalse(TextSlices.hasMoreLinesThan("", 0))
    }

    @Test
    fun countsLineBreaks() {
        assertFalse(TextSlices.hasMoreLinesThan("a\nb\nc", 2))
        assertTrue(TextSlices.hasMoreLinesThan("a\nb\nc\n", 2))
        assertFalse(TextSlices.hasMoreLinesThan("x".repeat(100), 2))
    }
}
