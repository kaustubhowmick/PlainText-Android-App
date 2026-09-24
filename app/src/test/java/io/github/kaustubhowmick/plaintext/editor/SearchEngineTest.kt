package io.github.kaustubhowmick.plaintext.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchEngineTest {

    private val text = "Hello world, hello there, HELLO"

    @Test
    fun matchCase() {
        assertEquals(0, SearchEngine.find(text, "hello", 0, true, matchCase = false, wrap = false))
        assertEquals(13, SearchEngine.find(text, "hello", 0, true, matchCase = true, wrap = false))
    }

    @Test
    fun forwardFromSelectionEndAndWrap() {
        assertEquals(13, SearchEngine.find(text, "hello", 5, true, matchCase = false, wrap = false))
        assertEquals(26, SearchEngine.find(text, "hello", 18, true, matchCase = false, wrap = false))
        assertEquals(-1, SearchEngine.find(text, "hello", 31, true, matchCase = false, wrap = false))
        assertEquals(0, SearchEngine.find(text, "hello", 31, true, matchCase = false, wrap = true))
    }

    @Test
    fun backwardFromSelectionStartAndWrap() {
        assertEquals(13, SearchEngine.find(text, "hello", 26, false, matchCase = false, wrap = false))
        assertEquals(-1, SearchEngine.find(text, "hello", 0, false, matchCase = false, wrap = false))
        assertEquals(26, SearchEngine.find(text, "hello", 0, false, matchCase = false, wrap = true))
    }

    @Test
    fun notFoundAndEmpty() {
        assertEquals(-1, SearchEngine.find(text, "absent", 0, true, matchCase = false, wrap = true))
        assertEquals(-1, SearchEngine.find(text, "", 0, true, matchCase = false, wrap = true))
    }

    @Test
    fun overlappingMatchesSearchFromSelectionEnd() {
        // See DECISIONS.md: Find Next continues after the selected match.
        assertEquals(0, SearchEngine.find("aaa", "aa", 0, true, matchCase = true, wrap = true))
        assertEquals(0, SearchEngine.find("aaa", "aa", 2, true, matchCase = true, wrap = true))
    }

    @Test
    fun replaceAll() {
        val r = SearchEngine.replaceAll("aAa", "a", "b", matchCase = false)
        assertEquals("bbb", r.text)
        assertEquals(3, r.count)
        assertEquals("bAb", SearchEngine.replaceAll("aAa", "a", "b", matchCase = true).text)
        assertEquals(0, SearchEngine.replaceAll("xyz", "a", "b", matchCase = false).count)
    }

    @Test
    fun matchesSelection() {
        assertTrue(SearchEngine.matches("HeLLo", "hello", matchCase = false))
        assertFalse(SearchEngine.matches("HeLLo", "hello", matchCase = true))
    }
}
