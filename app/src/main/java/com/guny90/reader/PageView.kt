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
import android.os.Build
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
import java.util.Locale

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
        /** Line height as a multiple of the font's (menu choices). */
        val LINE_SPACINGS = listOf("Tight" to 1.0f, "Normal" to 1.15f, "Relaxed" to 1.35f, "Loose" to 1.6f)
        /** Page margins in dp (menu choices). */
        val MARGINS = listOf("Narrow" to 8, "Medium" to 16, "Wide" to 32)

        /**
         * [pictureSize] (null: pictures off) gives a picture's pixel size; it is then drawn in place
         * of its "[image]" placeholder, scaled to fit the page. [pixelScale]: screen pixels per picture pixel.
         */
        fun paginate(
            chapter: CharSequence, width: Int, height: Int, textSizePx: Float, paragraphSpacing: Float,
            pictureSize: ((String) -> IntArray?)? = null, pixelScale: Float = 1f, typeface: Typeface = Typeface.SERIF,
            lineSpacing: Float = 1.15f,
            /** Justified lines, hyphenated in [locale]'s language (Android 8 and newer). */
            justify: Boolean = false, locale: Locale? = null,
        ): Pages {
            val text = SpannableStringBuilder(chapter)
            val gap = (paragraphSpacing * textSizePx).toInt()
            if (gap > 0) text.setSpan(ParagraphGap(gap), 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            if (pictureSize != null) {
                val pad = (0.3f * textSizePx).toInt()
                // A picture is one line, so it must fit on one page together with its spacing.
                val maxHeight = ((height - 2 * pad - gap * lineSpacing) / lineSpacing).toInt() - 2
                for (ref in text.getSpans(0, text.length, HtmlText.ImageRef::class.java)) {
                    val size = pictureSize(ref.path) ?: continue
                    val w = size[0] * pixelScale
                    val h = size[1] * pixelScale
                    val fit = minOf(1f, width / w, maxHeight / h)
                    val picture = PictureSpan(
                        ref.path, (w * fit).toInt().coerceAtLeast(1), (h * fit).toInt().coerceAtLeast(1), width, pad,
                        lineSpacing,
                    )
                    text.setSpan(picture, text.getSpanStart(ref), text.getSpanEnd(ref), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }

            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Ink.text // set again before drawing, as night mode can change without a new layout
                linkColor = Ink.text // default is transparent: links (e.g. TOC pages) would be invisible
                textSize = textSizePx
                this.typeface = typeface
                if (locale != null) textLocale = locale // hyphenation patterns of the book's language
            }
            val justified = justify && Build.VERSION.SDK_INT >= 26
            val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, lineSpacing)
                .setIncludePad(false)
                // Only the slower strategies hyphenate; without hyphens, justified lines get wide gaps.
                .setBreakStrategy(if (justified) Layout.BREAK_STRATEGY_HIGH_QUALITY else Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(if (justified) Layout.HYPHENATION_FREQUENCY_NORMAL else Layout.HYPHENATION_FREQUENCY_NONE)
            if (justified) builder.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
            val layout = builder.build()

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
    private val lineSpacing: Float,
) : ReplacementSpan() {
    @Volatile var bitmap: Bitmap? = null
    @Volatile var failed = false

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null) {
            // The layout multiplies line heights by its line spacing; divide so the line is just the picture.
            val lineHeight = ((height + 2 * pad) / lineSpacing).toInt()
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
        else canvas.drawRect(box, framePaint.apply { color = Ink.text })
    }

    companion object {
        /** Paint for pictures; gray or in their own colors (see [setColors]). */
        val picturePaint = Paint(Paint.FILTER_BITMAP_FLAG)
        /** Paint for PDF pages: like [picturePaint], and inverted in night mode (pages are mostly text). */
        val pdfPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val gray = ColorMatrix().apply { setSaturation(0f) }
        private val invert = ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ))

        /**
         * Gray suits black-and-white e-ink, where colors of similar brightness can merge and
         * dither unevenly; elsewhere pictures can keep their own colors. Redraw after changing it.
         */
        fun setColors(gray: Boolean, night: Boolean) {
            picturePaint.colorFilter = if (gray) ColorMatrixColorFilter(this.gray) else null
            val pdf = ColorMatrix()
            if (gray) pdf.postConcat(this.gray)
            if (night) pdf.postConcat(invert)
            pdfPaint.colorFilter = if (gray || night) ColorMatrixColorFilter(pdf) else null
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
    /** Tap on a link (instead of [onTap]). */
    var onLinkTap: ((HtmlText.Link) -> Unit)? = null
    /**
     * Long-press selects a word; sliding the finger (without lifting) extends the selection.
     * On release: the selected text, whether it is one word, and its vertical center on screen.
     */
    var onTextSelected: ((text: String, singleWord: Boolean, centerY: Int) -> Unit)? = null
    /** Long-press on a picture (instead of selecting text). */
    var onPictureLongPress: ((PictureSpan) -> Unit)? = null
    /** Long-press on a PDF page that has no words to select (e.g. a scanned page). */
    var onNoWords: (() -> Unit)? = null
    /** Called after layout; the activity re-paginates if the text area size changed. */
    var onTextAreaLaidOut: (() -> Unit)? = null

    private var pages: Pages? = null
    private var page = 0
    private var statusLeft = ""
    private var statusRight = ""
    private var message: String? = null
    /** A page shown as a picture (PDF), instead of laid-out text. */
    private var image: PdfBook.Rendered? = null
    /** The words of the PDF page shown, once read (null until then); positions in PDF points. */
    private var words: List<PageWords.Word>? = null
    /**
     * Selected text: character offsets of the chapter, or for a PDF page, indexes into [words].
     */
    private var selection: IntRange? = null
    /** The sentence being read aloud (chapter offsets), underlined. */
    private var spoken: IntRange? = null

    private val density = resources.displayMetrics.density
    /** Space around the text (not PDF pages). Re-paginate after changing it: it changes [textWidth]. */
    var margin = (16 * density).toInt()
        set(value) {
            field = value
            invalidate()
        }
    /** PDF pages bring their own margins (cropped to a little padding), so only a thin one here. */
    private val imageMargin = (4 * density).toInt()
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
    private var lastX = 0f
    private var lastY = 0f
    /** Long-pressed a PDF page before its words were read: select when they arrive. */
    private var waitingForWords = false
    private val longPressAction = Runnable {
        longPressed = true
        if (image != null) {
            val w = words
            if (w == null) waitingForWords = true else startOnPdfPage(w)
            return@Runnable
        }
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
    val imageWidth get() = width - paddingLeft - paddingRight - 2 * imageMargin
    val imageHeight get() = height - paddingTop - paddingBottom - imageMargin - statusHeight

    fun showPage(pages: Pages, page: Int, statusLeft: String, statusRight: String) {
        this.pages = pages
        this.page = page
        this.statusLeft = statusLeft
        this.statusRight = statusRight
        message = null
        image = null
        selection = null
        invalidate()
    }

    /** A PDF page as a picture: tap zones work as usual; words can be selected once [setWords] gives them. */
    fun showImage(rendered: PdfBook.Rendered, statusLeft: String, statusRight: String) {
        pages = null
        message = null
        if (image?.page != rendered.page) words = null
        image = rendered
        selection = null
        spoken = null
        this.statusLeft = statusLeft
        this.statusRight = statusRight
        invalidate()
    }

    /** The words of PDF page [page], for selecting them; ignored if another page is shown by now. */
    fun setWords(page: Int, pageWords: List<PageWords.Word>) {
        if (image?.page != page) return
        words = pageWords
        if (waitingForWords) {
            waitingForWords = false
            if (pressing || longPressed) startOnPdfPage(pageWords, lastX, lastY)
        }
    }

    fun showMessage(text: String) {
        pages = null
        image = null
        words = null
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
        canvas.drawColor(Ink.paper)
        val ink = Ink.text
        statusPaint.color = ink
        messagePaint.color = ink
        selectionPaint.color = ink
        underlinePaint.color = ink
        val img = image
        if (img != null) {
            canvas.drawBitmap(img.bitmap, imageLeft(img), imageTop(img), PictureSpan.pdfPaint)
            val w = words
            val sel = selection
            if (w != null && sel != null) drawPdfSelection(canvas, img, w, sel)
            drawStatus(canvas)
            return
        }
        val p = pages
        if (p == null) {
            drawMessage(canvas, message ?: "")
            return
        }
        val layout = p.layout
        layout.paint.color = Ink.text
        layout.paint.linkColor = Ink.text
        val top = layout.getLineTop(p.startLine(page))
        val bottom = layout.getLineTop(p.endLine(page))
        canvas.save()
        canvas.translate((paddingLeft + margin).toFloat(), (paddingTop + margin - top).toFloat())
        canvas.clipRect(0, top, layout.width, bottom)
        layout.draw(canvas) // only draws lines inside the clip
        spoken?.let { drawUnderline(canvas, layout, it) }
        selection?.let { sel -> drawSelection(canvas, layout, sel) }
        canvas.restore()
        drawStatus(canvas)
    }

    private fun drawStatus(canvas: Canvas) {
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

    private fun imageLeft(img: PdfBook.Rendered) = paddingLeft + imageMargin + (imageWidth - img.bitmap.width) / 2f
    private fun imageTop(img: PdfBook.Rendered) = paddingTop + imageMargin + (imageHeight - img.bitmap.height) / 2f
    private fun screenX(img: PdfBook.Rendered, x: Float) = imageLeft(img) + (x - img.box.left) * img.scale
    private fun screenY(img: PdfBook.Rendered, y: Float) = imageTop(img) + (y - img.box.top) * img.scale
    private fun pageX(img: PdfBook.Rendered, x: Float) = img.box.left + (x - imageLeft(img)) / img.scale
    private fun pageY(img: PdfBook.Rendered, y: Float) = img.box.top + (y - imageTop(img)) / img.scale

    /** One outline box per line of the selected words, as for text. */
    private fun drawPdfSelection(canvas: Canvas, img: PdfBook.Rendered, w: List<PageWords.Word>, sel: IntRange) {
        val pad = selectionPaint.strokeWidth
        var i = sel.first
        while (i <= sel.last) {
            val line = w[i].line
            var left = w[i].left
            var top = w[i].top
            var right = w[i].right
            var bottom = w[i].bottom
            while (i + 1 <= sel.last && w[i + 1].line == line) {
                i++
                left = minOf(left, w[i].left)
                top = minOf(top, w[i].top)
                right = maxOf(right, w[i].right)
                bottom = maxOf(bottom, w[i].bottom)
            }
            canvas.drawRect(
                screenX(img, left) - pad, screenY(img, top) - pad,
                screenX(img, right) + pad, screenY(img, bottom) + pad,
                selectionPaint,
            )
            i++
        }
    }

    /** Long-press on a PDF page: select the word under the finger, or say the page has no words. */
    private fun startOnPdfPage(w: List<PageWords.Word>, x: Float = downX, y: Float = downY) {
        val img = image ?: return
        if (w.isEmpty()) {
            onNoWords?.invoke()
            return
        }
        val slop = 8 * density / img.scale // a little forgiving: fingers are wider than letters
        val index = PageWords.wordAt(w, pageX(img, x), pageY(img, y), slop) ?: return
        anchor = index..index
        selection = index..index
        selecting = true
        invalidate()
    }

    private fun extendPdfSelectionTo(x: Float, y: Float) {
        val img = image ?: return
        val w = words ?: return
        val a = anchor ?: return
        val index = PageWords.nearest(w, pageX(img, x), pageY(img, y)) ?: return
        val range = minOf(a.first, index)..maxOf(a.last, index)
        if (range != selection) { // redraw only on change: every redraw costs an e-ink refresh
            selection = range
            invalidate()
        }
    }

    private fun finishPdfSelection() {
        val img = image ?: return
        val w = words ?: return
        val sel = selection ?: return
        val selected = PageWords.join(w, sel)
            .replace('\u2019', '\'')
            .trim { !it.isLetterOrDigit() } // punctuation around the words: "copying." -> "copying"
        if (selected.isEmpty()) {
            clearSelection()
            return
        }
        val top = sel.minOf { w[it].top }
        val bottom = sel.maxOf { w[it].bottom }
        val centerY = ((screenY(img, top) + screenY(img, bottom)) / 2).toInt()
        onTextSelected?.invoke(selected, !selected.contains(' '), centerY)
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
            val left = TextPositions.x(layout, line, start)
            val right = if (end >= layout.getLineEnd(line)) layout.getLineRight(line) else TextPositions.x(layout, line, end)
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
                lastX = event.x
                lastY = event.y
                waitingForWords = false
                pressing = true
                longPressed = false
                selecting = false
                postDelayed(longPressAction, longPressTimeout)
            }
            MotionEvent.ACTION_MOVE -> {
                lastX = event.x
                lastY = event.y
                if (selecting) {
                    if (image != null) extendPdfSelectionTo(event.x, event.y) else extendSelectionTo(event.x, event.y)
                } else if (pressing && Math.hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) > touchSlop) {
                    pressing = false
                    removeCallbacks(longPressAction)
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressAction)
                waitingForWords = false
                if (selecting) {
                    selecting = false
                    if (image != null) finishPdfSelection() else finishSelection()
                } else if (pressing && !longPressed) {
                    val link = linkAt(downX, downY)
                    if (link != null) {
                        pressing = false
                        onLinkTap?.invoke(link)
                        return true
                    }
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
                waitingForWords = false
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
        return TextPositions.offsetAt(layout, layout.getLineForVertical(ly), x - paddingLeft - margin)
    }

    /**
     * The link under (x, y) on the current page, or the nearest one within a finger's width:
     * note markers like "1" are much smaller than a fingertip.
     */
    private fun linkAt(x: Float, y: Float): HtmlText.Link? {
        val p = pages ?: return null
        val layout = p.layout
        val text = layout.text as? Spanned ?: return null
        val firstLine = p.startLine(page)
        val lastLine = p.endLine(page) - 1
        val pageStart = layout.getLineStart(firstLine)
        val pageEnd = layout.getLineEnd(lastLine)
        val links = text.getSpans(pageStart, pageEnd, HtmlText.Link::class.java)
        if (links.isEmpty()) return null
        val lx = x - paddingLeft - margin
        val ly = y - paddingTop - margin + layout.getLineTop(firstLine)
        var best: HtmlText.Link? = null
        var bestDistance = Float.MAX_VALUE
        for (link in links) {
            val start = maxOf(text.getSpanStart(link), pageStart)
            val end = minOf(text.getSpanEnd(link), pageEnd)
            if (start >= end) continue
            forEachLinePart(layout, start until end) { line, left, right ->
                val top = layout.getLineTop(line).toFloat()
                val bottom = layout.getLineBottom(line).toFloat()
                val dx = if (lx < left) left - lx else if (lx > right) lx - right else 0f
                val dy = if (ly < top) top - ly else if (ly > bottom) ly - bottom else 0f
                if (dx <= linkSlop && dy <= linkSlop / 2 && dx + dy < bestDistance) {
                    best = link
                    bestDistance = dx + dy
                }
            }
        }
        return best
    }

    private val linkSlop = 14 * density

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

        return wordAround(text, TextPositions.offsetAt(layout, line, lx))
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

/**
 * Horizontal positions in a [Layout] that may have justified lines. Android's own
 * [Layout.getPrimaryHorizontal] and [Layout.getOffsetForHorizontal] ignore the space that
 * justification adds between words (as of Android 15), so a selection drawn with them sits too
 * far left and a tap finds the wrong word. These add that space back.
 */
object TextPositions {

    /** The x of [offset], which is on [line]. */
    fun x(layout: Layout, line: Int, offset: Int): Float {
        val x = layout.getPrimaryHorizontal(offset)
        val gap = justifyGap(layout, line)
        if (gap == 0f) return x
        val text = layout.text
        var spaces = 0
        for (i in layout.getLineStart(line) until offset) if (text[i] == ' ') spaces++
        return x + gap * spaces
    }

    /** The character offset on [line] nearest to [x]. */
    fun offsetAt(layout: Layout, line: Int, x: Float): Int {
        if (justifyGap(layout, line) == 0f) return layout.getOffsetForHorizontal(line, x)
        val text = layout.text
        val start = layout.getLineStart(line)
        var end = layout.getLineEnd(line)
        while (end > start && text[end - 1].isWhitespace()) end--
        var best = start
        var bestDistance = Float.MAX_VALUE
        for (i in start..end) {
            if (i < text.length && Character.isLowSurrogate(text[i]) && i > start) continue
            val d = Math.abs(x(layout, line, i) - x)
            if (d < bestDistance) {
                best = i
                bestDistance = d
            }
        }
        return best
    }

    /** The width justification adds to (or takes from) each space on [line], if Android's positions leave it out; else 0. */
    fun justifyGap(layout: Layout, line: Int): Float {
        if (Build.VERSION.SDK_INT < 26 || layout.justificationMode == Layout.JUSTIFICATION_MODE_NONE) return 0f
        if (line >= layout.lineCount - 1) return 0f // a paragraph's last line isn't stretched
        if (layout.getParagraphDirection(line) != Layout.DIR_LEFT_TO_RIGHT) return 0f
        val text = layout.text
        val start = layout.getLineStart(line)
        val lineEnd = layout.getLineEnd(line)
        if (lineEnd > start && text[lineEnd - 1] == '\n') return 0f
        var end = lineEnd
        while (end > start && text[end - 1].isWhitespace()) end--
        var spaces = 0
        for (i in start until end) if (text[i] == ' ') spaces++
        if (spaces == 0) return 0f
        // A word broken across lines gets a hyphen that isn't in the text but takes up width.
        val hyphen = if (end == lineEnd && end < text.length && text[end - 1].isLetter() && text[end].isLetter()) {
            layout.paint.measureText("-")
        } else 0f
        val extra = layout.getParagraphRight(line) - layout.getPrimaryHorizontal(end) - hyphen
        // Lines are stretched to fit, or squeezed a little when slightly too long. Nearly no
        // difference means Android already counts the gaps.
        return if (Math.abs(extra) > 0.5f) extra / spaces else 0f
    }
}
