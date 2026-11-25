package com.guny90.reader

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SidebarTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun Sidebar.size(w: Int, h: Int) {
        measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        layout(0, 0, w, h)
    }

    private fun texts(v: View): List<TextView> = when (v) {
        is TextView -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun clickText(root: View, match: (String) -> Boolean) {
        var v: View = texts(root).first { match(it.text.toString()) }
        while (!v.isClickable) v = v.parent as View
        v.performClick()
    }

    @After fun day() { Ink.night = false }

    @Test fun rowsShowLabelsValuesAndBackRow() = onMain {
        val s = Sidebar(context)
        s.show(listOf(Sidebar.Row.Item("Night mode", "Off") {}), "Menu", back = {})
        s.size(1000, 1600)
        val shown = texts(s).map { it.text.toString() }
        assertTrue(shown.toString(), shown.containsAll(listOf("‹ Menu", "Night mode", "Off")))
        assertTrue(shown.indexOf("‹ Menu") < shown.indexOf("Night mode"))
    }

    @Test fun tapOnItemRunsIt() = onMain {
        var tapped = false
        val s = Sidebar(context)
        s.show(listOf(Sidebar.Row.Item("Night mode", "Off") { tapped = true }))
        s.size(1000, 1600)
        clickText(s) { it == "Night mode" }
        assertTrue(tapped)
    }

    @Test fun headerLinkOpens() = onMain {
        var opened = false
        val s = Sidebar(context)
        s.show(listOf(Sidebar.Row.Header("Beloved", "The Clearing", { opened = true }, "37%")))
        s.size(1000, 1600)
        val shown = texts(s).map { it.text.toString() }
        assertTrue(shown.toString(), shown.containsAll(listOf("Beloved", "The Clearing", "37%  ›")))
        clickText(s) { it.startsWith("The Clearing") }
        assertTrue(opened)
    }

    @Test fun headerLinkIsSmallerThanRows() = onMain {
        val s = Sidebar(context)
        s.show(listOf(Sidebar.Row.Header("Beloved", "The Clearing", {}, "37%"), Sidebar.Row.Item("Words") {}))
        val all = texts(s)
        val link = all.first { it.text == "The Clearing" }.textSize
        val row = all.first { it.text == "Words" }.textSize
        assertTrue("$link < $row", link < row)
    }

    @Test fun groupsAreSeparatedBySpaceNotRules() = onMain {
        val s = Sidebar(context)
        s.show(listOf(Sidebar.Row.Item("A") {}, Sidebar.Row.Divider, Sidebar.Row.Item("B") {}))
        s.size(1000, 1600)
        val list = ((s.panel.getChildAt(0) as android.view.ViewGroup).getChildAt(0)) as android.view.ViewGroup
        val gap = list.getChildAt(1)
        assertEquals(null, gap.background)
        assertTrue(gap.height > 0)
    }

    @Test fun rowsUseLiterata() = onMain {
        val s = Sidebar(context)
        s.show(listOf(Sidebar.Row.Item("Words") {}))
        assertEquals(Fonts.typeface(context, 1), texts(s).first { it.text == "Words" }.typeface)
    }

    @Test fun goBackCallsBackThenHides() = onMain {
        var back = false
        var hidden = false
        val s = Sidebar(context)
        s.onHidden = { hidden = true }
        s.show(listOf(Sidebar.Row.Item("Font", "Serif") {}), "Text", back = { back = true })
        assertTrue(s.goBack())
        assertTrue(back)
        s.show(listOf(Sidebar.Row.Item("Text", opens = true) {}))
        assertTrue(s.goBack())
        assertFalse(s.isShowing)
        assertTrue(hidden)
        assertFalse(s.goBack())
    }

    @Test fun tapOutsideClosesWithoutPassingThrough() = onMain {
        val s = Sidebar(context)
        s.show(listOf(Sidebar.Row.Item("Night mode", "Off") {}))
        s.size(1000, 1600)
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 950f, 800f, 0)
        val up = MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, 950f, 800f, 0)
        assertTrue(s.dispatchTouchEvent(down))
        assertTrue(s.dispatchTouchEvent(up))
        assertFalse(s.isShowing)
    }

    @Test fun colorsFollowInk() = onMain {
        Ink.night = true
        val s = Sidebar(context)
        s.show(listOf(Sidebar.Row.Item("Night mode", "On") {}))
        assertEquals(Ink.NIGHT_TEXT, texts(s).first { it.text == "Night mode" }.currentTextColor)
    }

    /** Async results (e.g. the library's shared folder) check this to tell whether the user moved on meanwhile. */
    @Test fun generationChangesOnShowAndHide() = onMain {
        val s = Sidebar(context)
        val g0 = s.generation
        s.show(listOf(Sidebar.Row.Item("Night mode", "On") {}))
        val g1 = s.generation
        assertTrue(g1 != g0)
        s.show(listOf(Sidebar.Row.Item("Font", "Serif") {}), "Text", back = {})
        val g2 = s.generation
        assertTrue(g2 != g1)
        s.hide()
        assertTrue(s.generation != g2)
        val g3 = s.generation
        s.hide() // already hidden: nothing changes
        assertEquals(g3, s.generation)
    }

    @Test fun panelWidthIsCapped() = onMain {
        val cap = (360 * context.resources.displayMetrics.density).toInt()
        val s = Sidebar(context)
        s.show(listOf(Sidebar.Row.Item("Night mode", "On") {}))
        s.size(1000, 1600)
        assertEquals(minOf(800, cap), s.panel.width)
        s.size(3000, 1600)
        assertEquals(minOf(2400, cap), s.panel.width)
    }
}
