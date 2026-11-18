package com.guny90.reader

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.util.TypedValue
import android.text.InputType
import android.text.format.DateUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.guny90.reader.dict.DefinitionText
import com.guny90.reader.dict.Dictionaries
import com.guny90.reader.sync.KoSync
import com.guny90.reader.sync.PositionSync
import com.guny90.reader.sync.SharedFolder
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Single-screen reader. All file / parsing / layout work happens on one worker thread;
 * the UI thread only swaps the finished page in and redraws once.
 */
class MainActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor()
    /** Separate thread so a lookup never waits behind a chapter load. */
    private val dictWorker = Executors.newSingleThreadExecutor()
    /** Network calls for position sync; never blocks reading. */
    private val syncWorker = Executors.newSingleThreadExecutor()
    /** Reads the words of PDF pages (for selecting them) without delaying page rendering. */
    private val wordsWorker = Executors.newSingleThreadExecutor()
    /** Reads and writes the shared books folder, so a big copy never delays reading or the library. */
    private val folderWorker = Executors.newSingleThreadExecutor()
    private val sharedFolder by lazy { SharedFolder(this, prefs) }
    private val positionSync by lazy { PositionSync(prefs, deviceName()) }
    /** Book fingerprint (same on every device), used as the sync document id. */
    private var bookHash: String? = null
    private var syncCheckPending = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var pageView: PageView

    private val library by lazy { Library(File(filesDir, "library"), prefs) }
    /** The open book's file (EPUB or PDF), in the library. */
    private var currentFile: File? = null
    private val dictionaries by lazy { Dictionaries(File(filesDir, "dicts")) }

    // UI-thread state
    private var book: EpubBook? = null
    /** Open PDF (instead of [book]); its pages are shown as pictures. */
    private var pdf: PdfBook? = null
    private var pdfPage = 0
    /** Reads the open PDF's words, page by page (used on [wordsWorker] only). */
    private var pdfWords: PdfWords? = null
    /** The page whose words are wanted; older requests are skipped (the reader moved on). */
    @Volatile private var wantedWordsPage = -1
    private var bookKey = ""
    private var chapter = 0
    private var chapterText: CharSequence? = null
    /** Link targets (ids) of [chapterText]: their character offsets. */
    private var chapterAnchors: Map<String, Int> = emptyMap()
    /** Where to return to after following links (chapter, offset), newest last. */
    private val backStack = ArrayList<Pair<Int, Int>>()
    /** The chapter last read for showing a note from another chapter (worker thread only). */
    private var noteChapter: Pair<Int, HtmlText.Chapter>? = null
    private var pages: Pages? = null
    private var page = 0
    private var pendingOffset = 0
    private var fontSp = 20
    private var paragraphSpacing = 1 // index into Pages.PARAGRAPH_SPACINGS
    private var oneWordAction = OneWord.DICTIONARY
    private var severalWordsAction = SeveralWords.TRANSLATE
    private var showPictures = true
    /** Pictures and PDF pages drawn in gray rather than their own colors. */
    private var grayPictures = true
    /** Latest page waiting for its pictures to be decoded. */
    private var pictureJob = 0
    /** Pictures holding a decoded bitmap, oldest first (worker thread only). */
    private val decodedPictures = ArrayDeque<PictureSpan>()

    /** What a selection does on release; ASK shows the action list. */
    private enum class OneWord(val label: String) { DICTIONARY("Dictionary"), TRANSLATE("Google Translate"), ASK("Ask") }
    private enum class SeveralWords(val label: String) { TRANSLATE("Google Translate"), COPY("Copy"), ASK("Ask") }
    private var loading = false
    private lateinit var root: LinearLayout
    /** Index into [Fonts.names]. */
    private var fontIndex = 0
    /** In night mode, PDF pages are inverted (good for text, odd for pictures). */
    private var invertPdfAtNight = true
    @Volatile private var jobId = 0 // also read on the worker, to skip superseded PDF renders
    private var laidOutW = 0
    private var laidOutH = 0
    private var popup: LookupPopup? = null
    private var lookupId = 0
    private var popupDict: Dictionaries.Info? = null

    // Read aloud
    private var speech: ReadAloud? = null
    /** The read-aloud bar is shown (playing or paused). */
    private var readingAloud = false
    private var speaking = false
    /** The sentence being (or last) read, in the current chapter. */
    private var spokenRange: IntRange? = null
    /** Start speaking at the top of the page once the chapter being loaded is shown. */
    private var speakAfterLoad = false
    private var speechRate = 1f
    private lateinit var controls: LinearLayout
    private lateinit var playButton: TextView
    private lateinit var rateLabel: TextView
    private val hasSpeechEngine by lazy {
        packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0).isNotEmpty()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setWindowAnimations(0)
        prefs = getSharedPreferences("reader", MODE_PRIVATE)
        fontSp = prefs.getInt("font_sp", 20)
        paragraphSpacing = prefs.getInt("paragraph_spacing", 1).coerceIn(0, Pages.PARAGRAPH_SPACINGS.lastIndex)
        oneWordAction = OneWord.entries.firstOrNull { it.name == prefs.getString("one_word_action", null) }
            ?: OneWord.DICTIONARY
        severalWordsAction = SeveralWords.entries.firstOrNull { it.name == prefs.getString("several_words_action", null) }
            ?: SeveralWords.TRANSLATE
        speechRate = prefs.getFloat("speech_rate", 1f)
        showPictures = prefs.getBoolean("pictures", true)
        grayPictures = prefs.getBoolean("gray_pictures", true)
        Ink.night = prefs.getBoolean("night", false)
        invertPdfAtNight = prefs.getBoolean("night_invert_pdf", true)
        fontIndex = prefs.getInt("font", 0).coerceIn(0, Fonts.names.lastIndex)
        PictureSpan.setColors(grayPictures, Ink.night && invertPdfAtNight)
        volumeControlStream = AudioManager.STREAM_MUSIC
        removeOldPdfText()

        pageView = PageView(this)
        controls = buildReadAloudBar()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ink.paper)
            // Showing the bar shrinks the page; the chapter is re-paginated, keeping the position.
            addView(pageView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(controls, MATCH_PARENT, WRAP_CONTENT)
        }
        setContentView(root)
        updateSystemBars()
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        pageView.onTap = { zone ->
            if (speaking) pauseReading() // any tap pauses reading aloud
            else when (zone) {
                PageView.Zone.PREV -> prevPage()
                PageView.Zone.NEXT -> nextPage()
                PageView.Zone.MENU -> showMenu()
            }
        }
        pageView.onTextAreaLaidOut = ::onTextAreaLaidOut
        pageView.onTextSelected = ::onTextSelected
        pageView.onPictureLongPress = ::openPicture
        pageView.onLinkTap = { link -> if (speaking) pauseReading() else followLink(link) }
        pageView.onNoWords = {
            Toast.makeText(this, "No text to select on this page (it may be a scanned picture).", Toast.LENGTH_SHORT).show()
        }

        val uri = intent?.data
        if (uri != null && savedInstanceState == null) openUri(uri) else restoreLastBook()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { openUri(it) }
    }

    override fun onResume() {
        super.onResume()
        // Back from another app or screen turned on: another device may have read on.
        if ((book != null || pdf != null) && !syncCheckPending) checkRemotePosition()
    }

    override fun onPause() {
        super.onPause()
        if (speaking) pauseReading() // no background reading: stops with the screen
        pushPosition() // leaving the app or screen turning off
    }

    override fun onDestroy() {
        val b = book
        val d = pdf
        val words = pdfWords
        worker.execute {
            b?.close()
            d?.close()
        }
        worker.shutdown()
        wantedWordsPage = -1
        wordsWorker.execute { words?.close() }
        wordsWorker.shutdown()
        dictWorker.execute { dictionaries.closeAll() }
        dictWorker.shutdown()
        syncWorker.shutdown() // lets a pending upload finish
        folderWorker.shutdown() // lets a copy to the shared folder finish
        speech?.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- opening books

    /**
     * The books opened on this device, most recent first, below "Add a new book…", then the
     * books in the shared folder that aren't on this device yet.
     */
    private fun showLibrary() {
        if (!sharedFolder.isEnabled) return showLibrary(emptyList(), null)
        folderWorker.execute {
            val (shared, error) = try {
                sharedFolder.books() to null
            } catch (e: Exception) {
                emptyList<SharedFolder.Book>() to e
            }
            mainHandler.post { if (!isFinishing) showLibrary(shared, error) }
        }
    }

    private fun showLibrary(shared: List<SharedFolder.Book>, folderError: Exception?) {
        if (folderError != null) {
            Toast.makeText(this, "Can't read the shared folder: ${folderError.message ?: folderError.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
        val entries = library.entries()
        val here = entries.map { it.fileName.substringBeforeLast('.') }.toSet()
        val onlyShared = shared.filter { it.hash !in here }
        if (entries.isEmpty() && onlyShared.isEmpty()) return pickBook() // nothing to choose from yet
        val open = currentFile?.name
        val items = listOf("Add a new book…") +
            entries.map { e -> (if (e.fileName == open) "\u25B8 " else "") + e.title + (if (e.isPdf) " (PDF)" else "") } +
            onlyShared.map { b -> "\u21E3 " + b.title + (if (b.isPdf) " (PDF)" else "") }
        val dialog = showPlain(
            dialog()
                .setTitle("Library (long-press to remove)")
                .setItems(items.toTypedArray()) { _, i ->
                    when {
                        i == 0 -> pickBook()
                        i <= entries.size -> if (entries[i - 1].fileName != open) openFromLibrary(entries[i - 1])
                        else -> openUri(onlyShared[i - 1 - entries.size].uri)
                    }
                }
        )
        dialog.listView.setOnItemLongClickListener { _, _, i, _ ->
            when {
                i == 0 -> return@setOnItemLongClickListener false
                i <= entries.size -> {
                    val entry = entries[i - 1]
                    if (entry.fileName == open) {
                        Toast.makeText(this, "This book is open. Open another book first, then remove it.", Toast.LENGTH_LONG).show()
                    } else {
                        dialog.dismiss()
                        confirmRemove(entry)
                    }
                }
                else -> {
                    dialog.dismiss()
                    confirmDeleteShared(onlyShared[i - 1 - entries.size])
                }
            }
            true
        }
    }

    private fun confirmDeleteShared(book: SharedFolder.Book) {
        showPlain(
            dialog()
                .setTitle("Delete from the shared folder?")
                .setMessage("${book.name}\n\nThe sync app will delete it on your other devices' folders too. Books already in a device's library stay there.")
                .setPositiveButton("Delete") { _, _ ->
                    folderWorker.execute {
                        val error = try { sharedFolder.delete(book); null } catch (e: Exception) { e }
                        mainHandler.post {
                            if (error != null) showMessage("Could not delete", error.message ?: error.javaClass.simpleName)
                            else showLibrary()
                        }
                    }
                }
                .setNegativeButton("Cancel") { _, _ -> showLibrary() }
                .setOnCancelListener { showLibrary() }
        )
    }

    /** Copies a book just added to the library into the shared folder, if one is set. */
    private fun shareBook(file: File, title: String, hash: String, isPdf: Boolean) {
        if (!sharedFolder.isEnabled) return
        folderWorker.execute {
            try {
                sharedFolder.add(file, title, hash, isPdf)
            } catch (e: Exception) {
                mainHandler.post {
                    Toast.makeText(this, "Couldn't copy the book to the shared folder: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun confirmRemove(entry: Library.Entry) {
        showPlain(
            dialog()
                .setTitle("Remove from library?")
                .setMessage("${entry.title}\n\nIts reading position is kept, so if you add it again it opens where you left off.")
                .setPositiveButton("Remove") { _, _ ->
                    worker.execute { // deletes the file: not on the main thread
                        library.remove(entry)
                        val left = library.entries().isNotEmpty()
                        mainHandler.post { if (left) showLibrary() }
                    }
                }
                .setNegativeButton("Cancel") { _, _ -> showLibrary() }
                .setOnCancelListener { showLibrary() }
        )
    }

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
                pageView.showMessage("No file picker found on this device.\nOpen an .epub or .pdf from your file manager with Book.")
            }
        }
    }

    @Deprecated("Plain Activity API; fine for this minimal app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQUEST_OPEN -> data.data?.let { openUri(it) }
            REQUEST_FOLDER -> data.data?.let { useSharedFolder(it) }
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
                val tmp = File(filesDir, "incoming")
                val input = contentResolver.openInputStream(uri) ?: error("Cannot read file")
                input.use { inp -> tmp.outputStream().use { inp.copyTo(it, 64 * 1024) } }
                val isPdf = PdfBook.isPdf(tmp)
                // Validate before adding it to the library.
                if (isPdf) PdfBook.open(tmp, "").close() else EpubBook.open(tmp).close()
                val hash = KoSync.partialMd5(tmp)
                val target = library.fileFor(hash, isPdf)
                if (target.exists()) {
                    tmp.delete() // the same book is already in the library (maybe open right now)
                } else if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                if (isPdf) {
                    // A PDF's title isn't readable without a library: use the file name.
                    val title = displayName(uri)?.let { if (it.endsWith(".pdf", true)) it.dropLast(4) else it }
                        ?.ifBlank { null } ?: "PDF"
                    val opened = PdfBook.open(target, title)
                    library.add(target, title)
                    shareBook(target, title, hash, isPdf = true)
                    mainHandler.post {
                        if (id == jobId) setPdf(opened, hash, target) else worker.execute { opened.close() }
                    }
                } else {
                    val opened = EpubBook.open(target)
                    library.add(target, opened.title)
                    shareBook(target, opened.title, hash, isPdf = false)
                    mainHandler.post {
                        if (id == jobId) setBook(opened, hash, target) else worker.execute { opened.close() }
                    }
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

    /** Opens the most recent book of the library, if any. */
    private fun restoreLastBook() {
        val id = ++jobId
        loading = true
        worker.execute {
            migrateCurrentBook()
            val entry = library.entries().firstOrNull()
            mainHandler.post {
                if (id != jobId) return@post
                loading = false
                if (entry != null) openFromLibrary(entry)
                else pageView.showMessage("Book\n\nTap the middle of the screen\nto open an .epub or .pdf book.")
            }
        }
    }

    /**
     * Worker thread. Before the library, only one book was kept, as current.epub or current.pdf: it becomes the
     * library's first book. Positions are kept, as they don't depend on the file's name.
     */
    private fun migrateCurrentBook() {
        for (old in listOf(File(filesDir, "current.epub"), File(filesDir, "current.pdf"))) {
            if (!old.exists()) continue
            try {
                val isPdf = old.name.endsWith(".pdf")
                val target = library.fileFor(KoSync.partialMd5(old), isPdf)
                if (!old.renameTo(target)) {
                    old.copyTo(target, overwrite = true)
                    old.delete()
                }
                val title = if (isPdf) prefs.getString("pdf_title", null) ?: "PDF"
                else EpubBook.open(target).use { it.title }
                library.add(target, title)
            } catch (e: Exception) {
                // unreadable: leave it; the library starts empty
            }
        }
        prefs.edit().remove("pdf_title").apply()
    }

    private fun openFromLibrary(entry: Library.Entry) {
        val file = library.file(entry)
        val id = ++jobId
        loading = true
        pageView.showMessage("Opening…")
        worker.execute {
            try {
                val hash = KoSync.partialMd5(file)
                if (entry.isPdf) {
                    val opened = PdfBook.open(file, entry.title)
                    mainHandler.post { if (id == jobId) setPdf(opened, hash, file) else worker.execute { opened.close() } }
                } else {
                    val opened = EpubBook.open(file)
                    mainHandler.post { if (id == jobId) setBook(opened, hash, file) else worker.execute { opened.close() } }
                }
                library.touch(entry)
            } catch (e: Exception) {
                mainHandler.post {
                    if (id != jobId) return@post
                    loading = false
                    pageView.showMessage("Could not open ${entry.title}.\n${e.message ?: e.javaClass.simpleName}\n\nTap the middle to open another book.")
                }
            }
        }
    }

    /** Saves the position of the open book (EPUB or PDF) and closes it. */
    private fun closeCurrentBook() {
        stopReadingAloud()
        pushPosition() // the book being replaced
        backStack.clear()
        updateBackHandler()
        chapterAnchors = emptyMap()
        val oldNotes = noteChapter
        if (oldNotes != null) worker.execute { if (noteChapter === oldNotes) noteChapter = null }
        val oldBook = book
        val oldPdf = pdf
        val oldWords = pdfWords
        book = null
        pdf = null
        pdfWords = null
        if (oldBook != null || oldPdf != null) worker.execute {
            oldBook?.close()
            oldPdf?.close()
        }
        if (oldWords != null) {
            wantedWordsPage = -1
            wordsWorker.execute { oldWords.close() }
        }
    }

    /**
     * Deletes what the removed PDF text view left behind: the cached text of a PDF, its
     * per-book setting, and PdfBox temp files of an interrupted run.
     */
    private fun removeOldPdfText() {
        File(filesDir, "current.pdftext").delete()
        File(filesDir, "current.pdftext.tmp").delete()
        val keys = prefs.all.keys.filter { it.startsWith("pdf_text_view_") }
        if (keys.isNotEmpty()) prefs.edit().apply { keys.forEach { remove(it) } }.apply()
        cacheDir.listFiles { f -> f.name.startsWith("PDFBox") }?.forEach { it.delete() }
    }

    private fun setPdf(newPdf: PdfBook, hash: String, file: File) {
        closeCurrentBook()
        pdf = newPdf
        currentFile = file
        pdfWords = PdfWords(applicationContext, file)
        showTitleInRecents(newPdf.title)
        bookKey = "pos_" + (newPdf.title + "|" + file.length()).hashCode()
        chapterText = null
        pages = null
        bookHash = hash
        syncCheckPending = true // after the first page is shown
        val saved = prefs.getString(bookKey, null)?.substringBefore(':')?.toIntOrNull() ?: 0
        showPdfPage(saved)
    }

    private fun setBook(newBook: EpubBook, hash: String, file: File) {
        closeCurrentBook()
        book = newBook
        currentFile = file
        showTitleInRecents(newBook.title)
        bookKey = "pos_" + (newBook.title + "|" + file.length()).hashCode()
        chapterText = null
        pages = null
        val saved = prefs.getString(bookKey, null)?.split(':')
        val ch = saved?.getOrNull(0)?.toIntOrNull() ?: 0
        val offset = saved?.getOrNull(1)?.toIntOrNull() ?: 0
        bookHash = hash
        syncCheckPending = true // after the first page is shown
        loadChapter(ch, offset)
    }

    /** Labels this app's card in the recent-apps screen with the book title instead of "Book". */
    private fun showTitleInRecents(title: String) {
        val description = if (Build.VERSION.SDK_INT >= 33) {
            ActivityManager.TaskDescription.Builder().setLabel(title).build()
        } else {
            @Suppress("DEPRECATION") ActivityManager.TaskDescription(title)
        }
        setTaskDescription(description)
    }

    // ---------------------------------------------------------------- layout & paging

    /**
     * Loads (or re-paginates) a chapter and shows the page containing [offset], or the id
     * [anchor] if the chapter has it. Int.MAX_VALUE means "last page" (used when paging backwards).
     */
    private fun loadChapter(index: Int, offset: Int, anchor: String? = null) {
        val b = book ?: return
        val target = index.coerceIn(0, b.spine.size - 1)
        val w = pageView.textWidth
        val h = pageView.textHeight
        val cached = if (target == chapter) chapterText else null
        val cachedAnchors = chapterAnchors
        if (cached == null && spokenRange != null) {
            spokenRange = null // offsets of another chapter
            pageView.setSpoken(null, redraw = false)
        }
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
        val pictures = showPictures
        val typeface = Fonts.typeface(this, fontIndex)
        // Picture pixels are CSS pixels: as large as on a phone screen, then fitted to the page.
        val pixelScale = resources.displayMetrics.density
        worker.execute {
            try {
                val content = if (cached != null) HtmlText.Chapter(cached, cachedAnchors)
                else HtmlText.toText(b.readChapter(target), b.spine[target])
                val text = content.text
                val at = anchor?.let { findAnchor(content.anchors, it) } ?: offset
                val pictureSize: ((String) -> IntArray?)? = if (pictures) b::pictureSize else null
                val result = Pages.paginate(text, w, h, textPx, spacing, pictureSize, pixelScale, typeface)
                mainHandler.post {
                    if (id != jobId) return@post
                    chapterText = text
                    chapterAnchors = content.anchors
                    pages = result
                    loading = false
                    // While reading aloud, a re-layout (e.g. the bar appearing) keeps the spoken sentence in view.
                    val spoken = spokenRange
                    val showAt = if (speaking && !speakAfterLoad && spoken != null) spoken.first else at
                    // [page] must belong to the new pages right away: showPage sets it only once the
                    // page's pictures are decoded, and the code below (sync check, reading) uses it now.
                    page = result.pageForOffset(showAt)
                    showPage(page)
                    if (speakAfterLoad) {
                        speakAfterLoad = false
                        if (speaking) speakFrom(result.offsetOf(page))
                    }
                    if (syncCheckPending) {
                        syncCheckPending = false
                        checkRemotePosition()
                    }
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
        return if (p != null && !loading) p.offsetOf(page.coerceIn(0, p.count - 1)) else pendingOffset
    }

    private fun onTextAreaLaidOut() {
        if (pdf != null) {
            if (pageView.imageWidth > 0 && pageView.imageHeight > 0 &&
                (pageView.imageWidth != laidOutW || pageView.imageHeight != laidOutH)
            ) showPdfPage(pdfPage)
            return
        }
        val w = pageView.textWidth
        val h = pageView.textHeight
        if (book == null || w <= 0 || h <= 0 || (w == laidOutW && h == laidOutH)) return
        loadChapter(chapter, currentOffset())
    }

    private fun showPage(newPage: Int) {
        val p = pages ?: return
        val b = book ?: return
        val target = newPage.coerceIn(0, p.count - 1)
        // Decode the page's pictures first, so the page appears once, complete (one e-ink refresh).
        val missing = p.picturesToLoad(target)
        val job = ++pictureJob
        if (missing.isNotEmpty()) {
            worker.execute {
                for (picture in missing) decodePicture(b, picture)
                mainHandler.post { if (job == pictureJob && pages === p) showPage(target) }
            }
            return
        }
        page = target
        pageView.showPage(
            p, page,
            statusLeft = b.title,
            statusRight = "${page + 1}/${p.count}   ch ${chapter + 1}/${b.spine.size}",
        )
        prefs.edit().putString(bookKey, "$chapter:${p.offsetOf(page)}").apply()
    }

    /**
     * Renders and shows PDF page [index]. Taps while a page is rendering aren't lost: the newest
     * request wins. The next page is rendered ahead, so paging forward is usually instant.
     */
    private fun showPdfPage(index: Int) {
        val d = pdf ?: return
        val target = index.coerceIn(0, d.pageCount - 1)
        pdfPage = target
        val w = pageView.imageWidth
        val h = pageView.imageHeight
        if (w <= 0 || h <= 0) {
            loading = true // the view isn't measured yet; onTextAreaLaidOut will call us again
            return
        }
        laidOutW = w
        laidOutH = h
        val id = ++jobId
        loading = true
        worker.execute {
            if (id != jobId) return@execute // already superseded by a later tap
            val result = try {
                Result.success(d.page(target, w, h))
            } catch (e: Throwable) { // includes OutOfMemoryError
                Result.failure(e)
            }
            mainHandler.post {
                if (id != jobId || pdf !== d) return@post
                loading = false
                val rendered = result.getOrNull()
                if (rendered != null) {
                    pageView.showImage(rendered, d.title, "${target + 1}/${d.pageCount}")
                    loadWords(target)
                } else {
                    val e = result.exceptionOrNull()
                    pageView.showMessage("Could not show page ${target + 1}.\n${e?.message ?: e?.javaClass?.simpleName ?: ""}")
                }
                prefs.edit().putString(bookKey, "$target:0").apply()
                if (syncCheckPending) {
                    syncCheckPending = false
                    checkRemotePosition()
                }
            }
            if (target + 1 < d.pageCount && id == jobId) {
                try { d.page(target + 1, w, h) } catch (e: Throwable) { } // shown later, or the error then
            }
        }
    }

    /**
     * Reads the words of the PDF page being shown, in the background while it's read, so a
     * long-press can select them. Pages left before their turn came are skipped.
     */
    private fun loadWords(page: Int) {
        val source = pdfWords ?: return
        wantedWordsPage = page
        wordsWorker.execute {
            if (wantedWordsPage != page) return@execute
            val words = try { source.page(page) } catch (e: Throwable) { emptyList() } // incl. OutOfMemoryError
            mainHandler.post { if (pdfWords === source) pageView.setWords(page, words) }
        }
    }

    private fun nextPage() {
        pdf?.let { d ->
            if (pdfPage + 1 < d.pageCount) showPdfPage(pdfPage + 1)
            return
        }
        val p = pages ?: return
        if (loading) return
        when {
            page + 1 < p.count -> showPage(page + 1)
            chapter + 1 < (book?.spine?.size ?: 0) -> loadChapter(chapter + 1, 0)
        }
    }

    private fun prevPage() {
        if (pdf != null) {
            if (pdfPage > 0) showPdfPage(pdfPage - 1)
            return
        }
        if (pages == null || loading) return
        when {
            page > 0 -> showPage(page - 1)
            chapter > 0 -> loadChapter(chapter - 1, Int.MAX_VALUE)
        }
    }

    /** Worker thread. Keeps only the last few decoded pictures in memory. */
    private fun decodePicture(b: EpubBook, picture: PictureSpan) {
        if (picture.bitmap != null || picture.failed) return
        val bitmap = try {
            b.decodePicture(picture.path, picture.width, picture.height)
        } catch (e: Throwable) { // includes OutOfMemoryError: show a frame instead
            null
        }
        if (bitmap == null) {
            picture.failed = true
            return
        }
        picture.bitmap = bitmap
        decodedPictures.addLast(picture)
        while (decodedPictures.size > MAX_DECODED_PICTURES) decodedPictures.removeFirst().bitmap = null
    }

    private fun togglePictures() {
        showPictures = !showPictures
        prefs.edit().putBoolean("pictures", showPictures).apply()
        loadChapter(chapter, currentOffset()) // same text either way: the position is kept
    }

    private fun togglePictureColors() {
        grayPictures = !grayPictures
        prefs.edit().putBoolean("gray_pictures", grayPictures).apply()
        PictureSpan.setColors(grayPictures, Ink.night && invertPdfAtNight)
        pageView.invalidate()
    }

    /** Light text on black, for reading in the dark. Pictures keep their colors; PDF pages are inverted. */
    private fun toggleNight() {
        Ink.night = !Ink.night
        prefs.edit().putBoolean("night", Ink.night).apply()
        PictureSpan.setColors(grayPictures, Ink.night && invertPdfAtNight)
        root.setBackgroundColor(Ink.paper)
        // The read-aloud bar's buttons take their colors when built.
        val visibility = controls.visibility
        val index = root.indexOfChild(controls)
        root.removeView(controls)
        controls = buildReadAloudBar()
        controls.visibility = visibility
        root.addView(controls, index, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        updateReadAloudBar()
        updateSystemBars()
        pageView.invalidate()
    }

    /** Dark status and navigation bar icons on white, light ones in night mode. */
    private fun updateSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !Ink.night
            isAppearanceLightNavigationBars = !Ink.night
        }
    }

    private fun chooseFont() {
        chooseOption("Font", Fonts.names, fontIndex) { i ->
            if (i == fontIndex) return@chooseOption
            fontIndex = i
            prefs.edit().putInt("font", i).apply()
            if (book != null) loadChapter(chapter, currentOffset())
        }
    }

    private fun dialog() =
        if (Ink.night) AlertDialog.Builder(this, R.style.Theme_Reader_Dialog_Night) else AlertDialog.Builder(this)

    private fun pictureColorsName() = if (grayPictures) "Gray" else "Original"

    /** Long-press on a picture: show it full screen, at the screen's resolution. */
    private fun openPicture(picture: PictureSpan) {
        val b = book ?: return
        if (speaking) pauseReading()
        val metrics = resources.displayMetrics
        worker.execute {
            val bitmap = try {
                val size = b.pictureSize(picture.path)
                if (size == null) null else {
                    val fit = minOf(metrics.widthPixels.toFloat() / size[0], metrics.heightPixels.toFloat() / size[1])
                    b.decodePicture(
                        picture.path,
                        (size[0] * fit).toInt().coerceAtLeast(1),
                        (size[1] * fit).toInt().coerceAtLeast(1),
                    )
                }
            } catch (e: Throwable) {
                null
            }
            mainHandler.post {
                if (bitmap == null) showMessage("Picture", "This picture can't be shown.")
                else PictureViewer(this, bitmap) { bitmap.recycle() }.show()
            }
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

    /**
     * Main menu, most used first: getting around the book, then settings grouped in submenus
     * (each item shows its current values), then sync, then opening another book.
     */
    private fun showMenu() {
        val b = book
        val d = pdf
        val actions = ArrayList<Pair<String, () -> Unit>>()
        when {
            d != null -> {
                // Pages are pictures: no text settings or reading aloud, but words can be selected.
                actions += "Go to page… (${pdfPage + 1} of ${d.pageCount})" to ::showGoToPage
                actions += "Words: ${oneWordAction.label} · ${severalWordsAction.label}" to ::showWordsMenu
                actions += "Colors: ${pictureColorsName()}" to { togglePictureColors(); showMenu() }
                if (Ink.night) actions += "Invert pages at night: ${if (invertPdfAtNight) "On" else "Off"}" to {
                    invertPdfAtNight = !invertPdfAtNight
                    prefs.edit().putBoolean("night_invert_pdf", invertPdfAtNight).apply()
                    PictureSpan.setColors(grayPictures, Ink.night && invertPdfAtNight)
                    pageView.invalidate()
                    showMenu()
                }
            }
            b != null -> {
                backStack.lastOrNull()?.let { (ch, _) ->
                    actions += "Back to where you were (ch ${ch + 1})" to ::goBack
                }
                actions += "Contents" to ::showContents
                actions += if (readingAloud) "Stop reading aloud" to ::stopReadingAloud
                else "Read aloud" to ::startReadingAloud
                actions += "Text: ${Fonts.names[fontIndex]} $fontSp · ${spacingName()} spacing · pictures ${onOff(showPictures)}" to ::showTextMenu
                actions += "Words: ${oneWordAction.label} · ${severalWordsAction.label}" to ::showWordsMenu
            }
            else -> actions += "Dictionaries" to ::showDictionaries
        }
        actions += "Sync: positions ${onOff(positionSync.isEnabled)} · books ${onOff(sharedFolder.isEnabled)}" to ::showSyncMenu
        actions += "Night mode: ${if (Ink.night) "On" else "Off"}" to { toggleNight(); showMenu() }
        actions += "Open book…" to ::showLibrary
        showList(d?.title ?: b?.title ?: "Book", actions.map { it.first }) { actions[it].second() }
    }

    /** Text size, spacing and pictures. Stays open after a quick change, so you can tap again. */
    private fun showTextMenu() {
        val actions = ArrayList<Pair<String, () -> Unit>>()
        actions += "Larger text (${fontSp + 2})" to { changeFont(+2); showTextMenu() }
        actions += "Smaller text (${fontSp - 2})" to { changeFont(-2); showTextMenu() }
        actions += "Font: ${Fonts.names[fontIndex]}" to ::chooseFont
        actions += "Paragraph spacing: ${spacingName()}" to ::chooseParagraphSpacing
        actions += "Pictures: ${if (showPictures) "On" else "Off"}" to { togglePictures(); showTextMenu() }
        if (showPictures) {
            actions += "Picture colors: ${pictureColorsName()}" to { togglePictureColors(); showTextMenu() }
        }
        showList("Text", actions.map { it.first }) { actions[it].second() }
    }

    /** What selecting words does, and the dictionaries used for it. */
    private fun showWordsMenu() {
        val count = dictionaries.list().size
        val actions = ArrayList<Pair<String, () -> Unit>>()
        actions += "One word: ${oneWordAction.label}" to ::chooseOneWordAction
        actions += "Several words: ${severalWordsAction.label}" to ::chooseSeveralWordsAction
        actions += "Dictionaries (${if (count == 0) "none" else count})" to ::showDictionaries
        showList("Words", actions.map { it.first }) { actions[it].second() }
    }

    private fun spacingName() = Pages.PARAGRAPH_SPACINGS[paragraphSpacing].first
    private fun onOff(on: Boolean) = if (on) "on" else "off"

    private fun chooseParagraphSpacing() {
        val names = Pages.PARAGRAPH_SPACINGS.map { it.first }.toTypedArray()
        showPlain(
            dialog()
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

    private fun chooseOneWordAction() {
        chooseOption("When one word is selected", OneWord.entries.map { it.label }, oneWordAction.ordinal) {
            oneWordAction = OneWord.entries[it]
            prefs.edit().putString("one_word_action", oneWordAction.name).apply()
        }
    }

    private fun chooseSeveralWordsAction() {
        chooseOption("When several words are selected", SeveralWords.entries.map { it.label }, severalWordsAction.ordinal) {
            severalWordsAction = SeveralWords.entries[it]
            prefs.edit().putString("several_words_action", severalWordsAction.name).apply()
        }
    }

    private fun chooseOption(title: String, names: List<String>, selected: Int, onPick: (Int) -> Unit) {
        showPlain(
            dialog()
                .setTitle(title)
                .setSingleChoiceItems(names.toTypedArray(), selected) { dialog, which ->
                    dialog.dismiss()
                    onPick(which)
                }
        )
    }

    private fun showContents() {
        val b = book ?: return
        val entries = b.toc.ifEmpty {
            b.spine.indices.map { EpubBook.TocEntry("Chapter ${it + 1}", it) }
        }
        showList("Contents", entries.map { it.title }) {
            val entry = entries[it]
            val p = pages
            val offset = entry.anchor?.let { a -> findAnchor(chapterAnchors, a) }
            // Same chapter, already laid out: just turn to the page.
            if (entry.spineIndex == chapter && p != null && !loading && offset != null) showPage(p.pageForOffset(offset))
            else loadChapter(entry.spineIndex, 0, entry.anchor)
        }
    }

    // ---------------------------------------------------------------- links

    private fun followLink(link: HtmlText.Link) {
        when (link.kind) {
            HtmlText.Link.Kind.EXTERNAL -> openExternalLink(link.target)
            HtmlText.Link.Kind.NOTE -> showNote(link.target)
            HtmlText.Link.Kind.INTERNAL -> jumpTo(link.target)
        }
    }

    /** Asks first: a stray tap shouldn't take you out of the book. */
    private fun openExternalLink(url: String) {
        showPlain(
            dialog()
                .setTitle("Open in browser?")
                .setMessage(url)
                .setPositiveButton("Open") { _, _ ->
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(this, "No app on this device can open this link.", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
        )
    }

    /** An id from a link's #fragment, as written or percent-decoded. */
    private fun findAnchor(anchors: Map<String, Int>, id: String): Int? = anchors[id] ?: anchors[Uri.decode(id)]

    /** Goes to [target] ("path#id") in the book; Back returns here. */
    private fun jumpTo(target: String) {
        val b = book ?: return
        val index = b.spine.indexOf(target.substringBefore('#'))
        if (index < 0) {
            Toast.makeText(this, "This link points to a part of the book that can't be shown.", Toast.LENGTH_SHORT).show()
            return
        }
        val anchor = target.substringAfter('#', "").ifEmpty { null }
        backStack += chapter to currentOffset()
        if (backStack.size > MAX_BACK) backStack.removeAt(0)
        updateBackHandler()
        val p = pages
        val offset = anchor?.let { findAnchor(chapterAnchors, it) }
        if (index == chapter && p != null && !loading) showPage(p.pageForOffset(offset ?: 0))
        else loadChapter(index, 0, anchor)
    }

    /** Returns to where the last followed link was tapped. */
    private fun goBack() {
        val (ch, offset) = backStack.removeLastOrNull() ?: return
        updateBackHandler()
        if (speaking) pauseReading()
        val p = pages
        if (ch == chapter && p != null && !loading) showPage(p.pageForOffset(offset)) else loadChapter(ch, offset)
    }

    /** The system Back gesture goes back along followed links while there are any, else as usual. */
    private val backCallback: Any? by lazy {
        if (Build.VERSION.SDK_INT >= 33) android.window.OnBackInvokedCallback { goBack() } else null
    }

    private fun updateBackHandler() {
        if (Build.VERSION.SDK_INT < 33) return
        val callback = backCallback as android.window.OnBackInvokedCallback
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
        if (backStack.isNotEmpty()) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        }
    }

    @Deprecated("Used below Android 13 only; newer versions use updateBackHandler")
    override fun onBackPressed() {
        if (backStack.isNotEmpty()) goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    /**
     * Shows the footnote [target] points to in a popup, without leaving the page. If the note
     * can't be found, goes there instead.
     */
    private fun showNote(target: String) {
        val b = book ?: return
        val index = b.spine.indexOf(target.substringBefore('#'))
        val anchor = target.substringAfter('#', "")
        if (index < 0 || anchor.isEmpty()) return jumpTo(target)
        val current = chapterText
        if (index == chapter && current != null) {
            val offset = findAnchor(chapterAnchors, anchor) ?: return jumpTo(target)
            return showNotePopup(HtmlText.noteAt(HtmlText.Chapter(current, chapterAnchors), offset), target)
        }
        if (speaking) pauseReading()
        val currentBook = b
        worker.execute {
            val note = try {
                val cachedNotes = noteChapter
                val content = if (cachedNotes != null && cachedNotes.first == index) cachedNotes.second
                else HtmlText.toText(b.readChapter(index), b.spine[index]).also { noteChapter = index to it }
                findAnchor(content.anchors, anchor)?.let { HtmlText.noteAt(content, it) }
            } catch (e: Exception) {
                null
            }
            mainHandler.post {
                if (book !== currentBook) return@post
                if (note != null) showNotePopup(note, target) else jumpTo(target)
            }
        }
    }

    private fun showNotePopup(note: CharSequence, target: String) {
        if (note.isBlank()) return jumpTo(target)
        showPlain(
            dialog()
                .setTitle("Note")
                .setMessage(note)
                .setPositiveButton("Close", null)
                .setNeutralButton("Go to note") { _, _ -> jumpTo(target) }
        )
    }

    private fun showGoToPage() {
        val d = pdf ?: return
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            hint = "1–${d.pageCount}"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val form = LinearLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input, MATCH_PARENT, WRAP_CONTENT)
        }
        fun go() {
            val number = input.text.toString().trim().toIntOrNull() ?: return
            showPdfPage(number - 1)
        }
        val dialog = showPlain(
            dialog()
                .setTitle("Go to page")
                .setView(form)
                .setPositiveButton("Go") { _, _ -> go() }
                .setNegativeButton("Cancel", null)
        )
        input.setOnEditorActionListener { _, _, _ ->
            dialog.dismiss()
            go()
            true
        }
        input.requestFocus()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    private fun showList(title: String, items: List<String>, onCancel: (() -> Unit)? = null, onPick: (Int) -> Unit) {
        val builder = dialog()
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
            if (Ink.night) {
                // Black on a black page: a thin border shows where the dialog is (it has no visible shadow).
                val dp = resources.displayMetrics.density
                setBackgroundDrawable(InsetDrawable(GradientDrawable().apply {
                    setColor(Ink.paper)
                    setStroke((1 * dp).toInt().coerceAtLeast(1), Ink.text)
                    cornerRadius = 2 * dp
                }, (16 * dp).toInt()))
            }
        }
        dialog.show()
        return dialog
    }

    private fun showMessage(title: String, message: String) {
        showPlain(dialog().setTitle(title).setMessage(message).setPositiveButton("OK", null))
    }

    // ---------------------------------------------------------------- dictionaries

    private fun showDictionaries() {
        val dicts = dictionaries.list()
        val builder = dialog()
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
            dialog()
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
        val busy = showPlain(dialog().setMessage("Adding dictionary…").setCancelable(false))
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

    /**
     * One gesture, no menu: by default one word opens the dictionary and several words open
     * Google Translate. Falls back when the chosen tool isn't available; "Ask" shows the list.
     */
    private fun onTextSelected(text: String, singleWord: Boolean, centerY: Int) {
        if (speaking) pauseReading()
        val hasDictionary = dictionaries.list().isNotEmpty()
        val translate = translateIntent(text)
        if (singleWord) {
            when {
                oneWordAction == OneWord.DICTIONARY && hasDictionary -> return lookUpWord(text, centerY)
                oneWordAction == OneWord.TRANSLATE && translate != null -> return openTranslate(translate)
                oneWordAction != OneWord.ASK && hasDictionary -> return lookUpWord(text, centerY)
                oneWordAction != OneWord.ASK && translate != null -> return openTranslate(translate)
            }
        } else {
            when {
                severalWordsAction == SeveralWords.TRANSLATE && translate != null -> return openTranslate(translate)
                severalWordsAction != SeveralWords.ASK -> return copyText(text)
            }
        }
        askSelectionAction(text, singleWord, centerY)
    }

    /** The action list: Dictionary (one word only), Google Translate (if installed), Copy. */
    private fun askSelectionAction(text: String, singleWord: Boolean, centerY: Int) {
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
        clipboard.setPrimaryClip(ClipData.newPlainText("Book", text))
        // Android 13+ shows its own "copied" confirmation.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    /** Dictionary flow: pick the book's dictionary (asking once if there are several), then look up. */
    private fun lookUpWord(word: String, centerY: Int) {
        val dicts = dictionaries.list()
        if (dicts.isEmpty()) {
            pageView.clearSelection()
            showPlain(
                dialog()
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
            onSpeak = if (hasSpeechEngine) { shown -> speech().speakWord(shown, bookLocale()) } else null,
            onTranslate = translateIntent(word)?.let { intent -> { openTranslate(intent) } },
            onCopy = { copyText(word) },
            onDismiss = {
                popup = null
                pageView.clearSelection()
            },
        )
        popup = newPopup
        newPopup.show(content)
    }

    // ---------------------------------------------------------------- read aloud

    private fun speech(): ReadAloud = speech ?: ReadAloud(this, speechListener).also {
        it.rate = speechRate
        speech = it
    }

    /** Voice language: the book's own (dc:language), else the device's. */
    private fun bookLocale(): Locale =
        book?.language?.let { Locale.forLanguageTag(it.replace('_', '-')) }?.takeIf { it.language.isNotEmpty() }
            ?: Locale.getDefault()

    private fun startReadingAloud() {
        if (!hasSpeechEngine) return showMessage("Read aloud", ReadAloud.NO_ENGINE)
        if (pages == null) return
        readingAloud = true
        spokenRange = null
        controls.visibility = View.VISIBLE
        resumeReading()
    }

    /** Play: continues the current sentence if it's on this page, else starts at the top of the page. */
    private fun resumeReading() {
        val p = pages ?: return
        speaking = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // the screen timeout would stop it
        updateReadAloudBar()
        if (loading) {
            speakAfterLoad = true
            return
        }
        val spoken = spokenRange
        speakFrom(if (spoken != null && p.pageForOffset(spoken.first) == page) spoken.first else p.offsetOf(page))
    }

    private fun speakFrom(offset: Int) {
        val text = chapterText ?: return
        // Footnote markers ("[1]") would be read out as numbers: blank them, keeping the offsets.
        val markers = HtmlText.noteMarkers(text)
        val speakable = if (markers.isEmpty()) text else StringBuilder(text).apply {
            for (r in markers) for (i in r) setCharAt(i, ' ')
        }
        speech().start(speakable, offset, bookLocale())
    }

    private fun pauseReading() {
        speaking = false
        speakAfterLoad = false
        mainHandler.removeCallbacks(restartAtNewRate)
        speech?.stop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateReadAloudBar()
    }

    private fun stopReadingAloud() {
        if (!readingAloud) return
        pauseReading()
        readingAloud = false
        spokenRange = null
        pageView.setSpoken(null)
        controls.visibility = View.GONE
    }

    private val speechListener = object : ReadAloud.Listener {
        override fun onSentence(range: IntRange) {
            if (!speaking) return
            spokenRange = range
            turnPageTo(range.first)
            pageView.setSpoken(range)
        }

        override fun onProgress(offset: Int) {
            if (speaking) turnPageTo(offset) // a sentence continuing on the next page
        }

        override fun onChapterEnd() {
            if (!speaking) return
            val b = book ?: return
            if (chapter + 1 < b.spine.size) {
                speakAfterLoad = true
                loadChapter(chapter + 1, 0)
            } else {
                stopReadingAloud()
                showMessage("Read aloud", "End of the book.")
            }
        }

        override fun onError(message: String) {
            pauseReading()
            showMessage("Read aloud", message)
        }

        override fun onMissingVoice(locale: Locale, downloadable: Boolean) {
            val language = locale.getDisplayLanguage(Locale.ENGLISH).ifEmpty { locale.toString() }
            val message = if (downloadable) {
                "The $language voice isn't downloaded, so reading may need the internet or may not work.\n\n" +
                    "Download it in the text-to-speech settings (engine settings → Install voice data)."
            } else {
                "The text-to-speech engine has no $language voice, so another voice is used."
            }
            showPlain(
                dialog()
                    .setTitle("Read aloud")
                    .setMessage(message)
                    .setPositiveButton("Voice settings") { _, _ -> openSpeechSettings() }
                    .setNegativeButton("OK", null)
            )
        }
    }

    private fun turnPageTo(offset: Int) {
        val p = pages ?: return
        if (loading) return // the page is chosen from spokenRange when the layout is ready
        val target = p.pageForOffset(offset)
        if (target != page) showPage(target)
    }

    private fun openSpeechSettings() {
        try {
            startActivity(Intent(ReadAloud.SETTINGS_ACTION))
        } catch (e: Exception) {
            showMessage("Read aloud", "Open Settings → Accessibility (or Language & input) → Text-to-speech output.")
        }
    }

    private fun changeSpeechRate(delta: Float) {
        val rate = ((speechRate + delta) * 10).let { Math.round(it) / 10f }.coerceIn(0.5f, 3f)
        if (rate == speechRate) return
        speechRate = rate
        prefs.edit().putFloat("speech_rate", rate).apply()
        speech?.rate = rate
        updateReadAloudBar()
        // Already queued sentences keep the old speed: after a short pause in the presses,
        // restart the current sentence at the new speed.
        if (speaking) {
            mainHandler.removeCallbacks(restartAtNewRate)
            mainHandler.postDelayed(restartAtNewRate, 700)
        }
    }

    private val restartAtNewRate = Runnable {
        val spoken = spokenRange
        if (speaking && !loading && spoken != null) speakFrom(spoken.first)
    }

    private fun updateReadAloudBar() {
        playButton.text = if (speaking) "Pause" else "Play"
        rateLabel.text = String.format(Locale.US, "%.1f×", speechRate)
    }

    /** Pause/Play, Slower, speed, Faster, Stop. Plain bordered buttons, like the dictionary popup. */
    private fun buildReadAloudBar(): LinearLayout {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        fun button(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            setTextColor(Ink.text)
            textSize = 16f
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(dp(4), dp(10), dp(4), dp(10))
            background = GradientDrawable().apply {
                setColor(Ink.paper)
                setStroke(dp(1), Ink.text)
                cornerRadius = dp(4).toFloat()
            }
            setOnClickListener { onClick() }
        }
        playButton = button("Pause") { if (speaking) pauseReading() else resumeReading() }
        rateLabel = TextView(this).apply {
            setTextColor(Ink.text)
            textSize = 16f
            gravity = Gravity.CENTER
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            val items = listOf(
                playButton to 1.3f,
                button("Slower") { changeSpeechRate(-0.1f) } to 1f,
                rateLabel to 0.8f,
                button("Faster") { changeSpeechRate(+0.1f) } to 1f,
                button("Stop", ::stopReadingAloud) to 1f,
            )
            for ((i, item) in items.withIndex()) {
                addView(item.first, LinearLayout.LayoutParams(0, WRAP_CONTENT, item.second).apply {
                    if (i > 0) marginStart = dp(8)
                })
            }
        }
        updateReadAloudBar()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ink.paper)
            addView(View(this@MainActivity).apply { setBackgroundColor(Ink.text) }, MATCH_PARENT, dp(1))
            addView(row, MATCH_PARENT, WRAP_CONTENT)
            visibility = View.GONE
        }
    }

    // ---------------------------------------------------------------- position sync

    private fun deviceName(): String {
        val maker = Build.MANUFACTURER.replaceFirstChar { it.titlecase() }
        return if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL else "$maker ${Build.MODEL}"
    }

    /** For a PDF, the "chapter" is the page number (from 0) and the offset is 0. */
    private fun currentPosition() =
        if (pdf != null) PositionSync.Position(pdfPage, 0)
        else PositionSync.Position(chapter, currentOffset().coerceAtLeast(0))

    /** Rough share of the book read, for showing "45%" on the other device. */
    private fun percentRead(): Double {
        pdf?.let { return ((pdfPage + 1).toDouble() / it.pageCount).coerceIn(0.0, 1.0) }
        val chapters = book?.spine?.size ?: return 0.0
        val length = chapterText?.length ?: 0
        val inChapter = if (length > 0) (currentOffset().toDouble() / length).coerceIn(0.0, 1.0) else 0.0
        return ((chapter + inChapter) / chapters).coerceIn(0.0, 1.0)
    }

    /** Uploads the position in the background; failures (e.g. no Wi-Fi) are retried next time. */
    private fun pushPosition(force: Boolean = false, onDone: ((Exception?) -> Unit)? = null) {
        val hash = bookHash
        if (!positionSync.isEnabled || hash == null || (pages == null && pdf == null)) {
            onDone?.invoke(null)
            return
        }
        val position = currentPosition()
        val percentage = percentRead()
        syncWorker.execute {
            val error = try { positionSync.push(hash, position, percentage, force); null } catch (e: Exception) { e }
            if (onDone != null) mainHandler.post { onDone(error) }
        }
    }

    /** Offers to jump if another device uploaded a different position since we last looked. */
    private fun checkRemotePosition(onDone: ((found: Boolean, error: Exception?) -> Unit)? = null) {
        val hash = bookHash
        if (!positionSync.isEnabled || hash == null) {
            onDone?.invoke(false, null)
            return
        }
        val current = currentPosition()
        syncWorker.execute {
            val (remote, error) = try { positionSync.pullNewer(hash, current) to null } catch (e: Exception) { null to e }
            mainHandler.post {
                if (remote != null && hash == bookHash) offerRemotePosition(hash, remote)
                onDone?.invoke(remote != null, error)
            }
        }
    }

    private fun offerRemotePosition(hash: String, remote: PositionSync.Remote) {
        val d = pdf
        val (unit, count, here) = when {
            d != null -> Triple("page", d.pageCount, pdfPage)
            else -> Triple("chapter", book?.spine?.size ?: return, chapter)
        }
        val now = System.currentTimeMillis()
        val whenRead = if (now - remote.timestamp * 1000 < DateUtils.MINUTE_IN_MILLIS) "just now"
        else DateUtils.getRelativeTimeSpanString(remote.timestamp * 1000, now, DateUtils.MINUTE_IN_MILLIS)
        val message = "${remote.device} is at ${(remote.percentage * 100).toInt()}% " +
            "($unit ${remote.position.chapter + 1} of $count), $whenRead.\n\n" +
            "This device is at ${(percentRead() * 100).toInt()}% ($unit ${here + 1})."
        showPlain(
            dialog()
                .setTitle("Continue from ${remote.device}?")
                .setMessage(message)
                .setPositiveButton("Go there") { _, _ ->
                    positionSync.markSeen(hash, remote.timestamp)
                    if (pdf != null) showPdfPage(remote.position.chapter)
                    else loadChapter(remote.position.chapter, remote.position.offset)
                }
                .setNegativeButton("Stay here") { _, _ -> positionSync.declined(hash, remote.timestamp) }
                .setOnCancelListener { positionSync.declined(hash, remote.timestamp) }
        )
    }

    private fun showSyncMenu() {
        showList("Sync", listOf(
            "Reading position: ${if (positionSync.isEnabled) positionSync.username else "Off"}",
            "Books: ${if (sharedFolder.isEnabled) sharedFolder.displayName else "Off"}",
        )) { if (it == 0) showSyncSettings() else showFolderSettings() }
    }

    private fun showFolderSettings() {
        val builder = dialog().setTitle("Sync books")
        if (!sharedFolder.isEnabled) {
            builder.setMessage(
                "Book can use a folder that a sync app, such as Syncthing or FolderSync, keeps the same on all " +
                    "your devices. Books you open are copied into it, and books in it appear in the Library " +
                    "on every device.\n\nSet up the sync app first, then choose its folder here, on each device."
            )
                .setPositiveButton("Choose folder…") { _, _ -> pickSharedFolder() }
                .setNegativeButton("Cancel", null)
        } else {
            builder.setMessage(
                "Folder: ${sharedFolder.displayName}\n\nBooks you open are copied into this folder, and books " +
                    "in it appear in the Library (marked \u21E3) until you open them."
            )
                .setPositiveButton("Change folder…") { _, _ -> pickSharedFolder() }
                .setNegativeButton("Stop using") { _, _ -> sharedFolder.clear() }
                .setNeutralButton("Close", null)
        }
        showPlain(builder)
    }

    private fun pickSharedFolder() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_FOLDER)
        } catch (e: ActivityNotFoundException) {
            showMessage("Sync books", "This device has no folder picker, so a shared folder can't be chosen.")
        }
    }

    /** Starts using [tree] as the shared folder and copies the library's books into it. */
    private fun useSharedFolder(tree: Uri) {
        val busy = showPlain(dialog().setMessage("Copying your books to the folder…").setCancelable(false))
        folderWorker.execute {
            val (title, message) = try {
                sharedFolder.set(tree)
                val before = sharedFolder.books().map { it.hash }.toSet()
                var copied = 0
                for (entry in library.entries()) {
                    val hash = entry.fileName.substringBeforeLast('.')
                    if (hash in before) continue
                    sharedFolder.add(library.file(entry), entry.title, hash, entry.isPdf)
                    copied++
                }
                "Sync books" to "Using the folder ${sharedFolder.displayName}.\n\n" +
                    (if (copied == 0) "Your books were already there." else "Copied $copied ${if (copied == 1) "book" else "books"} into it.")
            } catch (e: Exception) {
                "Could not use this folder" to (e.message ?: e.javaClass.simpleName)
            }
            mainHandler.post {
                busy.dismiss()
                showMessage(title, message)
            }
        }
    }

    private fun showSyncSettings() {
        if (!positionSync.isEnabled) return showSignIn()
        showPlain(
            dialog()
                .setTitle("Sync reading position")
                .setMessage(
                    "Signed in as ${positionSync.username}\nServer: ${positionSync.server}\n\n" +
                        "The position is uploaded when you leave the app or turn off the screen, " +
                        "and checked when you open a book or come back to it. " +
                        "Use the same account on your other devices."
                )
                .setPositiveButton("Sync now") { _, _ -> syncNow() }
                .setNegativeButton("Sign out") { _, _ -> positionSync.signOut() }
                .setNeutralButton("Close", null)
        )
    }

    private fun syncNow() {
        if (bookHash == null) return showMessage("Sync", "Open a book first.")
        pushPosition(force = true) { pushError ->
            checkRemotePosition { found, pullError ->
                val error = pushError ?: pullError
                when {
                    error != null -> showMessage("Sync failed", error.message ?: error.javaClass.simpleName)
                    !found -> showMessage("Sync", "Up to date. This book's position is saved on the server.")
                }
            }
        }
    }

    private fun showSignIn() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        fun field(hint: String, value: String = "", password: Boolean = false) = EditText(this).apply {
            this.hint = hint
            setText(value)
            setSingleLine()
            inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val server = field("Server", positionSync.server)
        val user = field("Username", positionSync.username ?: "")
        val password = field("Password", password = true)
        val status = TextView(this).apply {
            text = "Uses a KOReader sync server. Use the same account on every device."
            setPadding(0, pad / 2, 0, 0)
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(server)
            addView(user)
            addView(password)
            addView(status)
        }
        val dialog = showPlain(
            dialog()
                .setTitle("Sync reading position")
                .setView(form)
                .setPositiveButton("Sign in", null)
                .setNeutralButton("Create account", null)
                .setNegativeButton("Cancel", null)
        )
        // Own click handling so the dialog stays open while connecting and on errors.
        fun submit(register: Boolean) {
            val url = server.text.toString().trim()
            val name = user.text.toString().trim()
            val pass = password.text.toString()
            val problem = when {
                !url.startsWith("https://") && !url.startsWith("http://") -> "The server address must start with https://"
                name.isEmpty() || name.contains(':') -> "Enter a username (without \":\")."
                pass.isEmpty() -> "Enter a password."
                else -> null
            }
            if (problem != null) {
                status.text = problem
                return
            }
            status.text = if (register) "Creating account…" else "Signing in…"
            syncWorker.execute {
                val error = try { positionSync.signIn(url, name, pass, register); null } catch (e: Exception) { e }
                mainHandler.post {
                    if (error != null) {
                        status.text = error.message ?: error.javaClass.simpleName
                    } else {
                        dialog.dismiss()
                        showMessage("Sync", "Signed in as $name.")
                        checkRemotePosition()
                    }
                }
            }
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit(register = false) }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { submit(register = true) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val volumeKey = keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (readingAloud && volumeKey) return super.onKeyDown(keyCode, event) // loudness while listening
        if (speaking && keyCode in PAGE_KEYS) {
            pauseReading()
            return true
        }
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
        private const val REQUEST_FOLDER = 3
        private const val GOOGLE_TRANSLATE = "com.google.android.apps.translate"
        private const val MAX_DECODED_PICTURES = 6
        /** Followed links remembered for Back. */
        private const val MAX_BACK = 20
        private val PAGE_KEYS = setOf(
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU,
        )
    }
}
