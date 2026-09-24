package io.github.kaustubhowmick.plaintext.print

/**
 * Notepad's Page Setup header/footer codes (design.md §5.7): &f file name,
 * &p page, &d date, &t time, &l / &c / &r alignment, && a literal ampersand.
 * Unknown codes print literally. Pure Kotlin.
 */
object HeaderFooter {

    data class Line(val left: String, val center: String, val right: String) {
        val isEmpty: Boolean get() = left.isEmpty() && center.isEmpty() && right.isEmpty()
    }

    fun format(pattern: String, fileName: String, page: Int, date: String, time: String): Line {
        val parts = arrayOf(StringBuilder(), StringBuilder(), StringBuilder()) // left, center, right
        var align = 1 // Notepad centers by default
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            if (c == '&' && i + 1 < pattern.length) {
                when (pattern[i + 1].lowercaseChar()) {
                    'f' -> parts[align].append(fileName)
                    'p' -> parts[align].append(page)
                    'd' -> parts[align].append(date)
                    't' -> parts[align].append(time)
                    'l' -> align = 0
                    'c' -> align = 1
                    'r' -> align = 2
                    '&' -> parts[align].append('&')
                    else -> parts[align].append(c).append(pattern[i + 1])
                }
                i += 2
            } else {
                parts[align].append(c)
                i++
            }
        }
        return Line(parts[0].toString(), parts[1].toString(), parts[2].toString())
    }
}
