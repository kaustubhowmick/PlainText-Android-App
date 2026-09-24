package io.github.kaustubhowmick.plaintext.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LineEndingsTest {

    @Test
    fun pureKinds() {
        assertEquals(EolStats(2, 0, 0), LineEndings.count("a\r\nb\r\n"))
        assertEquals(EolStats(0, 2, 0), LineEndings.count("a\nb\n"))
        assertEquals(EolStats(0, 0, 2), LineEndings.count("a\rb\r"))
        assertEquals(LineEnding.CRLF, LineEndings.count("a\r\nb").dominant())
        assertEquals(LineEnding.LF, LineEndings.count("a\nb").dominant())
        assertEquals(LineEnding.CR, LineEndings.count("a\rb").dominant())
        assertNull(LineEndings.count("single line").dominant())
        assertFalse(LineEndings.count("a\r\nb\r\n").isMixed)
    }

    @Test
    fun mixedPicksDominantWithTieBreak() {
        val mostlyLf = LineEndings.count("a\nb\nc\r\nd")
        assertTrue(mostlyLf.isMixed)
        assertEquals(LineEnding.LF, mostlyLf.dominant())
        assertEquals(LineEnding.CRLF, LineEndings.count("a\nb\r\n").dominant()) // tie → CRLF
        assertEquals(LineEnding.LF, LineEndings.count("a\nb\r").dominant())     // tie → LF over CR
        assertEquals(LineEnding.CRLF, LineEndings.count("a\rb\r\n").dominant())
    }

    @Test
    fun crCrLfIsCrPlusCrlf() {
        assertEquals(EolStats(1, 0, 1), LineEndings.count("a\r\r\nb"))
        assertEquals("a\n\nb", LineEndings.normalize("a\r\r\nb"))
    }

    @Test
    fun loneCrAtEnd() {
        assertEquals(EolStats(0, 0, 1), LineEndings.count("abc\r"))
        assertEquals("abc\n", LineEndings.normalize("abc\r"))
    }

    @Test
    fun normalizeDenormalizeAreInverseForUniformText() {
        for (eol in LineEnding.entries) {
            for (text in listOf("", "one", "one\ntwo", "one\ntwo\n", "\n\n", "\nlead")) {
                val onDisk = LineEndings.denormalize(text, eol)
                assertEquals(text, LineEndings.normalize(onDisk))
                assertEquals(onDisk, LineEndings.denormalize(LineEndings.normalize(onDisk), eol))
            }
        }
    }

    @Test
    fun trailingNewlinePresenceIsPreserved() {
        assertEquals("a\r\nb", LineEndings.denormalize(LineEndings.normalize("a\r\nb"), LineEnding.CRLF))
        assertEquals("a\r\nb\r\n", LineEndings.denormalize(LineEndings.normalize("a\r\nb\r\n"), LineEnding.CRLF))
    }
}
