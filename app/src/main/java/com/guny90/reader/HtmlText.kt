package com.guny90.reader

import android.text.Html
import android.text.SpannableStringBuilder
import android.text.Spanned

/** Turns chapter XHTML into styled text (bold, italic, headings) without a WebView. */
object HtmlText {
    private val dropBlocks = Regex(
        """<(style|script|head)\b[^>]*>.*?</\1\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val svgBlocks = Regex(
        """<svg\b[^>]*>.*?</svg\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val images = Regex("""<(img|image)\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val imageSource = Regex("""\s(?:src|xlink:href|href)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private const val IMAGE_START = '\uE001'
    private const val IMAGE_END = '\uE002'
    private val imageMarks = Regex("$IMAGE_START(\\d+)$IMAGE_END")
    const val IMAGE_PLACEHOLDER = "[image]"
    private val lineBreaks = Regex("""<br\b[^>]*>""", RegexOption.IGNORE_CASE)
    private const val BR_MARK = '\uE000' // private-use char, survives Html.fromHtml unchanged

    /** Marks a newline that came from <br>: a line break inside a paragraph, so no paragraph gap. */
    class LineBreak

    /** Covers an "[image]" placeholder: the picture's path inside the EPUB. */
    class ImageRef(val path: String)

    /** [baseDir]: the chapter's folder in the zip, for resolving picture paths. */
    fun toText(xhtml: String, baseDir: String = ""): CharSequence {
        var body = xhtml
        val bodyStart = body.indexOf("<body", ignoreCase = true)
        if (bodyStart >= 0) {
            val contentStart = body.indexOf('>', bodyStart) + 1
            val bodyEnd = body.lastIndexOf("</body", ignoreCase = true)
            body = body.substring(contentStart, if (bodyEnd >= contentStart) bodyEnd else body.length)
        }
        body = dropBlocks.replace(body, "")
        // Vector drawings can't be shown; a picture wrapped in SVG (typical for covers) can.
        body = svgBlocks.replace(body) { m -> images.find(m.value)?.value ?: "" }
        // Each picture becomes its own paragraph "[image]", marked with its path. The text is the same
        // whether pictures are shown or not, so saved and synced positions don't depend on it.
        val sources = ArrayList<String?>()
        body = images.replace(body) { m ->
            val src = imageSource.find(m.value)?.groupValues?.get(1)?.trim()
            sources += if (src == null || src.startsWith("data:")) null else EpubBook.resolve(baseDir, src).substringBefore('#')
            "<p>$IMAGE_START${sources.size - 1}$IMAGE_END</p>"
        }
        body = lineBreaks.replace(body, BR_MARK.toString())

        // COMPACT: paragraphs separated by one newline (LEGACY adds a blank line). The gap
        // between paragraphs is added at layout time, so it can be changed in the menu.
        val text = SpannableStringBuilder(Html.fromHtml(body, Html.FROM_HTML_MODE_COMPACT))
        for (m in imageMarks.findAll(text).toList().asReversed()) { // from the end: earlier offsets stay valid
            text.replace(m.range.first, m.range.last + 1, IMAGE_PLACEHOLDER)
            val path = sources.getOrNull(m.groupValues[1].toInt())
            if (path != null) {
                text.setSpan(ImageRef(path), m.range.first, m.range.first + IMAGE_PLACEHOLDER.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
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
