package io.github.kaustubhowmick.plaintext.io

/** The five supported encodings (design.md §3.3). "ANSI" always means Windows-1252. */
enum class Encoding(val label: String) {
    UTF8("UTF-8"),
    UTF8_BOM("UTF-8 with BOM"),
    UTF16LE("UTF-16 LE"),
    UTF16BE("UTF-16 BE"),
    ANSI("ANSI");

    val isUtf16: Boolean get() = this == UTF16LE || this == UTF16BE

    companion object {
        fun parse(name: String?, fallback: Encoding = UTF8): Encoding =
            entries.firstOrNull { it.name == name } ?: fallback
    }
}

enum class LineEnding(val label: String, val chars: String) {
    CRLF("Windows (CRLF)", "\r\n"),
    LF("Unix (LF)", "\n"),
    CR("Macintosh (CR)", "\r");

    companion object {
        fun parse(name: String?, fallback: LineEnding = CRLF): LineEnding =
            entries.firstOrNull { it.name == name } ?: fallback
    }
}
