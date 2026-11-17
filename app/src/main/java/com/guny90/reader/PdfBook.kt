package com.guny90.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import kotlin.math.roundToInt

/**
 * A PDF shown as page images, with Android's own renderer (no library). White margins are
 * cropped so the content is as large as possible.
 *
 * Worker thread only: PdfRenderer is not thread-safe.
 */
class PdfBook private constructor(
    private val fd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    val title: String,
) : Closeable {
    val pageCount: Int = renderer.pageCount

    /**
     * A rendered page: [bitmap] shows the area [box] of the page (PDF points from its top-left),
     * scaled by [scale] pixels per point. Maps word positions onto the picture.
     */
    class Rendered(val page: Int, val bitmap: Bitmap, val box: RectF, val scale: Float)

    /** Content box of each page, in PDF points (found once per page). */
    private val contentBounds = HashMap<Int, RectF>()

    /** Recently rendered pages (current and neighbours) at [cacheWidth] x [cacheHeight]. */
    private val cache = LinkedHashMap<Int, Rendered>()
    private var cacheWidth = 0
    private var cacheHeight = 0

    /** Page [index] cropped to its content and scaled to fit [maxWidth] x [maxHeight]. */
    fun page(index: Int, maxWidth: Int, maxHeight: Int): Rendered {
        if (maxWidth != cacheWidth || maxHeight != cacheHeight) {
            cache.clear()
            cacheWidth = maxWidth
            cacheHeight = maxHeight
        }
        cache.remove(index)?.let { cache[index] = it; return it } // re-insert: most recent last
        val rendered = render(index, maxWidth, maxHeight)
        cache[index] = rendered
        // Not recycled: the evicted page may still be on screen; the GC frees it.
        while (cache.size > CACHED_PAGES) cache.remove(cache.keys.first())
        return rendered
    }

    private fun render(index: Int, maxWidth: Int, maxHeight: Int): Rendered {
        val page = renderer.openPage(index)
        try {
            val box = contentBounds.getOrPut(index) { findContent(page) }
            val scale = minOf(maxWidth / box.width(), maxHeight / box.height())
            val w = (box.width() * scale).roundToInt().coerceIn(1, maxWidth)
            val h = (box.height() * scale).roundToInt().coerceIn(1, maxHeight)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) // the renderer requires ARGB_8888
            bitmap.eraseColor(Color.WHITE) // the renderer leaves the background transparent
            val matrix = Matrix().apply {
                setTranslate(-box.left, -box.top)
                postScale(scale, scale)
            }
            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return Rendered(index, bitmap, RectF(box), scale)
        } finally {
            page.close()
        }
    }

    /**
     * The area holding ink, from a quick low-resolution render, with a little padding. Isolated
     * specks (scan noise) are ignored. A nearly empty page (say, only a heading) isn't blown up:
     * its box is kept at least [MIN_WIDTH] x [MIN_HEIGHT] of the page.
     */
    private fun findContent(page: PdfRenderer.Page): RectF {
        val pw = page.width.toFloat()
        val ph = page.height.toFloat()
        val full = RectF(0f, 0f, pw, ph)
        val s = SAMPLE_SIZE / maxOf(pw, ph)
        val w = (pw * s).roundToInt().coerceAtLeast(1)
        val h = (ph * s).roundToInt().coerceAtLeast(1)
        val sample = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        try {
            sample.eraseColor(Color.WHITE)
            page.render(sample, null, Matrix().apply { setScale(s, s) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            sample.getPixels(pixels, 0, w, 0, 0, w, h)
        } finally {
            sample.recycle()
        }

        val inkPerRow = IntArray(h)
        val inkPerColumn = IntArray(w)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = pixels[y * w + x]
                val luminance = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                if (luminance < INK_LUMINANCE) {
                    inkPerRow[y]++
                    inkPerColumn[x]++
                }
            }
        }
        val minRowInk = maxOf(2, w / 200)
        val minColumnInk = maxOf(2, h / 200)
        val top = inkPerRow.indexOfFirst { it >= minRowInk }
        if (top < 0) return full // blank page
        val bottom = inkPerRow.indexOfLast { it >= minRowInk }
        val left = inkPerColumn.indexOfFirst { it >= minColumnInk }
        val right = inkPerColumn.indexOfLast { it >= minColumnInk }
        if (left < 0) return full

        val pad = PADDING * maxOf(w, h)
        val box = RectF(
            (left - pad) / s, (top - pad) / s, (right + 1 + pad) / s, (bottom + 1 + pad) / s,
        )
        grow(box, MIN_WIDTH * pw, MIN_HEIGHT * ph, full)
        box.intersect(full)
        return box
    }

    /** Widens [box] around its center to at least [minW] x [minH], shifting it back inside [bounds]. */
    private fun grow(box: RectF, minW: Float, minH: Float, bounds: RectF) {
        if (box.width() < minW) {
            val cx = box.centerX().coerceIn(bounds.left + minW / 2, bounds.right - minW / 2)
            box.left = cx - minW / 2
            box.right = cx + minW / 2
        }
        if (box.height() < minH) {
            val cy = box.centerY().coerceIn(bounds.top + minH / 2, bounds.bottom - minH / 2)
            box.top = cy - minH / 2
            box.bottom = cy + minH / 2
        }
    }

    override fun close() {
        cache.clear()
        renderer.close()
        fd.close()
    }

    companion object {
        private const val CACHED_PAGES = 3
        private const val SAMPLE_SIZE = 400f
        private const val INK_LUMINANCE = 200
        private const val PADDING = 0.012f
        private const val MIN_WIDTH = 0.6f
        private const val MIN_HEIGHT = 0.4f

        /** True if [file] starts like a PDF ("%PDF-" within the first kilobyte). */
        fun isPdf(file: File): Boolean {
            val head = ByteArray(1024)
            val n = file.inputStream().use { it.read(head) }
            if (n <= 0) return false
            return String(head, 0, n, Charsets.ISO_8859_1).contains("%PDF-")
        }

        fun open(file: File, title: String): PdfBook {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try {
                PdfRenderer(fd)
            } catch (e: SecurityException) {
                fd.close()
                error("This PDF is password-protected.")
            } catch (e: Exception) {
                fd.close()
                throw e
            }
            if (renderer.pageCount == 0) {
                renderer.close()
                fd.close()
                error("This PDF has no pages.")
            }
            return PdfBook(fd, renderer, title)
        }
    }
}
