package io.github.kaustubhowmick.plaintext.editor

import android.content.Context
import android.text.format.DateFormat
import java.util.Date

/** Notepad's F5 / `.LOG` stamp: short time, a space, short date, in the device locale (§5.15). */
object TimeDate {
    fun now(context: Context, date: Date = Date()): String =
        DateFormat.getTimeFormat(context).format(date) + " " + DateFormat.getDateFormat(context).format(date)
}
