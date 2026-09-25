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
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
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
 * 500,000-line file crashed on open). So for texts of more than [MANY_LINES]
 * lines, whole-text changes and layout rebuilds go through [setTextInSlices],
 * [replaceInSlices], and [onMeasure], which add the text [SLICE_LINES] lines at
 * a time: each pass stays small and the finished text is the same.
 *
 * Smaller texts keep the one-pass path: each inserted slice becomes one block
 * of the layout that is redrawn as a whole, and while an accessibility service
 * is on, TextView copies the whole text on every insert. Both make many small
 * slices slow.
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

    /**
     * When true the text can be selected and copied but not changed. A
     * view-only document has no IME connection, so the keyboard app never
     * reads or edits it.
     */
    var viewOnly = false
        set(value) {
            val changed = field != value
            field = value
            filters = if (value) arrayOf(REJECT_ALL) else emptyArray()
            showSoftInputOnFocus = !value
            if (changed) context.getSystemService(InputMethodManager::class.java)?.restartInput(this)
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

    /** Replaces the whole text with [content], in slices if it is long (see the class comment). */
    fun setTextInSlices(content: CharSequence) {
        val first = if (TextSlices.hasMoreLinesThan(content, MANY_LINES)) sliceEnd(content, 0) else content.length
        withoutFilters {
            setText(content.subSequence(0, first)) // a fresh Editable, and the IME starts over
            // Inclusive, so the span grows with the slices inserted at its end.
            text.setSpan(tabStops, 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            if (first < content.length) insertSlices(text, first, content.subSequence(first, content.length))
        }
    }

    /** `text.replace(start, end, content)`, inserting a big [content] in slices. */
    fun replaceInSlices(start: Int, end: Int, content: CharSequence) {
        val t = text
        if (!TextSlices.hasMoreLinesThan(content, MANY_LINES)) {
            t.replace(start, end, content)
            return
        }
        withoutFilters {
            t.delete(start, end)
            insertSlices(t, start, content)
        }
    }

    private fun insertSlices(t: Editable, at: Int, content: CharSequence) {
        beginBatchEdit() // one caret/IME update at the end instead of one per slice
        try {
            var from = 0
            while (from < content.length) {
                val to = sliceEnd(content, from)
                t.insert(at + from, content, from, to)
                from = to
            }
        } finally {
            endBatchEdit()
        }
    }

    private fun sliceEnd(content: CharSequence, from: Int) = TextSlices.end(content, from, SLICE_LINES, SLICE_CHARS)

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
        if (isSwappingText || !layoutWillBeRebuilt(widthMeasureSpec) || !TextSlices.hasMoreLinesThan(t, MANY_LINES)) {
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

    /**
     * Mirrors TextView.onMeasure: no layout yet, or a layout width or
     * ellipsized width that differs from the new one. Without word wrap the
     * layout is VERY_WIDE and its ellipsized width is too, so TextView rebuilds
     * it at every measure. When unsure, say yes: slicing is only slower.
     */
    private fun layoutWillBeRebuilt(widthMeasureSpec: Int): Boolean {
        val l = layout ?: return true
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.EXACTLY) return true
        val width = MeasureSpec.getSize(widthMeasureSpec) - compoundPaddingLeft - compoundPaddingRight
        val want = if (horizontallyScrolling) VERY_WIDE else width
        return l.width != want || l.ellipsizedWidth != width
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

    /**
     * A view-only document rejects edits with an input filter, but the rejected
     * replace still makes the layout re-measure the line: seconds per key in a
     * multi-MB line. So typing keys are dropped before that.
     */
    private fun changesText(keyCode: Int, event: KeyEvent): Boolean =
        !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed &&
            (event.isPrintingKey || keyCode == KeyEvent.KEYCODE_SPACE || keyCode == KeyEvent.KEYCODE_DEL ||
                keyCode == KeyEvent.KEYCODE_FORWARD_DEL || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        if (viewOnly && (keyCode == KeyEvent.KEYCODE_UNKNOWN || changesText(keyCode, event))) return true
        return super.onKeyMultiple(keyCode, repeatCount, event)
    }

    override fun onCheckIsTextEditor() = !viewOnly && super.onCheckIsTextEditor()

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? =
        if (viewOnly) null else super.onCreateInputConnection(outAttrs)

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (viewOnly && changesText(keyCode, event)) return true
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
        /**
         * Texts with more lines than this are laid out in slices. A 142,000-line
         * file lays out fine in one pass on a 192 MB heap; 500,000 lines don't.
         */
        const val MANY_LINES = 200_000

        /** A slice ends after this many lines, or after the line that reaches [SLICE_CHARS]. */
        const val SLICE_LINES = 20_000
        const val SLICE_CHARS = 1_000_000

        /** TextView's layout width without word wrap. */
        private const val VERY_WIDE = 1024 * 1024

        /** Keeps the destination unchanged: used for view-only documents. */
        private val REJECT_ALL = InputFilter { _, _, _, dest, dstart, dend -> dest.subSequence(dstart, dend) }
        private val NO_FILTERS = emptyArray<InputFilter>()
    }
}
