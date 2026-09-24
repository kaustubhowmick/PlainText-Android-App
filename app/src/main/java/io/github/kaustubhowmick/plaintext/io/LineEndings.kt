package io.github.kaustubhowmick.plaintext.io

/** Line-ending counts found in a text (design.md §5.25). */
data class EolStats(val crlf: Int, val lf: Int, val cr: Int) {
    val kinds: Int get() = (if (crlf > 0) 1 else 0) + (if (lf > 0) 1 else 0) + (if (cr > 0) 1 else 0)
    val isMixed: Boolean get() = kinds > 1

    /** The most frequent kind; ties prefer CRLF, then LF. Null when there are no line breaks. */
    fun dominant(): LineEnding? = when {
        kinds == 0 -> null
        crlf >= lf && crlf >= cr -> LineEnding.CRLF
        lf >= cr -> LineEnding.LF
        else -> LineEnding.CR
    }
}

object LineEndings {
    fun count(text: CharSequence): EolStats {
        var crlf = 0
        var lf = 0
        var cr = 0
        var i = 0
        val n = text.length
        while (i < n) {
            when (text[i]) {
                '\r' -> if (i + 1 < n && text[i + 1] == '\n') {
                    crlf++
                    i++
                } else {
                    cr++
                }
                '\n' -> lf++
            }
            i++
        }
        return EolStats(crlf, lf, cr)
    }

    /** Converts CRLF and lone CR to LF: the editor always holds '\n'-only text. */
    fun normalize(text: String): String {
        if (text.indexOf('\r') < 0) return text
        val sb = StringBuilder(text.length)
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c == '\r') {
                sb.append('\n')
                if (i + 1 < n && text[i + 1] == '\n') i++
            } else {
                sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /** Converts '\n' to [eol] for saving. */
    fun denormalize(text: CharSequence, eol: LineEnding): String {
        if (eol == LineEnding.LF) return text.toString()
        val sb = StringBuilder(text.length + text.length / 32)
        for (i in 0 until text.length) {
            val c = text[i]
            if (c == '\n') sb.append(eol.chars) else sb.append(c)
        }
        return sb.toString()
    }
}
