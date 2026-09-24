package io.github.kaustubhowmick.plaintext.storage

import android.content.Context
import android.graphics.Typeface
import io.github.kaustubhowmick.plaintext.io.Encoding
import io.github.kaustubhowmick.plaintext.io.LineEnding

/** Typed wrapper over the single SharedPreferences file (design.md §6.11). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var wordWrap: Boolean
        get() = prefs.getBoolean("word_wrap", true)
        set(v) = prefs.edit().putBoolean("word_wrap", v).apply()

    var fontFamily: String
        get() = prefs.getString("font_family", null) ?: "monospace"
        set(v) = prefs.edit().putString("font_family", v).apply()

    var fontStyle: Int
        get() = prefs.getInt("font_style", Typeface.NORMAL)
        set(v) = prefs.edit().putInt("font_style", v).apply()

    var fontSizeSp: Int
        get() = prefs.getInt("font_size_sp", 14)
        set(v) = prefs.edit().putInt("font_size_sp", v).apply()

    var zoomPercent: Int
        get() = prefs.getInt("zoom_percent", 100).coerceIn(ZOOM_MIN, ZOOM_MAX)
        set(v) = prefs.edit().putInt("zoom_percent", v.coerceIn(ZOOM_MIN, ZOOM_MAX)).apply()

    var statusBar: Boolean
        get() = prefs.getBoolean("status_bar", true)
        set(v) = prefs.edit().putBoolean("status_bar", v).apply()

    var defaultFolderUri: String?
        get() = prefs.getString("default_folder_uri", null)
        set(v) = prefs.edit().putString("default_folder_uri", v).apply()

    var defaultEncoding: Encoding
        get() = Encoding.parse(prefs.getString("default_encoding", null))
        set(v) = prefs.edit().putString("default_encoding", v.name).apply()

    var defaultLineEnding: LineEnding
        get() = LineEnding.parse(prefs.getString("default_line_ending", null))
        set(v) = prefs.edit().putString("default_line_ending", v.name).apply()

    var findMatchCase: Boolean
        get() = prefs.getBoolean("find_match_case", false)
        set(v) = prefs.edit().putBoolean("find_match_case", v).apply()

    var findWrapAround: Boolean
        get() = prefs.getBoolean("find_wrap_around", true)
        set(v) = prefs.edit().putBoolean("find_wrap_around", v).apply()

    /** Left, right, top, bottom in millimetres. */
    var pageMarginsMm: IntArray
        get() {
            val parts = (prefs.getString("page_margins_mm", null) ?: DEFAULT_MARGINS).split(',')
            val values = parts.mapNotNull { it.trim().toIntOrNull() }
            return if (values.size == 4) values.toIntArray() else DEFAULT_MARGINS.split(',').map { it.toInt() }.toIntArray()
        }
        set(v) = prefs.edit().putString("page_margins_mm", v.joinToString(",")).apply()

    var pageHeader: String
        get() = prefs.getString("page_header", null) ?: "&f"
        set(v) = prefs.edit().putString("page_header", v).apply()

    var pageFooter: String
        get() = prefs.getString("page_footer", null) ?: "Page &p"
        set(v) = prefs.edit().putString("page_footer", v).apply()

    var printFontSizePt: Int
        get() = prefs.getInt("print_font_size_pt", 10)
        set(v) = prefs.edit().putInt("print_font_size_pt", v).apply()

    var recentFilesJson: String
        get() = prefs.getString("recent_files", null) ?: "[]"
        set(v) = prefs.edit().putString("recent_files", v).apply()

    var firstRunDone: Boolean
        get() = prefs.getBoolean("first_run_done", false)
        set(v) = prefs.edit().putBoolean("first_run_done", v).apply()

    companion object {
        const val ZOOM_MIN = 10
        const val ZOOM_MAX = 500
        private const val DEFAULT_MARGINS = "19,19,25,25"
    }
}
