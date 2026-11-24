package com.guny90.reader

/** The chapter line of the menu: "Ch. 4. The Clearing", named from the book's contents. */
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

    /** "Ch. [number]. [name]", or "Ch. [number]" when there's no name or it only repeats the number. */
    fun label(number: Int, name: String?): String {
        val n = name?.trim()
        return if (n.isNullOrEmpty() || isJustNumber(n, number)) "Ch. $number" else "Ch. $number. $n"
    }

    /** "4", "IV", "Chapter 4", "Глава 4." and the like, for [number]. */
    fun isJustNumber(name: String, number: Int): Boolean {
        val words = name.trim().trimEnd('.', ':').split(Regex("\\s+"))
        if (words.size > 2) return false
        return numeral(words.last()) == number
    }

    private fun numeral(word: String): Int? = word.toIntOrNull() ?: roman(word.lowercase())

    private fun roman(s: String): Int? {
        if (s.isEmpty()) return null
        val values = mapOf('i' to 1, 'v' to 5, 'x' to 10, 'l' to 50, 'c' to 100, 'd' to 500, 'm' to 1000)
        var total = 0
        for (i in s.indices) {
            val v = values[s[i]] ?: return null
            val next = if (i + 1 < s.length) values[s[i + 1]] ?: return null else 0
            total += if (v < next) -v else v
        }
        return total
    }
}
