# Sidebar menu

## Why

The menu is a flat pop-up list of up to 9 rows. Several labels cram in their current values
("Text: Literata 28 · Small spacing · pictures on"), things you do are mixed with settings and
with rarely used setup, and every submenu opens another pop-up. It feels overloaded. The new menu
should group items the way a reader thinks about them, and stay minimal and e-ink friendly.

## Goals

- A sidebar instead of the pop-up, opened by tapping the middle of the page, as now.
- Items in three groups: reading, page, setup. Short labels.
- Submenus open inside the sidebar; nothing stacks.

## Non-goals

- New features or settings. Every existing item stays reachable.
- Redesigning the other pop-ups: sign-in, confirmations, the dictionary popup, picture viewer,
  "Go to page" input. They stay as they are.

## Anti-goals

- Animation, dimming, shadows, or anything that costs extra e-ink refreshes.
- Decoration: icons, colors, headings for the groups.

## Approaches

- **A. Sidebar with sections opening inside it (chosen).** Text, Words, Sync, Contents and
  Library replace the sidebar's content, with a "‹ Back" row on top. One screen update per tap,
  nothing stacks.
- B. Sidebar for the main menu only, submenus as today's pop-ups. Less work, but inconsistent:
  half the menu would still be pop-ups.

## Design

```
┌──────────────────────────┬─────────┐
│ Beloved                  │         │
│ Ch. 4. The Clearing   ›  │  page   │
│ 37%                      │ (tap    │
│                          │  here   │
│ Read aloud               │  to     │
│ ──────────────────────── │  close) │
│ Aa   −   28   +          │         │
│ Text                  ›  │         │
│ Night mode         Off   │         │
│ Page turns    Left·right │         │
│ ──────────────────────── │         │
│ Words                 ›  │         │
│ Sync                  ›  │         │
│                          │         │
│ Library                  │         │
└──────────────────────────┴─────────┘
```

- **Header:** book title, then the chapter, then the percentage read.
  - The chapter reads "Ch. N. <name>". N is the same chapter number as the status line
    ("ch 4/22"), and the name comes from the book's table of contents.
  - The name is the last contents entry at or before the current page. Within a chapter, an
    entry's anchor must be at or before the page's first character.
  - Without a name, or when the name is only the number again (e.g. "4", "Chapter 4", "IV"), it
    reads "Ch. N".
  - Long names are cut with "…" on one line.
  - Tapping the chapter line opens Contents, marked with "›". There is no separate Contents
    row. For PDFs the line is "Page N of M ›" and opens Go to page.
- **Reading group:** Read aloud (or Stop reading aloud).
  - There is no "Back to ch N" row. After following a link, the Back button still returns.
  - The README's "Menu → Back to where you were" goes.
- **Page group:**
  - A text-size row: "Aa − 28 +". The minus and plus change the size in place and the sidebar
    stays open.
  - Text › for font, line spacing, paragraph spacing, margins, justify and pictures.
  - Night mode and Page turns switch with one tap, showing their value on the right.
- **Setup group:** Words ›, Sync ›.
- **Library** sits at the bottom.
- **Values** sit right-aligned and only where they're short. "›" marks a row that opens a section.
- **Sections** (Text, Words, Sync, Contents, Library, the choice lists inside them) replace the
  sidebar content. Their first row is "‹ <parent name>", e.g. "‹ Menu" or "‹ Text".
  - Back (button or gesture) goes up one level, and closes the sidebar at the top level.
  - Picking a value goes back to the section it came from, as today.
- **Look:**
  - Black on white, plain type, a thin vertical border at the sidebar's edge, no shadow and no
    dimming.
  - It appears and changes in one redraw, with no animation.
  - In night mode, the colors are inverted.
- **Closing:** tapping the visible strip of page closes the sidebar. So does picking an action
  that leaves the menu (a contents entry, a book, Read aloud).
- **PDFs:** the same panel, holding only what applies: Go to page (from the header), Colors,
  Invert pages at night, Words, Sync, Night mode, Page turns, Library.
- **No book open:** Dictionaries, Sync, Night mode, Library.

## Left to the builder

- **Side:** the left edge, the usual Android side. The user didn't choose; this is my call.
- **Width:** about 80% of the screen on phones, capped near 360 dp on large screens, so some page
  always shows to tap.
- **Long lists** (Contents, Library, Dictionaries) scroll inside the sidebar. Contents marks the
  current chapter.
- **Implementation:** a plain View or overlay inside the activity, not a support-library drawer,
  which slides and dims. The existing pop-ups for input and confirmation stay.
