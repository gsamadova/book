package com.guny90.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View

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
    /** Called after layout; the activity re-paginates if the text area size changed. */
    var onTextAreaLaidOut: (() -> Unit)? = null

    private var pages: Pages? = null
    private var page = 0
    private var statusLeft = ""
    private var statusRight = ""
    private var message: String? = null

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
        invalidate()
    }

    fun showMessage(text: String) {
        pages = null
        message = text
        invalidate()
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
        if (event.action == MotionEvent.ACTION_UP) {
            val zone = when {
                event.x < width / 3f -> Zone.PREV
                event.x > width * 2f / 3f -> Zone.NEXT
                else -> Zone.MENU
            }
            onTap?.invoke(zone)
        }
        return true
    }
}
