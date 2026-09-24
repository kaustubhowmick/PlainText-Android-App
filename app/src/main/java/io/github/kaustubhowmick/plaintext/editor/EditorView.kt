package io.github.kaustubhowmick.plaintext.editor

import android.content.Context
import android.text.Editable
import android.text.InputFilter
import android.text.Spanned
import android.text.style.TabStopSpan
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.accessibility.AccessibilityNodeInfo
import android.view.textclassifier.TextClassifier
import android.widget.EditText
import io.github.kaustubhowmick.plaintext.R

/**
 * The document text area (design.md §3.2): an EditText tuned for large plain
 * text, with pinch/Ctrl+wheel zoom, a real Tab key, plain-text paste, and
 * Notepad-style 8-column tab stops.
 *
 * Android lays out an EditText by measuring every paragraph of the affected
 * text in one pass, holding a measurement object per paragraph until the pass
 * ends. For a few hundred thousand lines that runs out of memory (a
 * 500,000-line file crashed on open). So whole-text changes and layout rebuilds
 * go through [setTextInSlices], [replaceInSlices], and [onMeasure], which add
 * the text [SLICE_LINES] lines at a time: each pass stays small and the
 * finished layout is the same.
 */
class EditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : EditText(context, attrs) {

    interface Listener {
        fun onSelectionChanged(start: Int, end: Int)
        fun onScrolled()
        /** A clipboard command (cut/paste) is about to change the text. */
        fun onBeforeClipboardEdit()
        fun onZoomStep(zoomIn: Boolean)
        fun onPinch(scale: Float, finished: Boolean)
    }

    var listener: Listener? = null

    /** When true the text can be selected and copied but not changed. */
    var viewOnly = false
        set(value) {
            field = value
            filters = if (value) arrayOf(REJECT_ALL) else emptyArray()
            showSoftInputOnFocus = !value
        }

    /** True while the view takes its text out and puts it back to rebuild the layout; not an edit. */
    var isSwappingText = false
        private set

    /** One tab stop every 8 spaces. Its width is updated in place, so font changes don't re-add a span. */
    private val tabStops = object : TabStopSpan {
        var width = 1
        override fun getTabStop() = width
    }
    private var horizontallyScrolling = false
    private var pinchScale = 1f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            pinchScale = 1f
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            pinchScale *= detector.scaleFactor
            listener?.onPinch(pinchScale, false)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            listener?.onPinch(pinchScale, true)
        }
    })

    init {
        // Stop smart-selection/link scanning of the whole (possibly huge) text.
        setTextClassifier(TextClassifier.NO_OP)
    }

    /**
     * Sets the tab width for the current font: a stop every 8 spaces, like Notepad.
     * Takes effect at the next layout, which a font change always causes.
     */
    fun applyTabStops() {
        tabStops.width = (paint.measureText(" ") * 8).toInt().coerceAtLeast(1)
    }

    /** Replaces the whole text with [content], adding it in slices (see the class comment). */
    fun setTextInSlices(content: CharSequence) {
        withoutFilters {
            setText("") // a fresh Editable, and the IME starts over
            // Attached while the text is empty, the inclusive span grows with each slice.
            // Adding it over a big text would re-lay out all of it at once.
            text.setSpan(tabStops, 0, 0, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            insertSlices(text, 0, content)
        }
    }

    /** `text.replace(start, end, content)`, inserting a big [content] in slices. */
    fun replaceInSlices(start: Int, end: Int, content: CharSequence) {
        val t = text
        if (!TextSlices.hasMoreLinesThan(content, SLICE_LINES)) {
            t.replace(start, end, content)
            return
        }
        withoutFilters {
            t.delete(start, end)
            insertSlices(t, start, content)
        }
    }

    private fun insertSlices(t: Editable, at: Int, content: CharSequence) {
        var from = 0
        while (from < content.length) {
            val to = TextSlices.end(content, from, SLICE_LINES)
            t.insert(at + from, content, from, to)
            from = to
        }
    }

    private inline fun withoutFilters(block: () -> Unit) {
        val saved = filters
        filters = NO_FILTERS
        try {
            block()
        } finally {
            filters = saved
        }
    }

    override fun setHorizontallyScrolling(whether: Boolean) {
        horizontallyScrolling = whether
        super.setHorizontallyScrolling(whether)
    }

    /**
     * Zoom, font, word-wrap, and width changes (rotation, split screen) make
     * TextView rebuild the layout of the whole text here. For a big document,
     * take the text out first and put it back in slices once the new, empty
     * layout exists.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val t = text
        if (isSwappingText || !layoutWillBeRebuilt(widthMeasureSpec) || !TextSlices.hasMoreLinesThan(t, SLICE_LINES)) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val content = t.toString()
        val selStart = selectionStart
        val selEnd = selectionEnd
        isSwappingText = true
        try {
            withoutFilters { t.clear() }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            withoutFilters { insertSlices(t, 0, content) }
            setSelection(selStart.coerceIn(0, content.length), selEnd.coerceIn(0, content.length))
        } finally {
            isSwappingText = false
        }
        post { bringPointIntoView(selectionEnd) }
    }

    /** Mirrors TextView.onMeasure: no layout yet, or a new wrapping width. */
    private fun layoutWillBeRebuilt(widthMeasureSpec: Int): Boolean {
        val l = layout ?: return true
        if (horizontallyScrolling || MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.EXACTLY) return false
        return l.width != MeasureSpec.getSize(widthMeasureSpec) - compoundPaddingLeft - compoundPaddingRight
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        listener?.onSelectionChanged(selStart, selEnd)
    }

    override fun onScrollChanged(horiz: Int, vert: Int, oldHoriz: Int, oldVert: Int) {
        super.onScrollChanged(horiz, vert, oldHoriz, oldVert)
        listener?.onScrolled()
    }

    override fun onTextContextMenuItem(id: Int): Boolean {
        if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText || id == android.R.id.cut) {
            if (viewOnly) return false
            listener?.onBeforeClipboardEdit()
        }
        // Paste is always plain text (design.md §5.11).
        return super.onTextContextMenuItem(if (id == android.R.id.paste) android.R.id.pasteAsPlainText else id)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_TAB && !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed) {
            if (!event.isShiftPressed && !viewOnly) {
                val t = text
                val s = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
                val e = maxOf(selectionStart, selectionEnd).coerceAtLeast(0)
                t.replace(s, e, "\t")
            }
            return true // Tab and Shift+Tab never move focus out of the editor.
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress || event.pointerCount > 1) {
            // Don't let a pinch start a selection or scroll.
            if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                super.onTouchEvent(cancel)
                cancel.recycle()
            }
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL && event.metaState and KeyEvent.META_CTRL_ON != 0) {
            val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (v != 0f) {
                listener?.onZoomStep(v > 0)
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        // A hint for TalkBack without a visible hint (design.md §5.35).
        info.hintText = context.getString(R.string.editor_hint)
    }

    companion object {
        /** Lines per slice: small enough that one layout pass stays a few MB. */
        const val SLICE_LINES = 10_000

        /** Keeps the destination unchanged: used for view-only documents. */
        private val REJECT_ALL = InputFilter { _, _, _, dest, dstart, dend -> dest.subSequence(dstart, dend) }
        private val NO_FILTERS = emptyArray<InputFilter>()
    }
}
