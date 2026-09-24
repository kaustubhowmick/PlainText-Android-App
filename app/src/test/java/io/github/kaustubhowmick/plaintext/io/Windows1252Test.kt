package io.github.kaustubhowmick.plaintext.io

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Windows1252Test {

    @Test
    fun everyByteRoundTripsToADistinctChar() {
        val all = ByteArray(256) { it.toByte() }
        val text = Windows1252.decode(all)
        assertEquals(256, text.toSet().size)
        assertArrayEquals(all, Windows1252.encode(text))
    }

    @Test
    fun undefinedBytesMapToC1Controls() {
        for (b in intArrayOf(0x81, 0x8D, 0x8F, 0x90, 0x9D)) {
            assertEquals(b.toChar(), Windows1252.decodeByte(b))
        }
        assertEquals('€', Windows1252.decodeByte(0x80))
        assertEquals('Ÿ', Windows1252.decodeByte(0x9F))
    }

    @Test
    fun unrepresentableCharacters() {
        assertEquals(-1, Windows1252.encodeChar('\u0080')) // C1 control not used by 1252
        assertEquals(-1, Windows1252.encodeChar('你'))
        assertEquals(0x80, Windows1252.encodeChar('€'))
        assertEquals(0xE9, Windows1252.encodeChar('é'))
    }
}
