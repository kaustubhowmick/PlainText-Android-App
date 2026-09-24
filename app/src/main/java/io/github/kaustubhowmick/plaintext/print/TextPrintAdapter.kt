package io.github.kaustubhowmick.plaintext.print

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.pdf.PrintedPdfDocument
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.TabStopSpan
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService

/**
 * Paginates plain text into a PDF for the Android print framework (design.md §5.7):
 * editor font family/style at the Page Setup point size, black on white, 8-column
 * tab stops, always wrapped, with Notepad-style header and footer lines.
 */
class TextPrintAdapter(
    private val context: Context,
    private val text: String,
    private val fileName: String,
    private val typeface: Typeface,
    private val fontSizePt: Int,
    private val marginsMm: IntArray, // left, right, top, bottom
    private val header: String,
    private val footer: String,
    private val date: String,
    private val time: String,
    private val worker: ExecutorService,
    private val main: android.os.Handler,
    private val onFailed: () -> Unit,
) : PrintDocumentAdapter() {

    private var attributes: PrintAttributes? = null
    private var layout: StaticLayout? = null

    /** First line of each page, plus a final sentinel. */
    private var pageStarts = IntArray(0)
    private var geometry: Geometry? = null

    private class Geometry(
        val pageWidth: Float,
        val pageHeight: Float,
        val left: Float,
        val right: Float,
        val top: Float,
        val bottom: Float,
        val bandHeight: Float,
    ) {
        val bodyTop get() = top + bandHeight
        val bodyBottom get() = pageHeight - bottom - bandHeight
        val bodyWidth get() = pageWidth - left - right
    }

    private fun paint() = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        this.typeface = this@TextPrintAdapter.typeface
        textSize = fontSizePt.toFloat() // PDF canvas units are points
    }

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }
        val changed = oldAttributes == null || oldAttributes != newAttributes || layout == null
        attributes = newAttributes
        if (!changed) {
            callback.onLayoutFinished(info(), false)
            return
        }
        worker.execute {
            try {
                val ok = paginate(newAttributes, cancellationSignal)
                main.post {
                    if (!ok) callback.onLayoutCancelled() else callback.onLayoutFinished(info(), true)
                }
            } catch (t: Throwable) {
                main.post {
                    callback.onLayoutFailed(t.message)
                    onFailed()
                }
            }
        }
    }

    private fun info() = PrintDocumentInfo.Builder(fileName.ifEmpty { "document" })
        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
        .setPageCount(maxOf(1, pageStarts.size - 1))
        .build()

    /** Runs on the worker thread. Returns false if cancelled. */
    private fun paginate(attrs: PrintAttributes, cancel: CancellationSignal): Boolean {
        val media = attrs.mediaSize ?: PrintAttributes.MediaSize.ISO_A4
        val pageWidth = media.widthMils * 72f / 1000f
        val pageHeight = media.heightMils * 72f / 1000f
        fun mm(v: Int) = v * 72f / 25.4f
        val paint = paint()
        val fm = paint.fontMetrics
        val lineHeight = fm.descent - fm.ascent
        val g = Geometry(
            pageWidth, pageHeight,
            mm(marginsMm[0]), mm(marginsMm[1]), mm(marginsMm[2]), mm(marginsMm[3]),
            lineHeight * 1.5f,
        )
        val width = g.bodyWidth.toInt().coerceAtLeast(1)
        val spanned = SpannableString(text)
        spanned.setSpan(
            TabStopSpan.Standard((paint.measureText(" ") * 8).toInt().coerceAtLeast(1)),
            0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE,
        )
        val built = StaticLayout.Builder.obtain(spanned, 0, spanned.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setIncludePad(false)
            .build()
        if (cancel.isCanceled) return false

        val bodyHeight = g.bodyBottom - g.bodyTop
        val starts = ArrayList<Int>()
        var line = 0
        while (line < built.lineCount) {
            if (cancel.isCanceled) return false
            starts.add(line)
            val pageTop = built.getLineTop(line)
            var next = line + 1
            while (next < built.lineCount && built.getLineBottom(next) - pageTop <= bodyHeight) next++
            line = next
        }
        if (starts.isEmpty()) starts.add(0)
        starts.add(built.lineCount)
        layout = built
        geometry = g
        pageStarts = starts.toIntArray()
        return true
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback,
    ) {
        val attrs = attributes
        val built = layout
        val g = geometry
        if (attrs == null || built == null || g == null) {
            callback.onWriteFailed(null)
            return
        }
        worker.execute {
            val pdf = PrintedPdfDocument(context, attrs)
            try {
                val count = maxOf(1, pageStarts.size - 1)
                val written = ArrayList<PageRange>()
                for (i in 0 until count) {
                    if (cancellationSignal.isCanceled) {
                        main.post { callback.onWriteCancelled() }
                        return@execute
                    }
                    if (!inRanges(i, pages)) continue
                    val page = pdf.startPage(i)
                    drawPage(page.canvas, built, g, i)
                    pdf.finishPage(page)
                    written.add(PageRange(i, i))
                }
                FileOutputStream(destination.fileDescriptor).use { pdf.writeTo(it) }
                main.post { callback.onWriteFinished(written.toTypedArray()) }
            } catch (t: Throwable) {
                main.post {
                    callback.onWriteFailed(t.message)
                    onFailed()
                }
            } finally {
                pdf.close()
            }
        }
    }

    private fun inRanges(page: Int, ranges: Array<out PageRange>): Boolean =
        ranges.any { it == PageRange.ALL_PAGES || page in it.start..it.end }

    private fun drawPage(canvas: android.graphics.Canvas, built: StaticLayout, g: Geometry, index: Int) {
        canvas.drawColor(Color.WHITE)
        val paint = paint()
        val fm = paint.fontMetrics
        drawBand(canvas, paint, g, HeaderFooter.format(header, fileName, index + 1, date, time), g.top - fm.ascent)
        drawBand(canvas, paint, g, HeaderFooter.format(footer, fileName, index + 1, date, time), g.pageHeight - g.bottom - fm.descent)

        if (built.lineCount == 0) return
        val first = pageStarts[index]
        val last = pageStarts[index + 1] // exclusive
        if (first >= last) return
        val top = built.getLineTop(first).toFloat()
        val bottom = built.getLineTop(last).toFloat()
        canvas.save()
        canvas.translate(g.left, g.bodyTop)
        canvas.clipRect(0f, 0f, g.bodyWidth, bottom - top)
        canvas.translate(0f, -top)
        built.draw(canvas)
        canvas.restore()
    }

    private fun drawBand(canvas: android.graphics.Canvas, paint: TextPaint, g: Geometry, line: HeaderFooter.Line, baseline: Float) {
        if (line.isEmpty) return
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(line.left, g.left, baseline, paint)
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(line.center, g.left + g.bodyWidth / 2, baseline, paint)
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText(line.right, g.pageWidth - g.right, baseline, paint)
        paint.textAlign = Paint.Align.LEFT
    }
}
