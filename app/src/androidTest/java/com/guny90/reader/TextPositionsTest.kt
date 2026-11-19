package com.guny90.reader

import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextPositionsTest {
    private val text = "один два три четыре пять шесть семь восемь девять десять одиннадцать двенадцать\nконец"
    private val paint = TextPaint().apply { textSize = 40f }

    private fun layout(justify: Boolean): Layout {
        val b = StaticLayout.Builder.obtain(text, 0, text.length, paint, 600)
        if (justify) b.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
        return b.build()
    }

    private fun visibleEnd(l: Layout, line: Int): Int {
        var e = l.getLineEnd(line)
        while (e > l.getLineStart(line) && text[e - 1].isWhitespace()) e--
        return e
    }

    @SdkSuppress(minSdkVersion = 26)
    @Test
    fun justifiedLineEndsAtRightEdge() {
        val l = layout(true)
        assertEquals(600f, TextPositions.x(l, 0, visibleEnd(l, 0)), 2f)
    }

    @SdkSuppress(minSdkVersion = 26)
    @Test
    fun tapFindsTheCharacterDrawnThere() {
        val l = layout(true)
        for (i in l.getLineStart(0)..visibleEnd(l, 0)) {
            assertEquals(i, TextPositions.offsetAt(l, 0, TextPositions.x(l, 0, i)))
        }
    }

    @Test
    fun lastLineAndUnjustifiedTextAreUnchanged() {
        val j = layout(true)
        val last = j.lineCount - 1
        assertEquals(0f, TextPositions.justifyGap(j, last), 0f)
        val plain = layout(false)
        for (line in 0 until plain.lineCount) {
            assertEquals(0f, TextPositions.justifyGap(plain, line), 0f)
            val i = visibleEnd(plain, line)
            assertEquals(plain.getPrimaryHorizontal(i), TextPositions.x(plain, line, i), 0f)
        }
        assertTrue(j.lineCount > 1)
    }
}
