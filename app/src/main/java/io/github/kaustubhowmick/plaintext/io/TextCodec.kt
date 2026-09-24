package io.github.kaustubhowmick.plaintext.io

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Decode/encode for each [Encoding], with BOM handling (design.md §3.5). */
object TextCodec {
    private val BOM_UTF8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val BOM_UTF16LE = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val BOM_UTF16BE = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    fun charset(e: Encoding): Charset = when (e) {
        Encoding.UTF8, Encoding.UTF8_BOM -> Charsets.UTF_8
        Encoding.UTF16LE -> Charsets.UTF_16LE
        Encoding.UTF16BE -> Charsets.UTF_16BE
        Encoding.ANSI -> Charsets.ISO_8859_1 // unused: ANSI goes through Windows1252
    }

    /** Length of the BOM for [e] at the start of [bytes], or 0. */
    fun bomLength(bytes: ByteArray, e: Encoding): Int {
        val bom = when (e) {
            Encoding.UTF8, Encoding.UTF8_BOM -> BOM_UTF8
            Encoding.UTF16LE -> BOM_UTF16LE
            Encoding.UTF16BE -> BOM_UTF16BE
            Encoding.ANSI -> return 0
        }
        if (bytes.size < bom.size) return 0
        for (i in bom.indices) if (bytes[i] != bom[i]) return 0
        return bom.size
    }

    /**
     * Decodes [bytes] after skipping [bomLength] bytes. With [strict], malformed
     * input throws [java.nio.charset.CharacterCodingException]; otherwise it is
     * replaced (used only for read-only previews).
     */
    fun decode(bytes: ByteArray, encoding: Encoding, bomLength: Int, strict: Boolean = true): String {
        if (encoding == Encoding.ANSI) return Windows1252.decode(bytes, bomLength)
        val action = if (strict) CodingErrorAction.REPORT else CodingErrorAction.REPLACE
        return charset(encoding).newDecoder()
            .onMalformedInput(action)
            .onUnmappableCharacter(action)
            .decode(ByteBuffer.wrap(bytes, bomLength, bytes.size - bomLength))
            .toString()
    }

    /**
     * Encodes [text] for saving. [withBom] only matters for UTF-16 (a BOM-less
     * UTF-16 file stays BOM-less); UTF8_BOM always has one and UTF8/ANSI never do.
     */
    fun encode(text: String, encoding: Encoding, withBom: Boolean = true): ByteArray {
        val body = when (encoding) {
            Encoding.ANSI -> return Windows1252.encode(text)
            else -> text.toByteArray(charset(encoding))
        }
        val bom = when (encoding) {
            Encoding.UTF8_BOM -> BOM_UTF8
            Encoding.UTF16LE -> if (withBom) BOM_UTF16LE else null
            Encoding.UTF16BE -> if (withBom) BOM_UTF16BE else null
            else -> null
        } ?: return body
        return bom + body
    }

    /** Index of the first character [encoding] can't store, or -1. Only ANSI is limited. */
    fun findUnmappable(text: CharSequence, encoding: Encoding): Int =
        if (encoding == Encoding.ANSI) Windows1252.findUnmappable(text) else -1
}
