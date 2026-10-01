# Anki Popup

**Android floating-card edition is now available as an alpha:** [Download APK](https://github.com/iruom/anki-popup/releases/tag/android-v0.2.0) · [Android setup guide](android/README.md). The rest of this page describes the Mac app.

**A little Anki on your desktop.** Random notes, front and back together, on a quiet floating card.

[日本語](README.ja.md) · [Download for macOS](https://github.com/iruom/anki-popup/releases/latest) · [Report an issue](https://github.com/iruom/anki-popup/issues/new/choose)

![Anki Popup with a fictional sample note](docs/preview.png)

Anki Popup is a native Swift + AppKit menu bar app for macOS. It reads your local Anki collection, shuffles your selected deck, and displays each note for 30 seconds by default. No Anki add-on or running Anki instance is required.

## What it does

- Shows the front and back together, with optional examples and extra notes.
- Uses a macOS Vibrancy background and keeps your keyboard focus in your working app.
- Plays the selected fields' saved Anki audio when you click the card or ▶︎.
- Lets you drag the header anywhere; remembers the position across cards and restarts.
- Hides the current card with × **until the next scheduled change**. Closing it does not reset the timer.
- Includes child decks; shuffles notes without repeats until the deck is exhausted.
- Offers pause, next card, hide, settings, and corner positions from the menu bar.
- Lets you choose a profile, deck, front/back fields, interval (5–3600 seconds), and click-through mode.
- Provides Japanese and English UI based on your macOS language.

This is ambient exposure to material you already study. It does not grade answers or change Anki's review schedule.

## Install

**macOS 13 Ventura or later**, on Apple silicon or Intel. Anki Desktop must already have a local collection with notes.

1. Download `AnkiPopup-1.0.0-universal.zip` from [Releases](https://github.com/iruom/anki-popup/releases/latest).
2. Unzip it and move **Anki Popup.app** to Applications.
3. Open the app. Choose your Anki profile and deck, then **Save & start**.
4. Look for **Anki** in the menu bar to change settings, pause, or quit.

The initial release is ad hoc signed, **not Developer ID signed or notarized**. macOS may block the downloaded app. Only if you trust this project, use Apple's **System Settings → Privacy & Security → Open Anyway** flow after trying to open it. Do not disable Gatekeeper. See [Apple's guidance](https://support.apple.com/guide/mac-help/open-a-mac-app-from-an-unknown-developer-mh40616/mac). You can also build it yourself below.

Optional: compare the ZIP's SHA-256 with `SHA256SUMS.txt` in the release.

```sh
shasum -a 256 AnkiPopup-1.0.0-universal.zip
```

## First-run choices

| Setting | Behavior |
| --- | --- |
| Profile | Auto-discovers `~/Library/Application Support/Anki2/*/collection.anki2`. Browse supports custom locations. |
| Deck | All decks or a selected deck and its children. |
| Front / back | Automatic recognizes common names like Front/Back, Expression/Meaning, 表面/裏面; otherwise uses the first two fields. Select fields explicitly for other layouts. |
| Interval | Default 30 seconds. × preserves the current interval's deadline. |
| Extra fields | Common example, translation, notes, and memo fields are included when enabled. |
| Pass clicks through | Mouse events go to the app underneath. Audio clicks, dragging, and × are disabled; use the menu bar for hiding, audio-independent controls, and position presets. |

Audio comes from `[sound:filename]` tags in your chosen front and back fields. It plays only on request, skips missing files, and never synthesizes speech or downloads audio. Clicking again restarts playback. Hiding the card, pausing, or changing cards stops playback.

## Compatibility and limits

Anki Popup is a **note-field viewer**, not Anki's card-template renderer. It supports text-oriented notes with at least two fields. Reverse cards are deduplicated into one note. Cloze deletion rendering, custom template logic, images, video, MathJax, and card-side styling are not reproduced. HTML formatting is converted to plain text. These limitations are shown in the settings window too.

Both modern `decks`/`fields` SQLite tables and older `col.decks`/`col.models` JSON metadata are supported. The SQLite schema is Anki's internal implementation, so a future Anki update may require a compatibility fix. Tested locally with a modern collection and synthetic modern/legacy fixtures. Intel builds are cross-compiled; runtime UI testing was performed on Apple silicon.

Cards are loaded at startup or when you save settings. After editing or syncing in Anki, save settings again to reload. A manually moved window keeps its top-left anchor as card height changes and is kept inside an available display.

Anki Popup is independent software and is not affiliated with the Anki project.

## Privacy

The app opens your selected collection with SQLite **read-only** access and a read transaction. It never writes to your collection or review history. It has no networking, analytics, accounts, or automatic uploads. Local audio is restricted to the collection's media directory. Preferences contain only your selected path, deck/field choices, interval, and window position; stored in macOS UserDefaults. Your collection and media are not bundled with the app or repository.

## Build from source

Install Apple's Xcode Command Line Tools (`xcode-select --install`) or Xcode, then:

```sh
git clone https://github.com/iruom/anki-popup.git
cd anki-popup
./scripts/test.sh
./scripts/build.sh
open "dist/Anki Popup.app"
```

No third-party runtime dependencies. The build produces a universal macOS 13+ app, ZIP, and SHA-256 checksum in `dist/`. Build products are excluded from Git. Set `VERSION=1.0.1 ./scripts/build.sh` to change the version.

Tests use generated fixtures only, never your own collection. They cover deck selection, subdecks, filtered decks, duplicate reverse cards, field mapping, unchanged database bytes, media path checks, and the dismiss/reappear deadline.

## Contributing

Small fixes and improvements are welcome; see [CONTRIBUTING.md](CONTRIBUTING.md). For a bug report, share your macOS/Anki/app versions and field names or a fictional example. Please do not upload your collection, audio, or personal note contents.

Licensed under [MIT](LICENSE).
