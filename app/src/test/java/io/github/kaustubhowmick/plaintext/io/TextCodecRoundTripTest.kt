package io.github.kaustubhowmick.plaintext.io

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * design.md §10.1: for every encoding × line ending × trailing newline × sample,
 * encode → detect → decode → normalize must give back the text, the encoding and
 * the line ending, and re-encoding must give identical bytes.
 */
class TextCodecRoundTripTest {

    private val samples = mapOf(
        "empty" to "",
        "ascii" to "Shopping list\n- milk\n- eggs",
        "latin1" to "Café crème\nNaïve façade — “quotes” €5",
        "cjk" to "你好，世界\n日本語のテキスト",
        "emoji" to "Party 🎉\nRocket 🚀 done",
    )

    private fun representable(text: String, e: Encoding) = e != Encoding.ANSI || Windows1252.findUnmappable(text) < 0

    @Test
    fun everyEncodingLineEndingAndSampleRoundTrips() {
        for (encoding in Encoding.entries) for (eol in LineEnding.entries) for (trailing in listOf(false, true)) {
            for ((name, base) in samples) {
                val text = if (trailing) base + "\n" else base
                if (!representable(text, encoding)) continue
                val label = "$name/$encoding/$eol/trailing=$trailing"

                val bytes = TextCodec.encode(LineEndings.denormalize(text, eol), encoding)
                val detection = EncodingDetector.detect(bytes, Encoding.UTF8)
                assertFalse("binary? $label", detection.isBinary)

                // Pure ASCII (and empty) bytes are identical in UTF-8 and ANSI, so either is correct.
                val asciiOnly = text.all { it.code < 0x80 }
                if (!(asciiOnly && encoding in setOf(Encoding.UTF8, Encoding.ANSI))) {
                    assertEquals("encoding $label", encoding, detection.encoding)
                }

                val decoded = TextCodec.decode(bytes, detection.encoding, detection.bomLength)
                val stats = LineEndings.count(decoded)
                assertEquals("text $label", text, LineEndings.normalize(decoded))
                if (text.contains('\n')) assertEquals("eol $label", eol, stats.dominant())
                assertFalse("mixed $label", stats.isMixed)

                val again = TextCodec.encode(
                    LineEndings.denormalize(LineEndings.normalize(decoded), stats.dominant() ?: eol),
                    detection.encoding,
                    withBom = detection.bomLength > 0 || !detection.encoding.isUtf16,
                )
                assertArrayEquals("bytes $label", bytes, again)
            }
        }
    }

    @Test
    fun bomLessUtf16StaysBomLess() {
        val bytes = "Hello, world\r\nSecond line\r\n".toByteArray(Charsets.UTF_16LE)
        val d = EncodingDetector.detect(bytes)
        assertEquals(Encoding.UTF16LE, d.encoding)
        assertEquals(0, d.bomLength)
        val text = TextCodec.decode(bytes, d.encoding, d.bomLength)
        assertArrayEquals(bytes, TextCodec.encode(text, Encoding.UTF16LE, withBom = false))
    }

    @Test
    fun bomsAreWritten() {
        assertArrayEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 'a'.code.toByte()), TextCodec.encode("a", Encoding.UTF8_BOM))
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 'a'.code.toByte(), 0), TextCodec.encode("a", Encoding.UTF16LE))
        assertArrayEquals(byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0, 'a'.code.toByte()), TextCodec.encode("a", Encoding.UTF16BE))
        assertArrayEquals(byteArrayOf('a'.code.toByte()), TextCodec.encode("a", Encoding.UTF8))
    }

    @Test
    fun cafeInAnsi() {
        // design.md §5.24 AC: "café" saved as ANSI yields 63 61 66 E9.
        assertArrayEquals(byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte()), TextCodec.encode("café", Encoding.ANSI))
    }

    @Test
    fun utf16BeWithCrMatchesDesignExample() {
        // design.md §5.5 AC: UTF-16 BE + CR → FE FF then big-endian code units and \r-only breaks.
        val bytes = TextCodec.encode(LineEndings.denormalize("a\nb", LineEnding.CR), Encoding.UTF16BE)
        assertArrayEquals(byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0, 0x61, 0, 0x0D, 0, 0x62), bytes)
    }

    @Test
    fun unmappable() {
        assertEquals(-1, TextCodec.findUnmappable("café €5 “x”", Encoding.ANSI))
        assertEquals(2, TextCodec.findUnmappable("ab你c", Encoding.ANSI))
        assertEquals(-1, TextCodec.findUnmappable("ab你c", Encoding.UTF8))
        assertArrayEquals("ab?c".toByteArray(), TextCodec.encode("ab你c", Encoding.ANSI))
    }
}
