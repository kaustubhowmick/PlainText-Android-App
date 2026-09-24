package io.github.kaustubhowmick.plaintext.ui

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import io.github.kaustubhowmick.plaintext.R

/**
 * The bottom status bar (design.md §5.19): Ln/Col, zoom, line ending, encoding.
 * The line-ending and encoding segments are tappable.
 */
class StatusBarView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    val position: TextView
    val zoom: TextView
    val lineEnding: TextView
    val encoding: TextView
    private val zoomDivider: View

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val d = resources.displayMetrics.density
        setPadding((8 * d).toInt(), 0, (8 * d).toInt(), 0)
        position = segment(clickable = false).also { it.layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f) }
        addView(position)
        zoomDivider = divider()
        addView(zoomDivider)
        zoom = segment(clickable = false)
        addView(zoom)
        addView(divider())
        lineEnding = segment(clickable = true)
        addView(lineEnding)
        addView(divider())
        encoding = segment(clickable = true)
        addView(encoding)
    }

    private fun segment(clickable: Boolean) = TextView(context).apply {
        val d = resources.displayMetrics.density
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        gravity = Gravity.CENTER_VERTICAL
        setSingleLine()
        minWidth = if (clickable) (48 * d).toInt() else 0
        setPadding((8 * d).toInt(), 0, (8 * d).toInt(), 0)
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
        if (clickable) {
            isClickable = true
            isFocusable = true
            val tv = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
        }
    }

    private fun divider() = View(context).apply {
        val d = resources.displayMetrics.density
        layoutParams = LayoutParams(maxOf(1, d.toInt()), (16 * d).toInt())
        setBackgroundColor(context.getColor(R.color.divider))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setZoomVisible(visible: Boolean) {
        zoom.visibility = if (visible) VISIBLE else GONE
        zoomDivider.visibility = if (visible) VISIBLE else GONE
    }
}
