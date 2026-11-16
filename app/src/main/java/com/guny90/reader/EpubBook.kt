package com.guny90.reader

import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Minimal EPUB (2 and 3) reader built only on platform APIs.
 *
 * Reads container.xml -> OPF (title, manifest, spine) -> TOC (NCX or nav).
 * Chapter files are read lazily, one at a time, to keep memory low.
 * Not thread-safe: use from a single worker thread (the immutable fields are safe anywhere).
 */
class EpubBook private constructor(
    private val zip: ZipFile,
    val title: String,
    /** Zip entry paths of the chapters, in reading order. */
    val spine: List<String>,
    val toc: List<TocEntry>,
) : Closeable {

    data class TocEntry(val title: String, val spineIndex: Int)

    fun readChapter(index: Int): String {
        val entry = zip.getEntry(spine[index]) ?: return ""
        return zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    override fun close() = zip.close()

    companion object {
        fun open(file: File): EpubBook {
            val zip = ZipFile(file)
            try {
                return parse(zip)
            } catch (e: Exception) {
                zip.close()
                throw e
            }
        }

        private class Item(val href: String, val mediaType: String, val properties: String)

        private fun parse(zip: ZipFile): EpubBook {
            val opfPath = zip.stream("META-INF/container.xml").use { findRootFile(it) }
                ?: error("Not an EPUB file (no container.xml)")
            val opfDir = opfPath.substringBeforeLast('/', "")

            val manifest = HashMap<String, Item>()
            val spineIds = ArrayList<String>()
            var title = ""
            var ncxId: String? = null

            zip.stream(opfPath).use { input ->
                val p = parser(input)
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    if (p.eventType != XmlPullParser.START_TAG) continue
                    when (localName(p.name)) {
                        "title" -> if (title.isEmpty()) title = p.nextText().trim()
                        "item" -> {
                            val id = p.attr("id") ?: continue
                            manifest[id] = Item(
                                href = p.attr("href") ?: continue,
                                mediaType = p.attr("media-type") ?: "",
                                properties = p.attr("properties") ?: "",
                            )
                        }
                        "spine" -> ncxId = p.attr("toc")
                        "itemref" -> p.attr("idref")?.let { spineIds.add(it) }
                    }
                }
            }

            val spine = spineIds.mapNotNull { manifest[it] }
                .filter { it.mediaType.contains("html") || it.mediaType.isEmpty() }
                .map { resolve(opfDir, it.href) }
            if (spine.isEmpty()) error("This EPUB has no readable chapters")

            val toc = try {
                val nav = manifest.values.firstOrNull { it.properties.split(' ').contains("nav") }
                val ncx = ncxId?.let { manifest[it] }
                    ?: manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
                when {
                    nav != null -> readNav(zip, resolve(opfDir, nav.href), spine)
                    ncx != null -> readNcx(zip, resolve(opfDir, ncx.href), spine)
                    else -> emptyList()
                }
            } catch (e: Exception) {
                emptyList() // a broken TOC should never prevent reading
            }

            return EpubBook(zip, title.ifEmpty { "Untitled" }, spine, toc)
        }

        private fun findRootFile(input: InputStream): String? {
            val p = parser(input)
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && localName(p.name) == "rootfile") {
                    return p.attr("full-path")
                }
            }
            return null
        }

        /** EPUB 2 table of contents (toc.ncx). */
        private fun readNcx(zip: ZipFile, path: String, spine: List<String>): List<TocEntry> {
            val dir = path.substringBeforeLast('/', "")
            val result = ArrayList<TocEntry>()
            var label: String? = null
            zip.stream(path).use { input ->
                val p = parser(input)
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    if (p.eventType != XmlPullParser.START_TAG) continue
                    when (localName(p.name)) {
                        "navPoint" -> label = null
                        "text" -> if (label == null) label = p.nextText().trim()
                        "content" -> {
                            val src = p.attr("src") ?: continue
                            addTocEntry(result, label, resolve(dir, src), spine)
                        }
                    }
                }
            }
            return result
        }

        private val navToc = Regex("""epub:type\s*=\s*["']toc["']""")
        private val anchor = Regex(
            """<a\b[^>]*href\s*=\s*["']([^"']*)["'][^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val tags = Regex("<[^>]*>")

        /** EPUB 3 table of contents (nav document). Regex is enough for this simple structure. */
        private fun readNav(zip: ZipFile, path: String, spine: List<String>): List<TocEntry> {
            val dir = path.substringBeforeLast('/', "")
            var html = zip.stream(path).use { it.readBytes().toString(Charsets.UTF_8) }
            // Only the "toc" nav; skip landmarks / page-list navs.
            navToc.find(html)?.let { m ->
                val end = html.indexOf("</nav", m.range.last)
                html = html.substring(m.range.first, if (end > 0) end else html.length)
            }
            val result = ArrayList<TocEntry>()
            for (m in anchor.findAll(html)) {
                val text = android.text.Html.fromHtml(
                    tags.replace(m.groupValues[2], ""), android.text.Html.FROM_HTML_MODE_LEGACY
                ).toString().trim()
                addTocEntry(result, text, resolve(dir, m.groupValues[1]), spine)
            }
            return result
        }

        private fun addTocEntry(out: MutableList<TocEntry>, label: String?, path: String, spine: List<String>) {
            val index = spine.indexOf(path.substringBefore('#'))
            if (index >= 0) out.add(TocEntry(label?.ifEmpty { null } ?: "Chapter ${index + 1}", index))
        }

        /** Resolves an href relative to a directory inside the zip ("a/b" + "../c.html" -> "a/c.html"). */
        private fun resolve(dir: String, href: String): String {
            val fragment = href.substringAfter('#', "")
            val parts = ArrayList<String>()
            if (dir.isNotEmpty()) parts.addAll(dir.split('/'))
            for (part in Uri.decode(href.substringBefore('#')).split('/')) {
                when (part) {
                    "", "." -> {}
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                    else -> parts.add(part)
                }
            }
            val path = parts.joinToString("/")
            return if (fragment.isEmpty()) path else "$path#$fragment"
        }

        private fun ZipFile.stream(path: String): InputStream =
            getInputStream(getEntry(path) ?: error("Missing file in EPUB: $path"))

        private fun parser(input: InputStream): XmlPullParser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, null)
        }

        private fun localName(name: String) = name.substringAfter(':')

        private fun XmlPullParser.attr(name: String): String? {
            for (i in 0 until attributeCount) {
                if (localName(getAttributeName(i)) == name) return getAttributeValue(i)
            }
            return null
        }
    }
}
