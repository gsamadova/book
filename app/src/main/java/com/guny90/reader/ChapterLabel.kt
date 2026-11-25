package com.guny90.reader

/** The chapter line of the menu: "The Clearing", named from the book's contents. */
object ChapterLabel {
    /**
     * The title of the last contents entry at or before the page starting at [pageStart] in
     * [chapter] (a spine index); null if none. An anchor [anchorAt] can't find counts as the
     * chapter's start.
     */
    fun currentName(toc: List<EpubBook.TocEntry>, chapter: Int, pageStart: Int, anchorAt: (String) -> Int?): String? =
        toc.lastOrNull { e ->
            e.spineIndex < chapter ||
                (e.spineIndex == chapter && (e.anchor == null || (anchorAt(e.anchor) ?: 0) <= pageStart))
        }?.title

    /** The chapter's [name] from the contents, or "Ch. [number]" when the book gives none. */
    fun label(number: Int, name: String?): String = name?.trim()?.takeIf { it.isNotEmpty() } ?: "Ch. $number"
}
