# Sidebar Menu Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the pop-up menu and its submenu pop-ups with a left sidebar. The sidebar groups
items into reading, page and setup, and opens sections in place.

**Architecture:**
- A new `Sidebar` view draws a list of rows that the activity describes. The view knows nothing
  about books.
- `MainActivity` builds the rows for each section and calls `sidebar.show(...)`. The current
  `showMenu`, `showTextMenu`, `showWordsMenu`, `showSyncMenu`, `showContents`, `showLibrary`,
  `showDictionaries` and the `choose*` functions keep their names but fill the sidebar instead of
  opening dialogs.
- The chapter name for the header is pure logic in `ChapterLabel`, unit-tested on the JVM.

**Tech Stack:** Kotlin, Android platform views only (no support-library drawer), JUnit 4.
Instrumented tests run on a device or emulator.

**Spec:** `docs/superpowers/specs/2026-11-24-sidebar-menu-design.md`

## Global Constraints

- No animation, no dimming, no shadow. Each change to the sidebar is one redraw (e-ink).
- Colors only from `Ink.text` / `Ink.paper`. The sidebar has a 1 dp vertical border in `Ink.text`
  on its right edge.
- No icons, no colors, and no group headings. Groups are separated by thin 1 dp lines.
- Width: `min(0.8 × screen width, 360 dp)`, on the left edge.
- Header line 2 is "Ch. N. <name>", or "Ch. N" when there's no name or the name is only the
  number. N is `chapter + 1`, the status line's number. Long names are cut with "…" on one line.
- Back row text: "‹ <parent name>", e.g. "‹ Menu", "‹ Text".
- "›" marks rows that open a section. Values are right-aligned and short.
- Dialogs for input and confirmation stay dialogs: sign-in, folder settings, Go to page, remove
  confirmations, notes, the word-selection actions and the dictionary chooser. When they're
  cancelled, they go back to the sidebar section they came from.
- Never run gradle connected tests: they uninstall the app. Install both APKs with
  `adb install -r` and run
  `adb shell am instrument -w com.guny90.reader.test/androidx.test.runner.AndroidJUnitRunner`.

## Review Focus

1. **Back gesture on Android 13+ while link history exists.** The sidebar's Back must win over
   the link-history callback. Back goes up one level, and from the top level it closes the
   sidebar without leaving the book. → Task 3, step 4 (registered at `PRIORITY_OVERLAY`), checked
   in Task 5 step 3.
2. **Tapping the visible strip of page.** It closes the sidebar and must not also turn the page
   or open a link. → Task 2 test `tapOutsideClosesWithoutPassingThrough`.
3. **Night mode switched from inside the sidebar.** The sidebar redraws in the new colors right
   away. → Task 2 test `colorsFollowInk`.
4. **Text size at its limits (12 and 40 sp).** − and + stay visible and do nothing beyond the
   limit. No crash, no re-layout. → Task 3: the size row calls `changeFont`, which already clamps.
   Checked in Task 5 step 3.
5. **A book with no contents, or a contents entry whose anchor isn't found.** The header shows
   "Ch. N". The anchor counts as the chapter's start. → Task 1 tests `noTocGivesNumberOnly` and
   `missingAnchorCountsAsChapterStart`.

---

### Task 1: Chapter label

**Files:**
- Create: `app/src/main/java/com/guny90/reader/ChapterLabel.kt`
- Test: `app/src/test/java/com/guny90/reader/ChapterLabelTest.kt`

**Interfaces:**
- Produces:
  - `object ChapterLabel`
  - `fun currentName(toc: List<EpubBook.TocEntry>, chapter: Int, pageStart: Int, anchorAt: (String) -> Int?): String?`
    returns the title of the last toc entry, in toc order, that is at or before the page.
    "At or before" means: `spineIndex < chapter`; or `spineIndex == chapter` and the anchor is null,
    or `anchorAt(anchor) ?: 0` is `<= pageStart`.
  - `fun label(number: Int, name: String?): String` returns "Ch. $number. $name", or
    "Ch. $number" when the name is null or blank, or when `isJustNumber(name, number)`.
  - `fun isJustNumber(name: String, number: Int): Boolean`. After trimming and dropping trailing
    `.`/`:`, the name is either a numeral, or one word followed by a numeral. The numeral (Arabic,
    or Roman in either case) must equal `number`.

