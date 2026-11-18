package com.guny90.reader

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The books opened on this device, most recent first, at most [MAX_BOOKS]. Each book's file is
 * kept in [dir], named after its content hash, so adding the same book again doesn't duplicate it.
 * Dropping a book off the end deletes its file; its reading position stays saved.
 *
 * Safe to use from any thread.
 */
class Library(private val dir: File, private val prefs: SharedPreferences) {

    class Entry(val fileName: String, val title: String) {
        val isPdf get() = fileName.endsWith(".pdf")
    }

    fun file(entry: Entry) = File(dir, entry.fileName)

    /** Where a book with content [hash] is stored. */
    fun fileFor(hash: String, isPdf: Boolean): File {
        dir.mkdirs()
        return File(dir, hash + if (isPdf) ".pdf" else ".epub")
    }

    /** The books whose files still exist, most recent first. */
    @Synchronized
    fun entries(): List<Entry> = load().filter { file(it).exists() }

    /** Puts the book stored in [file] on top, and drops (and deletes) books beyond [MAX_BOOKS]. */
    @Synchronized
    fun add(file: File, title: String) {
        val list = load().filter { it.fileName != file.name }.toMutableList()
        list.add(0, Entry(file.name, title))
        while (list.size > MAX_BOOKS) file(list.removeAt(list.size - 1)).delete()
        save(list)
    }

    /** Moves a book to the top: it was just opened. */
    @Synchronized
    fun touch(entry: Entry) = add(file(entry), entry.title)

    /** Takes a book out of the library and deletes its file (its reading position stays saved). */
    @Synchronized
    fun remove(entry: Entry) {
        save(load().filter { it.fileName != entry.fileName })
        file(entry).delete()
    }

    private fun load(): List<Entry> = try {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Entry(o.getString("file"), o.getString("title"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(list: List<Entry>) {
        val array = JSONArray()
        for (e in list) array.put(JSONObject().put("file", e.fileName).put("title", e.title))
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    companion object {
        const val MAX_BOOKS = 10
        private const val KEY = "library"
    }
}
