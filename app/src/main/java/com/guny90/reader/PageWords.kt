package com.guny90.reader

/**
 * The words of one PDF page with their boxes, for selecting words on the page picture.
 * Coordinates are PDF points from the page's top-left corner. Plain Kotlin, so it can be unit-tested.
 */
object PageWords {
    /** [line] counts the page's lines in reading order: words on one line share it. */
    class Word(
        val text: String,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val line: Int,
    )

    /** Index of the word whose box, grown by [slop] points, contains (x, y); the nearest if several. */
    fun wordAt(words: List<Word>, x: Float, y: Float, slop: Float): Int? {
        var best: Int? = null
        var bestDistance = Float.MAX_VALUE
        for ((i, w) in words.withIndex()) {
            if (x < w.left - slop || x > w.right + slop || y < w.top - slop || y > w.bottom + slop) continue
            val d = distance(w, x, y)
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return best
    }

    /**
     * Index of the word nearest to (x, y), for extending a selection while the finger moves:
     * the finger's height picks the line, then the closest word on it (past a line's end, its last word).
     */
    fun nearest(words: List<Word>, x: Float, y: Float): Int? {
        if (words.isEmpty()) return null
        val bestDy = words.minOf { dy(it, y) }
        var best: Int? = null
        var bestDx = Float.MAX_VALUE
        for ((i, w) in words.withIndex()) {
            if (dy(w, y) > bestDy + 0.5f) continue
            val d = dx(w, x)
            if (d < bestDx) {
                bestDx = d
                best = i
            }
        }
        return best
    }

    private fun dx(w: Word, x: Float) = if (x < w.left) w.left - x else if (x > w.right) x - w.right else 0f
    private fun dy(w: Word, y: Float) = if (y < w.top) w.top - y else if (y > w.bottom) y - w.bottom else 0f
    private fun distance(w: Word, x: Float, y: Float) = dx(w, x) + dy(w, y)

    /**
     * The selected words as text: spaces between words, and a word hyphenated at a line end
     * joined back together ("dis-" + "play" -> "display").
     */
    fun join(words: List<Word>, range: IntRange): String {
        val sb = StringBuilder()
        var prev: Word? = null
        for (i in range) {
            val w = words[i]
            val p = prev
            if (p != null) {
                val hyphen = p.text.endsWith("-") || p.text.endsWith("­")
                val brokenWord = hyphen && w.line != p.line && p.text.length >= 2 &&
                    p.text[p.text.length - 2].isLetter() && w.text.firstOrNull()?.isLowerCase() == true
                if (brokenWord) sb.setLength(sb.length - 1) else sb.append(' ')
            }
            sb.append(w.text)
            prev = w
        }
        return sb.toString()
    }
}