- [ ] **Step 1: Write the failing tests**

```kotlin
class ChapterLabelTest {
    private val toc = listOf(
        EpubBook.TocEntry("Cover", 0),
        EpubBook.TocEntry("The Clearing", 3),
        EpubBook.TocEntry("Part two", 3, "p2"),
        EpubBook.TocEntry("Sweet Home", 5),
    )
    private val anchors = mapOf("p2" to 500)

    @Test fun nameOfChapterStart() = assertEquals("The Clearing", ChapterLabel.currentName(toc, 3, 0, anchors::get))
    @Test fun sectionOnceItsAnchorIsReached() = assertEquals("Part two", ChapterLabel.currentName(toc, 3, 500, anchors::get))
    @Test fun earlierEntryCarriesOver() = assertEquals("The Clearing", ChapterLabel.currentName(toc.take(2), 4, 0, anchors::get))
    @Test fun noTocGivesNumberOnly() = assertEquals("Ch. 4", ChapterLabel.label(4, ChapterLabel.currentName(emptyList(), 3, 0) { null }))
    @Test fun missingAnchorCountsAsChapterStart() = assertEquals("Part two", ChapterLabel.currentName(toc, 3, 0) { null })
    @Test fun labelWithName() = assertEquals("Ch. 4. The Clearing", ChapterLabel.label(4, "The Clearing"))
    @Test fun numberOnlyNamesAreDropped() {
        for (n in listOf("4", "Chapter 4", "IV", "Глава 4.", "chapter iv:")) assertEquals(n, "Ch. 4", ChapterLabel.label(4, n))
    }
    @Test fun otherNumbersAreKept() = assertEquals("Ch. 6. Chapter 4", ChapterLabel.label(6, "Chapter 4"))
    @Test fun numbersInsideNamesAreKept() = assertEquals("Ch. 4. 1984 in Winter", ChapterLabel.label(4, "1984 in Winter"))
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew -q testDebugUnitTest --tests com.guny90.reader.ChapterLabelTest`
Expected: compilation failure (`ChapterLabel` is unresolved).

- [ ] **Step 3: Implement `ChapterLabel` as specified in Interfaces**

- [ ] **Step 4: Run them to see them pass**

Run: `./gradlew -q testDebugUnitTest --tests com.guny90.reader.ChapterLabelTest`
Expected: no output, exit 0.

- [ ] **Step 5: Commit** `git add` both files, message "Chapter names for the menu header".

---

### Task 2: Sidebar view

**Files:**
- Create: `app/src/main/java/com/guny90/reader/Sidebar.kt`
- Test: `app/src/androidTest/java/com/guny90/reader/SidebarTest.kt`

**Interfaces:**
- Produces:

```kotlin
class Sidebar(context: Context) : FrameLayout(context) {
    sealed class Row {
        /**
         * Title, then smaller lines. [link] is the first line, shown with "›" and tappable
         * (chapter → Contents, PDF page → Go to page); null for none.
         */
        class Header(val title: String, val link: String?, val onLink: (() -> Unit)?, val lines: List<String>) : Row()
        class Item(
            val label: String, val value: String? = null, val opens: Boolean = false,
            val marked: Boolean = false, // shown bold: the current chapter / book / chosen value
            val onLongPress: (() -> Unit)? = null, val onTap: () -> Unit,
        ) : Row()
        /** "Aa  −  28  +" */
        class Stepper(val label: String, val value: String, val onMinus: () -> Unit, val onPlus: () -> Unit) : Row()
        object Divider : Row()
    }
    /** Shows [rows]. With [back], a "‹ [backLabel]" row comes first, and the Back button calls [back]. */
    fun show(rows: List<Row>, backLabel: String? = null, back: (() -> Unit)? = null, scrollTo: Int = -1)
    fun hide()
    val isShowing: Boolean
    /** Back pressed: [back] if the section has one, else hide. Returns false if not showing. */
    fun goBack(): Boolean
    /** Called after hide(). */
    var onHidden: (() -> Unit)?
}
```

