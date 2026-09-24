package io.github.kaustubhowmick.plaintext.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import io.github.kaustubhowmick.plaintext.R

/**
 * A draggable scroll thumb on the right edge for long documents (design.md §5.31).
 * Shown only when the text is taller than ten screens.
 */
class FastScroller @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var target: EditText? = null
    private val density = resources.displayMetrics.density
    private val thumbHeight = 48 * density
    private val thumbWidth = 6 * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.accent) }
    private val rect = RectF()
    private var dragging = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun range(): Int {
        val t = target ?: return 0
        val layout = t.layout ?: return 0
        return (layout.height + t.totalPaddingTop + t.totalPaddingBottom - t.height).coerceAtLeast(0)
    }

    /** Called after scrolling or relayout of the editor. */
    fun sync() {
        val t = target
        val long = t != null && t.layout != null && t.height > 0 && t.layout.height > t.height * 10
        visibility = if (long) VISIBLE else GONE
        if (long) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val t = target ?: return
        val r = range()
        if (r <= 0) return
        val fraction = t.scrollY.toFloat() / r
        val top = fraction.coerceIn(0f, 1f) * (height - thumbHeight)
        rect.set(width - thumbWidth - 4 * density, top, width - 4 * density, top + thumbHeight)
        paint.alpha = if (dragging) 255 else 160
        canvas.drawRoundRect(rect, thumbWidth / 2, thumbWidth / 2, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val t = target ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {}
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                invalidate()
                return true
            }
            else -> return false
        }
        val fraction = ((event.y - thumbHeight / 2) / (height - thumbHeight)).coerceIn(0f, 1f)
        t.scrollTo(t.scrollX, (fraction * range()).toInt())
        invalidate()
        return true
    }
}
