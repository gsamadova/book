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
import com.guny90.reader.dict.DefinitionText
import com.guny90.reader.dict.Dictionaries
import com.guny90.reader.sync.KoSync
import com.guny90.reader.sync.PositionSync
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
    private val positionSync by lazy { PositionSync(prefs, deviceName()) }
    /** Book fingerprint (same on every device), used as the sync document id. */
    private var bookHash: String? = null
    private var syncCheckPending = false
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
    private var oneWordAction = OneWord.DICTIONARY
    private var severalWordsAction = SeveralWords.TRANSLATE
    private var showPictures = true
    /** Latest page waiting for its pictures to be decoded. */
    private var pictureJob = 0
    /** Pictures holding a decoded bitmap, oldest first (worker thread only). */
    private val decodedPictures = ArrayDeque<PictureSpan>()

    /** What a selection does on release; ASK shows the action list. */
    private enum class OneWord(val label: String) { DICTIONARY("Dictionary"), TRANSLATE("Google Translate"), ASK("Ask") }
    private enum class SeveralWords(val label: String) { TRANSLATE("Google Translate"), COPY("Copy"), ASK("Ask") }
    private var loading = false
    private var jobId = 0
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
        volumeControlStream = AudioManager.STREAM_MUSIC

        pageView = PageView(this)
        controls = buildReadAloudBar()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            // Showing the bar shrinks the page; the chapter is re-paginated, keeping the position.
            addView(pageView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(controls, MATCH_PARENT, WRAP_CONTENT)
        }
        setContentView(root)
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
        if (book != null && !syncCheckPending) checkRemotePosition()
    }

    override fun onPause() {
        super.onPause()
        if (speaking) pauseReading() // no background reading: stops with the screen
        pushPosition() // leaving the app or screen turning off
    }

    override fun onDestroy() {
        val b = book
        worker.execute { b?.close() }
        worker.shutdown()
        dictWorker.execute { dictionaries.closeAll() }
        dictWorker.shutdown()
        syncWorker.shutdown() // lets a pending upload finish
        speech?.shutdown()
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
                val hash = KoSync.partialMd5(bookFile)
                mainHandler.post {
                    if (id == jobId) setBook(opened, hash) else worker.execute { opened.close() }
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
                val hash = KoSync.partialMd5(bookFile)
                mainHandler.post { if (id == jobId) setBook(opened, hash) else worker.execute { opened.close() } }
            } catch (e: Exception) {
                mainHandler.post {
                    loading = false
                    pageView.showMessage("Tap the middle of the screen\nto open an .epub book.")
                }
            }
        }
    }

    private fun setBook(newBook: EpubBook, hash: String) {
        stopReadingAloud()
        pushPosition() // the book being replaced
        val old = book
        if (old != null) worker.execute { old.close() }
        book = newBook
        showTitleInRecents(newBook.title)
        bookKey = "pos_" + (newBook.title + "|" + bookFile.length()).hashCode()
        chapterText = null
        pages = null
        val saved = prefs.getString(bookKey, null)?.split(':')
        val ch = saved?.getOrNull(0)?.toIntOrNull() ?: 0
        val offset = saved?.getOrNull(1)?.toIntOrNull() ?: 0
        bookHash = hash
        syncCheckPending = true // after the first page is shown
        loadChapter(ch, offset)
    }

    /** Labels this app's card in the recent-apps screen with the book title instead of "Reader". */
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
     * Loads (or re-paginates) a chapter and shows the page containing [offset].
     * Int.MAX_VALUE means "last page" (used when paging backwards).
     */
    private fun loadChapter(index: Int, offset: Int) {
        val b = book ?: return
        val target = index.coerceIn(0, b.spine.size - 1)
        val w = pageView.textWidth
        val h = pageView.textHeight
        val cached = if (target == chapter) chapterText else null
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
        // Picture pixels are CSS pixels: as large as on a phone screen, then fitted to the page.
        val pixelScale = resources.displayMetrics.density
        worker.execute {
            try {
                val text = cached ?: HtmlText.toText(b.readChapter(target), b.chapterDir(target))
                val pictureSize: ((String) -> IntArray?)? = if (pictures) b::pictureSize else null
                val result = Pages.paginate(text, w, h, textPx, spacing, pictureSize, pixelScale)
                mainHandler.post {
                    if (id != jobId) return@post
                    chapterText = text
                    pages = result
                    loading = false
                    // While reading aloud, a re-layout (e.g. the bar appearing) keeps the spoken sentence in view.
                    val spoken = spokenRange
                    val showAt = if (speaking && !speakAfterLoad && spoken != null) spoken.first else offset
                    showPage(result.pageForOffset(showAt))
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

    private fun showMenu() {
        val b = book
        val actions = ArrayList<Pair<String, () -> Unit>>()
        actions += "Open book…" to ::pickBook
        actions += "Dictionaries" to ::showDictionaries
        if (b != null) {
            actions += "Contents" to ::showContents
            actions += if (readingAloud) "Stop reading aloud" to ::stopReadingAloud
            else "Read aloud" to ::startReadingAloud
            actions += "Larger text (${fontSp + 2})" to { changeFont(+2) }
            actions += "Smaller text (${fontSp - 2})" to { changeFont(-2) }
            actions += "Paragraph spacing: ${Pages.PARAGRAPH_SPACINGS[paragraphSpacing].first}" to ::chooseParagraphSpacing
            actions += "Pictures: ${if (showPictures) "On" else "Off"}" to ::togglePictures
            actions += "One word: ${oneWordAction.label}" to ::chooseOneWordAction
            actions += "Several words: ${severalWordsAction.label}" to ::chooseSeveralWordsAction
        }
        actions += "Sync: ${if (positionSync.isEnabled) positionSync.username else "Off"}" to ::showSyncSettings
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
            AlertDialog.Builder(this)
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
        speech().start(text, offset, bookLocale())
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
                AlertDialog.Builder(this@MainActivity)
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
            setTextColor(Color.BLACK)
            textSize = 16f
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(dp(4), dp(10), dp(4), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(dp(1), Color.BLACK)
                cornerRadius = dp(4).toFloat()
            }
            setOnClickListener { onClick() }
        }
        playButton = button("Pause") { if (speaking) pauseReading() else resumeReading() }
        rateLabel = TextView(this).apply {
            setTextColor(Color.BLACK)
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
            setBackgroundColor(Color.WHITE)
            addView(View(this@MainActivity).apply { setBackgroundColor(Color.BLACK) }, MATCH_PARENT, dp(1))
            addView(row, MATCH_PARENT, WRAP_CONTENT)
            visibility = View.GONE
        }
    }

    // ---------------------------------------------------------------- position sync

    private fun deviceName(): String {
        val maker = Build.MANUFACTURER.replaceFirstChar { it.titlecase() }
        return if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL else "$maker ${Build.MODEL}"
    }

    private fun currentPosition() = PositionSync.Position(chapter, currentOffset().coerceAtLeast(0))

    /** Rough share of the book read, for showing "45%" on the other device. */
    private fun percentRead(): Double {
        val chapters = book?.spine?.size ?: return 0.0
        val length = chapterText?.length ?: 0
        val inChapter = if (length > 0) (currentOffset().toDouble() / length).coerceIn(0.0, 1.0) else 0.0
        return ((chapter + inChapter) / chapters).coerceIn(0.0, 1.0)
    }

    /** Uploads the position in the background; failures (e.g. no Wi-Fi) are retried next time. */
    private fun pushPosition(force: Boolean = false, onDone: ((Exception?) -> Unit)? = null) {
        val hash = bookHash
        if (!positionSync.isEnabled || hash == null || pages == null) {
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
        val b = book ?: return
        val chapters = b.spine.size
        val now = System.currentTimeMillis()
        val whenRead = if (now - remote.timestamp * 1000 < DateUtils.MINUTE_IN_MILLIS) "just now"
        else DateUtils.getRelativeTimeSpanString(remote.timestamp * 1000, now, DateUtils.MINUTE_IN_MILLIS)
        val message = "${remote.device} is at ${(remote.percentage * 100).toInt()}% " +
            "(chapter ${remote.position.chapter + 1} of $chapters), $whenRead.\n\n" +
            "This device is at ${(percentRead() * 100).toInt()}% (chapter ${chapter + 1})."
        showPlain(
            AlertDialog.Builder(this)
                .setTitle("Continue from ${remote.device}?")
                .setMessage(message)
                .setPositiveButton("Go there") { _, _ ->
                    positionSync.markSeen(hash, remote.timestamp)
                    loadChapter(remote.position.chapter, remote.position.offset)
                }
                .setNegativeButton("Stay here") { _, _ -> positionSync.declined(hash, remote.timestamp) }
                .setOnCancelListener { positionSync.declined(hash, remote.timestamp) }
        )
    }

    private fun showSyncSettings() {
        if (!positionSync.isEnabled) return showSignIn()
        showPlain(
            AlertDialog.Builder(this)
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
            AlertDialog.Builder(this)
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
        private const val GOOGLE_TRANSLATE = "com.google.android.apps.translate"
        private const val MAX_DECODED_PICTURES = 6
        private val PAGE_KEYS = setOf(
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU,
        )
    }
}
