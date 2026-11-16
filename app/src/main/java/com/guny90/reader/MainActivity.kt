package com.guny90.reader

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.KeyEvent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.util.concurrent.Executors

/**
 * Single-screen reader. All file / parsing / layout work happens on one worker thread;
 * the UI thread only swaps the finished page in and redraws once.
 */
class MainActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var pageView: PageView

    private val bookFile by lazy { File(filesDir, "current.epub") }

    // UI-thread state
    private var book: EpubBook? = null
    private var bookKey = ""
    private var chapter = 0
    private var chapterText: CharSequence? = null
    private var pages: Pages? = null
    private var page = 0
    private var pendingOffset = 0
    private var fontSp = 20
    private var loading = false
    private var jobId = 0
    private var laidOutW = 0
    private var laidOutH = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setWindowAnimations(0)
        prefs = getSharedPreferences("reader", MODE_PRIVATE)
        fontSp = prefs.getInt("font_sp", 20)

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
        if (requestCode == REQUEST_OPEN && resultCode == RESULT_OK) data?.data?.let { openUri(it) }
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
        val textPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, fontSp.toFloat(), resources.displayMetrics
        )
        worker.execute {
            try {
                val text = cached ?: HtmlText.toText(b.readChapter(target))
                val result = Pages.paginate(text, w, h, textPx)
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
        if (b != null) {
            actions += "Contents" to ::showContents
            actions += "Larger text (${fontSp + 2})" to { changeFont(+2) }
            actions += "Smaller text (${fontSp - 2})" to { changeFont(-2) }
        }
        showList(b?.title ?: "Reader", actions.map { it.first }) { actions[it].second() }
    }

    private fun showContents() {
        val b = book ?: return
        val entries = b.toc.ifEmpty {
            b.spine.indices.map { EpubBook.TocEntry("Chapter ${it + 1}", it) }
        }
        showList("Contents", entries.map { it.title }) { loadChapter(entries[it].spineIndex, 0) }
    }

    private fun showList(title: String, items: List<String>, onPick: (Int) -> Unit) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items.toTypedArray()) { _, which -> onPick(which) }
            .create()
        dialog.window?.apply {
            setWindowAnimations(0)
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND) // gray dim = extra e-ink refresh
        }
        dialog.show()
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
    }
}