- Layout and behavior:
  - The panel is a `ScrollView` holding a vertical `LinearLayout`. It is `min(0.8 × width, 360 dp)`
    wide, on the left, with a 1 dp `Ink.text` line on its right edge.
  - The rest of the Sidebar is an empty transparent area. A tap on it calls `hide()` and consumes
    the touch.
  - Rows are 48 dp tall with 16 dp side padding, using a serif type at 18 sp. Values are on the
    right. "›" is appended to the value for `opens`.
  - `show()` rebuilds the children and calls `requestLayout()` once. There is no animation.
  - Colors are read from `Ink` on every `show()`.
  - `scrollTo` is a row index to bring into view (the current chapter in Contents).
  - Header lines are single-line with `TextUtils.TruncateAt.END`.

- [ ] **Step 1: Write the failing instrumented tests** in `SidebarTest`, using
  `InstrumentationRegistry.getInstrumentation().runOnMainSync` and a Sidebar measured at
  1000×1600 px:
  - `rowsShowLabelsValuesAndBackRow`: `show(listOf(Item("Night mode", "Off") {}), "Menu") {}`.
    The panel's texts in order include "‹ Menu", "Night mode" and "Off".
  - `tapOnItemRunsIt`: `performClick()` on the item's view sets a flag.
  - `goBackCallsBackThenHides`: with `back` set, `goBack()` runs it and returns true. With no
    `back`, `goBack()` hides it (`isShowing == false`), and `onHidden` was called.
  - `tapOutsideClosesWithoutPassingThrough`: dispatch DOWN and UP at x = 950 (outside the 800 px
    panel). `isShowing == false`, and `dispatchTouchEvent` returned true.
  - `headerLinkOpens`: tapping the view of `Header("Beloved", "Ch. 4. X", { flag = true }, listOf("37%"))`'s
    link line sets the flag, and its text ends with "›".
  - `colorsFollowInk`: set `Ink.night = true`, then `show(...)`. An item's TextView `currentTextColor`
    equals `Ink.NIGHT_TEXT`. Reset `Ink.night = false` in `@After`.
  - `panelWidthIsCapped`: at 1000 px width and density 1, the panel is 360 px wide (`min(800, 360)`).
- [ ] **Step 2: Build and run them to see them fail.**
  `./gradlew -q assembleDebug assembleDebugAndroidTest`, install both APKs, then
  `adb shell am instrument -w -e class com.guny90.reader.SidebarTest com.guny90.reader.test/androidx.test.runner.AndroidJUnitRunner`.
  Expected: compilation fails.
- [ ] **Step 3: Implement `Sidebar`** per the Interfaces above.
- [ ] **Step 4: Run them again.** Expected: `OK (7 tests)`.
- [ ] **Step 5: Commit**, message "Sidebar view for the menu".

---

### Task 3: Main menu in the sidebar

**Files:**
- Modify: `app/src/main/java/com/guny90/reader/MainActivity.kt`. Areas: `onCreate` layout
  (lines ~199–215), `showMenu` (~1114–1153), the back handling (~1316–1341) and `onKeyDown`
  (~2193).

**Interfaces:**
- Consumes: `Sidebar` (Task 2) and `ChapterLabel` (Task 1).
- Produces:
  - `private val sidebar: Sidebar`
  - `private fun menuHeader(): Sidebar.Row.Header`
  - `showMenu()` now fills the sidebar. Later tasks pass `::showMenu` as `back` with the label
    "Menu".

