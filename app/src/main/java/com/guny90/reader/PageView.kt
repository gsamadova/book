package com.guny90.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.Layout
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
        fun paginate(text: CharSequence, width: Int, height: Int, textSizePx: Float): Pages {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                linkColor = Color.BLACK // default is transparent: links (e.g. TOC pages) would be invisible
                textSize = textSizePx
                typeface = Typeface.SERIF
            }
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.15f)
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
    }
}

/**
 * Draws exactly one page. No scrolling and no animation: every change is a single
 * full redraw, which is what e-ink panels handle best.
 */
class PageView(context: Context) : View(context) {
    enum class Zone { PREV, MENU, NEXT }

    var onTap: ((Zone) -> Unit)? = null
    /** Long-press on a word: the word and the vertical center of it on screen. */
    var onWordLongPress: ((word: String, centerY: Int) -> Unit)? = null
    /** Called after layout; the activity re-paginates if the text area size changed. */
    var onTextAreaLaidOut: (() -> Unit)? = null

    private var pages: Pages? = null
    private var page = 0
    private var statusLeft = ""
    private var statusRight = ""
    private var message: String? = null
    private var selection: IntRange? = null

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
    private val selectionPath = Path()

    // Long-press detection. Generous slop: e-ink touch panels report jittery coordinates.
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val touchSlop = 24 * density
    private var downX = 0f
    private var downY = 0f
    private var pressing = false
    private var longPressed = false
    private val longPressAction = Runnable {
        longPressed = true
        selectWordAt(downX, downY)
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
        selection?.let { sel ->
            selectionPath.reset()
            layout.getSelectionPath(sel.first, sel.last + 1, selectionPath)
            canvas.drawPath(selectionPath, selectionPaint) // outline box, no gray fill
        }
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
                postDelayed(longPressAction, longPressTimeout)
            }
            MotionEvent.ACTION_MOVE -> {
                if (pressing && Math.hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) > touchSlop) {
                    pressing = false
                    removeCallbacks(longPressAction)
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressAction)
                if (pressing && !longPressed) {
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
            }
        }
        return true
    }

    private fun selectWordAt(x: Float, y: Float) {
        val p = pages ?: return
        val range = wordAt(p, x, y) ?: return
        selection = range
        invalidate()
        val layout = p.layout
        val line = layout.getLineForOffset(range.first)
        val pageTop = layout.getLineTop(p.startLine(page))
        val centerY = paddingTop + margin + (layout.getLineTop(line) + layout.getLineBottom(line)) / 2 - pageTop
        val word = layout.text.subSequence(range.first, range.last + 1).toString()
            .replace("\u00AD", "") // soft hyphens
            .replace('\u2019', '\'')
        onWordLongPress?.invoke(word, centerY)
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

        var offset = layout.getOffsetForHorizontal(line, lx)
        val lineStart = layout.getLineStart(line)
        if (offset >= text.length || !isWordChar(text, offset)) {
            if (offset > lineStart && isWordChar(text, offset - 1)) offset-- else return null
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
