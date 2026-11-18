# Book

A simple, offline reader for EPUB and PDF books on Android, made to work well on e-ink devices: no animations, plain black on white, and one screen refresh per page turn.

You can look up words in your own dictionaries, send phrases to Google Translate, have the book read aloud, and keep your reading position in sync across devices.

Works on Android 7.0 and newer.

## Installing

1. Copy the `.apk` file to your device, or open it from Google Drive.
2. Tap it and allow installing from this source if Android asks.
3. A newer version installs over the old one and keeps your books, positions and settings.

## Opening a book

- On first start, tap the **middle of the screen** and pick an `.epub` or `.pdf` file.
- Or open a book from your file manager with **Book**.
- To switch books later: **Menu → Open book…**

Book shows one book at a time. When you come back to a book, it opens where you left off.

## Turning pages

The screen is split into three columns:

| Tap the… | Does |
|---|---|
| left third | previous page |
| middle | opens the menu |
| right third | next page |

The **volume keys** turn pages too: down for next, up for previous. Page-turn buttons and keyboard arrows also work.

The bottom line shows the book title, the page within the chapter and the chapter number.

## The menu

Tap the middle of the screen. The items depend on the book:

- **Back to where you were**: appears after you follow a link (see below).
- **Contents**: the book's table of contents.
- **Read aloud**
- **Text**: text size, paragraph spacing, pictures.
- **Words**: what selecting words does, and your dictionaries.
- **Sync**: reading position sync.
- **Open book…**

For PDFs: **Go to page…**, **Words** and **Colors**.

## Text and pictures

**Menu → Text**:

- **Larger text / Smaller text**: changes the size in steps of 2. The menu stays open, so you can tap again.
- **Paragraph spacing**: None, Small, Medium or Large.
- **Pictures: On / Off**: shows or hides the pictures in the book. Your place in the book stays the same either way.
- **Picture colors: Gray / Original**: Gray suits black-and-white e-ink screens. Choose Original on a phone, tablet or color screen.

**Long-press a picture** to see it full screen.

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

1. **Menu → Sync**
2. The first time, tap **Create account**. On your other devices, enter the same username and password and tap **Sign in**.
3. When you open a book on a device that is behind, Book asks whether to continue from the other device's position.

Your position is uploaded when you leave the app or turn off the screen. **Sync now** checks right away. Both devices need the **same book file** for positions to match.

## Troubleshooting

- **"Could not open this book"**: the file may be damaged or DRM-protected. Book can't open DRM-protected books.
- **A tap opens the wrong thing**: links take priority over page turns. Tap a little away from underlined text.
- **No dictionary popup**: add a dictionary first (see *Adding dictionaries*).
- **Google Translate isn't offered**: install the Google Translate app.
- **Read aloud is silent or uses the wrong voice**: download the book's language voice in the text-to-speech settings.

---

## For developers

Kotlin, platform APIs only (no WebView). Open in Android Studio, or build from the command line:

```sh
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

`tools/sjp_to_stardict.py` converts the SJP.pl Polish dictionary to StarDict. See the comment at the top of the script.
