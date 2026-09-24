package io.github.kaustubhowmick.plaintext.editor

/** Find / Replace over a text snapshot (design.md §5.12, §5.13). Pure Kotlin. */
object SearchEngine {

    /**
     * Finds [term] in [text]. Forward searches start at [from] (the end of the
     * selection); backward searches find a match that starts before [from]
     * (the start of the selection). Returns the match start, or -1.
     */
    fun find(text: String, term: String, from: Int, forward: Boolean, matchCase: Boolean, wrap: Boolean): Int {
        if (term.isEmpty() || term.length > text.length) return -1
        val ignoreCase = !matchCase
        return if (forward) {
            val start = from.coerceIn(0, text.length)
            val hit = text.indexOf(term, start, ignoreCase)
            if (hit >= 0 || !wrap) hit else text.indexOf(term, 0, ignoreCase).takeIf { it in 0 until start } ?: -1
        } else {
            val before = from.coerceIn(0, text.length) - 1
            val hit = if (before < 0) -1 else text.lastIndexOf(term, before, ignoreCase)
            if (hit >= 0 || !wrap) hit else text.lastIndexOf(term, text.length, ignoreCase).takeIf { it > before } ?: -1
        }
    }

    /** True if [candidate] is a match for [term] (used by Replace on the current selection). */
    fun matches(candidate: CharSequence, term: String, matchCase: Boolean): Boolean =
        candidate.length == term.length && candidate.toString().equals(term, ignoreCase = !matchCase)

    class ReplaceAllResult(val text: String, val count: Int)

    /** Replaces every occurrence in the whole document, regardless of caret or wrap. */
    fun replaceAll(text: String, term: String, replacement: String, matchCase: Boolean): ReplaceAllResult {
        if (term.isEmpty()) return ReplaceAllResult(text, 0)
        val ignoreCase = !matchCase
        var i = text.indexOf(term, 0, ignoreCase)
        if (i < 0) return ReplaceAllResult(text, 0)
        val sb = StringBuilder(text.length)
        var last = 0
        var count = 0
        while (i >= 0) {
            sb.append(text, last, i).append(replacement)
            last = i + term.length
            count++
            i = text.indexOf(term, last, ignoreCase)
        }
        sb.append(text, last, text.length)
        return ReplaceAllResult(sb.toString(), count)
    }
}
