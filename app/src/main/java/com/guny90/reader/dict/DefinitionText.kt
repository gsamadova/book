package com.guny90.reader.dict

import android.graphics.Typeface
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan

/** Turns StarDict definition parts (plain, HTML, Pango, XDXF…) into styled text. */
object DefinitionText {
    private val dropBlocks = Regex(
        """<(style|script|head)\b[^>]*>.*?</\1\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val images = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val blockTags = Regex("""<(br|p|div|li|tr|table)\b""", RegexOption.IGNORE_CASE)
    private val xdxfItalic = Regex("""<(/?)(ex|abr|abbr|co)\b[^>]*>""")
    private val xdxfBold = Regex("""<(/?)(k|kref)\b[^>]*>""")

    /** Several definitions (e.g. "apple" and "Apple") are shown one after another, each with its headword. */
    fun format(definitions: List<StarDict.Definition>): CharSequence {
        val out = SpannableStringBuilder()
        for (def in definitions) {
            if (out.isNotEmpty()) out.append("\n\n")
            if (definitions.size > 1) {
                val start = out.length
                out.append(def.word).append('\n')
                out.setSpan(StyleSpan(Typeface.BOLD), start, out.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            for ((k, part) in def.parts.withIndex()) {
                if (k > 0) out.append('\n')
                out.append(formatPart(part.first, part.second))
            }
        }
        return out
    }

    private fun formatPart(type: Char, text: String): CharSequence = when (type) {
        'h' -> html(text, newlinesAreBreaks = !blockTags.containsMatchIn(text))
        'g' -> html(text, newlinesAreBreaks = true) // Pango markup is close enough to HTML
        'x' -> html( // XDXF
            text.replace(xdxfItalic, "<$1i>").replace(xdxfBold, "<$1b>")
                .replace("<tr>", "[").replace("</tr>", "]"),
            newlinesAreBreaks = true,
        )
        't' -> "[${text.trim()}]" // phonetic transcription
        else -> text.trim() // 'm' plain text and anything else readable
    }

    private fun html(source: String, newlinesAreBreaks: Boolean): CharSequence {
        var s = dropBlocks.replace(source, "")
        s = images.replace(s, "")
        if (newlinesAreBreaks) s = s.replace("\r\n", "\n").replace("\n", "<br>")
        val spanned = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY)
        var end = spanned.length
        while (end > 0 && spanned[end - 1].isWhitespace()) end--
        var start = 0
        while (start < end && spanned[start].isWhitespace()) start++
        return spanned.subSequence(start, end)
    }
}
