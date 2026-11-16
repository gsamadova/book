package com.guny90.reader

import android.text.Html

/** Turns chapter XHTML into styled text (bold, italic, headings) without a WebView. */
object HtmlText {
    private val dropBlocks = Regex(
        """<(style|script|svg|head)\b[^>]*>.*?</\1\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val images = Regex("""<(img|image)\b[^>]*>""", RegexOption.IGNORE_CASE)

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

        val text = Html.fromHtml(body, Html.FROM_HTML_MODE_LEGACY)
        var start = 0
        var end = text.length
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        return text.subSequence(start, end)
    }
}
