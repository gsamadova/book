package com.guny90.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.LineHeightSpan
import android.text.style.ReplacementSpan
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/** A chapter laid out once, split into pages by line index. */
class Pages(val layout: StaticLayout, private val starts: IntArray) {
    val count get() = starts.size

    fun startLine(page: Int) = starts[page]
    fun endLine(page: Int) = if (page + 1 < starts.size) starts[page + 1] else layout.lineCount

    /** Character offset of the first character on [page]; stable across font/size changes. */
    fun offsetOf(page: Int) = layout.getLineStart(starts[page])

    fun pageForOffset(offset: Int): Int {
        val line = layout.getLineForOffset(offset.coerceIn(0, layout.text.length))
        var page = 0
        while (page + 1 < starts.size && starts[page + 1] <= line) page++
        return page
    }

    companion object {
        /** Runs on a background thread. The cheap break strategy matters a lot on slow e-ink CPUs. */
        /** Extra space after each paragraph, as a fraction of the text size (menu choices). */
        val PARAGRAPH_SPACINGS = listOf("None" to 0f, "Small" to 0.35f, "Medium" to 0.7f, "Large" to 1.1f)

        /**
         * [pictureSize] (null: pictures off) gives a picture's pixel size; it is then drawn in place
         * of its "[image]" placeholder, scaled to fit the page. [pixelScale]: screen pixels per picture pixel.
         */
        fun paginate(
            chapter: CharSequence, width: Int, height: Int, textSizePx: Float, paragraphSpacing: Float,
            pictureSize: ((String) -> IntArray?)? = null, pixelScale: Float = 1f,
        ): Pages {
            val text = SpannableStringBuilder(chapter)
            val gap = (paragraphSpacing * textSizePx).toInt()
            if (gap > 0) text.setSpan(ParagraphGap(gap), 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            if (pictureSize != null) {
                val pad = (0.3f * textSizePx).toInt()
                // A picture is one line, so it must fit on one page together with its spacing.
                val maxHeight = ((height - 2 * pad - gap * LINE_SPACING) / LINE_SPACING).toInt() - 2
                for (ref in text.getSpans(0, text.length, HtmlText.ImageRef::class.java)) {
                    val size = pictureSize(ref.path) ?: continue
                    val w = size[0] * pixelScale
                    val h = size[1] * pixelScale
                    val fit = minOf(1f, width / w, maxHeight / h)
                    val picture = PictureSpan(
                        ref.path, (w * fit).toInt().coerceAtLeast(1), (h * fit).toInt().coerceAtLeast(1), width, pad,
                    )
                    text.setSpan(picture, text.getSpanStart(ref), text.getSpanEnd(ref), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }

            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                linkColor = Color.BLACK // default is transparent: links (e.g. TOC pages) would be invisible
                textSize = textSizePx
                typeface = Typeface.SERIF
            }
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, LINE_SPACING)
                .setIncludePad(false)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build()

            val starts = ArrayList<Int>()
            starts.add(0)
            var pageTop = 0
            for (line in 0 until layout.lineCount) {
                if (layout.getLineBottom(line) - pageTop > height && line > starts.last()) {
                    starts.add(line)
                    pageTop = layout.getLineTop(line)
                }
            }
            return Pages(layout, starts.toIntArray())
        }

        const val LINE_SPACING = 1.15f
    }

    /** Pictures on [page] that still need decoding (see [PictureSpan.bitmap]). */
    fun picturesToLoad(page: Int): List<PictureSpan> {
        val text = layout.text as? Spanned ?: return emptyList()
        val start = layout.getLineStart(startLine(page))
        val end = layout.getLineEnd(endLine(page) - 1)
        return text.getSpans(start, end, PictureSpan::class.java).filter { it.bitmap == null && !it.failed }
    }

    /** Adds [gap] px below the last line of every paragraph (but not after a <br> line break). */
    private class ParagraphGap(private val gap: Int) : LineHeightSpan {
        override fun chooseHeight(
            text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt,
        ) {
            if (end <= 0 || end > text.length || text[end - 1] != '\n') return
            if (text is Spanned && text.getSpans(end - 1, end, HtmlText.LineBreak::class.java).isNotEmpty()) return
            fm.descent += gap
            fm.bottom += gap
        }
    }
}

/**
 * A picture drawn in place of its "[image]" placeholder: one line of [width] x [height] pixels
 * (plus [pad] above and below), centered in a line of [lineWidth]. The bitmap is decoded before
 * the page is shown; until then (or if it fails) a frame is drawn.
 */
class PictureSpan(
    val path: String,
    val width: Int,
    val height: Int,
    private val lineWidth: Int,
    private val pad: Int,
) : ReplacementSpan() {
    @Volatile var bitmap: Bitmap? = null
    @Volatile var failed = false

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null) {
            // The layout multiplies line heights by its line spacing; divide so the line is just the picture.
            val lineHeight = ((height + 2 * pad) / Pages.LINE_SPACING).toInt()
            fm.ascent = -lineHeight
            fm.top = -lineHeight
            fm.descent = 0
            fm.bottom = 0
        }
        return lineWidth
    }

    override fun draw(
        canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint,
    ) {
        val left = x + (lineWidth - width) / 2f
        val box = RectF(left, (top + pad).toFloat(), left + width, (top + pad + height).toFloat())
        val bmp = bitmap
        if (bmp != null) canvas.drawBitmap(bmp, null, box, picturePaint)
        else canvas.drawRect(box, framePaint)
    }

    companion object {
        /** Gray: an e-ink screen shows nothing else anyway, and colors would dither unevenly. */
        val picturePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        private val framePaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
    }
}

