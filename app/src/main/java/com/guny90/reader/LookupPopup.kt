package com.guny90.reader

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Dictionary popup. Plain black-on-white panel, no animation, no dimming. It sits in the
 * half of the screen that does not contain the selected word, so the sentence stays visible.
 */
class LookupPopup(
    private val activity: Activity,
    wordCenterY: Int,
    private val onSuggestion: (String) -> Unit,
    private val onChangeDictionary: () -> Unit,
    /** Says the shown word aloud; null hides the Speak button (no text-to-speech engine). */
    onSpeak: ((String) -> Unit)?,
    /** Null hides the Translate button (Google Translate not installed). */
    onTranslate: (() -> Unit)?,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    class Content(
        val title: String,
        val body: CharSequence?,
        val note: String? = null,
        val suggestions: List<String> = emptyList(),
        val dictName: String,
    )

    private val density = activity.resources.displayMetrics.density
    private val dialog = Dialog(activity)
    private val titleView: TextView
    private val bodyBox: LinearLayout
    private val scroll: ScrollView
    private val dictButton: TextView

    val isShowing get() = dialog.isShowing

    init {
        val screenHeight = activity.window.decorView.height
        val pad = dp(16)

        titleView = TextView(activity).apply {
            setTextColor(Color.BLACK)
            textSize = 22f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        bodyBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        scroll = MaxHeightScrollView(activity, (screenHeight * 0.4).toInt()).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalFadingEdgeEnabled = false
            isScrollbarFadingEnabled = false // always show that there is more to read
            addView(bodyBox, MATCH_PARENT, WRAP_CONTENT)
        }
        dictButton = button("").apply {
            ellipsize = TextUtils.TruncateAt.END
            maxLines = 1
            setOnClickListener { onChangeDictionary() }
        }
        // Row 1: which dictionary. Row 2: the other actions, so none needs an extra menu.
        val actions = ArrayList<TextView>()
        if (onSpeak != null) actions += button("Speak").apply {
            setOnClickListener { onSpeak(titleView.text.toString()) } // stays open: listen again or read on
        }
        if (onTranslate != null) actions += button("Translate").apply {
            setOnClickListener { dialog.dismiss(); onTranslate() }
        }
        actions += button("Copy").apply { setOnClickListener { dialog.dismiss(); onCopy() } }
        actions += button("Close").apply { setOnClickListener { dialog.dismiss() } }
        val actionRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            for ((i, b) in actions.withIndex()) {
                addView(b, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = dp(8)
                })
            }
        }
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(dictButton, MATCH_PARENT, WRAP_CONTENT)
            addView(actionRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(titleView, MATCH_PARENT, WRAP_CONTENT)
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        }

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener { onDismiss() }
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(dp(2), Color.BLACK)
            })
            setWindowAnimations(0)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setLayout(MATCH_PARENT, WRAP_CONTENT)
            setGravity(if (wordCenterY < screenHeight / 2) Gravity.BOTTOM else Gravity.TOP)
        }
    }

    fun show(content: Content) {
        update(content)
        dialog.show()
    }

    fun update(content: Content) {
        titleView.text = content.title
        dictButton.text = "Dictionary: ${content.dictName}"
        bodyBox.removeAllViews()
        content.note?.let { bodyBox.addView(text(it, italic = true)) }
        content.body?.let { bodyBox.addView(text(it)) }
        for (s in content.suggestions) {
            bodyBox.addView(text("›  $s").apply {
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { onSuggestion(s) }
            })
        }
        scroll.scrollTo(0, 0)
    }

    fun dismiss() = dialog.dismiss()

    private fun text(value: CharSequence, italic: Boolean = false) = TextView(activity).apply {
        text = value
        setTextColor(Color.BLACK)
        textSize = 18f
        typeface = Typeface.create(Typeface.SERIF, if (italic) Typeface.ITALIC else Typeface.NORMAL)
        setLineSpacing(0f, 1.1f)
    }

    /** Bordered text "button": no ripple or gray states, which cost extra e-ink refreshes. */
    private fun button(label: String) = TextView(activity).apply {
        text = label
        setTextColor(Color.BLACK)
        textSize = 16f
        gravity = Gravity.CENTER
        setPadding(dp(6), dp(10), dp(6), dp(10))
        maxLines = 1
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            setStroke(dp(1), Color.BLACK)
            cornerRadius = dp(4).toFloat()
        }
        isClickable = true
    }

    private fun dp(v: Int) = (v * density).toInt()

    private class MaxHeightScrollView(context: Context, private val maxHeight: Int) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
        }
    }
}
