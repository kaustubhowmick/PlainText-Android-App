package io.github.kaustubhowmick.plaintext.io

/** File-name helpers (design.md §4.5, §4.6). Pure Kotlin. */
object FileNames {
    const val INVALID_CHARS = "\\/:*?\"<>|"
    private const val MAX_BYTES = 255
    private const val MAX_PROPOSED = 40

    private val KNOWN_MIME = mapOf(
        "txt" to "text/plain",
        "md" to "text/markdown",
        "csv" to "text/csv",
        "json" to "application/json",
        "xml" to "application/xml",
        "html" to "text/html",
        "htm" to "text/html",
    )

    fun hasInvalidChars(name: String): Boolean = name.any { it in INVALID_CHARS || it < ' ' }

    /** Removes characters invalid on FAT/exFAT, collapses whitespace, trims, and limits length. */
    fun sanitize(name: String): String {
        val cleaned = buildString {
            for (c in name) if (c !in INVALID_CHARS && c >= ' ' && c != '\u007F') append(c)
        }.replace(Regex("\\s+"), " ").trim().trimEnd('.', ' ')
        return limitBytes(cleaned)
    }

    /** Cuts the base name so the whole name fits in 255 UTF-8 bytes, keeping the extension. */
    fun limitBytes(name: String): String {
        if (name.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) return name
        val ext = extension(name)
        val suffix = if (ext.isEmpty()) "" else ".$ext"
        var base = if (ext.isEmpty()) name else name.dropLast(suffix.length)
        while (base.isNotEmpty() && (base + suffix).toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            base = base.dropLast(if (base.length >= 2 && base[base.length - 1].isLowSurrogate()) 2 else 1)
        }
        return base + suffix
    }

    /** First non-blank line, trimmed to 40 chars, sanitized, plus ".txt"; else "Untitled.txt". */
    fun proposeName(text: CharSequence, fallbackBase: String = "Untitled"): String {
        val firstLine = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
        var base = sanitize(firstLine.take(MAX_PROPOSED))
        if (base.isEmpty()) base = fallbackBase
        return "$base.txt"
    }

    /** Appends ".txt" when the user typed no extension. */
    fun ensureExtension(name: String): String = if (extension(name).isEmpty()) "$name.txt" else name

    fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1)
    }

    /** The part before the extension, e.g. for pre-selecting in the name field. */
    fun baseLength(name: String): Int {
        val ext = extension(name)
        return if (ext.isEmpty()) name.length else name.length - ext.length - 1
    }

    /** "notes.txt" → "notes (2).txt", "notes (3).txt", … the first name for which [exists] is false. */
    fun nextFreeName(name: String, exists: (String) -> Boolean): String {
        val ext = extension(name)
        val base = if (ext.isEmpty()) name else name.substring(0, name.length - ext.length - 1)
        val suffix = if (ext.isEmpty()) "" else ".$ext"
        var n = 2
        while (true) {
            val candidate = "$base ($n)$suffix"
            if (!exists(candidate)) return candidate
            n++
        }
    }

    /**
     * MIME type for creating [name], derived from its extension so providers don't
     * append a second extension. [lookup] is the platform MimeTypeMap in the app.
     */
    fun mimeFor(name: String, lookup: (String) -> String? = { null }): String {
        val ext = extension(name).lowercase()
        if (ext.isEmpty()) return "text/plain"
        return KNOWN_MIME[ext] ?: lookup(ext) ?: "application/octet-stream"
    }
}
