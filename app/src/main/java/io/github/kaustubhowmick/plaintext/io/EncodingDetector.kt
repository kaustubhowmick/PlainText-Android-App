package io.github.kaustubhowmick.plaintext.io

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

data class Detection(val encoding: Encoding, val bomLength: Int, val isBinary: Boolean)

/** Detects the encoding of file bytes (design.md §3.5). The first rule that matches wins. */
object EncodingDetector {
    private const val SAMPLE = 8192

    fun detect(bytes: ByteArray, default: Encoding = Encoding.UTF8): Detection {
        val n = bytes.size
        fun b(i: Int) = bytes[i].toInt() and 0xFF

        // 1. Byte order marks.
        if (n >= 3 && b(0) == 0xEF && b(1) == 0xBB && b(2) == 0xBF) return Detection(Encoding.UTF8_BOM, 3, false)
        if (n >= 4 && b(0) == 0xFF && b(1) == 0xFE && b(2) == 0 && b(3) == 0) return Detection(Encoding.ANSI, 0, true)
        if (n >= 2 && b(0) == 0xFF && b(1) == 0xFE) return Detection(Encoding.UTF16LE, 2, false)
        if (n >= 2 && b(0) == 0xFE && b(1) == 0xFF) return Detection(Encoding.UTF16BE, 2, false)

        // 2. Empty file.
        if (n == 0) return Detection(default, 0, false)

        // 3. BOM-less UTF-16: many zero bytes on one side of each pair.
        if (n % 2 == 0 && n >= 4) {
            val limit = minOf(n, SAMPLE) and 1.inv()
            var zEven = 0
            var zOdd = 0
            var i = 0
            while (i < limit) {
                if (b(i) == 0) zEven++
                if (b(i + 1) == 0) zOdd++
                i += 2
            }
            val pairs = (limit / 2).toDouble()
            val candidate = when {
                zOdd / pairs >= 0.4 && zEven / pairs <= 0.05 -> Encoding.UTF16LE
                zEven / pairs >= 0.4 && zOdd / pairs <= 0.05 -> Encoding.UTF16BE
                else -> null
            }
            if (candidate != null && decodesStrictly(bytes, 0, TextCodec.charset(candidate))) {
                return Detection(candidate, 0, false)
            }
        }

        // 4. Binary: NUL bytes or too many control characters.
        val sample = minOf(n, SAMPLE)
        var controls = 0
        for (i in 0 until sample) {
            val c = b(i)
            if (c == 0) return Detection(Encoding.ANSI, 0, true)
            if (c < 0x20 && c != 0x09 && c != 0x0A && c != 0x0B && c != 0x0C && c != 0x0D && c != 0x1B) controls++
        }
        if (controls * 10 > sample) return Detection(Encoding.ANSI, 0, true)

        // 5. Strict UTF-8 (pure ASCII lands here too).
        if (decodesStrictly(bytes, 0, Charsets.UTF_8)) return Detection(Encoding.UTF8, 0, false)

        // 6. Fallback: lossless Windows-1252.
        return Detection(Encoding.ANSI, 0, false)
    }

    private fun decodesStrictly(bytes: ByteArray, offset: Int, cs: Charset): Boolean = try {
        cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
        true
    } catch (e: CharacterCodingException) {
        false
    }
}
