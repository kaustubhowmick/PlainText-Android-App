package io.github.kaustubhowmick.plaintext.print

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeaderFooterTest {

    private fun f(p: String) = HeaderFooter.format(p, "notes.txt", 3, "9/23/2026", "10:42 AM")

    @Test
    fun codes() {
        assertEquals(HeaderFooter.Line("", "notes.txt", ""), f("&f"))
        assertEquals(HeaderFooter.Line("", "Page 3", ""), f("Page &p"))
        assertEquals(HeaderFooter.Line("", "9/23/2026 10:42 AM", ""), f("&d &t"))
        assertEquals(HeaderFooter.Line("", "A & B", ""), f("A && B"))
        assertEquals(HeaderFooter.Line("", "&x", ""), f("&x"))
    }

    @Test
    fun alignmentSections() {
        assertEquals(HeaderFooter.Line("notes.txt", "", "3"), f("&l&f&r&p"))
        assertEquals(HeaderFooter.Line("L", "C", "R"), f("&lL&cC&rR"))
    }

    @Test
    fun empty() {
        assertTrue(f("").isEmpty)
    }
}
