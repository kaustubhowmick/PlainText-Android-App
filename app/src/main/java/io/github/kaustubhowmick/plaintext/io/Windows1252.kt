package io.github.kaustubhowmick.plaintext.io

/**
 * Lossless Windows-1252 (design.md §3.5). The five bytes Windows leaves
 * undefined (81 8D 8F 90 9D) map to the matching C1 control characters, as in
 * the WHATWG Encoding Standard, so every byte 00–FF round-trips.
 */
object Windows1252 {
    /** Characters for bytes 0x80–0x9F. */
    private val HIGH = charArrayOf(
        '€', '\u0081', '‚', 'ƒ', '„', '…', '†', '‡',
        'ˆ', '‰', 'Š', '‹', 'Œ', '\u008D', 'Ž', '\u008F',
        '\u0090', '‘', '’', '“', '”', '•', '–', '—',
        '˜', '™', 'š', '›', 'œ', '\u009D', 'ž', 'Ÿ',
    )

    fun decodeByte(b: Int): Char = if (b in 0x80..0x9F) HIGH[b - 0x80] else b.toChar()

    /** The byte for [c], or -1 if Windows-1252 can't represent it. */
    fun encodeChar(c: Char): Int {
        val code = c.code
        if (code < 0x80 || code in 0xA0..0xFF) return code
        for (i in HIGH.indices) if (HIGH[i] == c) return 0x80 + i
        return -1
    }

    fun decode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String {
        val chars = CharArray(length)
        for (i in 0 until length) chars[i] = decodeByte(bytes[offset + i].toInt() and 0xFF)
        return String(chars)
    }

    /** Encodes [text]; unmappable characters become '?'. */
    fun encode(text: CharSequence): ByteArray {
        val out = ByteArray(text.length)
        for (i in 0 until text.length) {
            val b = encodeChar(text[i])
            out[i] = (if (b < 0) '?'.code else b).toByte()
        }
        return out
    }

    /** Index of the first character that can't be encoded, or -1. */
    fun findUnmappable(text: CharSequence): Int {
        for (i in 0 until text.length) if (encodeChar(text[i]) < 0) return i
        return -1
    }
}
