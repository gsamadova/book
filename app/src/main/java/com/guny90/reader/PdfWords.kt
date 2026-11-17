package com.guny90.reader

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.Closeable
import java.io.File

/**
 * Reads the words of single PDF pages, with their positions, using PdfBox. Only the page being
 * read is processed (the document is opened once, on first use), so it stays quick.
 *
 * Use from one background thread only: PdfBox is not thread-safe.
 */
class PdfWords(private val context: Context, private val file: File) : Closeable {
    private var doc: PDDocument? = null
    private var failed = false
    private val collector by lazy { Collector() }
    /** The last few pages, so paging back and forth doesn't read them again. */
    private val cache = LinkedHashMap<Int, List<PageWords.Word>>()

    /** The words of page [index] (from 0); empty if it has none (e.g. a scanned page) or can't be read. */
    fun page(index: Int): List<PageWords.Word> {
        cache.remove(index)?.let { cache[index] = it; return it }
        val d = open() ?: return emptyList()
        val words = try {
            collector.wordsOf(d, index)
        } catch (e: Exception) {
            emptyList()
        }
        cache[index] = words
        while (cache.size > CACHED_PAGES) cache.remove(cache.keys.first())
        return words
    }

    private fun open(): PDDocument? {
        doc?.let { return it }
        if (failed) return null
        return try {
            PDFBoxResourceLoader.init(context.applicationContext)
            // Temp files instead of memory: e-readers have little RAM.
            PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).also { doc = it }
        } catch (e: Exception) {
            failed = true // e.g. password-protected: no selection, but pages still show
            null
        }
    }

    override fun close() {
        cache.clear()
        try { doc?.close() } catch (e: Exception) { }
        doc = null
    }

    /**
     * PdfBox calls [writeString] for each word of a line (with each letter's position) and
     * [writeLineSeparator] after each line. Content order, which keeps columns apart in most PDFs.
     */
    private class Collector : PDFTextStripper() {
        private val words = ArrayList<PageWords.Word>()
        private var line = 0

        init {
            setSortByPosition(false)
            setSuppressDuplicateOverlappingText(true)
        }

        fun wordsOf(doc: PDDocument, index: Int): List<PageWords.Word> {
            words.clear()
            line = 0
            setStartPage(index + 1)
            setEndPage(index + 1)
            getText(doc) // the text itself is ignored: the words are collected below
            return ArrayList(words)
        }

        /**
         * Called per word when the PDF positions words apart, but with a whole line when its
         * spaces are real characters: words are split at spaces here, letter by letter.
         */
        override fun writeString(text: String, textPositions: List<TextPosition>) {
            var start = -1
            for (i in textPositions.indices) {
                val blank = textPositions[i].unicode.isNullOrBlank()
                if (!blank && start < 0) start = i
                if (blank && start >= 0) {
                    addWord(textPositions.subList(start, i))
                    start = -1
                }
            }
            if (start >= 0) addWord(textPositions.subList(start, textPositions.size))
        }

        private fun addWord(textPositions: List<TextPosition>) {
            val clean = textPositions.joinToString("") { ligatures[it.unicode] ?: it.unicode ?: "" }.trim()
            if (clean.isEmpty()) return
            var left = Float.MAX_VALUE
            var right = -Float.MAX_VALUE
            var baseline = 0f
            var size = 0f
            for (p in textPositions) {
                left = minOf(left, p.xDirAdj)
                right = maxOf(right, p.xDirAdj + p.widthDirAdj)
                baseline = maxOf(baseline, p.yDirAdj)
                // Size from the font and text matrix; some PDFs scale a 1pt font instead.
                val pt = p.fontSizeInPt
                size = maxOf(size, if (pt > 1.5f) pt else p.heightDir * 1.4f)
            }
            // Ascent and descent aren't reliable in PDFs: approximate them from the size.
            words += PageWords.Word(clean, left, baseline - size * 0.8f, right, baseline + size * 0.25f, line)
        }

        override fun writeLineSeparator() {
            line++
        }
    }

    private companion object {
        const val CACHED_PAGES = 6

        /** Ligature glyphs spelled out, so "ﬁnd" is looked up as "find". */
        val ligatures = mapOf(
            "ﬀ" to "ff", "ﬁ" to "fi", "ﬂ" to "fl", "ﬃ" to "ffi", "ﬄ" to "ffl",
            "ﬅ" to "st", "ﬆ" to "st",
        )
    }
}
