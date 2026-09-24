# Implementation decisions

`design.md` is the source of truth. This file records every place where the implementation had to interpret it, resolve a conflict, or deviate from it, and why. The tie-breaker is always the design's philosophy: real `.txt` files, no internal notes store, and the smallest possible APK.

## Build and tooling

- **Plugin versions.** AGP 8.13.0 with Kotlin 2.2.20 and Gradle 8.14.3, exactly as §11.3 pins them. Newer Kotlin releases exist, but this combination is known to work together; bump both together later.
- **Release signing.** §11.5 reads the release key from `PT_KEYSTORE*` environment variables. When they are absent (local builds, forks, the default GitHub Actions run) the release build is signed with the debug key instead of producing an uninstallable unsigned APK. The CI workflow caches the debug key so successive CI builds can be installed over each other. For a Play Store release, set the four `PT_*` secrets.
- **Instrumented-test dependencies.** §2.3 lists AndroidX test, Espresso, and UI Automator for `androidTest`. The requested scope for this build is JVM unit tests only, so those dependencies are not declared yet; they are test-only and would not affect the APK when added.
- **CI lint job.** §10.4 runs lint on every PR. Lint runs as a separate job so a lint finding never prevents the APK artifacts from being produced.

## Core editor and state

- **Dialogs across recreation.** §5.29 says the retained session also reopens the open dialog after a recreation. Rotation, resizing, and keyboard changes don't recreate the activity at all (`configChanges`), so this only matters for dark-mode, font-scale, and locale changes. In those cases the dialog is dismissed but the pending action (for example "Exit after saving") is kept. Rebuilding each dialog from saved state would add code for a rare case.
- **Text is laid out in slices.** Android lays out an EditText by measuring every paragraph of the changed text in one pass, and holds one measurement object per paragraph until the pass ends. A 500,000-line file ran out of memory on open (found with `stress-test-files/`). Opening, Replace All, Undo/Redo of a big change, and every full relayout (zoom, font, word wrap, rotation, window resizing) therefore add the text 10,000 lines at a time (`EditorView.setTextInSlices`, `replaceInSlices`, `onMeasure`). The result is the same layout with bounded memory. The tab-stop span is attached while the text is empty and grows with it, because adding a span over a big text re-lays out all of it at once.
- **Very long lines open read-only by default.** §5.31 only shows a toast for lines over 100,000 characters. Android re-measures a whole line on every change to it, so typing in a 5,000,000-character line froze the app for about 2 seconds per keystroke, long enough for "App isn't responding". A file with a line over 500,000 characters (about 0.2 s per keystroke) therefore asks first: **Open read-only**, **Edit anyway**, or **Cancel**. Read-only here behaves like the binary view (no editing, Save, or Save As). A clean document restored after process death reopens read-only without asking.
- **Slow relayouts are announced, and zoom steps are batched.** Word wrap, zoom, and font changes re-lay out the whole text on the main thread, which takes seconds for a multi-MB or 10,000+-line document and can't be split across frames without a custom text view. For such documents the app shows a "Reformatting a large document" toast first (toasts are drawn by the system, so it stays visible while the app is busy). Repeated zoom steps (Ctrl+= held or pressed several times, or a pinch) update the zoom percentage immediately but relayout only once, 400 ms after the last step.
- **Clean document after process death.** The design covers a dirty document (restore from the recovery buffer). For a clean document with a file behind it, the app reads the file again from its URI, without `.LOG` and without touching Recent files, so the user comes back to the same file instead of an empty Untitled one.

## File I/O

- **Undecodable file with a detected encoding.** If a file has a UTF-8 or UTF-16 BOM (or passes the UTF-16 heuristic) but then fails strict decoding, it is handled as binary (read-only view) rather than silently decoded with replacement characters, which would violate byte fidelity on save.
- **`fsync` failures.** §3.4 step 5 calls `fd.sync()`. Pipes from cloud providers always fail `fsync` even after a successful write, so sync errors are ignored; write and close errors still fail the save. `SyncFailedException` from the write itself is still reported as "storage full" per §8.
- **Shared files and Recent files.** A file received through `ACTION_SEND` + `EXTRA_STREAM` is a read-only copy (§4.7), so it is not added to Recent files.
- **Shared text is dirty.** Text received through `ACTION_SEND` opens as an `*Untitled` document (§5.26 AC), which means it is protected by the recovery buffer and the unsaved-changes prompt like any typed text.
- **"Keep my version" and later saves.** After the user keeps their version following an external change, Save still shows the "changed since you opened it" overwrite prompt, because saving really does overwrite the other app's changes.

## Edit, Format, and View features

- **Find Next starts at the end of the selection.** §5.12 says Find Next searches forward from the end of the current selection, but the §10.1 test note expects overlapping matches ("aa" in "aaa" found at 0, then 1). Those two can't both hold; the behavior spec wins (it is also what Notepad does), so "aa" in "aaa" is found at 0 and then wraps back to 0.
- **Match highlight.** Android draws an EditText selection only while it has focus, and focus stays in the find field so Enter can repeat the search. The current match therefore also gets a temporary background-color span. It is removed on the next edit, search, or when the bar closes; it never reaches the file (saves use the plain text).
- **Font dialog layout.** §6.6 shows three lists. Family and style are radio groups; size is a spinner in every layout, because a third scrolling list inside a dialog is awkward on phones. §6.6 already allows spinners on narrow screens.
- **Status bar height.** §6.1 says 28 dp with 48 dp touch targets via `TouchDelegate`. A view can have only one `TouchDelegate` and there are two tappable segments, so the bar is 48 dp tall. That meets the 48 dp requirement in §5.35 without extending touch areas over the text.
- **Edge-to-edge before Android 11.** §6.13 pads for system-bar insets on API 26–29 as well. There, the app lets the system fit the window instead, with theme-colored status and navigation bars and light/dark icons from the theme. The visual result is the same, and it avoids deprecated `SYSTEM_UI_FLAG` layout flags.
- **About text.** §5.21 asks for the license name, but the repository has no license file yet, so the About box doesn't name one. Add the license name to `about_text` once one is chosen.
- **F3/F5 in menus.** Framework menus can only display letter shortcuts with modifiers, so F3, Shift+F3, and F5 are not shown next to their menu items. They work, and they are listed in Help ▸ Keyboard Shortcuts and in the system shortcut helper (Meta+/).

## Tests

- **Scope.** The JVM unit tests cover everything in §10.1 that runs without Android: encoding and line-ending round-trips, Windows-1252, encoding detection, `.LOG`, undo coalescing and save points, the line index (randomized against brute force), search and Replace All, header/footer codes, and file names. `RecentFiles` isn't unit-tested because it uses `org.json`, which is only a stub in local unit tests; testing it would need Robolectric or an instrumented test. The instrumented SAF, intent, and lifecycle tests in §10.2 are not written yet.
- **Lint suppressions.** Two framework-only patterns trip lint checks written for AndroidX apps: `onBackPressed` (used only on API 26–32; API 33+ uses `OnBackInvokedCallback`) and `Layout.BREAK_STRATEGY_SIMPLE` (same value as the API 29 `LineBreaker` constant). Both are suppressed at the call site with a comment.
