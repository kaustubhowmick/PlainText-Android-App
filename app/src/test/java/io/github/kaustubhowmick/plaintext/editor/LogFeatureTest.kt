package io.github.kaustubhowmick.plaintext.editor

import io.github.kaustubhowmick.plaintext.io.Encoding
import io.github.kaustubhowmick.plaintext.io.EncodingDetector
import io.github.kaustubhowmick.plaintext.io.LineEnding
import io.github.kaustubhowmick.plaintext.io.LineEndings
import io.github.kaustubhowmick.plaintext.io.TextCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** design.md §5.27 and §10.1 LogFeatureTest. */
class LogFeatureTest {

    private val stamp = "10:42 AM 9/23/2026"

    @Test
    fun detectsOnlyAnExactFirstLine() {
        assertTrue(LogFeature.isLogFile(".LOG\nfirst\n"))
        assertTrue(LogFeature.isLogFile(".LOG"))
        assertFalse(LogFeature.isLogFile(".log\nfirst"))
        assertFalse(LogFeature.isLogFile(" .LOG\nfirst"))
        assertFalse(LogFeature.isLogFile(".LOGGING\nfirst"))
        assertFalse(LogFeature.isLogFile(".LOG \nfirst"))
        assertFalse(LogFeature.isLogFile(""))
        assertFalse(LogFeature.isLogFile("notes\n.LOG"))
    }

    @Test
    fun entryWhenTextEndsWithNewline() {
        assertEquals("$stamp\n", LogFeature.entry(".LOG\nfirst\n", stamp))
    }

    @Test
    fun entryWhenTextDoesNotEndWithNewline() {
        assertEquals("\n$stamp\n", LogFeature.entry(".LOG\nfirst", stamp))
        assertEquals("\n$stamp\n", LogFeature.entry(".LOG", stamp))
    }

    @Test
    fun designExampleKeepsCrlfOnSave() {
        // Opening ".LOG\r\nfirst\r\n" shows .LOG, first, a stamp line and an empty last line;
        // saving writes CRLF endings (design.md §5.27 AC).
        val bytes = ".LOG\r\nfirst\r\n".toByteArray()
        val detection = EncodingDetector.detect(bytes)
        val decoded = TextCodec.decode(bytes, detection.encoding, detection.bomLength)
        val eol = LineEndings.count(decoded).dominant()!!
        val text = LineEndings.normalize(decoded)
        assertTrue(LogFeature.isLogFile(text))

        val edited = text + LogFeature.entry(text, stamp)
        assertEquals(".LOG\nfirst\n$stamp\n", edited)
        assertEquals(listOf(".LOG", "first", stamp, ""), edited.split('\n'))

        val saved = TextCodec.encode(LineEndings.denormalize(edited, eol), Encoding.UTF8)
        assertEquals(LineEnding.CRLF, eol)
        assertArrayEquals(".LOG\r\nfirst\r\n$stamp\r\n".toByteArray(), saved)
    }

    @Test
    fun repeatedOpensAppendOneEntryEach() {
        var text = ".LOG\n"
        repeat(3) { text += LogFeature.entry(text, stamp) }
        assertEquals(".LOG\n$stamp\n$stamp\n$stamp\n", text)
    }
}
