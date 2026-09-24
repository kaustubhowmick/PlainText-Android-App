package io.github.kaustubhowmick.plaintext.editor

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class LineIndexTest {

    private fun expectedStarts(text: CharSequence): List<Int> {
        val starts = mutableListOf(0)
        text.forEachIndexed { i, c -> if (c == '\n') starts.add(i + 1) }
        return starts
    }

    private fun check(index: LineIndex, text: CharSequence) {
        val expected = expectedStarts(text)
        assertEquals(expected.size, index.lineCount)
        expected.forEachIndexed { line, start -> assertEquals(start, index.lineStart(line)) }
        for (offset in 0..text.length) {
            val line = expected.indexOfLast { it <= offset }
            assertEquals("offset $offset", line, index.lineOf(offset))
        }
    }

    @Test
    fun randomEditsMatchBruteForce() {
        val rnd = Random(42)
        val text = StringBuilder("first\nsecond\n\nthird")
        val index = LineIndex().apply { rebuild(text) }
        val alphabet = "ab\n"
        repeat(10_000) {
            val start = rnd.nextInt(text.length + 1)
            val removed = rnd.nextInt(minOf(4, text.length - start) + 1)
            val inserted = buildString { repeat(rnd.nextInt(4)) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
            text.replace(start, start + removed, inserted)
            index.onReplace(start, removed, inserted)
            if (it % 250 == 0) check(index, text)
        }
        check(index, text)
    }

    @Test
    fun wholeTextReplacement() {
        val index = LineIndex().apply { rebuild("a\nb\nc") }
        index.onReplace(0, 5, "x\ny")
        check(index, "x\ny")
    }
}