- [ ] **Step 1: Wrap the layout.** `setContentView` gets a `FrameLayout` holding the existing
  `LinearLayout` root and then `sidebar` (MATCH_PARENT, `visibility = GONE` until shown). Move the
  insets listener and its padding to the FrameLayout, so the sidebar sits inside the system bars.
- [ ] **Step 2: Rewrite `showMenu()`** to build rows and call `sidebar.show(rows)`.
  - **Header:**
    - title: the book's title, or "Book" with no book open
    - EPUB: link `ChapterLabel.label(chapter + 1, ChapterLabel.currentName(b.toc, chapter, p.offsetOf(page), chapterAnchors::get))`
      opening `showContents()`, then the line `"${percentShown()}%"`
    - PDF: link `"Page ${pdfPage + 1} of ${d.pageCount}"` opening `showGoToPage()` (hide the
      sidebar first), then the line `"${pdfPercent(...)}%"`
  - **EPUB rows:**
    - Read aloud / Stop reading aloud
    - divider
    - Stepper("Aa", "$fontSp", −2, +2). Each step calls `changeFont`, then `showMenu()` again to
      refresh the value.
    - Text ›
    - Night mode (value On/Off)
    - Page turns (value "Left · right" / "Top · bottom")
    - divider
    - Words ›
    - Sync ›
    - divider
    - Library
  - **PDF rows:**
    - Colors (value)
    - Invert pages at night (value, at night only)
    - Night mode
    - Page turns
    - divider
    - Words ›
    - Sync ›
    - divider
    - Library
  - **With no book open:** Dictionaries ›, Sync ›, Night mode, Page turns, divider, Library.
  - Actions that leave the menu call `sidebar.hide()` first: Read aloud and Go to page.
  - There are no Contents or "Back to ch N" rows. The header's chapter line opens Contents, and
    the Back button still returns along links (`goBack`).
    Toggles call `showMenu()` again to refresh their values.
- [ ] **Step 3: Rename the existing `turnZonesName()`** values to "Left · right" / "Top · bottom",
  and the row label to "Page turns".
- [ ] **Step 4: Back.**
  - When the sidebar is shown (API 33+), register an `OnBackInvokedCallback` at
    `OnBackInvokedDispatcher.PRIORITY_OVERLAY` that calls `sidebar.goBack()`. Unregister it in
    `sidebar.onHidden`.
  - `onBackPressed()` checks `sidebar.goBack()` first.
  - `onKeyDown`: while the sidebar is showing, MENU / DPAD_CENTER / ENTER hide it. Page keys do
    nothing.
  - `PageView.Zone.MENU` taps open it, as now.
- [ ] **Step 5: Build, install and check by hand on the emulator.**
  - Tap the middle of the page: the sidebar shows the header with "Ch. N. <name> ›". Tapping it
    opens Contents.
  - Toggle Night mode: the values and colors change in place.
  - Use − and +: the number changes and the page re-lays out underneath.
  - Tap the page strip: the sidebar closes and the page doesn't turn.
  - Get the texts with `uiautomator dump`.
- [ ] **Step 6: Commit**, message "Menu in a sidebar".

---

### Task 4: Sections in the sidebar

**Files:**
- Modify: `MainActivity.kt`: `showTextMenu`, `showWordsMenu`, `showSyncMenu`, `chooseOption`,
  `chooseParagraphSpacing`, `chooseFont`, `showDictionaries`, `showContents`, `showLibrary`.

**Interfaces:**
- Consumes: `sidebar.show(rows, backLabel, back, scrollTo)` and `Sidebar.Row.*`.
- Produces: the same function names, which now fill the sidebar. `chooseOption(title, names,
  selected, back, onPick)` keeps its signature. `back` also names the parent for "‹ …": add a
  `backLabel: String` parameter.

