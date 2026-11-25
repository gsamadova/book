package com.guny90.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The menu: a panel on the left showing rows the activity describes. Sections replace its
 * content in place, with a "‹ parent" row on top. No animation, dimming or shadow: each change
 * is one redraw (e-ink). Tapping the page beside it closes it.
 */
class Sidebar(context: Context) : FrameLayout(context) {
    sealed class Row {
        /**
         * Title, then where you are: [link] is a tappable line under it, smaller than the rows,
         * with [value] (e.g. "37%") and "›" on its right (chapter → Contents, PDF page → Go to
         * page). Null [link] for a plain title.
         */
        class Header(val title: String, val link: String?, val onLink: (() -> Unit)?, val value: String? = null) : Row()
        class Item(
            val label: String, val value: String? = null, val opens: Boolean = false,
            /** Shown bold: the current chapter, the open book, the chosen value. */
            val marked: Boolean = false,
            val onLongPress: (() -> Unit)? = null, val onTap: () -> Unit,
        ) : Row()
        /** "Aa  −  28  +" */
        class Stepper(val label: String, val value: String, val onMinus: () -> Unit, val onPlus: () -> Unit) : Row()
        /** A line of text that isn't tappable, e.g. "No dictionaries yet". */
        class Note(val text: String) : Row()
        /** Space between groups: no rule, so the panel stays light on e-ink. */
        object Divider : Row()
    }

    private val density = resources.displayMetrics.density
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context).apply {
        overScrollMode = View.OVER_SCROLL_NEVER
        isFillViewport = true
        addView(list, MATCH_PARENT, WRAP_CONTENT)
    }
    private val border = View(context)

    /** The visible panel; the rest of the sidebar is the page, showing through. */
    val panel = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(scroll, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        addView(border, LinearLayout.LayoutParams(dp(1).coerceAtLeast(1), MATCH_PARENT))
        isClickable = true // taps between rows stay in the panel
    }

    var isShowing = false
        private set
    /** Changes on every [show] and [hide]: a result that arrives later can tell whether the user moved on. */
    var generation = 0
        private set
    private var back: (() -> Unit)? = null
    /** Called after [hide]. */
    var onHidden: (() -> Unit)? = null
    /** Called when [show] makes it appear (not when it changes sections). */
    var onShown: (() -> Unit)? = null

    init {
        visibility = View.GONE
        addView(panel, LayoutParams(WRAP_CONTENT, MATCH_PARENT, Gravity.START))
    }

    /** Shows [rows]. With [back], a "‹ [backLabel]" row comes first, and Back calls [back]. */
    fun show(rows: List<Row>, backLabel: String? = null, back: (() -> Unit)? = null, scrollTo: Int = -1) {
        generation++
        this.back = back
        panel.setBackgroundColor(Ink.paper)
        border.setBackgroundColor(Ink.text)
        list.removeAllViews()
        if (back != null) list.addView(item(Row.Item("‹ ${backLabel ?: ""}", onTap = back)))
        val offset = list.childCount
        for (row in rows) list.addView(view(row))
        scroll.scrollTo(0, 0)
        if (scrollTo >= 0 && offset + scrollTo < list.childCount) {
            val target = list.getChildAt(offset + scrollTo)
            scroll.post { scroll.scrollTo(0, (target.top - scroll.height / 3).coerceAtLeast(0)) }
        }
        visibility = View.VISIBLE
        if (!isShowing) {
            isShowing = true
            onShown?.invoke()
        }
    }

    fun hide() {
        if (!isShowing) return
        generation++
        isShowing = false
        visibility = View.GONE
        list.removeAllViews()
        back = null
        onHidden?.invoke()
    }

    /** The Back button: up one level, or closes at the top. False if not showing. */
    fun goBack(): Boolean {
        if (!isShowing) return false
        val b = back
        if (b != null) b() else hide()
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        panel.layoutParams.width = minOf((width * 0.8f).toInt(), dp(360))
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** Touches outside the panel: closed on lift, and never passed to the page underneath. */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP && event.x >= panel.right) hide()
        return true
    }

    private fun view(row: Row): View = when (row) {
        is Row.Header -> header(row)
        is Row.Item -> item(row)
        is Row.Stepper -> stepper(row)
        is Row.Note -> text(row.text, 16f).apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isSingleLine = false
            maxLines = 3
        }
        Row.Divider -> View(context).apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(20)) }
    }

    private fun header(row: Row.Header) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(16), 0, dp(8))
        addView(text(row.title, 20f).apply {
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16), 0, dp(16), dp(4))
        })
        if (row.link != null) {
            addView(item(Row.Item(row.link, row.value, opens = true) { row.onLink?.invoke() }, height = 40, sp = 16f))
        }
    }

    private fun item(row: Row.Item, height: Int = 48, sp: Float = 18f) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(height)
        setPadding(dp(16), 0, dp(16), 0)
        isClickable = true
        setOnClickListener { row.onTap() }
        row.onLongPress?.let { long -> setOnLongClickListener { long(); true } }
        addView(text(row.label, sp).apply {
            if (row.marked) setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val value = listOfNotNull(row.value, if (row.opens) "›" else null).joinToString("  ")
        if (value.isNotEmpty()) addView(text(value, 16f).apply { setPadding(dp(12), 0, 0, 0) })
    }

    private fun stepper(row: Row.Stepper) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(48)
        setPadding(dp(16), 0, dp(4), 0)
        addView(text(row.label, 18f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(button("−", row.onMinus))
        addView(text(row.value, 18f).apply { gravity = Gravity.CENTER; minWidth = dp(40) })
        addView(button("+", row.onPlus))
    }

    private fun button(label: String, onTap: () -> Unit) = text(label, 22f).apply {
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(56), dp(48))
        isClickable = true
        setOnClickListener { onTap() }
    }

    private fun text(s: String, sp: Float) = TextView(context).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(Ink.text)
        typeface = Fonts.typeface(context, 1) // Literata, the book font the app ships
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun dp(v: Int) = (v * density).toInt()
}
