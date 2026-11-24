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
        body = svgBlocks.replaceEach(body) { m -> images.find(m.group())?.value ?: "" }
        // Each picture becomes its own paragraph "[image]", marked with its path. The text is the same
        // whether pictures are shown or not, so saved and synced positions don't depend on it.
        val sources = ArrayList<String?>()
        body = images.replaceEach(body) { m ->
            val src = imageSource.find(m.group())?.groupValues?.get(1)?.trim()
            sources += if (src == null || src.startsWith("data:")) null else EpubBook.resolve(baseDir, src).substringBefore('#')
            "<p>$IMAGE_START${sources.size - 1}$IMAGE_END</p>"
        }
        // Every id becomes a mark (removed again below) that says where the link target is. Note
        // and back links are tagged in their href, as Html.fromHtml keeps only the href.
        val ids = ArrayList<String>()
        body = replaceStartTags(body) { name, attrs, wholeTag ->
            val isLink = name.equals("a", ignoreCase = true)
            // Most tags (<p class="...">) have neither: no need to look for them.
            if (!isLink && !attrs.contains("id", ignoreCase = true)) return@replaceStartTags wholeTag
            var tag = wholeTag
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
        body = replaceLineBreaks(body)

        // COMPACT: paragraphs separated by one newline (LEGACY adds a blank line). The gap
        // between paragraphs is added at layout time, so it can be changed in the menu.
        val text = SpannableStringBuilder(Html.fromHtml(body, Html.FROM_HTML_MODE_COMPACT))
        val marks = ArrayList<IntArray>() // start, end, source index
        val found = imageMarks.toPattern().matcher(text.toString())
        while (found.find()) marks += intArrayOf(found.start(), found.end(), found.group(1)!!.toInt())
        for ((start, end, source) in marks.asReversed()) { // from the end: earlier offsets stay valid
            text.replace(start, end, IMAGE_PLACEHOLDER)
            val path = sources.getOrNull(source)
            if (path != null) {
                text.setSpan(ImageRef(path), start, start + IMAGE_PLACEHOLDER.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        val anchors = takeAnchors(text, ids)
        val plain = text.toString() // searching a String is much faster than a SpannableStringBuilder
        var br = plain.indexOf(BR_MARK)
        while (br >= 0) {
            text.replace(br, br + 1, "\n") // same length: offsets and spans stay valid
            text.setSpan(LineBreak(), br, br + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            br = plain.indexOf(BR_MARK, br + 1)
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
     * Like [Regex.replace] with a function, but with one matcher for the whole input. Kotlin's
     * version starts a new matcher for each match, and on Android each one copies the whole
     * input: for a long chapter with thousands of tags that took seconds.
     */
    private inline fun Regex.replaceEach(input: String, transform: (java.util.regex.Matcher) -> CharSequence): String {
        val m = toPattern().matcher(input)
        if (!m.find()) return input
        val out = StringBuilder(input.length + input.length / 8)
        var last = 0
        do {
            out.append(input, last, m.start()).append(transform(m))
            last = m.end()
        } while (m.find())
        return out.append(input, last, input.length).toString()
    }

    private fun isNameChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == ':' || c == '.' || c == '-'

    /**
     * Replaces each start tag that has attributes, <name attrs...>, with what [transform] returns
     * for it. A hand-written scan: Android's regex engine took a quarter of a second for this on
     * a long chapter.
     */
    private inline fun replaceStartTags(input: String, transform: (name: String, attrs: String, tag: String) -> String): String {
        var out: StringBuilder? = null
        var copied = 0
        var i = input.indexOf('<')
        while (i >= 0 && i + 1 < input.length) {
            var next = i + 1
            if (input[i + 1].let { it in 'a'..'z' || it in 'A'..'Z' }) {
                var j = i + 2
                while (j < input.length && isNameChar(input[j])) j++
                if (j < input.length && input[j].isWhitespace()) {
                    val close = input.indexOf('>', j)
                    if (close >= 0) {
                        val tag = input.substring(i, close + 1)
                        val replaced = transform(input.substring(i + 1, j), input.substring(j, close), tag)
                        if (replaced != tag) {
                            val b = out ?: StringBuilder(input.length + input.length / 8).also { out = it }
                            b.append(input, copied, i).append(replaced)
                            copied = close + 1
                        }
                        next = close + 1
                    }
                }
            }
            i = input.indexOf('<', next)
        }
        val b = out ?: return input
        return b.append(input, copied, input.length).toString()
    }

    /** Replaces each <br>, <br/> or <br class="..."> with [BR_MARK]. */
    private fun replaceLineBreaks(input: String): String {
        var out: StringBuilder? = null
        var copied = 0
        var i = input.indexOf('<')
        while (i >= 0) {
            var next = i + 1
            if (i + 3 < input.length && input[i + 1].lowercaseChar() == 'b' && input[i + 2].lowercaseChar() == 'r' &&
                !isWordChar(input[i + 3])
            ) {
                val close = input.indexOf('>', i + 3)
                if (close >= 0) {
                    val b = out ?: StringBuilder(input.length).also { out = it }
                    b.append(input, copied, i).append(BR_MARK)
                    copied = close + 1
                    next = close + 1
                }
            }
            i = input.indexOf('<', next)
        }
        val b = out ?: return input
        return b.append(input, copied, input.length).toString()
    }

    /** \w in Android's regexes: letters, digits, marks and connectors in any script. */
    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_' ||
        Character.getType(c).let { it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt() || it == Character.CONNECTOR_PUNCTUATION.toInt() }

    /**
     * Removes the id marks, leaving the text exactly as without them, and returns where each id was.
     * A mark alone in an otherwise empty block (<div id="x"></div>) made Html.fromHtml add a line
     * break for it; that one goes too.
     *
     * Finds everything to remove first, then removes it from the end backwards: each removal from
     * a long chapter moves the rest of the text, and going backwards keeps those moves short.
     */
    private fun takeAnchors(text: SpannableStringBuilder, ids: List<String>): Map<String, Int> {
        val anchors = HashMap<String, Int>()
        val plain = text.toString()
        val cuts = ArrayList<Int>() // start, end (exclusive) pairs, in order
        var removed = 0
        var lastKept = -1 // index in [plain] of the last character that stays, -1 if none yet
        var i = plain.indexOf(ANCHOR_START)
        while (i >= 0) {
            val close = plain.indexOf(ANCHOR_END, i)
            if (close < 0) break
            // Characters between the previous mark and this one stay.
            val prevCutEnd = if (cuts.isEmpty()) 0 else cuts[cuts.size - 1]
            if (i > prevCutEnd) lastKept = i - 1
            val id = plain.substring(i + 1, close).toIntOrNull()?.let { ids.getOrNull(it) }
            var end = close + 1
            removed += end - i
            if ((lastKept < 0 || plain[lastKept] == '\n') && end < plain.length && plain[end] == '\n') {
                end++
                removed++
            }
            if (id != null) anchors.putIfAbsent(id, end - removed)
            cuts += i
            cuts += end
            i = plain.indexOf(ANCHOR_START, end)
        }
        var k = cuts.size - 2
        while (k >= 0) {
            text.delete(cuts[k], cuts[k + 1])
            k -= 2
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