- [ ] **Step 1: Text, Words, Sync.**
  - Text:
    - "Larger text" and "Smaller text" are dropped, because the main menu's stepper does that.
    - Font (value), Line spacing, Paragraph spacing, Margins, Justify (API 26+), Pictures, and
      Picture colors when pictures are on.
    - Back label "Menu".
  - Words: One word (value), Several words (value), Dictionaries › (value: count or "none").
  - Sync:
    - Reading position (value: username or Off) opens the existing `showSyncSettings` dialog.
    - Books (value: folder or Off) opens the existing `showFolderSettings` dialog.
    - Hide the sidebar before opening a dialog. The dialogs' existing `::showSyncMenu` back
      reopens it.
- [ ] **Step 2: Choices.**
  - `chooseOption` and `chooseParagraphSpacing` show one Item per name, with `marked` on the
    selected one.
  - Picking runs `onPick` and then `back()`. Back label is the parent section's name, e.g.
    "Text" or "Words".
  - Remove `chooseParagraphSpacing`'s own dialog: it calls `chooseLayoutOption` like line
    spacing does.
- [ ] **Step 3: Dictionaries.**
  - Rows: one per dictionary, with value "N words", and a tap opens the existing remove
    confirmation (hide the sidebar first).
  - Then a divider and "Add…" (`pickDictionary()`).
  - With none, one non-tappable line, "No dictionaries yet", then "Add…".
  - Back label "Words", or "Menu" with no book open.
- [ ] **Step 4: Contents.**
  - One Item per entry (the `Chapter N` fallback stays). `marked` is on the entry
    `ChapterLabel.currentName` picks, and `scrollTo` is that index.
  - A tap hides the sidebar, then does the existing jump.
- [ ] **Step 5: Library.**
  - Rows: "Add a new book…" first, then one per library entry. The value is the percentage, and
    `marked` is on the open book.
  - Then one per shared-only book, prefixed "⇣ ".
  - Long-press does the existing remove, delete or "this book is open" toast.
  - After a confirmation dialog, `showLibrary()` reopens the sidebar on the Library section.
  - A tap on a book hides the sidebar first.
  - Library with nothing in it still goes straight to `pickBook()`.
- [ ] **Step 6: Build, install and check every section by hand on the emulator.**
  - Open each section and choose a value. It comes back to its section with the new value.
  - Back goes up one level.
  - Contents jumps, and its current chapter is bold and scrolled into view.
  - Library opens a book. Long-press on the open book shows the toast.
  - Dictionaries lists them.
  - Run all instrumented tests (`OK`) and `./gradlew -q testDebugUnitTest`.
- [ ] **Step 7: Commit**, message "Menu sections open inside the sidebar".

---

### Task 5: Docs and final check

**Files:**
- Modify: `README.md`, in *Links and footnotes* (drop "Menu → Back to where you were"; keep
  the Back button), *Turning pages* (the "Menu → Tap to turn pages" line becomes
  "Menu → Page turns"), *The menu*, *Text and pictures* (text size is now − / + at the top of
  the menu), and *Library* ("Menu → Library").

- [ ] **Step 1: Update the README.**
  - *The menu*: a sidebar from the left, with its three groups and the header (title,
    "Ch. N. name", percentage). Back goes up one level. Tapping the page closes it.
  - Fix every "Menu → X" path that changed: "Open book…" → "Library", "Tap to turn pages" →
    "Page turns", and Larger/Smaller text → "Aa − +".
- [ ] **Step 2: Remove dead code.** That's `showList` if nothing calls it anymore. The selection
  action list and the dictionary chooser still use it, so it probably stays. Confirm with
  `grep -n "showList(" MainActivity.kt`.
- [ ] **Step 3: Final run on the emulator.**
  - All unit and instrumented tests pass.
  - By hand, follow a link and then press Back with the sidebar open. It goes up or closes and
    doesn't jump back along the link.
  - Press − at 12 sp and + at 40 sp: nothing changes.
  - Open a PDF, open the menu, and see the PDF rows.
- [ ] **Step 4: Commit**, message "README: the sidebar menu".
