package com.guny90.reader

import android.text.Html
import android.text.SpannableStringBuilder
import android.text.Spanned

/** Turns chapter XHTML into styled text (bold, italic, headings) without a WebView. */
object HtmlText {
    private val dropBlocks = Regex(
        """<(style|script|svg|head)\b[^>]*>.*?</\1\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val images = Regex("""<(img|image)\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val lineBreaks = Regex("""<br\b[^>]*>""", RegexOption.IGNORE_CASE)
    private const val BR_MARK = '\uE000' // private-use char, survives Html.fromHtml unchanged

    /** Marks a newline that came from <br>: a line break inside a paragraph, so no paragraph gap. */
    class LineBreak

    fun toText(xhtml: String): CharSequence {
        var body = xhtml
        val bodyStart = body.indexOf("<body", ignoreCase = true)
        if (bodyStart >= 0) {
            val contentStart = body.indexOf('>', bodyStart) + 1
            val bodyEnd = body.lastIndexOf("</body", ignoreCase = true)
            body = body.substring(contentStart, if (bodyEnd >= contentStart) bodyEnd else body.length)
        }
        body = dropBlocks.replace(body, "")
        body = images.replace(body, "<p>[image]</p>")
        body = lineBreaks.replace(body, BR_MARK.toString())

        // COMPACT: paragraphs separated by one newline (LEGACY adds a blank line). The gap
        // between paragraphs is added at layout time, so it can be changed in the menu.
        val text = SpannableStringBuilder(Html.fromHtml(body, Html.FROM_HTML_MODE_COMPACT))
        for (i in text.indices) {
            if (text[i] == BR_MARK) {
                text.replace(i, i + 1, "\n") // same length: offsets and spans stay valid
                text.setSpan(LineBreak(), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        var start = 0
        var end = text.length
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        return text.subSequence(start, end)
    }
}