/**
 * Draws exactly one page. No scrolling and no animation: every change is a single
 * full redraw, which is what e-ink panels handle best.
 */
class PageView(context: Context) : View(context) {
    enum class Zone { PREV, MENU, NEXT }

    var onTap: ((Zone) -> Unit)? = null
    /**
     * Long-press selects a word; sliding the finger (without lifting) extends the selection.
     * On release: the selected text, whether it is one word, and its vertical center on screen.
     */
    var onTextSelected: ((text: String, singleWord: Boolean, centerY: Int) -> Unit)? = null
    /** Long-press on a picture (instead of selecting text). */
    var onPictureLongPress: ((PictureSpan) -> Unit)? = null
    /** Called after layout; the activity re-paginates if the text area size changed. */
    var onTextAreaLaidOut: (() -> Unit)? = null

    private var pages: Pages? = null
    private var page = 0
    private var statusLeft = ""
    private var statusRight = ""
    private var message: String? = null
    private var selection: IntRange? = null
    /** The sentence being read aloud (chapter offsets), underlined. */
    private var spoken: IntRange? = null

    private val density = resources.displayMetrics.density
    private val margin = (16 * density).toInt()
    private val statusHeight = (28 * density).toInt()
    private val statusPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 13 * resources.displayMetrics.scaledDensity
    }
    private val messagePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 18 * resources.displayMetrics.scaledDensity
        typeface = Typeface.SERIF
    }

    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
    }
    private val underlinePaint = Paint().apply {
        color = Color.BLACK
        strokeWidth = 2 * density
    }

    // Long-press detection. Generous slop: e-ink touch panels report jittery coordinates.
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val touchSlop = 24 * density
    private var downX = 0f
    private var downY = 0f
    private var pressing = false
    private var longPressed = false
    private var selecting = false
    private var anchor: IntRange? = null // the long-pressed word; the selection always includes it
    private val longPressAction = Runnable {
        longPressed = true
        val picture = pictureAt(downX, downY)
        if (picture != null) onPictureLongPress?.invoke(picture)
        else selecting = startSelection(downX, downY)
    }

    init {
        setBackgroundColor(Color.WHITE)
        isFocusable = true
    }

    val textWidth get() = width - paddingLeft - paddingRight - 2 * margin
    val textHeight get() = height - paddingTop - paddingBottom - margin - statusHeight

    fun showPage(pages: Pages, page: Int, statusLeft: String, statusRight: String) {
        this.pages = pages
        this.page = page
        this.statusLeft = statusLeft
        this.statusRight = statusRight
        message = null
        selection = null
        invalidate()
    }

    fun showMessage(text: String) {
        pages = null
        message = text
        selection = null
        invalidate()
    }

    /** Underlines [range] (or nothing). With [redraw] false the change shows on the next redraw. */
    fun setSpoken(range: IntRange?, redraw: Boolean = true) {
        if (range == spoken) return
        spoken = range
        if (redraw) invalidate()
    }

    fun clearSelection() {
        if (selection != null) {
            selection = null
            invalidate()
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        onTextAreaLaidOut?.invoke()
    }

    override fun onDraw(canvas: Canvas) {
        val p = pages
        if (p == null) {
            drawMessage(canvas, message ?: "")
            return
        }
        val layout = p.layout
        val top = layout.getLineTop(p.startLine(page))
        val bottom = layout.getLineTop(p.endLine(page))
        canvas.save()
        canvas.translate((paddingLeft + margin).toFloat(), (paddingTop + margin - top).toFloat())
        canvas.clipRect(0, top, layout.width, bottom)
        layout.draw(canvas) // only draws lines inside the clip
        spoken?.let { drawUnderline(canvas, layout, it) }
        selection?.let { sel -> drawSelection(canvas, layout, sel) }
        canvas.restore()

        val baseline = (height - paddingBottom - statusHeight / 3).toFloat()
        val rightWidth = statusPaint.measureText(statusRight)
        val left = (paddingLeft + margin).toFloat()
        val right = (width - paddingRight - margin).toFloat()
        val title = TextUtils.ellipsize(
            statusLeft, statusPaint, (right - left - rightWidth - margin).coerceAtLeast(0f), TextUtils.TruncateAt.END
        )
        canvas.drawText(title, 0, title.length, left, baseline, statusPaint)
        canvas.drawText(statusRight, right - rightWidth, baseline, statusPaint)
    }

    /** One outline box per line, from the first to the last selected letter (no gray fill). */
    private fun drawSelection(canvas: Canvas, layout: Layout, sel: IntRange) {
        val descent = layout.paint.fontMetricsInt.descent
        val pad = selectionPaint.strokeWidth
        forEachLinePart(layout, sel) { line, left, right ->
            canvas.drawRect(
                left - pad, layout.getLineTop(line).toFloat(),
                right + pad, (layout.getLineBaseline(line) + descent).toFloat() + pad,
                selectionPaint,
            )
        }
    }

    /** A plain line under each line of [range]: thin enough to need only a light e-ink refresh. */
    private fun drawUnderline(canvas: Canvas, layout: Layout, range: IntRange) {
        val gap = layout.paint.fontMetricsInt.descent * 0.6f
        forEachLinePart(layout, range) { line, left, right ->
            val y = layout.getLineBaseline(line) + gap
            canvas.drawLine(left, y, right, y, underlinePaint)
        }
    }

    /** Calls [action] with the horizontal extent of [range] on each of its lines, without edge spaces. */
    private inline fun forEachLinePart(layout: Layout, range: IntRange, action: (line: Int, left: Float, right: Float) -> Unit) {
        val text = layout.text
        val last = range.last.coerceAtMost(text.length - 1)
        if (range.first > last) return
        for (line in layout.getLineForOffset(range.first)..layout.getLineForOffset(last)) {
            var start = maxOf(range.first, layout.getLineStart(line))
            var end = minOf(last + 1, layout.getLineEnd(line))
            while (start < end && text[start].isWhitespace()) start++
            while (end > start && text[end - 1].isWhitespace()) end--
            if (start >= end) continue
            val left = layout.getPrimaryHorizontal(start)
            val right = if (end >= layout.getLineEnd(line)) layout.getLineRight(line) else layout.getPrimaryHorizontal(end)
            action(line, minOf(left, right), maxOf(left, right))
        }
    }

    private fun drawMessage(canvas: Canvas, text: String) {
        val w = textWidth.coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, messagePaint, w)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
        canvas.save()
        canvas.translate((paddingLeft + margin).toFloat(), ((height - layout.height) / 2).toFloat())
        layout.draw(canvas)
        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                pressing = true
                longPressed = false
                selecting = false
                postDelayed(longPressAction, longPressTimeout)
            }
            MotionEvent.ACTION_MOVE -> {
                if (selecting) {
                    extendSelectionTo(event.x, event.y)
                } else if (pressing && Math.hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) > touchSlop) {
                    pressing = false
                    removeCallbacks(longPressAction)
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressAction)
                if (selecting) {
                    selecting = false
                    finishSelection()
                } else if (pressing && !longPressed) {
                    val zone = when {
                        event.x < width / 3f -> Zone.PREV
                        event.x > width * 2f / 3f -> Zone.NEXT
                        else -> Zone.MENU
                    }
                    onTap?.invoke(zone)
                }
                pressing = false
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressAction)
                pressing = false
                if (selecting) {
                    selecting = false
                    clearSelection()
                }
            }
        }
        return true
    }

    private fun startSelection(x: Float, y: Float): Boolean {
        val p = pages ?: return false
        val range = wordAt(p, x, y) ?: return false
        anchor = range
        selection = range
        invalidate()
        return true
    }

    /** Grows the selection from the anchor word to the word (or position) under the finger. */
    private fun extendSelectionTo(x: Float, y: Float) {
        val p = pages ?: return
        val a = anchor ?: return
        val text = p.layout.text
        val offset = offsetAt(p, x, y)
        val word = wordAround(text, offset)
        val pageStart = p.layout.getLineStart(p.startLine(page))
        val pageLast = p.layout.getLineEnd(p.endLine(page) - 1) - 1
        val start = minOf(a.first, word?.first ?: offset).coerceAtLeast(pageStart)
        val end = maxOf(a.last, word?.last ?: (offset - 1)).coerceAtMost(pageLast)
        val range = start..end
        if (range != selection) { // redraw only on change: every redraw costs an e-ink refresh
            selection = range
            invalidate()
        }
    }

    private fun finishSelection() {
        val p = pages ?: return
        val sel = selection ?: return
        val text = p.layout.text
        var start = sel.first
        var end = sel.last
        while (start < end && !text[start].isLetterOrDigit()) start++
        while (end > start && !text[end].isLetterOrDigit()) end--
        val selected = text.subSequence(start, end + 1).toString()
            .replace("\u00AD", "") // soft hyphens
            .replace('\u2019', '\'')
            .replace(Regex("\\s+"), " ")
            .trim()
        if (selected.isEmpty()) {
            clearSelection()
            return
        }
        val layout = p.layout
        val pageTop = layout.getLineTop(p.startLine(page))
        val top = layout.getLineTop(layout.getLineForOffset(start))
        val bottom = layout.getLineBottom(layout.getLineForOffset(end))
        val centerY = paddingTop + margin + (top + bottom) / 2 - pageTop
        onTextSelected?.invoke(selected, !selected.contains(' '), centerY)
    }

    /** Character offset nearest to (x, y), clamped to the lines of the current page. */
    private fun offsetAt(p: Pages, x: Float, y: Float): Int {
        val layout = p.layout
        val top = layout.getLineTop(p.startLine(page))
        val bottom = layout.getLineTop(p.endLine(page))
        val ly = ((y - paddingTop - margin).toInt() + top).coerceIn(top, bottom - 1)
        return layout.getOffsetForHorizontal(layout.getLineForVertical(ly), x - paddingLeft - margin)
    }

    private fun pictureAt(x: Float, y: Float): PictureSpan? {
        val p = pages ?: return null
        val layout = p.layout
        val text = layout.text as? Spanned ?: return null
        val top = layout.getLineTop(p.startLine(page))
        val ly = (y - paddingTop - margin).toInt() + top
        if (ly < top || ly >= layout.getLineTop(p.endLine(page))) return null
        val line = layout.getLineForVertical(ly)
        return text.getSpans(layout.getLineStart(line), layout.getLineEnd(line), PictureSpan::class.java).firstOrNull()
    }

    /** The word under (x, y) on the current page, as a character range of the chapter text. */
    private fun wordAt(p: Pages, x: Float, y: Float): IntRange? {
        val layout = p.layout
        val text = layout.text
        val top = layout.getLineTop(p.startLine(page))
        val ly = (y - paddingTop - margin).toInt() + top
        if (ly < top || ly >= layout.getLineTop(p.endLine(page))) return null
        val line = layout.getLineForVertical(ly)
        val lx = x - paddingLeft - margin
        if (lx < layout.getLineLeft(line) - margin || lx > layout.getLineRight(line) + margin) return null

        return wordAround(text, layout.getOffsetForHorizontal(line, lx))
    }

    /** The word containing [position] (or ending right before it), or null between words. */
    private fun wordAround(text: CharSequence, position: Int): IntRange? {
        var offset = position
        if (offset >= text.length || !isWordChar(text, offset)) {
            if (offset > 0 && offset <= text.length && isWordChar(text, offset - 1)) offset-- else return null
        }
        var start = offset
        while (start > 0 && isWordChar(text, start - 1)) start--
        var end = offset + 1
        while (end < text.length && isWordChar(text, end)) end++
        while (start < end && isJoiner(text[start])) start++
        while (end > start && isJoiner(text[end - 1])) end--
        return if (start < end) start until end else null
    }

    private fun isJoiner(c: Char) = c == '\'' || c == '\u2019' || c == '-' || c == '\u00AD'

    private fun isWordChar(text: CharSequence, i: Int): Boolean {
        val c = text[i]
        if (c.isLetterOrDigit() || Character.getType(c) == Character.NON_SPACING_MARK.toInt()) return true
        // Apostrophes and hyphens count only inside a word: "don't", "well-known".
        return isJoiner(c) && i > 0 && i + 1 < text.length && text[i - 1].isLetter() && text[i + 1].isLetter()
    }
}
