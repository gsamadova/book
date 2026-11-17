package com.guny90.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageWordsTest {
    // Two lines, 12pt apart: "The quick dis-" / "play here."
    private val words = listOf(
        PageWords.Word("The", 72f, 92f, 92f, 103f, 0),
        PageWords.Word("quick", 96f, 92f, 126f, 103f, 0),
        PageWords.Word("dis-", 130f, 92f, 150f, 103f, 0),
        PageWords.Word("play", 72f, 104f, 95f, 115f, 1),
        PageWords.Word("here.", 99f, 104f, 125f, 115f, 1),
    )

    @Test fun findsTheWordUnderTheFinger() {
        assertEquals(1, PageWords.wordAt(words, 110f, 98f, slop = 2f))
        assertEquals(3, PageWords.wordAt(words, 80f, 110f, slop = 2f))
    }

    @Test fun findsNothingFarFromWords() {
        assertNull(PageWords.wordAt(words, 300f, 98f, slop = 2f))
        assertNull(PageWords.wordAt(emptyList(), 80f, 98f, slop = 2f))
    }

    @Test fun picksTheCloserWordBetweenTwo() {
        // In the gap between "The" (ends 92) and "quick" (starts 96), nearer to "quick".
        assertEquals(1, PageWords.wordAt(words, 95f, 98f, slop = 4f))
    }

    @Test fun nearestPrefersTheLineUnderTheFinger() {
        // Far right of the second line: "here." rather than "dis-" above, which is closer sideways.
        assertEquals(4, PageWords.nearest(words, 160f, 110f))
    }

    @Test fun joinsWordsAndHyphenatedLineEnds() {
        assertEquals("The quick", PageWords.join(words, 0..1))
        assertEquals("quick display here.", PageWords.join(words, 1..4))
    }

    @Test fun keepsHyphensInsideALineOrBeforeCapitals() {
        val compound = listOf(
            PageWords.Word("well-", 72f, 92f, 100f, 103f, 0),
            PageWords.Word("Known", 72f, 104f, 100f, 115f, 1),
        )
        assertEquals("well- Known", PageWords.join(compound, 0..1))
    }
}
