# Anki Popup for Android — floating cards

[日本語](README.ja.md) · [Download APK](https://github.com/iruom/anki-popup/releases/tag/android-v0.2.0)

A native Kotlin companion for **AnkiDroid**. Front and back appear together in a movable, translucent card over other apps. Cards change every 30 seconds by default. A quiet notification provides ongoing session controls.

This first release is **alpha**: built and checked with automated Android 9/API 28 and Android 15/API 35 framework tests using synthetic cards. Physical-device behavior, real AnkiDroid installations, and manufacturer battery policies still need testing. It is distributed on GitHub, not Google Play.

## Install and start

1. Install [AnkiDroid](https://ankidroid.org/) and sync your decks. Open AnkiDroid once and enable its API under Settings → Advanced if it is disabled.
2. Download **AnkiPopupAndroid-0.2.0.apk** from the Android release. Android will ask you to allow installation from the app used to open the APK. This APK is signed with the project's local release key, not the Android debug key.
3. Open Anki Popup and tap **Connect AnkiDroid / reload decks**. Grant the AnkiDroid API permission.
4. Select a deck, interval, and optional front/back field names. Blank field names select common Front/Back, Expression/Meaning, 表面/裏面 names or the first two fields.
5. Keep **Floating card over other apps** enabled and tap **Start**. Allow **Display over other apps** for Anki Popup in Android settings, return to the app, and tap Start again. Allow notifications on Android 13+.
6. Switch to another app: the card stays over it. Drag the header to move it; the position is saved. Use **Audio**, **Next**, **Hide card**, or **Stop** on the card. The card body scrolls for longer examples.

**Android 8.0/API 26 or newer**. AnkiDroid's standard `com.ichi2.anki` package is supported; parallel/debug flavors with different providers are not supported yet.

The app has a built-in **sample mode**, so you can test notifications without AnkiDroid or access to your collection.

## How it behaves

- Floating mode requires Android’s **Display over other apps** permission. Disable the floating switch for the original notification-only mode. No accessibility service or screen recording is used.
- The compact window does not take keyboard focus or intercept touches outside its bounds. The card itself receives touches.
- Floating cards are hidden while the screen is off or locked; the window is excluded from screenshots/screen capture.
- Android can suppress overlays on sensitive apps, permission dialogs, system screens, or apps that request overlays be hidden. It cannot appear over absolutely every screen.
- A user-started foreground service keeps a single study notification visible.
- **Hide card** completely removes the floating card and replaces notification content with a neutral running-session message. The existing deadline stays intact, and the next card appears at the next interval. The small service notification remains visible so the session can always be stopped.
- **Next** is available on the floating card and in the app; while a card is hidden, the notification also offers Next to show one immediately.
- Uses shuffled notes without repeats until a full pass is complete. Reverse cards are deduplicated.
- Stops when you tap Stop. There is no boot autostart or automatic process restart.
- During device sleep, updates are deferred; when scheduling resumes, it advances to one fresh card rather than flooding notifications. Battery restrictions and notification settings can change behavior.
- Content is marked **private** on the lock screen, with a generic public version. Android and user lock-screen settings ultimately control what is shown.
- Text-oriented notes are supported. Custom Anki template rendering, cloze formatting, images, videos, and MathJax are not reproduced. Expand the notification for the answer; long text is limited by Android's notification layout.

## Audio

Default audio is **device text-to-speech of the front field**. Choose the reading language, such as `en-US` or `ja-JP`. The app selects an offline voice and does not fall back to a network-required voice. If no compatible offline voice is installed, it tells you to install one.

To use saved Anki recordings, optionally choose a **readable media directory** using Android's folder picker. Matching `[sound:filename]` recordings from the front/back fields are played in order. If files cannot be accessed, the app uses offline device speech instead. This can also be a directory containing copies of those audio files.

AnkiDroid's API does not provide a general-purpose endpoint to read saved media. On some installations (especially app-private/scoped-storage locations), Android does not let you select its media directory. AnkiDroid API permission alone does **not** make recordings available. This release does not request broad storage access or try to bypass Android's storage restrictions.

Audio only plays when tapped. Hiding, stopping, or switching cards cancels playback. Notification-channel settings remain silent; the Audio button intentionally plays speech/media.

## Privacy and permissions

- Queries AnkiDroid's documented ContentProvider for decks, notes, and field names. It never calls insert, update, delete, or review-grading endpoints.
- AnkiDroid's permission is named **READ_WRITE_DATABASE** even though this app only reads. Android currently grants that API permission as a combined read/write capability.
- No Internet permission, analytics, backend, external card uploads, or included user collections.
- Preferences are stored locally; cloud backups and device transfers of app data are excluded.
- Optional saved audio uses only a folder explicitly selected by the user, with read-only persisted access. Clearing that folder revokes the saved access.
- The device's TTS engine runs separately; an offline voice is required by the app.

## Build and test

Requires JDK 17 and Android SDK Platform 35/Build Tools 35.0.0. Android Studio can import this folder directly.

```sh
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```

The Gradle wrapper has a pinned distribution SHA-256. `assembleDebug` creates a debug-signed developer APK; `assembleRelease` creates an unsigned release APK. Publish only after signing it with your own private release key. Never commit signing keys or passwords.

The GitHub workflow builds and tests Android, and uploads a debug APK as a development artifact. Published APKs use the maintainer's separate release key, retained locally; future updates must use the same key. GitHub is not given that private key.

Tests cover floating-window creation/removal, non-focusable window flags, lock/unlock hiding, the original hide deadline, automatic reappearance at 30 seconds, notification replacement and stop cleanup, private lock-screen content, shuffle boundaries, field mapping, HTML stripping, and API queries without writes. All test notes are fictional.

## Report a problem

Include your Android version, device manufacturer, AnkiDroid version/install source, app version, and what you expected. Share fictional examples or field names, not your collection or audio.

Independent from the Anki/AnkiDroid projects. MIT license, like the Mac app.
