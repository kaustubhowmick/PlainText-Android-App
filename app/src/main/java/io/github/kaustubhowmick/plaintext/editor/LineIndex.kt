package io.github.kaustubhowmick.plaintext.editor

/**
 * Start offsets of logical lines (text split on '\n'), kept up to date
 * incrementally as the document changes (design.md §3.2, §5.31).
 * Line numbers here are 0-based; the UI adds 1.
 */
class LineIndex {
    private var starts = IntArray(64)
    private var count = 1 // starts[0] == 0 always

    val lineCount: Int get() = count

    fun rebuild(text: CharSequence) {
        count = 1
        starts[0] = 0
        for (i in 0 until text.length) {
            if (text[i] == '\n') append(i + 1)
        }
    }

    fun lineStart(line: Int): Int = starts[line.coerceIn(0, count - 1)]

    /** The 0-based line containing [offset]. */
    fun lineOf(offset: Int): Int {
        var lo = 0
        var hi = count - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** `[start, start + removedLength)` was replaced by [inserted]. */
    fun onReplace(start: Int, removedLength: Int, inserted: CharSequence) {
        val removedEnd = start + removedLength
        val delta = inserted.length - removedLength
        // Line starts in (start, removedEnd] were produced by removed newlines.
        val lo = firstGreaterThan(start)
        val hi = firstGreaterThan(removedEnd)
        var added = 0
        for (i in 0 until inserted.length) if (inserted[i] == '\n') added++
        val newCount = count - (hi - lo) + added
        val tail = count - hi
        if (newCount > starts.size) starts = starts.copyOf(maxOf(newCount, starts.size * 2))
        System.arraycopy(starts, hi, starts, lo + added, tail)
        var k = lo
        for (i in 0 until inserted.length) if (inserted[i] == '\n') starts[k++] = start + i + 1
        for (i in lo + added until lo + added + tail) starts[i] += delta
        count = newCount
    }

    private fun firstGreaterThan(offset: Int): Int {
        var lo = 0
        var hi = count
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] > offset) hi = mid else lo = mid + 1
        }
        return lo
    }

    private fun append(start: Int) {
        if (count == starts.size) starts = starts.copyOf(starts.size * 2)
        starts[count++] = start
    }
}
