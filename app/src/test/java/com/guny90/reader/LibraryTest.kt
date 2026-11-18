package com.guny90.reader

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun library() = Library(tmp.root.resolve("library"), MemoryPrefs())

    private fun Library.addBook(hash: String, title: String, pdf: Boolean = false) =
        fileFor(hash, pdf).also { it.writeText(hash); add(it, title) }

    @Test fun newestFirst() {
        val lib = library()
        lib.addBook("a", "A")
        lib.addBook("b", "B", pdf = true)
        assertEquals(listOf("B", "A"), lib.entries().map { it.title })
        assertTrue(lib.entries()[0].isPdf)
    }

    @Test fun addingAgainMovesToTopWithoutDuplicate() {
        val lib = library()
        lib.addBook("a", "A")
        lib.addBook("b", "B")
        lib.touch(lib.entries()[1])
        assertEquals(listOf("A", "B"), lib.entries().map { it.title })
        lib.addBook("b", "B")
        assertEquals(listOf("B", "A"), lib.entries().map { it.title })
    }

    @Test fun keepsTenAndDeletesTheOldestFile() {
        val lib = library()
        val first = lib.addBook("h0", "Book 0")
        for (i in 1..10) lib.addBook("h$i", "Book $i")
        assertEquals(Library.MAX_BOOKS, lib.entries().size)
        assertEquals("Book 10", lib.entries().first().title)
        assertEquals("Book 1", lib.entries().last().title)
        assertFalse(first.exists())
    }

    @Test fun skipsBooksWhoseFileIsGone() {
        val lib = library()
        lib.addBook("a", "A").delete()
        lib.addBook("b", "B")
        assertEquals(listOf("B"), lib.entries().map { it.title })
    }

    @Test fun removeDeletesTheFile() {
        val lib = library()
        val a = lib.addBook("a", "A")
        lib.addBook("b", "B")
        lib.remove(lib.entries()[1])
        assertEquals(listOf("B"), lib.entries().map { it.title })
        assertFalse(a.exists())
    }

    /** Just enough SharedPreferences for Library (strings only). */
    private class MemoryPrefs : SharedPreferences {
        val map = HashMap<String, Any?>()
        override fun getString(key: String, defValue: String?) = map[key] as String? ?: defValue
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(key: String, value: String?) = apply { map[key] = value }
            override fun apply() {}
            override fun commit() = true
            override fun putStringSet(key: String, values: MutableSet<String>?) = this
            override fun putInt(key: String, value: Int) = this
            override fun putLong(key: String, value: Long) = this
            override fun putFloat(key: String, value: Float) = this
            override fun putBoolean(key: String, value: Boolean) = this
            override fun remove(key: String) = apply { map.remove(key) }
            override fun clear() = apply { map.clear() }
        }
        override fun getAll(): MutableMap<String, *> = map
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String, defValue: Int) = defValue
        override fun getLong(key: String, defValue: Long) = defValue
        override fun getFloat(key: String, defValue: Float) = defValue
        override fun getBoolean(key: String, defValue: Boolean) = defValue
        override fun contains(key: String) = map.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }
}
