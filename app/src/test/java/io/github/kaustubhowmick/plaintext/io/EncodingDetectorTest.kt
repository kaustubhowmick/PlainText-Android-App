package io.github.kaustubhowmick.plaintext.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncodingDetectorTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun byteOrderMarks() {
        assertEquals(Detection(Encoding.UTF8_BOM, 3, false), EncodingDetector.detect(bytes(0xEF, 0xBB, 0xBF, 0x41)))
        assertEquals(Detection(Encoding.UTF16LE, 2, false), EncodingDetector.detect(bytes(0xFF, 0xFE, 0x41, 0)))
        assertEquals(Detection(Encoding.UTF16BE, 2, false), EncodingDetector.detect(bytes(0xFE, 0xFF, 0, 0x41)))
        assertTrue("UTF-32 LE is unsupported", EncodingDetector.detect(bytes(0xFF, 0xFE, 0, 0, 0x41, 0, 0, 0)).isBinary)
    }

    @Test
    fun emptyFileUsesDefault() {
        assertEquals(Encoding.ANSI, EncodingDetector.detect(ByteArray(0), Encoding.ANSI).encoding)
        assertEquals(Encoding.UTF8, EncodingDetector.detect(ByteArray(0)).encoding)
    }

    @Test
    fun bomLessUtf16() {
        val text = "The quick brown fox jumps over the lazy dog.\n"
        assertEquals(Encoding.UTF16LE, EncodingDetector.detect(text.toByteArray(Charsets.UTF_16LE)).encoding)
        assertEquals(Encoding.UTF16BE, EncodingDetector.detect(text.toByteArray(Charsets.UTF_16BE)).encoding)
    }

    @Test
    fun bomLessUtf16WithBrokenSurrogateFallsThrough() {
        // Passes the zero-byte heuristic as UTF-16 LE, but a lone high surrogate (D800)
        // makes strict decoding fail, so detection falls through (to binary: NUL bytes).
        val b = "abcdefghijklmnopqrst".toByteArray(Charsets.UTF_16LE) + bytes(0x00, 0xD8, 0x63, 0)
        val d = EncodingDetector.detect(b)
        assertTrue(d.encoding != Encoding.UTF16LE)
        assertTrue(d.isBinary)
    }

    @Test
    fun utf8WithoutBom() {
        assertEquals(Detection(Encoding.UTF8, 0, false), EncodingDetector.detect("naïve 你好".toByteArray()))
        assertEquals(Detection(Encoding.UTF8, 0, false), EncodingDetector.detect("plain ascii".toByteArray()))
    }

    @Test
    fun invalidUtf8IsAnsi() {
        // "café" in Windows-1252.
        assertEquals(Detection(Encoding.ANSI, 0, false), EncodingDetector.detect(bytes(0x63, 0x61, 0x66, 0xE9)))
    }

    @Test
    fun binaryDetection() {
        assertTrue(EncodingDetector.detect(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D)).isBinary)
        val controls = ByteArray(100) { if (it % 5 == 0) 0x01 else 0x41 }
        assertTrue(EncodingDetector.detect(controls).isBinary)
    }

    @Test
    fun ansiColorLogsAreText() {
        val log = "\u001B[31mERROR\u001B[0m something failed\n\u001B[32mOK\u001B[0m\n".toByteArray()
        assertFalse(EncodingDetector.detect(log).isBinary)
    }
}
