package io.github.kaustubhowmick.plaintext.editor

import android.content.Context
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

    private var tabSpan: TabStopSpan? = null
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

    /** Applies one TabStopSpan over the whole text: a stop every 8 spaces, like Notepad. */
    fun applyTabStops() {
        val text = text ?: return
        tabSpan?.let { text.removeSpan(it) }
        val width = (paint.measureText(" ") * 8).toInt().coerceAtLeast(1)
        val span = TabStopSpan.Standard(width)
        text.setSpan(span, 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        tabSpan = span
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

    private companion object {
        /** Keeps the destination unchanged: used for view-only documents. */
        val REJECT_ALL = InputFilter { _, _, _, dest, dstart, dend -> dest.subSequence(dstart, dend) }
    }
}
