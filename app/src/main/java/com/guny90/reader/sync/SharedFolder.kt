package com.guny90.reader.sync

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * A folder that another app (Syncthing, FolderSync, …) keeps the same on all your devices.
 * Books opened on this device are copied into it, and books found in it are offered in the
 * library, so a book opened on one device becomes available on the others.
 *
 * Only the folder's top level is used. All calls but [uri], [set] and [clear] read or write
 * files: run them on a background thread, one at a time.
 */
class SharedFolder(context: Context, private val prefs: SharedPreferences) {

    /** A book file in the folder. [hash] is its [KoSync.partialMd5], the same id the library uses. */
    class Book(val uri: Uri, val name: String, val hash: String, val modified: Long) {
        val title get() = name.substringBeforeLast('.')
        val isPdf get() = name.endsWith(".pdf", ignoreCase = true)
    }

    private val resolver: ContentResolver = context.contentResolver
    /** documentId → "size|modified|hash", so unchanged files aren't read again. */
    private val hashes: SharedPreferences = context.getSharedPreferences("shared_folder_hashes", Context.MODE_PRIVATE)

    val uri: Uri? get() = prefs.getString(KEY, null)?.let(Uri::parse)
    val isEnabled get() = uri != null
    /** The folder's name when it was chosen. */
    val displayName: String get() = prefs.getString(NAME_KEY, null) ?: "Shared folder"

    /** Uses the folder picked with ACTION_OPEN_DOCUMENT_TREE, keeping access to it across restarts. */
    fun set(tree: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        resolver.takePersistableUriPermission(tree, flags)
        val old = uri
        if (old != null && old != tree) release(old)
        prefs.edit().putString(KEY, tree.toString()).apply()
        prefs.edit().putString(NAME_KEY, name() ?: "Shared folder").apply()
        hashes.edit().clear().apply()
    }

    fun clear() {
        uri?.let(::release)
        prefs.edit().remove(KEY).remove(NAME_KEY).apply()
        hashes.edit().clear().apply()
    }

    private fun release(tree: Uri) = try {
        resolver.releasePersistableUriPermission(
            tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    } catch (e: SecurityException) {
        // already gone
    }

    /** The folder's name as the file manager shows it, or null if it can't be reached. */
    fun name(): String? {
        val tree = uri ?: return null
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return try {
            resolver.query(doc, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** The EPUB and PDF books in the folder, most recently changed first. Throws if the folder can't be read. */
    fun books(): List<Book> {
        val tree = uri ?: return emptyList()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_MIME_TYPE,
        )
        val found = ArrayList<Book>()
        val seen = HashSet<String>()
        val cursor = resolver.query(children, columns, null, null, null) ?: error("Can't read the shared folder")
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                if (c.getString(4) == Document.MIME_TYPE_DIR || !isBookName(name)) continue
                val size = c.getLong(2)
                val modified = c.getLong(3)
                val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                val hash = cachedHash(id, size, modified) ?: try {
                    hashOf(docUri).also { hashes.edit().putString(id, "$size|$modified|$it").apply() }
                } catch (e: Exception) {
                    continue // being written, or unreadable
                }
                seen += id
                found += Book(docUri, name, hash, modified)
            }
        }
        val stale = hashes.all.keys - seen
        if (stale.isNotEmpty()) hashes.edit().apply { stale.forEach { remove(it) } }.apply()
        return found.sortedByDescending { it.modified }
    }

    private fun cachedHash(id: String, size: Long, modified: Long): String? {
        val parts = hashes.getString(id, null)?.split('|') ?: return null
        return if (parts.size == 3 && parts[0] == "$size" && parts[1] == "$modified") parts[2] else null
    }

    private fun hashOf(doc: Uri): String {
        val pfd = resolver.openFileDescriptor(doc, "r") ?: error("Can't read $doc")
        return pfd.use {
            FileInputStream(it.fileDescriptor).channel.use { ch ->
                KoSync.partialMd5(ch.size()) { offset, buffer -> ch.read(ByteBuffer.wrap(buffer), offset) }
            }
        }
    }

    /**
     * Copies a library book into the folder, unless a book with the same [hash] is there already
     * ([present], from [books]). Written under a temporary name first, so other devices never
     * pick up half a book.
     */
    fun add(file: File, title: String, hash: String, isPdf: Boolean, present: List<Book> = books()) {
        if (present.any { it.hash == hash }) return
        val tree = uri ?: return
        val folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val name = fileName(title, isPdf)
        val partial = DocumentsContract.createDocument(resolver, folder, "application/octet-stream", "$name.part")
            ?: error("Can't write to the shared folder")
        try {
            val out = resolver.openOutputStream(partial, "w") ?: error("Can't write to the shared folder")
            out.use { o -> file.inputStream().use { it.copyTo(o, 64 * 1024) } }
            val done = DocumentsContract.renameDocument(resolver, partial, uniqueName(name, present.map { it.name }))
                ?: error("Can't rename in the shared folder")
            hashes.edit().remove(DocumentsContract.getDocumentId(done)).apply()
        } catch (e: Exception) {
            try { DocumentsContract.deleteDocument(resolver, partial) } catch (_: Exception) {}
            throw e
        }
    }

    fun delete(book: Book) {
        DocumentsContract.deleteDocument(resolver, book.uri)
    }

    companion object {
        private const val KEY = "shared_folder"
        private const val NAME_KEY = "shared_folder_name"

        fun isBookName(name: String) =
            !name.startsWith(".") && (name.endsWith(".epub", true) || name.endsWith(".pdf", true))

        /** A file name for a book: its title, without characters file systems or sync apps reject. */
        fun fileName(title: String, isPdf: Boolean): String {
            val clean = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
                .replace(Regex("\\s+"), " ").trim().trimEnd('.').take(120).trim()
            return clean.ifEmpty { "Book" } + if (isPdf) ".pdf" else ".epub"
        }

        /** [name], or "Title (2).epub" and so on if a different book already has that name. */
        fun uniqueName(name: String, present: List<String>): String {
            val taken = present.map { it.lowercase() }.toSet()
            if (name.lowercase() !in taken) return name
            val base = name.substringBeforeLast('.')
            val ext = name.substringAfterLast('.')
            var n = 2
            while ("$base ($n).$ext".lowercase() in taken) n++
            return "$base ($n).$ext"
        }
    }
}
