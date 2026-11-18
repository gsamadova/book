package com.guny90.reader

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat

/** Page colors: black on white, or light on black in night mode. Set [night] on the main thread, then redraw. */
object Ink {
    @Volatile var night = false

    val text get() = if (night) NIGHT_TEXT else Color.BLACK
    val paper get() = if (night) Color.BLACK else Color.WHITE

    /** Same as @color/night_text. */
    const val NIGHT_TEXT = 0xFFDDDDDD.toInt()
}

/** The fonts the text can be shown in. Literata is bundled; the others come with Android. */
object Fonts {
    val names = listOf("Serif", "Literata", "Sans-serif", "Condensed", "Monospace")

    @Volatile private var literata: Typeface? = null

    fun typeface(context: Context, index: Int): Typeface = when (index) {
        1 -> literata ?: (ResourcesCompat.getFont(context, R.font.literata) ?: Typeface.SERIF).also { literata = it }
        2 -> Typeface.SANS_SERIF
        3 -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        4 -> Typeface.MONOSPACE
        else -> Typeface.SERIF
    }
}
