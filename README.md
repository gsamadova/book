# Book

A simple, offline reader for EPUB and PDF books on Android, made to work well on e-ink devices: no animations, plain black on white, and one screen refresh per page turn.

You can look up words in your own dictionaries, send phrases to Google Translate, have the book read aloud, and keep your reading position in sync across devices.

Works on Android 7.0 and newer.

## Installing

1. Copy the `.apk` file to your device, or open it from Google Drive.
2. Tap it and allow installing from this source if Android asks.
3. A newer version installs over the old one and keeps your books, positions and settings.

## Opening a book

- On first start, tap the **middle of the screen**, then **Open book…**, and pick an `.epub` or `.pdf` file.
- Or open a book from your file manager with **Book**.

### Library

**Menu → Open book…** shows your library: the last 10 books you opened on this device, most recent first. The book that is open now is marked with ▸.

- Each book shows how much of it you've read.
- Tap a book to open it where you left off.
- Tap **Add a new book…** at the top to pick a file from your device.
- **Long-press a book** to remove it from the library. Its reading position is kept. You can't remove the book that is open: open another one first.
- Books marked **↓** are in your shared folder but not on this device yet (see *Syncing your books*). Tap one to open it; long-press it to delete it from the shared folder.

Book keeps its own copy of each book in the library, so the original file can be moved or deleted. When an 11th book is added, the oldest one leaves the library. Its reading position is remembered, so if you add it again it opens where you left off.

When the app starts, it opens the book you read last.

## Turning pages

The screen is split into three columns:

| Tap the… | Does |
|---|---|
| left third | previous page |
| middle | opens the menu |
| right third | next page |

The **volume keys** turn pages too: down for next, up for previous. Page-turn buttons and keyboard arrows also work.

The bottom line shows the book title, the page within the chapter, the chapter number, and how much of the whole book you've read. Long chapters count for more than short ones, so a cover page doesn't count as a whole chapter.

While you read a chapter, Book prepares the next one in the background, so turning into it doesn't make you wait.

While you read, the screen stays on, even if your phone normally turns it off sooner. After 5 minutes without turning a page, your usual screen timeout applies again.

## The menu

Tap the middle of the screen. The items depend on the book:

- **Back to where you were**: appears after you follow a link (see below).
- **Contents**: the book's table of contents.
- **Read aloud**
- **Text**: font, text size, spacing, margins, justified text, pictures.
- **Words**: what selecting words does, and your dictionaries.
- **Sync**: reading position and book sync.
- **Night mode**
- **Open book…**

For PDFs: **Go to page…**, **Words**, **Colors**, and at night **Invert pages at night**.

In a submenu, the **Back** button or gesture goes up one level, for example from *Paragraph spacing* to *Text*, then to the main menu. Tapping outside a menu closes it.

## Text and pictures

**Menu → Text**:

- **Font**: Serif, Literata (a font made for reading on screens), Sans-serif, Condensed (fits more words on a line) or Monospace.
- **Larger text / Smaller text**: changes the size in steps of 2. The menu stays open, so you can tap again.
- **Line spacing**: Tight, Normal, Relaxed or Loose.
- **Paragraph spacing**: None, Small, Medium or Large.
- **Margins**: Narrow, Medium or Wide.
- **Justify: On / Off**: lines fill the full width, as in a printed book, and long words are hyphenated in the book's language. Opening a chapter takes a little longer. Needs Android 8 or newer.
- **Pictures: On / Off**: shows or hides the pictures in the book. Your place in the book stays the same either way.
- **Picture colors: Gray / Original**: Gray suits black-and-white e-ink screens. Choose Original on a phone, tablet or color screen.

**Long-press a picture** to see it full screen.

## Night mode

**Menu → Night mode** shows light text on black, for reading in the dark. Menus, the dictionary popup and the read-aloud bar turn dark too. Pictures in books keep their colors.

PDF pages are inverted at night, which suits pages of text. For comics or picture books, turn this off with **Menu → Invert pages at night**.

## Links and footnotes

