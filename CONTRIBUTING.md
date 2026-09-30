# Contributing

Anki Popup is a small native macOS utility. Please keep the startup and study flow simple and offline.

1. Fork the repo and make a focused change.
2. Run `./scripts/test.sh` and `./scripts/build.sh` on macOS.
3. For UI changes, check first-run settings, audio clicks, dragging, pause/resume, × followed by automatic reappearance, and click-through controls from the menu bar.
4. Include a short explanation and the relevant validation in your pull request.

Tests must use synthetic collections. Never commit real Anki collections, media, saved preferences, credentials, or signing certificates. Add no network requests without an explicit product discussion.

`Sources/Collection.swift` handles read-only data access and the popup deadline; `App.swift` handles the overlay; `Settings.swift` handles preferences. Builds use only Apple's tools and system SQLite.

Release maintainers: the CI release workflow builds from version tags and attaches the universal ZIP and checksum. The current distribution is ad hoc signed and not notarized; do not claim Apple notarization unless a Developer ID signing and notarization step is actually added.
