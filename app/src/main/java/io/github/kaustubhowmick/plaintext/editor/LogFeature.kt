package io.github.kaustubhowmick.plaintext.editor

/**
 * Notepad's `.LOG` feature (design.md §5.27): a file whose first line is exactly
 * `.LOG` gets a timestamp appended each time it is opened. Pure Kotlin; works on
 * the editor's normalized ('\n'-only) text.
 */
object LogFeature {
    private const val MARKER = ".LOG"

    /** True when the text starts with exactly `.LOG` followed by a line break or the end. */
    fun isLogFile(text: CharSequence): Boolean =
        text.startsWith(MARKER) && (text.length == MARKER.length || text[MARKER.length] == '\n')

    /** What to append: a line break if the text doesn't end with one, the stamp, and a line break. */
    fun entry(text: CharSequence, stamp: String): String =
        (if (text.isEmpty() || text[text.length - 1] == '\n') "" else "\n") + stamp + "\n"
}