- **Footnotes** (small numbers like `1` or `[1]`): tap one to read the note in a popup without leaving your page. **Go to note** takes you to where the note sits in the book.
- **Links within the book** (for example the book's own contents page): tap to jump there. Press the phone's **Back** button, or use **Menu → Back to where you were**, to return.
- **Web links**: Book asks before opening them in your browser.

Tip: on a page that is all links, such as a contents page, tap a blank area or use the volume keys to turn the page.

## Looking up words

**Long-press a word** to select it. To select more than one word, keep your finger down and slide it over the words. Lift your finger to act on the selection.

By default:

- **One word** opens the **dictionary**.
- **Several words** go to **Google Translate** (if it's installed).

You can change both in **Menu → Words**. Choosing **Ask** shows a list each time: Dictionary, Google Translate or Copy.

The dictionary popup has buttons to **Speak** the word, **Translate** it, **Copy** it, or switch to another dictionary. Book remembers the dictionary you chose for each book. If the word isn't found, the popup suggests similar words.

On PDF pages you can select words the same way. Scanned PDFs (pictures of pages) have no text to select.

### Adding dictionaries

Book uses dictionaries in the **StarDict** format, which you can find online for many languages.

1. **Menu → Words → Dictionaries → Add…**
2. Select the dictionary's files together (`.ifo`, `.idx` and `.dict` or `.dict.dz`), or one `.zip` / `.tar.gz` archive that contains them.

To remove a dictionary, tap it in the same list.

## Reading aloud

**Menu → Read aloud** reads the book from the top of the current page. It uses your device's text-to-speech voices and turns the pages as it goes. The sentence being read is underlined.

- The bar at the bottom has **Pause / Play**, **Slower**, **Faster** and **Stop**.
- Tapping the page pauses reading.
- Reading stops when the screen turns off or you leave the app.
- Footnote numbers are skipped.

If Book says a voice is missing, download it in your device's **text-to-speech settings**. Book can open them for you. Offline voices work without the internet.

## PDFs

PDF pages are shown as they are printed, with white margins trimmed so the text is as large as possible.

- **Menu → Go to page…** jumps to a page number.
- **Menu → Colors** switches between gray and original colors.

Text size and reading aloud are not available for PDFs.

## Syncing your reading position

Book can keep your place in sync between devices, for example a phone and an e-ink reader. It uses a **KOReader sync server**, which also works with KOReader itself. The default server is `https://kosync.eu`.

1. **Menu → Sync → Reading position**
2. The first time, tap **Create account**. On your other devices, enter the same username and password and tap **Sign in**.
3. When you open a book on a device that is behind, Book asks whether to continue from the other device's position.

Your position is uploaded when you leave the app or turn off the screen. **Sync now** checks right away. Both devices need the **same book file** for positions to match.

## Syncing your books

Book can make a book you open on one device show up in the Library on your others. It uses a folder that a sync app keeps the same on all your devices, such as [Syncthing](https://syncthing.net) (free, no account) or FolderSync.

1. Set up the sync app on each device so that it syncs one folder, for example `Documents/Books`.
2. In Book, **Menu → Sync → Books → Choose folder…**, and pick that folder. Do this on each device.
3. Book copies the books in your Library into the folder. From then on, every book you add is copied there too.

On your other devices, those books appear in the Library marked **↓**. Tap one to open it. If position sync is on, it opens where you left off.

- You can also put `.epub` and `.pdf` files into the folder yourself.
- Removing a book from a device's Library leaves it in the folder. To delete it from the folder, long-press it where it's marked ↓.
- **Menu → Sync → Books → Stop using** stops copying. The folder and its books are left as they are.

## Troubleshooting

- **"Could not open this book"** or **"Adding this book failed"**: the file may be damaged or DRM-protected. Book can't open DRM-protected books. The message says which step failed.
- **A tap opens the wrong thing**: links take priority over page turns. Tap a little away from underlined text.
- **A book from another device isn't in the Library**: check that the sync app has finished syncing the folder.
- **No dictionary popup**: add a dictionary first (see *Adding dictionaries*).
- **Google Translate isn't offered**: install the Google Translate app.
- **Read aloud is silent or uses the wrong voice**: download the book's language voice in the text-to-speech settings.

### Error logs

When something goes wrong, Book saves the details in a file, one per error, such as `local/book/logs/errors-2026-11-21_18-32-06-024.log` on your device's storage. The error message on screen says which file. Copy it to a computer over USB, or open it with a file manager, and send it along with your bug report.

On first start, Book asks for access to your storage to save logs there. On Android 11 and newer, tap **Allow**, then turn on **Allow access to manage all files**. Without this access, logs are saved in `Android/data/com.guny90.reader/files/logs` instead. Only the newest 50 logs are kept.

---

## For developers

Kotlin, platform APIs only (no WebView). Open in Android Studio, or build from the command line:

```sh
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

Release builds are signed with a key kept outside the repository. Set its location and password in `~/.gradle/gradle.properties`:

```properties
BOOK_STORE_FILE=/path/to/book-release.p12
BOOK_STORE_PASSWORD=...
BOOK_KEY_ALIAS=book
```

Without these, release builds are unsigned. Updates only install over an app signed with the same key, so keep a backup of the key file and its password.

`tools/sjp_to_stardict.py` converts the SJP.pl Polish dictionary to StarDict. See the comment at the top of the script.
