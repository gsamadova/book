package com.guny90.reader

import android.text.Html
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.SuperscriptSpan
import android.text.style.URLSpan

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
    private val startTags = Regex("""<([a-zA-Z][\w:.-]*)(\s[^>]*)?>""")
    private val idAttr = Regex("""\s(?:xml:)?id\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val nameAttr = Regex("""\sname\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val hrefStart = Regex("""(\shref\s*=\s*["'])""", RegexOption.IGNORE_CASE)
    private val noteRefType = Regex("""\s(?:epub:type|role)\s*=\s*["'][^"']*\b(?:doc-)?noteref\b""", RegexOption.IGNORE_CASE)
    private val backLinkType = Regex("""\s(?:epub:type|role)\s*=\s*["'][^"']*\b(?:doc-)?backlink\b""", RegexOption.IGNORE_CASE)
    /** Text of a footnote marker when the book doesn't tag it: "1", "[12]", "(3)", "*", "†". */
    private val noteMarkerText = Regex("""^[\[(]?(\d{1,4}|[*\u2020\u2021\u00A7\u00B6]{1,3})[\])]?$""")
    private val urlScheme = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*:""")
    private const val NOTE_PREFIX = "\uE005note:"
    private const val BACK_PREFIX = "\uE005back:"
    private const val ANCHOR_START = '\uE003'
    private const val ANCHOR_END = '\uE004'
    private val lineBreaks = Regex("""<br\b[^>]*>""", RegexOption.IGNORE_CASE)
    private const val BR_MARK = '\uE000' // private-use char, survives Html.fromHtml unchanged

    /** Marks a newline that came from <br>: a line break inside a paragraph, so no paragraph gap. */
    class LineBreak

    /** Covers an "[image]" placeholder: the picture's path inside the EPUB. */
    class ImageRef(val path: String)

    /**
     * A link, underlined. [target] is "path/in/zip.xhtml#id" (resolved) for a link inside the
     * book, or the URL as written for a web link.
     */
    class Link(val target: String, val kind: Kind) : CharacterStyle() {
        enum class Kind {
            /** A footnote or endnote marker: its note is shown in a popup. */
            NOTE,
            /** Another place in the book. */
            INTERNAL,
            /** A web page, e-mail address and the like. */
            EXTERNAL,
        }

        override fun updateDrawState(tp: TextPaint) {
            tp.isUnderlineText = true
        }
    }

    /** A chapter's text, and where in it each id (link target) is: character offsets. */
    class Chapter(val text: CharSequence, val anchors: Map<String, Int>)

    /** [chapterPath]: the chapter's path in the zip, for resolving pictures and links. */
    fun toText(xhtml: String, chapterPath: String = ""): Chapter {
        val baseDir = chapterPath.substringBeforeLast('/', "")
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
        // Every id becomes a mark (removed again below) that says where the link target is. Note
        // and back links are tagged in their href, as Html.fromHtml keeps only the href.
        val ids = ArrayList<String>()
        body = startTags.replace(body) { m ->
            val attrs = m.groupValues[2]
            if (attrs.isEmpty()) return@replace m.value
            val isLink = m.groupValues[1].equals("a", ignoreCase = true)
            var tag = m.value
            if (isLink) {
                if (noteRefType.containsMatchIn(attrs)) tag = hrefStart.replaceFirst(tag, "$1$NOTE_PREFIX")
                else if (backLinkType.containsMatchIn(attrs)) tag = hrefStart.replaceFirst(tag, "$1$BACK_PREFIX")
            }
            val found = idAttr.findAll(attrs).map { it.groupValues[1] } +
                (if (isLink) nameAttr.findAll(attrs).map { it.groupValues[1] } else emptySequence())
            val marks = StringBuilder(tag)
            for (id in found) {
                marks.append(ANCHOR_START).append(ids.size).append(ANCHOR_END)
                ids += id
            }
            marks.toString()
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
        val anchors = takeAnchors(text, ids)
        for (i in text.indices) {
            if (text[i] == BR_MARK) {
                text.replace(i, i + 1, "\n") // same length: offsets and spans stay valid
                text.setSpan(LineBreak(), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        makeLinks(text, chapterPath, baseDir)
        var start = 0
        var end = text.length
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        val trimmed = anchors.mapValues { (it.value - start).coerceIn(0, end - start) }
        return Chapter(text.subSequence(start, end), trimmed)
    }

    /**
     * Removes the id marks, leaving the text exactly as without them, and returns where each id was.
     * A mark alone in an otherwise empty block (<div id="x"></div>) made Html.fromHtml add a line
     * break for it; that one goes too.
     */
    private fun takeAnchors(text: SpannableStringBuilder, ids: List<String>): Map<String, Int> {
        val anchors = HashMap<String, Int>()
        var i = text.indexOf(ANCHOR_START)
        while (i >= 0) {
            val close = text.indexOf(ANCHOR_END, i)
            if (close < 0) break
            val id = text.substring(i + 1, close).toIntOrNull()?.let { ids.getOrNull(it) }
            text.delete(i, close + 1)
            if ((i == 0 || text[i - 1] == '\n') && i < text.length && text[i] == '\n' &&
                text.getSpans(i, i + 1, LineBreak::class.java).isEmpty()
            ) text.delete(i, i + 1)
            if (id != null) anchors.putIfAbsent(id, i)
            i = text.indexOf(ANCHOR_START, i)
        }
        return anchors
    }

    /** Replaces Html.fromHtml's URLSpans with [Link]s: resolved targets, and what kind of link. */
    private fun makeLinks(text: SpannableStringBuilder, chapterPath: String, baseDir: String) {
        for (span in text.getSpans(0, text.length, URLSpan::class.java)) {
            val start = text.getSpanStart(span)
            val end = text.getSpanEnd(span)
            text.removeSpan(span)
            var href = span.url?.trim() ?: continue
            var tagged: Link.Kind? = null
            when {
                href.startsWith(NOTE_PREFIX) -> { href = href.removePrefix(NOTE_PREFIX); tagged = Link.Kind.NOTE }
                href.startsWith(BACK_PREFIX) -> { href = href.removePrefix(BACK_PREFIX); tagged = Link.Kind.INTERNAL }
            }
            if (href.isEmpty() || start >= end) continue
            val link = if (urlScheme.containsMatchIn(href)) {
                Link(href, Link.Kind.EXTERNAL)
            } else {
                val target = if (href.startsWith("#")) chapterPath + href else EpubBook.resolve(baseDir, href)
                val label = text.substring(start, end).trim()
                val looksLikeNote = target.contains('#') && (noteMarkerText.matches(label) ||
                    (label.length <= 3 && text.getSpans(start, end, SuperscriptSpan::class.java).isNotEmpty()))
                Link(target, tagged ?: if (looksLikeNote) Link.Kind.NOTE else Link.Kind.INTERNAL)
            }
            text.setSpan(link, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /**
     * The note at [offset] of [chapter] (where a footnote link points): to the end of its
     * paragraph. A paragraph holding only the note's number is followed by the note itself.
     */
    fun noteAt(chapter: Chapter, offset: Int): CharSequence {
        val text = chapter.text
        val start = offset.coerceIn(0, text.length)
        var end = start
        do {
            end = text.indexOf('\n', end + 1).let { if (it < 0) text.length else it }
        } while (end < text.length && text.substring(start, end).trim().length <= 4)
        end = minOf(end, start + MAX_NOTE)
        return text.subSequence(start, end).trimEnd { it.isWhitespace() || it == '\u21A9' || it == '\uFE0E' }
    }

    private const val MAX_NOTE = 4000

    /** Footnote markers ([Link.Kind.NOTE] links, with brackets around them), as ranges to skip when reading aloud. */
    fun noteMarkers(text: CharSequence): List<IntRange> {
        val spanned = text as? Spanned ?: return emptyList()
        return spanned.getSpans(0, text.length, Link::class.java).filter { it.kind == Link.Kind.NOTE }.map { link ->
            var start = spanned.getSpanStart(link)
            var end = spanned.getSpanEnd(link)
            if (start > 0 && text[start - 1] in "[(") start--
            if (end < text.length && text[end] in "])") end++
            start until end
        }
    }
}
