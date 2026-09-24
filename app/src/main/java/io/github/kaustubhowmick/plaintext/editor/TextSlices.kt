package io.github.kaustubhowmick.plaintext.editor

/**
 * Splits a text into runs of whole lines for [EditorView.setTextInSlices].
 * Pure Kotlin so it is unit-testable.
 */
object TextSlices {
    /**
     * The end of the slice starting at [start]: just after the '\n' that ends
     * its [maxLines]-th line or the first line reaching [maxChars], or the end
     * of the text. A slice never ends inside a line, so no paragraph is laid
     * out twice.
     */
    fun end(text: CharSequence, start: Int, maxLines: Int, maxChars: Int): Int {
        var lines = 0
        var i = start
        val n = text.length
        while (i < n) {
            if (text[i++] == '\n' && (++lines == maxLines || i - start >= maxChars)) break
        }
        return i
    }

    /** True when [text] has more than [lines] line breaks. */
    fun hasMoreLinesThan(text: CharSequence, lines: Int): Boolean {
        if (text.length <= lines) return false
        var count = 0
        for (i in 0 until text.length) {
            if (text[i] == '\n' && ++count > lines) return true
        }
        return false
    }
}
