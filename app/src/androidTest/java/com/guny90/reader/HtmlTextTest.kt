package com.guny90.reader

import android.text.Spanned
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Html.fromHtml needs Android, so these run on a device. */
@RunWith(AndroidJUnit4::class)
class HtmlTextTest {
    private fun chapter(body: String) =
        HtmlText.toText("<html><head><style>p{}</style></head><body>$body</body></html>", "text/ch1.xhtml")

    @Test
    fun idsBecomeAnchorsAtTheirText() {
        val ch = chapter("""<p id="a">One</p><div id="b"></div><p class="x">Two <span id='c'>three</span></p>""")
        val text = ch.text.toString()
        assertEquals("One\nTwo three", text)
        assertEquals(0, ch.anchors["a"])
        assertEquals(text.indexOf("Two"), ch.anchors["b"])
        assertEquals(text.indexOf("three"), ch.anchors["c"])
    }

    @Test
    fun manyIdsInALongChapter() {
        val body = (0 until 2000).joinToString("") { """<p id="p$it" class="t">Paragraph $it.</p>""" }
        val ch = chapter(body)
        val text = ch.text.toString()
        assertTrue(text.startsWith("Paragraph 0.\nParagraph 1."))
        for (i in listOf(0, 1, 999, 1999)) assertEquals(text.indexOf("Paragraph $i."), ch.anchors["p$i"])
    }

    @Test
    fun lineBreaksInAnyForm() {
        val ch = chapter("""<p>a<br/>b<BR class="x">c<br>d</p><p>brief <bridge>e</bridge></p>""")
        val text = ch.text as Spanned
        assertEquals("a\nb\nc\nd\nbrief e", text.toString())
        val breaks = text.getSpans(0, text.length, HtmlText.LineBreak::class.java).map { text.getSpanStart(it) }.sorted()
        assertEquals(listOf(1, 3, 5), breaks)
    }

    @Test
    fun linksKnowTheirKind() {
        val ch = chapter(
            """<p>Text<a epub:type="noteref" href="#n1">1</a>, see <a href="ch2.xhtml#s">part two</a>""" +
                """ or <a href="https://example.com">the web</a>.</p><p id="n1">1. A note.</p>"""
        )
        val text = ch.text as Spanned
        val links = text.getSpans(0, text.length, HtmlText.Link::class.java).sortedBy { text.getSpanStart(it) }
        assertEquals(listOf(HtmlText.Link.Kind.NOTE, HtmlText.Link.Kind.INTERNAL, HtmlText.Link.Kind.EXTERNAL), links.map { it.kind })
        assertEquals(listOf("text/ch1.xhtml#n1", "text/ch2.xhtml#s", "https://example.com"), links.map { it.target })
        assertEquals(text.toString().indexOf("1. A note"), ch.anchors["n1"])
    }

    @Test
    fun picturesBecomePlaceholders() {
        val ch = chapter("""<p>Before</p><img class="pic" src="../images/p1.png" alt="x"/><p>After</p>""")
        val text = ch.text as Spanned
        assertEquals("Before\n${HtmlText.IMAGE_PLACEHOLDER}\nAfter", text.toString())
        val ref = text.getSpans(0, text.length, HtmlText.ImageRef::class.java).single()
        assertEquals("images/p1.png", ref.path)
        assertEquals(text.toString().indexOf(HtmlText.IMAGE_PLACEHOLDER), text.getSpanStart(ref))
    }
}
