package com.guny90.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterLabelTest {
    private val toc = listOf(
        EpubBook.TocEntry("Cover", 0),
        EpubBook.TocEntry("The Clearing", 3),
        EpubBook.TocEntry("Part two", 3, "p2"),
        EpubBook.TocEntry("Sweet Home", 5),
    )
    private val anchors = mapOf("p2" to 500)

    @Test fun nameOfChapterStart() = assertEquals("The Clearing", ChapterLabel.currentName(toc, 3, 0, anchors::get))
    @Test fun sectionOnceItsAnchorIsReached() = assertEquals("Part two", ChapterLabel.currentName(toc, 3, 500, anchors::get))
    @Test fun earlierEntryCarriesOver() = assertEquals("The Clearing", ChapterLabel.currentName(toc.take(2), 4, 0, anchors::get))
    @Test fun noTocGivesNumberOnly() = assertEquals("Ch. 4", ChapterLabel.label(4, ChapterLabel.currentName(emptyList(), 3, 0) { null }))
    @Test fun missingAnchorCountsAsChapterStart() = assertEquals("Part two", ChapterLabel.currentName(toc, 3, 0) { null })
    @Test fun labelWithName() = assertEquals("Ch. 4. The Clearing", ChapterLabel.label(4, "The Clearing"))
    @Test fun numberOnlyNamesAreDropped() {
        for (n in listOf("4", "Chapter 4", "IV", "Глава 4.", "chapter iv:")) assertEquals(n, "Ch. 4", ChapterLabel.label(4, n))
    }
    @Test fun otherNumbersAreKept() = assertEquals("Ch. 6. Chapter 4", ChapterLabel.label(6, "Chapter 4"))
    @Test fun numbersInsideNamesAreKept() = assertEquals("Ch. 4. 1984 in Winter", ChapterLabel.label(4, "1984 in Winter"))
}
