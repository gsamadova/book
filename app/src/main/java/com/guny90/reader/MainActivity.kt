package com.guny90.reader

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.KeyEvent
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.guny90.reader.dict.DefinitionText
import com.guny90.reader.dict.Dictionaries
import java.io.File
import java.util.concurrent.Executors

/**
 * Single-screen reader. All file / parsing / layout work happens on one worker thread;
 * the UI thread only swaps the finished page in and redraws once.
 */
class MainActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor()
    /** Separate thread so a lookup never waits behind a chapter load. */
    private val dictWorker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var pageView: PageView

    private val bookFile by lazy { File(filesDir, "current.epub") }
    private val dictionaries by lazy { Dictionaries(File(filesDir, "dicts")) }

    // UI-thread state
    private var book: EpubBook? = null
    private var bookKey = ""
    private var chapter = 0
    private var chapterText: CharSequence? = null
    private var pages: Pages? = null
    private var page = 0
    private var pendingOffset = 0
    private var fontSp = 20
    private var paragraphSpacing = 1 // index into Pages.PARAGRAPH_SPACINGS
    private var loading = false
    private var jobId = 0
    private var laidOutW = 0
    private var laidOutH = 0
    private var popup: LookupPopup? = null
    private var lookupId = 0
    private var popupDict: Dictionaries.Info? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setWindowAnimations(0)
        prefs = getSharedPreferences("reader", MODE_PRIVATE)
        fontSp = prefs.getInt("font_sp", 20)
        paragraphSpacing = prefs.getInt("paragraph_spacing", 1).coerceIn(0, Pages.PARAGRAPH_SPACINGS.lastIndex)

        pageView = PageView(this)
        setContentView(pageView)
        ViewCompat.setOnApplyWindowInsetsListener(pageView) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        pageView.onTap = { zone ->
            when (zone) {
                PageView.Zone.PREV -> prevPage()
                PageView.Zone.NEXT -> nextPage()
                PageView.Zone.MENU -> showMenu()
            }
        }
        pageView.onTextAreaLaidOut = ::onTextAreaLaidOut
        pageView.onTextSelected = ::onTextSelected

        val uri = intent?.data
        if (uri != null && savedInstanceState == null) openUri(uri) else restoreLastBook()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { openUri(it) }
    }

    override fun onDestroy() {
        val b = book
        worker.execute { b?.close() }
        worker.shutdown()
        dictWorker.execute { dictionaries.closeAll() }
        dictWorker.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- opening books

    private fun pickBook() {
        val open = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*") // many devices don't know the epub mime type
        try {
            startActivityForResult(open, REQUEST_OPEN)
        } catch (e: ActivityNotFoundException) {
            // Some e-ink firmwares strip the system document picker.
            try {
                startActivityForResult(Intent.createChooser(
                    Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                    "Open book"
                ), REQUEST_OPEN)
            } catch (e2: ActivityNotFoundException) {
                pageView.showMessage("No file picker found on this device.\nOpen an .epub from your file manager with Reader.")
            }
        }
    }

    @Deprecated("Plain Activity API; fine for this minimal app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQUEST_OPEN -> data.data?.let { openUri(it) }
            REQUEST_DICTIONARY -> {
                val clip = data.clipData
                val uris = if (clip != null) (0 until clip.itemCount).map { clip.getItemAt(it).uri }
                else listOfNotNull(data.data)
                if (uris.isNotEmpty()) installDictionaries(uris)
            }
        }
    }

    /** Copies the chosen file into private storage so it can be reopened later without permissions. */
    private fun openUri(uri: Uri) {
        val id = ++jobId
        loading = true
        pageView.showMessage("Opening…")
        worker.execute {
            try {
                val tmp = File(filesDir, "incoming.epub")
                val input = contentResolver.openInputStream(uri) ?: error("Cannot read file")
                input.use { inp -> tmp.outputStream().use { inp.copyTo(it, 64 * 1024) } }
                EpubBook.open(tmp).close() // validate before replacing the current book
                if (!tmp.renameTo(bookFile)) {
                    tmp.copyTo(bookFile, overwrite = true)
                    tmp.delete()
                }
                val opened = EpubBook.open(bookFile)
                mainHandler.post {
                    if (id == jobId) setBook(opened) else worker.execute { opened.close() }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    if (id == jobId) {
                        loading = false
                        pageView.showMessage("Could not open this book.\n${e.message ?: e.javaClass.simpleName}\n\nTap the middle to try another.")
                    }
                }
            }
        }
    }

    private fun restoreLastBook() {
        if (!bookFile.exists()) {
            pageView.showMessage("Reader\n\nTap the middle of the screen\nto open an .epub book.")
            return
        }
        val id = ++jobId
        loading = true
        pageView.showMessage("Opening…")
        worker.execute {
            try {
                val opened = EpubBook.open(bookFile)
                mainHandler.post { if (id == jobId) setBook(opened) else worker.execute { opened.close() } }
            } catch (e: Exception) {
                mainHandler.post {
                    loading = false
                    pageView.showMessage("Tap the middle of the screen\nto open an .epub book.")
                }
            }
        }
    }

    private fun setBook(newBook: EpubBook) {
        val old = book
        if (old != null) worker.execute { old.close() }
        book = newBook
        bookKey = "pos_" + (newBook.title + "|" + bookFile.length()).hashCode()
        chapterText = null
        pages = null
        val saved = prefs.getString(bookKey, null)?.split(':')
        val ch = saved?.getOrNull(0)?.toIntOrNull() ?: 0
        val offset = saved?.getOrNull(1)?.toIntOrNull() ?: 0
        loadChapter(ch, offset)
    }

    // ---------------------------------------------------------------- layout & paging

    /**
     * Loads (or re-paginates) a chapter and shows the page containing [offset].
     * Int.MAX_VALUE means "last page" (used when paging backwards).
     */
    private fun loadChapter(index: Int, offset: Int) {
        val b = book ?: return
        val target = index.coerceIn(0, b.spine.size - 1)
        val w = pageView.textWidth
        val h = pageView.textHeight
        val cached = if (target == chapter) chapterText else null
        chapter = target
        pendingOffset = offset
        if (w <= 0 || h <= 0) {
            loading = true // the view isn't measured yet; onTextAreaLaidOut will call us again
            return
        }
        laidOutW = w
        laidOutH = h
        val id = ++jobId
        loading = true
        val spacing = Pages.PARAGRAPH_SPACINGS[paragraphSpacing].second
        val textPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, fontSp.toFloat(), resources.displayMetrics
        )
        worker.execute {
            try {
                val text = cached ?: HtmlText.toText(b.readChapter(target))
                val result = Pages.paginate(text, w, h, textPx, spacing)
                mainHandler.post {
                    if (id != jobId) return@post
                    chapterText = text
                    pages = result
                    loading = false
                    showPage(result.pageForOffset(offset))
                }
            } catch (e: Exception) {
                mainHandler.post {
                    if (id != jobId) return@post
                    loading = false
                    pageView.showMessage("Could not read chapter ${target + 1}.\n${e.message ?: ""}")
                }
            }
        }
    }

    /** Position to keep when re-paginating. While a chapter is loading, that's the load target. */
    private fun currentOffset(): Int {
        val p = pages
        return if (p != null && !loading) p.offsetOf(page) else pendingOffset
    }

    private fun onTextAreaLaidOut() {
        val w = pageView.textWidth
        val h = pageView.textHeight
        if (book == null || w <= 0 || h <= 0 || (w == laidOutW && h == laidOutH)) return
        loadChapter(chapter, currentOffset())
    }

    private fun showPage(newPage: Int) {
        val p = pages ?: return
        val b = book ?: return
        page = newPage.coerceIn(0, p.count - 1)
        pageView.showPage(
            p, page,
            statusLeft = b.title,
            statusRight = "${page + 1}/${p.count}   ch ${chapter + 1}/${b.spine.size}",
        )
        prefs.edit().putString(bookKey, "$chapter:${p.offsetOf(page)}").apply()
    }

    private fun nextPage() {
        val p = pages ?: return
        if (loading) return
        when {
            page + 1 < p.count -> showPage(page + 1)
            chapter + 1 < (book?.spine?.size ?: 0) -> loadChapter(chapter + 1, 0)
        }
    }

    private fun prevPage() {
        if (pages == null || loading) return
        when {
            page > 0 -> showPage(page - 1)
            chapter > 0 -> loadChapter(chapter - 1, Int.MAX_VALUE)
        }
    }

    private fun changeFont(deltaSp: Int) {
        val newSize = (fontSp + deltaSp).coerceIn(12, 40)
        if (newSize == fontSp) return
        fontSp = newSize
        prefs.edit().putInt("font_sp", fontSp).apply()
        loadChapter(chapter, currentOffset())
    }

    // ---------------------------------------------------------------- menu & keys

    private fun showMenu() {
        val b = book
        val actions = ArrayList<Pair<String, () -> Unit>>()
        actions += "Open book…" to ::pickBook
        actions += "Dictionaries" to ::showDictionaries
        if (b != null) {
            actions += "Contents" to ::showContents
            actions += "Larger text (${fontSp + 2})" to { changeFont(+2) }
            actions += "Smaller text (${fontSp - 2})" to { changeFont(-2) }
            actions += "Paragraph spacing: ${Pages.PARAGRAPH_SPACINGS[paragraphSpacing].first}" to ::chooseParagraphSpacing
        }
        showList(b?.title ?: "Reader", actions.map { it.first }) { actions[it].second() }
    }

    private fun chooseParagraphSpacing() {
        val names = Pages.PARAGRAPH_SPACINGS.map { it.first }.toTypedArray()
        showPlain(
            AlertDialog.Builder(this)
                .setTitle("Paragraph spacing")
                .setSingleChoiceItems(names, paragraphSpacing) { dialog, which ->
                    dialog.dismiss()
                    if (which != paragraphSpacing) {
                        paragraphSpacing = which
                        prefs.edit().putInt("paragraph_spacing", which).apply()
                        loadChapter(chapter, currentOffset()) // same text, new layout: position is kept
                    }
                }
        )
    }

    private fun showContents() {
        val b = book ?: return
        val entries = b.toc.ifEmpty {
            b.spine.indices.map { EpubBook.TocEntry("Chapter ${it + 1}", it) }
        }
        showList("Contents", entries.map { it.title }) { loadChapter(entries[it].spineIndex, 0) }
    }

    private fun showList(title: String, items: List<String>, onCancel: (() -> Unit)? = null, onPick: (Int) -> Unit) {
        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items.toTypedArray()) { _, which -> onPick(which) }
        if (onCancel != null) builder.setOnCancelListener { onCancel() }
        showPlain(builder)
    }

    /** Shows a dialog without animation or gray dimming (each costs an extra e-ink refresh). */
    private fun showPlain(builder: AlertDialog.Builder): AlertDialog {
        val dialog = builder.create()
        dialog.window?.apply {
            setWindowAnimations(0)
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dialog.show()
        return dialog
    }

    private fun showMessage(title: String, message: String) {
        showPlain(AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null))
    }

    // ---------------------------------------------------------------- dictionaries

    private fun showDictionaries() {
        val dicts = dictionaries.list()
        val builder = AlertDialog.Builder(this)
            .setTitle(if (dicts.isEmpty()) "Dictionaries" else "Dictionaries (tap to remove)")
            .setPositiveButton("Add…") { _, _ -> pickDictionary() }
            .setNegativeButton("Close", null)
        if (dicts.isEmpty()) {
            builder.setMessage(
                "No dictionaries yet.\n\nTap Add… and select the StarDict files together " +
                    "(.ifo, .idx and .dict or .dict.dz), or one .zip / .tar.gz archive."
            )
        } else {
            builder.setItems(dicts.map { "${it.name}  (${it.words} words)" }.toTypedArray()) { _, i ->
                confirmRemove(dicts[i])
            }
        }
        showPlain(builder)
    }

    private fun confirmRemove(dict: Dictionaries.Info) {
        showPlain(
            AlertDialog.Builder(this)
                .setTitle("Remove dictionary?")
                .setMessage(dict.name)
                .setPositiveButton("Remove") { _, _ ->
                    dictWorker.execute {
                        dictionaries.remove(dict.id)
                        mainHandler.post(::showDictionaries)
                    }
                }
                .setNegativeButton("Cancel", null)
        )
    }

    private fun pickDictionary() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            startActivityForResult(intent, REQUEST_DICTIONARY)
        } catch (e: ActivityNotFoundException) {
            try {
                startActivityForResult(
                    Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true),
                    REQUEST_DICTIONARY,
                )
            } catch (e2: ActivityNotFoundException) {
                showMessage("Dictionaries", "No file picker found on this device.")
            }
        }
    }

    private fun installDictionaries(uris: List<Uri>) {
        val busy = showPlain(AlertDialog.Builder(this).setMessage("Adding dictionary…").setCancelable(false))
        dictWorker.execute {
            val temp = File(cacheDir, "dict-import")
            val (title, message) = try {
                temp.deleteRecursively()
                temp.mkdirs()
                val files = uris.mapIndexed { i, uri ->
                    val name = displayName(uri) ?: "file$i"
                    val copy = File(temp, "$i-${name.substringAfterLast('/')}")
                    val input = contentResolver.openInputStream(uri) ?: error("Cannot read $name")
                    input.use { inp -> copy.outputStream().use { inp.copyTo(it, 64 * 1024) } }
                    name to copy
                }
                val names = dictionaries.install(files)
                "Dictionary added" to names.joinToString("\n")
            } catch (e: Dictionaries.UserError) {
                "Could not add dictionary" to (e.message ?: "")
            } catch (e: Exception) {
                "Could not add dictionary" to (e.message ?: e.javaClass.simpleName)
            } finally {
                temp.deleteRecursively()
            }
            mainHandler.post {
                busy.dismiss()
                showMessage(title, message)
            }
        }
    }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    } ?: uri.lastPathSegment?.substringAfterLast('/')

    private val dictPrefKey get() = bookKey.replaceFirst("pos_", "dict_")

    // ---------------------------------------------------------------- selection actions

    /** After a selection: Dictionary (one word only), Google Translate (if installed), Copy. */
    private fun onTextSelected(text: String, singleWord: Boolean, centerY: Int) {
        val actions = ArrayList<Pair<String, () -> Unit>>()
        if (singleWord && dictionaries.list().isNotEmpty()) actions += "Dictionary" to { lookUpWord(text, centerY) }
        translateIntent(text)?.let { intent -> actions += "Google Translate" to { openTranslate(intent) } }
        actions += "Copy" to { copyText(text) }
        val title = if (text.length > 60) "“" + text.take(57).trimEnd() + "…”" else "“$text”"
        showList(title, actions.map { it.first }, onCancel = { pageView.clearSelection() }) { actions[it].second() }
    }

    /**
     * Google Translate's "translate selected text" screen (PROCESS_TEXT), falling back to sharing
     * the text with it. Null if the app isn't installed. Translate needs internet unless
     * the languages were downloaded for offline use in the Translate app.
     */
    private fun translateIntent(text: String): Intent? {
        val process = Intent(Intent.ACTION_PROCESS_TEXT)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
            .setPackage(GOOGLE_TRANSLATE)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
            .setPackage(GOOGLE_TRANSLATE)
        return listOf(process, send).firstOrNull { packageManager.queryIntentActivities(it, 0).isNotEmpty() }
    }

    private fun openTranslate(intent: Intent) {
        pageView.clearSelection()
        try {
            startActivity(intent)
        } catch (e: Exception) {
            showMessage("Google Translate", "Could not open Google Translate.\n${e.message ?: ""}")
        }
    }

    private fun copyText(text: String) {
        pageView.clearSelection()
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Reader", text))
        // Android 13+ shows its own "copied" confirmation.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    /** Dictionary flow: pick the book's dictionary (asking once if there are several), then look up. */
    private fun lookUpWord(word: String, centerY: Int) {
        val dicts = dictionaries.list()
        if (dicts.isEmpty()) {
            pageView.clearSelection()
            showPlain(
                AlertDialog.Builder(this)
                    .setTitle("No dictionary")
                    .setMessage("Add a StarDict dictionary to look up words.")
                    .setPositiveButton("Add…") { _, _ -> pickDictionary() }
                    .setNegativeButton("Close", null)
            )
            return
        }
        val saved = prefs.getString(dictPrefKey, null)
        val chosen = dicts.firstOrNull { it.id == saved } ?: dicts.singleOrNull()
        if (chosen != null) {
            lookUp(chosen, word, centerY)
        } else {
            chooseDictionary(dicts, onCancel = { pageView.clearSelection() }) { lookUp(it, word, centerY) }
        }
    }

    private fun chooseDictionary(
        dicts: List<Dictionaries.Info>,
        onCancel: (() -> Unit)? = null,
        onChosen: (Dictionaries.Info) -> Unit,
    ) {
        showList("Dictionary for this book", dicts.map { it.name }, onCancel) { i ->
            prefs.edit().putString(dictPrefKey, dicts[i].id).apply()
            onChosen(dicts[i])
        }
    }

    private fun lookUp(dict: Dictionaries.Info, word: String, centerY: Int) {
        val id = ++lookupId
        dictWorker.execute {
            val content = try {
                val result = dictionaries.lookup(dict.id, word)
                if (result.definitions.isNotEmpty()) {
                    LookupPopup.Content(
                        title = result.definitions.first().word,
                        body = DefinitionText.format(result.definitions),
                        dictName = result.dictName,
                    )
                } else {
                    LookupPopup.Content(
                        title = word,
                        body = null,
                        note = if (result.suggestions.isEmpty()) "Not found." else "Not found. Nearest entries:",
                        suggestions = result.suggestions,
                        dictName = result.dictName,
                    )
                }
            } catch (e: Exception) {
                LookupPopup.Content(word, null, note = "Dictionary error: ${e.message}", dictName = dict.name)
            }
            mainHandler.post { if (id == lookupId) showLookup(content, dict, word, centerY) }
        }
    }

    private fun showLookup(content: LookupPopup.Content, dict: Dictionaries.Info, word: String, centerY: Int) {
        popupDict = dict
        val current = popup
        if (current != null && current.isShowing) {
            current.update(content)
            return
        }
        val newPopup = LookupPopup(
            this,
            centerY,
            onSuggestion = { suggestion -> lookUp(popupDict ?: dict, suggestion, centerY) },
            onChangeDictionary = {
                chooseDictionary(dictionaries.list()) { chosen -> lookUp(chosen, word, centerY) }
            },
            onDismiss = {
                popup = null
                pageView.clearSelection()
            },
        )
        popup = newPopup
        newPopup.show(content)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_SPACE -> nextPage()
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> prevPage()
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> showMenu()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    companion object {
        private const val REQUEST_OPEN = 1
        private const val REQUEST_DICTIONARY = 2
        private const val GOOGLE_TRANSLATE = "com.google.android.apps.translate"
    }
}
