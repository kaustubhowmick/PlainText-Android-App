# PlainText — Design Document

A minimal Android plain-text editor that recreates the classic (pre-2020) Windows Notepad.

| | |
|---|---|
| App name | PlainText |
| Application ID | `io.github.kaustubhowmick.plaintext` |
| Document status | v1 design, ready for implementation |
| Audience | Android developers and AI coding agents building v1 |

This document is prescriptive. Where it says "must", the behavior is required for v1. Where a choice could go several ways, the decision and its reason are stated. If something is not specified here, do it the way classic Windows Notepad does it.

---

## Decisions at a glance

| Topic | Decision |
|---|---|
| Language / UI | Kotlin, Android framework widgets (`android.widget.*`, `android.app.*`). No AndroidX, no Compose, no Material Components. |
| Runtime dependencies | None besides the Kotlin standard library, which R8 shrinks to a few tens of KB. |
| APK size | Target ≤ 250 KB, alarm at 500 KB, hard CI failure at 2 MB. |
| minSdk / targetSdk | 26 / 36 |
| Storage | Storage Access Framework only. No storage permissions of any kind. |
| Permissions | None. The manifest declares zero `<uses-permission>` elements. |
| Documents | One open document at a time (like Notepad). No tabs. |
| Encodings | UTF-8, UTF-8 with BOM, UTF-16 LE, UTF-16 BE, ANSI (= Windows-1252). |
| Line endings | CRLF, LF, CR. Detected on open, preserved on save. |
| Default for new files | UTF-8 (no BOM), CRLF. Both configurable. |
| Max editable file | 10 MB. Larger files can be opened as a read-only preview of the first 10 MB. |
| Unsaved work across process death | One temporary recovery file in app-private storage, deleted on save or discard. |
| Settings | `SharedPreferences` only. |

---

## 1. Overview and goals

### 1.1 What PlainText is

PlainText is a text editor for real files. It opens a `.txt` (or other plain-text) file from anywhere the Android system file picker can reach, lets the user edit it, and writes it back. Every document the user saves is an ordinary file in shared storage that they can see in any file manager, attach to an email, copy to a PC, or open in any other editor.

The app looks and behaves like classic Windows Notepad: one text area filling the screen, the file name in the title bar with an asterisk when there are unsaved changes, and the familiar File / Edit / Format / View / Help menus.

### 1.2 Goals

1. **Files, not notes.** The unit of work is a file identified by a content URI. There is no internal database.
2. **Byte fidelity.** Opening and saving a file without edits produces identical bytes. Encoding, BOM, line endings, and the presence or absence of a trailing newline are preserved unless the user explicitly changes them.
3. **Notepad parity.** Every classic Notepad menu command has an Android equivalent (see §5 and the mapping table in §6.3).
4. **Tiny and fast.** Release APK well under 2 MB (target ≤ 250 KB). Cold start to an editable empty document in under 300 ms on a mid-range device.
5. **Private by construction.** Fully offline. No `INTERNET` permission, no analytics, no ads, no crash reporting SDK, no tracking.
6. **Works with the rest of the phone.** Appears under "Open with" for text files, accepts shared text, and cooperates with DeX, Chromebooks, and hardware keyboards.

### 1.3 Non-goals (v1)

| Non-goal | Reason |
|---|---|
| Cloud sync, accounts, sharing backends | The file lives wherever the user puts it; a synced folder provider (Drive, Nextcloud, Syncthing folder) already gives sync through SAF. |
| Note list, note database, tags, search across notes | That is a notes app. A file manager or the system picker is the "note list". |
| Rich text, Markdown preview, syntax highlighting | Notepad is plain text. Highlighting also costs size and large-file performance. |
| Tabs / multiple open documents | Notepad is single-document. One document keeps memory predictable for 10 MB files and keeps the save-prompt model simple. The Recent files menu covers "switch to the other file I was just editing". |
| Autosave to the user's file | Notepad never writes a file the user didn't ask to save. Writing silently would violate "never change the file beyond what the user typed" at moments they did not choose. Unsaved work is protected by the temporary recovery buffer instead (§5.30). |
| Regular-expression search | Classic Notepad has none. Listed in §12. |
| Encodings beyond the five listed | Keeps detection unambiguous and the codec table tiny. Listed in §12. |
| Localization beyond English | v1 ships English only to keep `resources.arsc` minimal; all strings are in `strings.xml` so translation is purely additive later. |

---

## 2. Tech stack decision

### 2.1 Options compared

Sizes are approximate ranges for a release build of an app of this scope (one activity, a text editor, a dozen dialogs), with R8 and resource shrinking enabled. Verify the actual number with `apkanalyzer` during implementation; the ranking is what matters.

| Option | Typical release APK | Where the size comes from | Other considerations |
|---|---|---|---|
| **Java + framework widgets** | 40–150 KB | App code only. | Smallest possible. More null-handling boilerplate around SAF's nullable cursor/URI APIs. |
| **Kotlin + framework widgets** | 60–250 KB | App code + residual `kotlin-stdlib` after R8 (typically 20–80 KB). | Null safety fits SAF well. Cost is small and bounded if we avoid coroutines, reflection, and serialization libraries. |
| Kotlin + AndroidX AppCompat | 0.8–1.5 MB | `appcompat` pulls `core`, `lifecycle`, `fragment`, `activity`, `savedstate`, `emoji2`, `resourceinspection`, plus drawables and strings for ~90 locales. | Backports widgets we don't need at minSdk 26. |
| Kotlin + AppCompat + Material Components | 1.8–3.5 MB | Above plus `material` (large resource set, many widgets). | Visual polish Notepad doesn't need. |
| Jetpack Compose + Material 3 | 1.5–4 MB | Compose runtime, UI, foundation, material3, icons; large dex even after R8. | `BasicTextField` has historically been slower than `EditText` on multi-MB text and has weaker IME/selection behavior on hardware keyboards. |
| Flutter | 5–7 MB per ABI APK; ~15–20 MB universal APK | `libflutter.so` engine, ICU data, Dart AOT snapshot. | Its own text engine; platform file-picker integration via plugins. Far over budget. |

### 2.2 Decision

**Kotlin with Android framework widgets and zero third-party runtime libraries.**

Kotlin costs roughly 20–80 KB over Java after R8, which keeps us an order of magnitude under the 2 MB ceiling, and its null safety prevents a whole class of crashes around `ContentResolver.query()` returning null cursors and columns. Every UI element we need (`EditText`, `Toolbar`, `PopupMenu`/options menu with submenus, `AlertDialog`, `Spinner`, `NumberPicker`, `ProgressBar`) exists in the framework at API 26, so AndroidX would only add weight.

Rules that keep the Kotlin cost small, enforced in code review:

- No `kotlinx.coroutines`. Background work uses one `java.util.concurrent` executor and a main-thread `Handler` (§3.6).
- No `kotlin-reflect`, no `kotlinx.serialization`. Persisted structured data (recent files) uses `org.json`, which ships with Android.
- Compile with `-Xno-param-assertions -Xno-call-assertions -Xno-receiver-assertions` to drop `Intrinsics` null checks from bytecode (§11).

### 2.3 Dependencies

| Dependency | Configuration | Ships in APK? | Justification |
|---|---|---|---|
| `org.jetbrains.kotlin:kotlin-stdlib` | implementation (added by the Kotlin plugin) | Yes, shrunk by R8 | Required by Kotlin code. |
| `junit:junit` | `testImplementation` | No | JVM unit tests for codecs, line endings, undo, search. |
| `androidx.test:runner`, `androidx.test:rules`, `androidx.test.ext:junit` | `androidTestImplementation` | No | Instrumented test runner. |
| `androidx.test.espresso:espresso-core`, `espresso-intents` | `androidTestImplementation` | No | UI tests; stubbing SAF picker results. |
| `androidx.test.uiautomator:uiautomator` | `androidTestImplementation` | No | Driving the real system picker (DocumentsUI) in end-to-end tests. |

There are no other dependencies. Adding any runtime dependency requires updating this table with a size measurement and a reason the framework cannot do the job.

### 2.4 minSdk and targetSdk

- **minSdk 26 (Android 8.0).** Covers well over 95% of active devices in 2026. It gives us, with no compat libraries: adaptive launcher icons defined entirely as vectors (so no PNG mipmaps, a real size saving), `DocumentsContract.EXTRA_INITIAL_URI` (open the picker in the user's notes folder), `AccessibilityNodeInfo.setHintText`, `android:tooltipText`, and v2+ APK signing only (no v1 JAR signature files).
- **targetSdk 36 (Android 16)**, **compileSdk 36.** Required for Play Store updates in 2026. Consequences we must handle: edge-to-edge is enforced (we apply window insets ourselves, §6.13), predictive back is on (we use `OnBackInvokedCallback`, §5.8), and on large screens orientation/resizability restrictions are ignored (we are fully resizable anyway).

---

## 3. Architecture

### 3.1 Overview

Single Gradle module, single activity, no fragments.

```
┌──────────────────────────── EditorActivity ─────────────────────────────┐
│  Toolbar  │  FindBar  │  EditorView (EditText)  │  StatusBarView         │
│  Menus, dialogs, key shortcuts, back handling, activity results          │
└───────────────┬──────────────────────────────────────────────────────────┘
                │ attaches to / detaches from (survives recreation)
┌───────────────▼──────────── EditorSession ───────────────────────────────┐
│  Document (metadata)   UndoManager   LineIndex   PendingAction           │
│  SearchEngine          revision counter                                  │
└──────┬──────────────────────┬──────────────────────┬─────────────────────┘
       │                      │                      │
┌──────▼───────┐   ┌──────────▼─────────┐   ┌────────▼───────────┐
│ io/          │   │ storage/           │   │ print/             │
│ DocumentIo   │   │ DefaultFolder      │   │ TextPrintAdapter   │
│ TextCodec    │   │ RecentFiles        │   │ HeaderFooter       │
│ Encoding-    │   │ RecoveryStore      │   └────────────────────┘
│  Detector    │   │ Settings           │
│ LineEndings  │   └────────────────────┘
│ Windows1252  │
└──────────────┘
```

The text itself lives in exactly one place: the `Editable` owned by `EditorView`. Everything else holds metadata or derived indexes. This avoids keeping two copies of a 10 MB document.

### 3.2 Packages and classes

Root package `io.github.kaustubhowmick.plaintext`.

| Package | Class | Responsibility |
|---|---|---|
| (root) | `EditorActivity` | The only activity. Owns views, menus, dialogs, shortcuts, `onActivityResult`, intent handling, back handling. |
| (root) | `EditorSession` | Retained across configuration changes via `onRetainNonConfigurationInstance()`. Holds `Document`, `UndoManager`, `LineIndex`, `SearchEngine`, the pending action, and the in-flight background task handles. Also holds the text (`CharSequence`) during the brief window between old activity destroy and new activity create. |
| `editor` | `Document` | Metadata of the open document (see §3.3). |
| `editor` | `EditorView` | `EditText` subclass: pinch-zoom, Tab key insertion, paste-as-plain-text, Ctrl+scroll zoom, text-change dispatch to `UndoManager`/`LineIndex`, tab-stop span. |
| `editor` | `UndoManager` | Multi-level undo/redo with coalescing (§5.10). |
| `editor` | `LineIndex` | Sorted `IntArray` of logical line start offsets, incrementally maintained. Used by status bar, Go To Line, and `.LOG`. |
| `editor` | `SearchEngine` | Find/replace over a text snapshot on a background thread (§5.12). |
| `editor` | `TimeDate` | Notepad-style time/date string for F5 and `.LOG`. |
| `io` | `DocumentIo` | All `ContentResolver` I/O: metadata query, bounded read, safe write, stat for external-change detection. |
| `io` | `EncodingDetector` | Pure Kotlin. `ByteArray → Detection` (§3.5). |
| `io` | `TextCodec` | Pure Kotlin. Decode/encode for each `Encoding`, BOM handling, unmappable-character check. |
| `io` | `Windows1252` | Pure Kotlin. Lossless 256-entry byte↔char table. |
| `io` | `LineEndings` | Pure Kotlin. Detect counts, normalize to `\n`, denormalize to target. |
| `io` | `FileNames` | Pure Kotlin. Sanitizing, default name from first line, "name (2).txt" collision naming, MIME-from-extension. |
| `storage` | `Settings` | Typed wrapper over one `SharedPreferences` file. |
| `storage` | `DefaultFolder` | Persisted tree URI: validate, find child by name, create document. |
| `storage` | `RecentFiles` | MRU list (max 10) of URIs + display names; manages persisted URI grants. |
| `storage` | `RecoveryStore` | The single temporary recovery slot (§5.30). |
| `ui` | `FindBar` | Inline find/replace bar view. |
| `ui` | `StatusBarView` | Bottom status bar. |
| `ui` | `FastScroller` | Draggable scroll thumb for long documents. |
| `ui` | `Dialogs` | Builders for all `AlertDialog`s: unsaved changes, Go To Line, Font, Save options, Page Setup, first-run, errors. |
| `print` | `TextPrintAdapter` | `PrintDocumentAdapter` that paginates text into a PDF. |
| `print` | `HeaderFooter` | Parser/formatter for `&f`, `&p`, `&d`, `&t`, `&l`, `&c`, `&r`, `&&`. |
| `util` | `Work` | The two background executors plus main-thread `Handler`. |

Pure-Kotlin classes in `io` must not import `android.*` so they run as JVM unit tests with no emulator and no Robolectric.

### 3.3 Editor state model

```kotlin
enum class Encoding(val label: String) {
    UTF8("UTF-8"), UTF8_BOM("UTF-8 with BOM"),
    UTF16LE("UTF-16 LE"), UTF16BE("UTF-16 BE"), ANSI("ANSI")
}

enum class LineEnding(val label: String, val chars: String) {
    CRLF("Windows (CRLF)", "\r\n"), LF("Unix (LF)", "\n"), CR("Macintosh (CR)", "\r")
}

enum class Origin { NEW, PICKER_OPEN, PICKER_CREATE, DEFAULT_FOLDER, VIEW_INTENT, EDIT_INTENT, SHARE, RECENT, RECOVERED }

enum class AccessMode {
    READ_WRITE,        // normal
    READ_ONLY_FILE,    // provider or grant disallows writing; user may edit, Save routes to Save As
    VIEW_ONLY_BINARY,  // binary file opened read-only; editing disabled, Save/Save As disabled
    VIEW_ONLY_PREVIEW  // first 10 MB of an oversized file; editing disabled, Save/Save As disabled
}

data class DiskStamp(val size: Long?, val lastModified: Long?)  // null = provider did not report it

class Document(
    var uri: Uri?,                 // null for Untitled
    var displayName: String,       // "Untitled" when uri == null
    var encoding: Encoding,
    var lineEnding: LineEnding,
    var mixedLineEndings: Boolean, // detected on open; cleared when the user confirms a conversion
    var access: AccessMode,
    var origin: Origin,
    var hadBom: Boolean,           // UTF-16 only: whether the file had a BOM (UTF-8 BOM is its own Encoding)
    var diskStamp: DiskStamp?,     // at last open/save; used for external-change detection
    var metadataDirty: Boolean,    // encoding or line ending changed by the user since last save
    var deletedOnDisk: Boolean,
)
```

**Dirty state** is derived, never stored as a separate flag that can drift:

```
isDirty = undoManager.position != undoManager.savePoint || document.metadataDirty
```

Undoing back to the save point makes the document clean again, and the asterisk disappears.

**Revision counter.** `EditorSession.revision: Long` increments on every text change. Background results (search, recovery writes, line-index rebuilds) carry the revision they were computed against and are discarded if stale.

### 3.4 File I/O layer

All SAF calls go through `DocumentIo`. Every method runs on the IO executor, never on the main thread.

**Metadata query** (`queryMeta(uri)`): query `OpenableColumns.DISPLAY_NAME`, `OpenableColumns.SIZE`, and, when `DocumentsContract.isDocumentUri()` is true, also `Document.COLUMN_FLAGS`, `COLUMN_LAST_MODIFIED`, `COLUMN_MIME_TYPE`. Any column may be missing or null; every field is nullable. If the display name is missing, use the last path segment of the URI, URL-decoded, or "Untitled".

**Read** (`read(uri, limit = 10 MiB)`): `openInputStream(uri)`, read into a `ByteArray` of up to `limit + 1` bytes. Reading `limit + 1` bytes tells us the file is oversized even when the provider reported no size.

**Write** (`write(uri, bytes)`), in this order:

1. Encode the whole document to a `ByteArray` first (in `TextCodec`), before touching the file, so an encoding problem can never leave a half-written file.
2. Open with mode `"wt"` (write + truncate). Mode `"w"` must never be used first: on several Android versions and providers `"w"` does not truncate, leaving stale bytes at the end of a file that got shorter.
3. If the provider rejects `"wt"` (`IllegalArgumentException`, `UnsupportedOperationException`, or `FileNotFoundException` mentioning the mode), open `openFileDescriptor(uri, "rw")`, write through a `FileOutputStream`, then `channel.truncate(bytes.size)`.
4. If that also fails, open `"w"`, write, then re-query `OpenableColumns.SIZE`. If the reported size is larger than what we wrote, show the "may contain leftover data" error (§8).
5. `flush()`, then `fd.sync()` when a file descriptor is available, then close. Close errors are write errors.
6. Re-query the `DiskStamp` and store it.

SAF has no portable atomic rename, so writes are in place. The failure window is limited by step 1, and the in-memory text plus the recovery buffer remain until a save succeeds.

**Stat** (`stat(uri)`): cheap query of size + last-modified used for external-change detection (§5.33).

### 3.5 Encoding detection

`EncodingDetector.detect(bytes): Detection` where

```kotlin
data class Detection(val encoding: Encoding, val bomLength: Int, val isBinary: Boolean)
```

The algorithm, in order. The first rule that matches wins.

1. **BOM check.**
   - `EF BB BF` → `UTF8_BOM`, bomLength 3.
   - `FF FE 00 00` → UTF-32 LE: unsupported → `isBinary = true`.
   - `FF FE` → `UTF16LE`, bomLength 2.
   - `FE FF` → `UTF16BE`, bomLength 2.
2. **Empty file** (0 bytes) → the user's default encoding for new files (default `UTF8`).
3. **BOM-less UTF-16 heuristic.** If the length is even and ≥ 4: over the first 8 KB, count zero bytes at even offsets (`zE`) and at odd offsets (`zO`) as fractions of byte pairs.
   - `zO ≥ 0.4` and `zE ≤ 0.05` → `UTF16LE`.
   - `zE ≥ 0.4` and `zO ≤ 0.05` → `UTF16BE`.
   - The candidate must then decode strictly (no malformed surrogates) over the whole file, else continue.
4. **Binary check.** Over the first 8 KB: if it contains any `0x00` byte, or more than 10% of bytes are C0 controls other than TAB (09), LF (0A), VT (0B), FF (0C), CR (0D), and ESC (1B) → `isBinary = true`, encoding `ANSI` (the lossless fallback, used only to show something in view-only mode).
5. **Strict UTF-8.** Decode the whole file with a `CharsetDecoder` set to `CodingErrorAction.REPORT`. Success → `UTF8`. Pure ASCII files land here, which is correct: ASCII bytes are identical in UTF-8 and ANSI, so saving is lossless either way.
6. **Fallback** → `ANSI` (Windows-1252). Decoding with our `Windows1252` table always succeeds and is lossless.

"ANSI" in this app always means Windows-1252, regardless of device locale. That matches what Notepad does on Western-European Windows installs and gives a deterministic, testable round-trip.

**Lossless Windows-1252.** Java's `windows-1252` charset maps the five undefined bytes (`81 8D 8F 90 9D`) to U+FFFD, which is not round-trippable. `Windows1252` instead maps them to the C1 control characters U+0081, U+008D, U+008F, U+0090, U+009D (as the WHATWG Encoding Standard does), so every byte 00–FF decodes to a unique char and encodes back to itself.

**Encoding on save.** `TextCodec.encode(text, encoding)` prepends the BOM for `UTF8_BOM` (`EF BB BF`), `UTF16LE` (`FF FE`), and `UTF16BE` (`FE FF`). A UTF-16 file that was opened BOM-less (rule 3) is saved BOM-less (`Document.hadBom = false`) so round-trips are exact; documents switched to UTF-16 by the user get a BOM. For `ANSI`, `TextCodec.findUnmappable(text)` returns the first character that Windows-1252 can't represent; see §5.24 for what the user sees.

### 3.6 Threading

- **IO executor**: `Executors.newSingleThreadExecutor()`. File reads, writes, stats, recovery writes. Single thread guarantees ordering (a recovery write can't race a save).
- **Compute executor**: another single thread. Search snapshots, Replace All, print layout, line-index full rebuilds.
- **Main**: `Handler(Looper.getMainLooper())`. Results are posted back and applied only if the activity is attached to the session and `revision` still matches.
- Long operations (open > 300 ms, Replace All, print layout) show the indeterminate `ProgressBar` at the bottom edge of the toolbar.

Both executors live in `EditorSession` and are shut down when the session is finally discarded (activity `isFinishing`).

---

## 4. Storage and permissions model

### 4.1 Principles

- The app declares **no permissions**: no `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, `READ_MEDIA_*`, `MANAGE_EXTERNAL_STORAGE`, or `INTERNET`.
- All access to user files is through the Storage Access Framework and URI grants.
- The only files the app itself creates in private storage are the settings XML and the temporary recovery buffer.

### 4.2 The default notes folder

- Chosen once on first launch (§5.1) with `ACTION_OPEN_DOCUMENT_TREE`, with `EXTRA_INITIAL_URI` pointing at `Documents` on primary storage (`DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents")`). The user can navigate anywhere and create a folder such as `Documents/Notes` from the picker.
- The result is persisted with `takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION)` and stored as `default_folder_uri` in settings.
- Since Android 11 the picker refuses the storage root and `Download` as a tree. The first-run dialog text says so, so the user isn't surprised.
- The folder is **optional**. If the user skips it, a plain Save of a new document goes straight to the system Save As picker. The folder can be set or changed later from File ▸ Default folder….
- Changing the folder releases the old tree grant (`releasePersistableUriPermission`) after the new one is taken.

**Validation** (`DefaultFolder.validate()`), run before each use:

1. The stored URI is present in `contentResolver.persistedUriPermissions` with both read and write.
2. Querying the tree's root document (`buildDocumentUriUsingTree(tree, getTreeDocumentId(tree))`) returns a row whose MIME type is `Document.MIME_TYPE_DIR`.

If either check fails (folder deleted or renamed, SD card removed, grant revoked by the user in system settings, app data restored on a new device), the folder is treated as unavailable. The user's text is never affected. See §5.4 for the dialog.

### 4.3 Save As anywhere and Open

- **Open** uses `ACTION_OPEN_DOCUMENT` with `type = "*/*"` and `EXTRA_MIME_TYPES = ["text/*", "application/json", "application/xml", "application/x-subrip", "application/octet-stream"]`, `CATEGORY_OPENABLE`, and `EXTRA_INITIAL_URI` set to the default folder when one is valid. `*/*` with the extra list lets files with unusual MIME types (e.g. `.cfg` reported as `application/octet-stream`) be picked.
- **Save As** uses `ACTION_CREATE_DOCUMENT` with `CATEGORY_OPENABLE`, `EXTRA_TITLE` = proposed file name, `type` = MIME derived from the extension (§4.5), and `EXTRA_INITIAL_URI` = the current file's URI (if any) or the default folder.
- Results of both are persisted with `takePersistableUriPermission` (read + write where granted). If the provider doesn't offer a persistable grant (`SecurityException`), the file is still opened and saved for this session; it simply won't reopen from Recent files after a reboot.
- SD cards, USB drives, Drive, Nextcloud, and any other `DocumentsProvider` work the same way, because the picker handles them.

### 4.4 Persisted grant budget

Android keeps at most 128 (API 26–29) or 512 (API 30+) persisted grants per app. We hold at most 1 tree grant + 10 recent-file grants. When a file drops off the Recent list, its grant is released unless it is the currently open document. This keeps us far below the limit.

### 4.5 File names, extensions, and MIME types

- **Proposed name for a new document**: the first non-blank line of the text, trimmed, truncated to 40 characters, sanitized, plus `.txt`. If that is empty, `Untitled.txt`.
- **Sanitizing**: remove `/ \ : * ? " < > |` and control characters (these are invalid on FAT/exFAT SD cards); collapse runs of whitespace; trim trailing dots and spaces; limit to 255 UTF-8 bytes including the extension.
- **No extension typed** → append `.txt`.
- **MIME for creation** is derived from the extension so that providers don't append a second extension. `ExternalStorageProvider` appends `.txt` to `notes.md` if we pass `text/plain`. Mapping: use `MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)`; if it returns null, use `application/octet-stream`, which providers leave alone.
- **Name returned by the provider**: after `createDocument`, re-query `DISPLAY_NAME`. Some providers silently rename on collision (`notes (1).txt`). If the returned name differs from what we asked for, show a toast: "Saved as notes (1).txt".

### 4.6 Name collisions in the default folder

Before creating, `DefaultFolder.findChild(name)` lists children via `buildChildDocumentsUriUsingTree` and compares display names case-insensitively (FAT/exFAT and most providers are case-insensitive). If a match exists:

> **notes.txt already exists.**
> Do you want to replace it?
> [Replace] [Keep both] [Cancel]

- **Replace** writes into the existing document's URI with `"wt"`.
- **Keep both** picks the first free name of the form `notes (2).txt`, `notes (3).txt`, …
- **Cancel** returns to the Save dialog with the name field focused.

Collisions in the system Save As picker are handled by the picker itself.

### 4.7 Read-only files and providers

A document is `READ_ONLY_FILE` when any of these is true:

- The document's `COLUMN_FLAGS` lacks `FLAG_SUPPORTS_WRITE`.
- It arrived via `ACTION_VIEW` and `checkUriPermission(uri, myPid, myUid, FLAG_GRANT_WRITE_URI_PERMISSION)` is not `PERMISSION_GRANTED`.
- It arrived via `ACTION_SEND` with `EXTRA_STREAM` (always treated as a copy).
- A save attempt failed with `SecurityException` or `FileNotFoundException` (downgrade at that point).

Behavior: the title shows `notes.txt (Read-only)`. The user **can** type (like Notepad, which lets you edit a read-only file). Save shows:

> **notes.txt can't be saved here.**
> The app that provided this file doesn't allow changes. Save a copy somewhere else?
> [Save As…] [Cancel]

### 4.8 Private storage

| Item | Location | Contents | Lifetime |
|---|---|---|---|
| Settings | `shared_prefs/settings.xml` | §6.11 | Until app data is cleared. |
| Recovery buffer | `noBackupFilesDir/recovery/` | At most one text snapshot + metadata | Deleted on save, discard, or after being declined (§5.30). |

`android:allowBackup="false"` and `android:fullBackupContent`/`dataExtractionRules` are not needed beyond that: nothing is backed up. Persisted URI grants don't survive a device restore anyway, so backing up the folder URI would only produce a broken setting.

---

## 5. Feature specifications

Each feature lists **Behavior**, **Edge cases**, and **Acceptance criteria (AC)**. UI locations refer to the menu tree in §6.3.

### 5.1 First launch and the default folder

**Behavior.** On the very first launch (setting `first_run_done` is false), after the empty editor appears, show the first-run dialog (§6.9). "Choose folder" launches the tree picker; on success the URI is persisted and a toast shows "Notes will be saved in Notes". "Not now" dismisses. Either way `first_run_done` becomes true and the dialog never appears again. If the app was launched by an intent (Open with, Share), the first-run dialog is deferred to the next launcher start so it doesn't interrupt the user's task.

**Edge cases.**
- Picker cancelled → same as "Not now".
- Device has no tree picker (`ActivityNotFoundException`; some TV/Go builds) → toast "This device doesn't support choosing a folder. Use Save As instead." and mark first run done.
- Picked a folder on removable storage → allowed.

**AC.**
- Fresh install: dialog shown once; after choosing, `persistedUriPermissions` contains the tree with read+write.
- Second launch: no dialog.
- Launch via VIEW intent on a fresh install: file opens, no dialog; next launcher start shows it.

### 5.2 New (File ▸ New, Ctrl+N)

**Behavior.** If dirty, run the unsaved-changes prompt (§5.9) first. Then clear the editor to an empty `Untitled` document with the default encoding and line ending from settings, clear undo history, delete the recovery buffer, and put the caret at 0.

**AC.** After New, the title is `Untitled`, the status bar shows the default encoding and EOL, and Undo is disabled.

### 5.3 Open (File ▸ Open…, Ctrl+O)

**Behavior.** Unsaved prompt if dirty, then `ACTION_OPEN_DOCUMENT` (§4.3). On result: take the persistable grant, then run the **open pipeline**:

1. `queryMeta` → name, size, flags, last-modified.
2. Size gate (§5.31): > 10 MB → oversized dialog.
3. `read` bytes.
4. `EncodingDetector.detect`. Binary → binary dialog (§5.32).
5. Decode (strip BOM).
6. `LineEndings.detect` + normalize to `\n` (§5.25).
7. Build `LineIndex`.
8. Main thread: `setText`, caret at 0, scroll to top, clear undo, set `Document`, add to Recent files, apply `.LOG` (§5.27), delete the recovery buffer.

Steps 1–7 run on the IO executor with a progress bar if slower than 300 ms.

**Edge cases.**
- Opening the file that is already open (same URI) → no reload, just focus the editor. If it changed on disk, the external-change rule applies (§5.33).
- Read fails (`FileNotFoundException`, `SecurityException`, `IOException`) → error dialog (§8), current document untouched. The unsaved prompt already ran, so if the user chose "Don't Save" the old text is gone; therefore **the pipeline must read and decode before the old document is replaced**, and on failure the old document (even if the user chose Don't Save) stays on screen.

**AC.** Opening each encoding × each EOL fixture from `androidTest/assets` shows the correct text, encoding, and EOL in the status bar, and a subsequent Save without edits doesn't write (§5.5) while Save As to a new file produces byte-identical output.

### 5.4 Save (File ▸ Save, Ctrl+S)

**Behavior.**

| Document state | Save does |
|---|---|
| Has URI, `READ_WRITE`, clean | Nothing (no write). Toast "No changes to save". Keeps the file's bytes and modified time untouched. |
| Has URI, `READ_WRITE`, dirty | External-change check (§5.33), unmappable check (§5.24), mixed-EOL confirmation (§5.25), then write in place (§3.4). |
| Has URI, `READ_ONLY_FILE` | "Can't be saved here" dialog → Save As. |
| `VIEW_ONLY_*` | Menu item disabled. |
| Untitled, valid default folder | Save options dialog (§6.7) in "default folder" mode: file name + encoding + line ending, buttons [Save] [Other location…]. |
| Untitled, default folder unset | Straight to Save As (§5.5). |
| Untitled, default folder unavailable | Dialog: "Your notes folder is no longer available. It may have been deleted, renamed, or on a removed SD card." [Choose folder] [Save As…] [Cancel]. "Choose folder" launches the tree picker and then continues the save. |
| `deletedOnDisk` and file was in default folder | Recreate with `createDocument` using the same name, then write. |
| `deletedOnDisk` otherwise | Save As. |

On success: the undo save point moves to the current position, `metadataDirty = false`, `mixedLineEndings = false`, title loses its asterisk, `DiskStamp` refreshed, recovery buffer deleted, Recent files updated, toast "Saved".

On failure: document remains dirty, error per §8, recovery buffer kept.

**AC.**
- New doc + Save with folder set → file appears in the folder with the confirmed name and exact bytes (encoding, EOL).
- Save when clean doesn't change the file's last-modified time.
- Revoking the tree grant (`adb shell` or test helper) then saving a new doc shows the "folder no longer available" dialog; text remains.

### 5.5 Save As (File ▸ Save As…, Ctrl+Shift+S)

**Behavior.** Show the Save options dialog (§6.7) in "anywhere" mode: encoding and line-ending spinners (pre-filled with the document's current values) and [Choose location…]. Then `ACTION_CREATE_DOCUMENT` with the proposed name. On result: take grant, write, and the document now points to the new URI with the chosen encoding and EOL (like Notepad, the new file becomes the open file). The old file is not modified.

Save As is the only UI where the user picks encoding at save time, mirroring Notepad's Save As dialog. Encoding and EOL can also be changed any time from the status bar or View ▸ menu (§5.24, §5.25), which marks the document dirty.

**Edge cases.**
- Picker cancelled → nothing changes; a pending action (e.g. New after "Save") is cancelled too.
- User picks the same file as currently open → write in place.
- Provider returns a different name → toast (§4.5).

**AC.** Save As with UTF-16 BE + CR to a new location produces `FE FF` followed by big-endian code units and `\r`-only line breaks; the title shows the new name.

### 5.6 Recent files (File ▸ Recent ▸)

**Behavior.** Substitute for Notepad's "New Window": up to 10 most recently opened or saved files, newest first. Each entry shows the display name; if two entries share a name, the provider/folder hint is appended: `notes.txt — Drive`, derived from the authority's provider label (`PackageManager.resolveContentProvider(authority, 0).loadLabel()`). The last item is "Clear recent files". Tapping an entry runs the unsaved prompt then the open pipeline.

Stored in settings as a JSON array (`org.json`) of `{uri, name, time}`.

**Edge cases.** Entry whose grant was lost or file was deleted → error "Can't open notes.txt. It may have been moved or deleted, or access was removed." and the entry is removed. Entries opened via VIEW intents without persistable grants are listed but may fail after reboot; same handling.

**AC.** Open 11 files → list has 10, oldest evicted, and its persisted grant released.

### 5.7 Print and Page Setup (File ▸ Page Setup…, File ▸ Print…, Ctrl+P)

**Page Setup behavior.** Dialog (§6.8) with margins in millimetres (left, right, top, bottom; defaults 19, 19, 25, 25, i.e. Notepad's 0.75″/1″), Header (default `&f`), Footer (default `Page &p`), and print font size in points (default 10). Paper size and orientation are chosen in the system print dialog, so they're not duplicated here. Values persist in settings.

**Header/footer codes** (Notepad-compatible):

| Code | Meaning |
|---|---|
| `&f` | File name (`Untitled` if unsaved) |
| `&p` | Page number |
| `&d` | Current date (short format) |
| `&t` | Current time (short format) |
| `&l` | Following text is left-aligned |
| `&c` | Following text is centered (default) |
| `&r` | Following text is right-aligned |
| `&&` | A literal ampersand |

Unknown codes (`&x`) print literally. A header can contain several alignment sections: `&l&f&r&d` prints the name on the left and the date on the right.

**Print behavior.** `PrintManager.print(jobName = displayName, TextPrintAdapter, attrs)`. `TextPrintAdapter`:
- `onLayout` (compute thread, honoring `CancellationSignal`): builds pages with `StaticLayout` using the editor's font family and style at the Page Setup point size, black on white, tab stops every 8 spaces, **always wrapping** regardless of the editor's Word Wrap (as Notepad does). Text area = page minus margins minus one header line and one footer line (plus 0.5 line gap each). Reports page count via `PrintDocumentInfo.Builder(...).setContentType(CONTENT_TYPE_DOCUMENT).setPageCount(n)`.
- `onWrite`: draws requested page ranges into a `PrintedPdfDocument`, header centered at top margin, footer at bottom margin.

The system print dialog provides preview, printer choice, and "Save as PDF".

**Edge cases.** Empty document prints one page with header and footer. A single line longer than a page width wraps. Changing paper size re-runs `onLayout`. Very large documents: layout is incremental page by page, checking the cancellation signal between pages.

**AC.** A 3-page fixture with header `&l&f&r&p` renders the file name at top-left and the correct page number at top-right on each page (verified by `PdfRenderer` in an instrumented test).

### 5.8 Exit (File ▸ Exit) and Back

**Behavior.** Exit and system Back behave identically: if the find bar is open, Back closes it first. Otherwise, if dirty, show the unsaved prompt; after Save or Don't Save, `finish()`. If clean, `finish()` immediately.

**Implementation.** On API 33+, register an `OnBackInvokedCallback` at `PRIORITY_DEFAULT` **only while** the document is dirty or the find bar is open, and unregister it otherwise, so that predictive back-to-home animates normally when there's nothing to confirm. On API 26–32, override `onBackPressed()` with the same logic. `android:enableOnBackInvokedCallback="true"` in the manifest.

**Leaving the app with Home, Recents, or an app switch** can't be intercepted on Android and must not be blocked. The document stays in memory, the recovery buffer is written in `onStop`, and when the user returns everything is exactly as they left it (§5.29–5.30).

**AC.** Dirty doc + Back → prompt. Clean doc + Back → activity finishes with predictive-back animation on API 34+.

### 5.9 Unsaved-changes prompt

**Behavior.** Triggered by New, Open, Recent, an incoming VIEW/EDIT/SEND intent, Exit, and Back when dirty. Dialog (§6.10 wireframe):

> **PlainText**
> Do you want to save changes to notes.txt?
> [Save] [Don't Save] [Cancel]

- **Save** runs Save (which may itself open dialogs or the picker). When the save completes successfully, the pending action continues. If the save fails or is cancelled, the pending action is cancelled.
- **Don't Save** deletes the recovery buffer and continues.
- **Cancel** (or tapping outside, or Back) aborts the action.

The pending action is stored in `EditorSession.pendingAction` and in the saved-instance `Bundle` (as a small parcelable: kind + optional URI/intent), so it survives rotation or process death while the system picker is showing.

**AC.** New → Save → cancel the picker → document unchanged and still dirty.

### 5.10 Undo and Redo (Edit ▸ Undo Ctrl+Z, Edit ▸ Redo Ctrl+Y / Ctrl+Shift+Z; toolbar Undo)

Classic Notepad had a single undo level that toggled. PlainText provides multi-level undo and redo, which is strictly more useful and matches later Notepad versions.

**Behavior.** `UndoManager` records edits from a `TextWatcher` on the editor: `(start, removedText, insertedText, caretBefore, caretAfter)`. Consecutive edits merge into one undo group while:
- they are contiguous (each new insert starts where the last ended, or each delete ends where the last began), and
- they are the same kind (insert vs. delete), and
- less than 2 seconds passed since the previous edit, and
- the edit doesn't insert a newline, and
- an IME composing region is active (composition updates always merge into the current group).

A new group starts on caret movement by the user, paste, cut, delete via menu, Replace, Replace All, Insert Time/Date, and `.LOG` insertion.

Undo applies the inverse of the most recent group as one `Editable.replace` sequence with recording suspended, restores `caretBefore`, and scrolls the caret into view. Redo re-applies. Any new edit after an undo clears the redo stack.

**Limits.** History capped at 1000 groups or 4 M stored characters (whichever comes first); oldest groups drop. Replace All on a document larger than 4 M characters shows: "Replace All can't be undone for a file this large. Continue?" [Replace All] [Cancel].

**Framework undo is disabled:** `android:allowUndo="false"` on the `EditText`, and Ctrl+Z / Ctrl+Y are intercepted in `EditorActivity.dispatchKeyEvent` before the view sees them.

**Undo history is in memory only.** It survives rotation (retained `EditorSession`) but not process death.

**AC.** Type "hello world", Undo → text empty in one step (typed in one burst). Type, pause 3 s, type → two steps. Undo back to the save point removes the asterisk.

### 5.11 Cut, Copy, Paste, Delete, Select All (Edit menu; Ctrl+X/C/V, Del, Ctrl+A)

**Behavior.**
- Cut/Copy/Delete are enabled only with a non-empty selection. Paste is enabled when `ClipboardManager.hasPrimaryClip()` and the clip description has a text MIME type (this check doesn't trigger Android 12+'s clipboard-access toast).
- Menu items call `editorView.onTextContextMenuItem(android.R.id.cut | copy | pasteAsPlainText | selectAll)`. Delete removes the selection via `Editable.delete`.
- **Paste is always plain text.** `EditorView.onTextContextMenuItem` maps `android.R.id.paste` to `android.R.id.pasteAsPlainText`, so the floating selection toolbar also pastes plain text and never inserts spans.
- The long-press floating toolbar (framework) keeps working for touch users.

**AC.** Copy rich text from a browser, paste → no spans on the `Editable` (`getSpans(0, len, CharacterStyle::class.java)` is empty).

### 5.12 Find, Find Next, Find Previous (Edit ▸ Find… Ctrl+F; F3; Shift+F3; toolbar Find)

**Behavior.** Opens the inline find bar (§6.4) under the toolbar rather than a dialog, so the text and keyboard stay visible. If there's a selection on a single line, it pre-fills the search field (Notepad does this). The field is focused with the keyboard shown.

- **Find Next** (↓ button, Enter in the field, F3 when the last direction was down) searches forward from the end of the current selection.
- **Find Previous** (↑ button, Shift+Enter, Shift+F3) searches backward from the start of the current selection.
- **F3** repeats the search in the last-used direction; Shift+F3 in the opposite direction. F3 with the find bar closed but a previous search term works too (Notepad behavior).
- Options (toggle buttons in the bar, persisted): **Match case** (default off), **Wrap around** (default on).
- A match is selected (`setSelection(start, end)`) and scrolled into view with a line of context above.
- Not found → toast `Cannot find "term"` (Notepad's wording). With wrap on, "not found" means not anywhere in the document.
- Escape or the ✕ button closes the bar; the selection stays.

**Implementation.** `SearchEngine` holds a snapshot `String` of the document keyed by `revision`, created on the main thread (a single `toString()`), and reused until the next edit. Search runs on the compute executor. Case-insensitive matching uses `String.regionMatches(ignoreCase = true, …)` in a loop anchored by `indexOf` of the first character in both cases, so no lowercase copy of the whole document is made. Results carry the revision and are discarded if the document changed.

**Edge cases.** Empty search term → buttons disabled. Search term containing a newline (pasted) matches across lines. Matching is on UTF-16 code units; case folding is per `Char.equals(ignoreCase)`, which handles Latin, Greek, Cyrillic correctly and is what Notepad effectively does.

**AC.** On a 10 MB fixture, Find Next for a term near the end completes in < 200 ms on the reference device (§9.1). Match case off finds "Hello" for "hello".

### 5.13 Replace and Replace All (Edit ▸ Replace… Ctrl+H)

**Behavior.** Opens the find bar with the replace row visible.
- **Replace**: if the current selection matches the search term (respecting Match case), replace it and then Find Next. Otherwise, just Find Next (Notepad behavior).
- **Replace All**: replaces every occurrence in the whole document regardless of caret or wrap setting, as one undo group. Toast "Replaced 12 occurrences" or `Cannot find "term"`.

**Implementation.** Replace All builds the new text in a `StringBuilder` on the compute executor from the snapshot, then on the main thread applies a single `Editable.replace(0, length, newText)` (with undo recording one group whose removed text is the old full text; see size cap in §5.10), and restores the caret to the same logical line and column if it still exists, else to the end.

**AC.** Replace All "a"→"b" on "aAa" with Match case off → "bbb", one Undo restores "aAa".

### 5.14 Go To Line (Edit ▸ Go To… Ctrl+G)

**Behavior.** Dialog (§6.5) with a numeric field pre-filled with the current line number, selected. OK moves the caret to the start of that logical line and scrolls to it.
- Line numbers are **logical** lines (separated by line breaks), regardless of Word Wrap. Classic Notepad disabled Go To when word wrap was on because it counted visual lines; we count logical lines, so it's always available.
- Out of range (0 or > line count) → inline error under the field: "The line number is beyond the total number of lines" (Notepad's message), dialog stays open.

**AC.** On a 5-line doc, Go To 5 puts the caret at the start of line 5; Go To 6 shows the error.

### 5.15 Insert Time/Date (Edit ▸ Time/Date, F5)

**Behavior.** Replaces the selection (or inserts at the caret) with `TimeDate.now()`: short time + space + short date in the device locale, via `android.text.format.DateFormat.getTimeFormat(ctx)` and `getDateFormat(ctx)`, e.g. `10:42 AM 9/23/2026` (en-US, 12-hour) or `10:42 23/09/2026` (en-GB, 24-hour). This matches Notepad's format. One undo group.

**AC.** F5 inserts the string at the caret; Undo removes it in one step.

### 5.16 Word Wrap (Format ▸ Word Wrap, checkable)

**Behavior.** On: lines wrap at the view's width. Off: lines don't wrap and the editor scrolls horizontally (`setHorizontallyScrolling(true)`), with a horizontal scrollbar. Persisted (`word_wrap`, default **on**, since horizontal scrolling on a phone is rarely what users want; Notepad's default was off, but its window was wide).

Toggling keeps the caret position and scrolls it into view.

**AC.** Toggling on a 1 MB file completes without ANR and keeps the caret on the same character.

### 5.17 Font (Format ▸ Font…)

**Behavior.** Dialog (§6.6) with:
- **Family**: Monospace (default), Sans Serif, Serif, Sans Serif Condensed, Serif Monospace. These are system font families (`Typeface.create(family, style)`), so no font files are bundled.
- **Style**: Regular, Italic, Bold, Bold Italic.
- **Size**: 8, 9, 10, 11, 12, 14 (default), 16, 18, 20, 22, 24, 26, 28, 36, 48, 72, in **sp** so the Android font-size setting scales it.
- **Sample** preview "AaBbYyZz 0Oo1lI" updating live.

Applies to the editor and (family/style only) to printing. Persisted (`font_family`, `font_style`, `font_size_sp`).

**Tab width.** Framework `Layout` uses fixed 20 px tab stops, which don't match Notepad. `EditorView` applies one `TabStopSpan.Standard(8 × width of ' ')` across the whole text with `SPAN_INCLUSIVE_INCLUSIVE`, recomputed on font or zoom change and re-applied after `setText`.

**AC.** Choosing Serif / Bold / 20 changes the editor immediately and persists across restart. A line `a\tb` shows `b` at column 9 in monospace.

### 5.18 Zoom (View ▸ Zoom ▸ Zoom In / Zoom Out / Restore Default Zoom; Ctrl+Plus, Ctrl+Minus, Ctrl+0; pinch; Ctrl+mouse wheel)

**Behavior.** Zoom is a percentage applied on top of the font size: effective text size = `font_size_sp × zoom / 100`. Range 10%–500%, steps of 10% for menu/keys (Notepad's range and step). Pinch-to-zoom uses a `ScaleGestureDetector` in `EditorView` and snaps to the nearest 10% on gesture end. Ctrl+scroll on DeX/Chromebook uses `ACTION_SCROLL` generic motion events with `META_CTRL_ON`. Current zoom shows in the status bar. Persisted (`zoom_percent`, default 100).

**Performance.** For documents over 1 MB, pinch updates the size only at gesture end (relayout of a multi-MB text per frame would stutter); under 1 MB it updates live.

**AC.** Ctrl+Plus ×3 from 100% → 130%; Ctrl+0 → 100%; values clamp at 10% and 500%.

### 5.19 Status bar (View ▸ Status Bar, checkable)

**Behavior.** A one-line bar at the bottom (above the IME when shown):

```
Ln 12, Col 5   │ 100% │ Windows (CRLF) │ UTF-8
```

- **Ln/Col**: 1-based logical line (from `LineIndex`, binary search on the caret offset) and 1-based column counted in characters from the line start (a tab counts as 1, like Notepad). With a selection, shows the caret end.
- **Zoom**.
- **Line ending**: `Windows (CRLF)`, `Unix (LF)`, `Macintosh (CR)`; `Mixed → Windows (CRLF)` while the document has unconfirmed mixed endings.
- **Encoding**: the `Encoding.label`.
- The line-ending and encoding segments are tappable (≥ 48 dp targets) and open a single-choice popup to change them (§5.24, §5.25).
- Updates are coalesced to one per frame via `postOnAnimation`.
- On widths under 360 dp the zoom segment is hidden.

Persisted (`status_bar`, default **on**; Notepad's default was off, but on a phone the encoding/EOL visibility is valuable and costs one line).

**AC.** Moving the caret updates Ln/Col within one frame; hiding the bar gives its space back to the editor.

### 5.20 Theme

**Behavior.** Follows the system dark setting through resource qualifiers: `values/themes.xml` extends `android:Theme.Material.Light.NoActionBar`; `values-night/themes.xml` extends `android:Theme.Material.NoActionBar`. On API 31+ (`values-v31`), the accent uses `@android:color/system_accent1_600` (light) / `system_accent1_200` (dark) so it matches the user's wallpaper colors. Editor colors: text `?android:textColorPrimary` on `?android:colorBackground`. Theme change (`uiMode`) recreates the activity (it's not in `configChanges`), and the retained session keeps everything (§5.29).

**AC.** Switching system dark mode while editing keeps text, caret, undo history, and dirty state.

### 5.21 Help (Help ▸ Keyboard Shortcuts, Help ▸ About PlainText)

- **Keyboard Shortcuts**: an `AlertDialog` listing §5.34. Also exposed through `onProvideKeyboardShortcuts` so Meta+/ shows the system shortcut helper.
- **About**: app name, version (from `PackageManager.getPackageInfo`), "No internet access. No tracking. Your files stay where you put them.", and the license name. Plain text only; no links, since the app never opens the network.

### 5.22 Title bar and unsaved indicator

**Behavior.** The toolbar title is the display name, prefixed with `*` when dirty: `*notes.txt`. Suffixes: ` (Read-only)`, ` (Preview)` for oversized preview, ` (deleted)` when the file vanished from disk. Long names are ellipsized in the middle so the extension stays visible. The same string is set on the task description (`setTaskDescription`) so Recents shows it.

**AC.** Typing one character adds `*`; Save removes it; Undo to the save point removes it.

### 5.23 Default folder settings (File ▸ Default Folder…)

Shows the current folder name (tree's display name) with [Change…] [Clear] [Close]. Change runs the tree picker. Clear releases the grant and unsets the setting.

### 5.24 Encoding

**Detect on open**: §3.5.

**Change**: from the status bar's encoding segment, View ▸ Encoding ▸ (radio group), or the Save As dialog. Changing the encoding does **not** re-decode the text; it sets the encoding to be used on the next save and marks the document dirty (`metadataDirty = true`). This is "save with encoding", as in Notepad.

**Reopen with encoding** (File ▸ Reopen with Encoding ▸ five choices): re-reads the file's bytes and decodes them with the chosen encoding (for when detection guessed wrong, e.g. a BOM-less UTF-16 file below the heuristic threshold). Unsaved prompt first if dirty. Decoding errors in strict encodings → "This file isn't valid UTF-8." and nothing changes. `ANSI` always succeeds.

**Unmappable characters on save as ANSI**:

> **Some characters can't be saved as ANSI.**
> This file contains characters, such as "你", that ANSI can't store. If you save as ANSI they will be replaced with "?".
> [Save as UTF-8] [Save as ANSI anyway] [Cancel]

The example character shown is the first unmappable one found. Characters in Windows-1252 such as "é" and "€" are not affected. "Save as UTF-8" switches the document's encoding and continues.

**AC.** A file containing `café` saved as ANSI yields `63 61 66 E9`. Round-trip unit tests pass for every encoding (§10.1).

### 5.25 Line endings

**Detect on open.** Scan the decoded text once, counting `\r\n` (CRLF), lone `\n` (LF), and lone `\r` (CR).
- Exactly one kind present → that kind.
- None present (single-line file) → default line ending from settings.
- More than one kind present → **mixed**. The dominant kind (highest count; ties prefer CRLF, then LF) becomes `lineEnding`, and `mixedLineEndings = true`.

**Normalize.** The editor always holds `\n`-only text (Android's `Layout` breaks lines only on `\n` and renders `\r` as a glyph). All three kinds convert to `\n` on load; on save, `\n` converts to the document's `lineEnding`.

**Mixed endings.** Normalizing is lossy for mixed files, so the conversion must be explicit:
- On open, a toast: "This file has mixed line endings."
- The status bar shows `Mixed → Windows (CRLF)`.
- On the first save, a confirmation:

> **Mixed line endings**
> notes.txt uses mixed line endings (CRLF: 120, LF: 3). Saving will convert all of them to:
> ( ) Windows (CRLF)  ( ) Unix (LF)  ( ) Macintosh (CR)
> [Save] [Cancel]

After confirming, `mixedLineEndings = false`. If the user never edits a mixed file, it's never written, so its bytes never change.

**Change.** Status bar segment or View ▸ Line Ending ▸ radio group. Marks dirty; applied on the next save.

**Trailing newline.** Preserved naturally, because the text keeps its final `\n` (or lack of one) through normalization.

**AC.** Round-trip tests for all three kinds, with and without trailing newline. A mixed fixture opened and closed without editing is byte-identical on disk.

### 5.26 Shared text and other apps (see also §7)

- **ACTION_SEND** with `EXTRA_TEXT` (`text/plain`): after the unsaved prompt, opens a new `Untitled` document containing the text (line endings normalized; `lineEnding` = the detected kind or the default), dirty, origin `SHARE`. `EXTRA_SUBJECT`, if present, becomes the proposed file name.
- **ACTION_SEND** with `EXTRA_STREAM` (a file): opened through the open pipeline as a read-only copy: title is the stream's display name, `READ_ONLY_FILE`, so Save goes to Save As.
- **ACTION_VIEW / ACTION_EDIT**: open pipeline. Access mode per §4.7. EDIT with a write grant is `READ_WRITE`.
- `file://` URIs (sent by some old apps): try to open; on `SecurityException`/`FileNotFoundException` show "PlainText can't read this file from that app. Use File ▸ Open instead."

**AC.** Sharing "Hello" from another app shows an `*Untitled` document with "Hello". Opening a `.log` from a file manager lists PlainText in "Open with".

### 5.27 The `.LOG` feature

**Behavior.** After a file is opened (open pipeline step 8: from the picker, Recent, or a VIEW/EDIT intent), if the text starts with exactly `.LOG` followed by `\n` or end of text (case-sensitive, after BOM removal, no leading spaces):
1. If the text doesn't end with `\n`, append `\n`.
2. Append `TimeDate.now()` + `\n`.
3. Place the caret at the end and scroll to it.
4. This is one undo group; the document becomes dirty (as in Notepad).

It does **not** run for: New, shared text, recovery restore, configuration change, Reopen with Encoding, automatic reload after external change, `VIEW_ONLY_*` documents, or opening the already-open file again.

**AC.** Opening a file `".LOG\r\nfirst\r\n"` shows `.LOG`, `first`, a timestamp line, and an empty last line with the caret on it; saving writes CRLF endings.

### 5.28 Keyboard (soft and hardware)

**Soft keyboard.** `inputType="textMultiLine|textNoSuggestions"` with `imeOptions="flagNoExtractUi"` (landscape doesn't switch to a full-screen extract editor). No autocorrect, no suggestions in the text (Notepad doesn't correct you).

**Tab key.** A hardware Tab without modifiers inserts `\t` instead of moving focus (`EditorView.onKeyDown`). Shift+Tab also inserts nothing and keeps focus.

### 5.29 State across rotation and configuration changes

**Behavior.**
- `configChanges="orientation|screenSize|smallestScreenSize|screenLayout|keyboard|keyboardHidden|navigation"`: rotation, window resize (split screen, DeX, Chromebook), and keyboard attach/detach **don't** recreate the activity. That avoids re-laying out a multi-MB text for the most frequent changes. `onConfigurationChanged` re-applies window insets and hides the zoom segment on narrow widths.
- Other changes (dark mode, locale, font scale, density) recreate the activity. `onRetainNonConfigurationInstance()` returns the `EditorSession` including the current text (`editable.toString()`), selection, scroll position, find-bar state, and open dialog id. The new activity calls `setText`, restores everything, and reopens the dialog.
- `EditText`'s own state saving is disabled (`android:saveEnabled="false"`), because the framework would put the whole text into the saved-instance `Bundle` and a large document would crash with `TransactionTooLargeException`. Our `onSaveInstanceState` stores only small data (< 10 KB): document URI, display name, encoding, EOL, access mode, selection, scroll, find-bar state, pending action, and "recovery available".

**AC.** Rotate, dark-mode toggle, and font-scale change with a dirty 5 MB document: text, caret, undo history (except after process death), and asterisk preserved; no crash.

### 5.30 Process death and the recovery buffer

Android can kill the app in the background. PlainText must not lose typed text, and must not become a hidden notes store.

**Scope of the recovery buffer.**
- Exactly **one** slot, for the single open document. Never a history, never a list.
- Location: `noBackupFilesDir/recovery/buffer.txt` (UTF-8 text) and `buffer.json` (metadata: document URI, display name, encoding, EOL, mixed flag, access mode, selection, disk stamp at open, timestamp). Not backed up, not visible to other apps.
- Written only while the document is **dirty**: in `onStop`, and while in the foreground, 3 seconds after the last edit for documents ≤ 1 MB (larger documents only in `onStop`, to avoid churn). Written atomically: write `*.tmp`, then `File.renameTo`.
- **Deleted** when: the document is saved successfully, the user chooses Don't Save, New/Open replaces the document, the document becomes clean by undo, the user declines the restore prompt, or it's older than 7 days at startup.

**Restore.**
- **Process death with saved state** (`savedInstanceState != null` and "recovery available"): restore silently. The text, title (`*notes.txt`), encoding, EOL, and caret come back exactly; undo history is empty; the document is dirty. The file URI's grant is still valid if it was persisted.
- **Cold start without saved state** (user swiped the app away, crash, reboot) and a buffer exists: show:

> **Recover unsaved changes?**
> PlainText closed before your changes to notes.txt were saved (edited 23 Sep, 10:42).
> [Restore] [Discard]

  Restore behaves as above. Discard deletes the buffer. If the app was launched by an intent (Open with, Share), the prompt is shown first and the intent is handled afterwards through the normal unsaved prompt.
- The recovery buffer is never written to the user's file automatically.

**AC.** Type in a document, press Home, `adb shell am kill <pkg>`, relaunch from Recents → text restored with `*`. Swipe away from Recents, relaunch → restore prompt. Save → `recovery/` directory is empty.

### 5.31 Large files

| Size | Behavior |
|---|---|
| ≤ 1 MB | Normal. |
| 1–10 MB | Opens editable with a progress bar and a toast "Large file — some actions may be slower." Live pinch-zoom and idle recovery writes are off (§5.18, §5.30). |
| > 10 MB | Dialog: "notes.log is 48 MB. PlainText can edit files up to 10 MB." [Open preview (read-only)] [Cancel]. Preview decodes only the first 10 MB (cutting at the last complete line; invalid bytes at the cut are replaced), sets `VIEW_ONLY_PREVIEW`, title `notes.log (Preview)`, disables editing, Save, and Save As. |

**Why 10 MB.** A 10 MB file becomes ~20 MB as a Java `String`, ~20+ MB as the `Editable`, plus layout structures and a transient 10 MB byte array: about 70 MB peak, safely inside the default heap of devices running API 26+. Beyond that, `EditText` layout and IME interactions become too slow on mid-range devices.

**Performance approach.**
- `EditText` configured for speed: `breakStrategy="simple"`, `hyphenationFrequency="none"`, `textNoSuggestions` (no spell checker), `importantForAutofill="no"`, `setTextClassifier(TextClassifier.NO_OP)` on API 26+ (stops smart-selection/link scanning of the whole text), no `autoLink`, `allowUndo="false"`.
- Line/column come from `LineIndex` (O(log n) lookup; O(lines after edit) int shift per edit, < 1 ms at 200k lines) instead of counting newlines.
- Search and Replace All run on a background thread over a cached snapshot (§5.12).
- A `FastScroller` thumb appears on the right edge when the document is taller than 10 screens, so users can drag through a long log.
- Loading happens off the main thread; only `setText` is on the main thread.
- A document containing a single line longer than 100,000 characters (e.g. minified JSON) shows a toast "This file has very long lines; editing may be slow." Nothing else changes.

**AC.** 10 MB fixture opens in < 3 s on the reference device; typing latency on a 1 MB fixture stays < 16 ms per keystroke (measured with `FrameMetrics`); 10.1 MB fixture shows the preview dialog.

### 5.32 Binary or non-text files

**Behavior.** When detection flags binary (§3.5):

> **This doesn't look like a text file.**
> photo.jpg contains data that isn't text. Editing and saving it would damage it.
> [Open read-only] [Cancel]

"Open read-only" decodes as ANSI (lossless for display), sets `VIEW_ONLY_BINARY`, disables editing, Save, and Save As. Copy and Find work.

**AC.** Opening a PNG fixture shows the dialog; after "Open read-only", typing does nothing and Save is disabled.

### 5.33 External modification and deletion

**Detection.** `DocumentIo.stat(uri)` runs in `onResume` (at most once every 2 seconds) and before every Save. It compares size and last-modified with the stored `DiskStamp`. If the provider doesn't report last-modified, only size is compared; if neither is reported, detection is skipped for that provider.

**Changed on disk, document clean** → reload automatically (open pipeline without `.LOG`, keeping caret line/column where possible) and toast "notes.txt was changed by another app and has been reloaded."

**Changed on disk, document dirty** (on resume):

> **notes.txt was changed by another app.**
> Do you want to reload it? Your unsaved changes will be lost.
> [Reload] [Keep my version]

"Keep my version" updates nothing on disk; the prompt isn't repeated until the disk stamp changes again.

**Changed on disk, at Save time**:

> **notes.txt has changed since you opened it.**
> Saving will overwrite the other app's changes.
> [Overwrite] [Save As…] [Cancel]

**Deleted** (query returns no row, or `FileNotFoundException`): title gets ` (deleted)`, the document is marked dirty, toast "notes.txt no longer exists. Your text is still here — save to keep it." Save follows §5.4's deleted rows.

**AC.** Modify the file through the test provider while the app is paused, resume → auto-reload (clean) or prompt (dirty).

### 5.34 Keyboard shortcuts

Handled in `EditorActivity.dispatchKeyEvent` (so they work regardless of focus and before `EditText`'s own handling), and advertised through `onProvideKeyboardShortcuts`. Ctrl means Ctrl or Meta on ChromeOS.

| Shortcut | Action |
|---|---|
| Ctrl+N | New |
| Ctrl+O | Open |
| Ctrl+S | Save |
| Ctrl+Shift+S | Save As |
| Ctrl+P | Print |
| Ctrl+Z | Undo |
| Ctrl+Y, Ctrl+Shift+Z | Redo |
| Ctrl+X / Ctrl+C / Ctrl+V | Cut / Copy / Paste (plain text) |
| Del | Delete (framework) |
| Ctrl+A | Select All |
| Ctrl+F | Find (focuses the field if the bar is open) |
| F3 / Shift+F3 | Find Next / Find Previous |
| Ctrl+H | Replace |
| Ctrl+G | Go To Line |
| F5 | Insert Time/Date |
| Ctrl+Plus (= or numpad +) / Ctrl+Minus / Ctrl+0 | Zoom in / out / reset |
| Ctrl+mouse wheel | Zoom |
| Esc | Close find bar, else nothing |
| Tab | Insert tab character |
| Enter / Shift+Enter in find field | Find Next / Find Previous |

Mouse: right-click in the editor shows the framework's text context menu (API 26+ behavior on DeX/ChromeOS). Scroll wheel scrolls.

**AC.** Instrumented test sends each key combination via `Instrumentation.sendKeySync` and asserts the effect.

### 5.35 Accessibility

- Every icon-only control has a `contentDescription` and `tooltipText`: "Undo", "Save", "Find", "More options", "Previous match", "Next match", "Match case", "Wrap around", "Close find", "Replace", "Replace all".
- Toggle buttons in the find bar expose checked state (`CompoundButton` semantics via `ToggleButton` styled as icons).
- The editor exposes the hint "Document text" through `AccessibilityNodeInfo.setHintText` (no visible hint, so the UI stays Notepad-plain). The title is the toolbar title, which TalkBack reads on window focus; the activity sets `setTitle()` too so the window title is announced.
- Status bar segments are separate focusable views with descriptions, e.g. "Line 12, column 5", "Encoding UTF-8, double-tap to change". The status bar isn't a live region (it changes on every caret move and would be noisy).
- Results are announced through toasts ("Saved", "Cannot find …", "Replaced 12 occurrences"), which TalkBack reads.
- All touch targets ≥ 48 × 48 dp, including status-bar segments and find-bar buttons.
- All UI text is in `sp`; the editor font size is in `sp`, so system font scaling (including Android 14's non-linear scaling up to 200%) enlarges it. Dialogs use `ScrollView` so they stay usable at 200%.
- Contrast: framework Material theme text colors meet WCAG AA on both backgrounds.

**AC.** Espresso accessibility checks (`AccessibilityChecks.enable()`) pass on the main screen, find bar, and every dialog; manual TalkBack pass per §10.3.

---

## 6. UI and wireframes

### 6.1 Layout structure

```
LinearLayout (vertical, root; applies window insets as padding)
├── Toolbar (android.widget.Toolbar, set as action bar)
│   └── ProgressBar (horizontal, indeterminate, 2dp, bottom edge, GONE by default)
├── FindBar (GONE by default)
├── FrameLayout (weight 1)
│   ├── EditorView (match_parent, no padding except 8dp horizontal, gravity top|start)
│   └── FastScroller (right edge, GONE unless long document)
└── StatusBarView (28dp min height, 48dp touch targets via TouchDelegate)
```

No `ScrollView` around the editor: `EditText` scrolls itself, which is far cheaper for long text.

### 6.2 Main editor

```
┌──────────────────────────────────────────┐
│ *notes.txt               ↶   💾   🔍   ⋮ │  ← Toolbar: title, Undo, Save, Find, overflow
├──────────────────────────────────────────┤
│Shopping list                             │
│- milk                                    │
│- eggs▌                                   │
│                                          │
│Call Sam about the invoice                │
│                                          │
│                                          │
│                                          │
│                                          │
│                                          │
│                                          │
├──────────────────────────────────────────┤
│Ln 3, Col 7  │ 100% │ Windows (CRLF) │ UTF-8│  ← Status bar (toggleable)
└──────────────────────────────────────────┘
```

On widths ≥ 600 dp the toolbar also shows Redo, New, and Open as icons (`showAsAction="ifRoom"`).

### 6.3 Menu structure

The overflow (⋮) shows five top-level entries mirroring Notepad's menu bar; each opens a submenu.

```
⋮
├── File              ▸ ┌───────────────────────────────┐
│                       │ New                    Ctrl+N │
│                       │ Open…                  Ctrl+O │
│                       │ Recent               ▸        │
│                       │ Save                   Ctrl+S │
│                       │ Save As…         Ctrl+Shift+S │
│                       │ Reopen with Encoding ▸        │
│                       │ Default Folder…               │
│                       │ Page Setup…                   │
│                       │ Print…                 Ctrl+P │
│                       │ Exit                          │
│                       └───────────────────────────────┘
├── Edit              ▸ ┌───────────────────────────────┐
│                       │ Undo                   Ctrl+Z │
│                       │ Redo                   Ctrl+Y │
│                       │ Cut                    Ctrl+X │
│                       │ Copy                   Ctrl+C │
│                       │ Paste                  Ctrl+V │
│                       │ Delete                    Del │
│                       │ Find…                  Ctrl+F │
│                       │ Find Next                  F3 │
│                       │ Find Previous        Shift+F3 │
│                       │ Replace…               Ctrl+H │
│                       │ Go To…                 Ctrl+G │
│                       │ Select All             Ctrl+A │
│                       │ Time/Date                  F5 │
│                       └───────────────────────────────┘
├── Format            ▸ ┌───────────────────────────────┐
│                       │ ☑ Word Wrap                   │
│                       │ Font…                         │
│                       └───────────────────────────────┘
├── View              ▸ ┌───────────────────────────────┐
│                       │ Zoom                 ▸ In / Out / Restore Default │
│                       │ ☑ Status Bar                  │
│                       │ Encoding             ▸ (radio, 5 items)           │
│                       │ Line Ending          ▸ (radio, 3 items)           │
│                       └───────────────────────────────┘
└── Help              ▸ ┌───────────────────────────────┐
                        │ Keyboard Shortcuts            │
                        │ About PlainText               │
                        └───────────────────────────────┘
```

Shortcuts are shown with `MenuItem.setAlphabeticShortcut(char, KeyEvent.META_CTRL_ON)` (API 26), which also makes the framework display them in menus. Items are enabled/disabled in `onPrepareOptionsMenu` (e.g. Cut/Copy/Delete need a selection; Save is disabled in view-only modes).

**Notepad → PlainText mapping**

| Notepad | PlainText location | Notes |
|---|---|---|
| File ▸ New | Overflow ▸ File ▸ New | |
| File ▸ Open… | File ▸ Open… | System picker |
| File ▸ Save | Toolbar 💾, File ▸ Save | |
| File ▸ Save As… | File ▸ Save As… | Encoding/EOL chosen in pre-picker dialog |
| (New Window) | File ▸ Recent ▸ | Substitute |
| File ▸ Page Setup… | File ▸ Page Setup… | Paper/orientation in system print dialog |
| File ▸ Print… | File ▸ Print… | Android print framework |
| File ▸ Exit | File ▸ Exit, system Back | |
| Edit ▸ Undo | Toolbar ↶, Edit ▸ Undo | Multi-level |
| (Redo, later versions) | Edit ▸ Redo | |
| Edit ▸ Cut/Copy/Paste/Delete | Edit menu, floating selection toolbar | |
| Edit ▸ Find… / Find Next | Toolbar 🔍, Edit menu, F3 | Inline bar, not a dialog |
| Edit ▸ Replace… | Edit ▸ Replace… | Same bar with replace row |
| Edit ▸ Go To… | Edit ▸ Go To… | Works with word wrap on |
| Edit ▸ Select All | Edit ▸ Select All | |
| Edit ▸ Time/Date | Edit ▸ Time/Date, F5 | |
| Format ▸ Word Wrap | Format ▸ Word Wrap | |
| Format ▸ Font… | Format ▸ Font… | System families only |
| View ▸ Zoom | View ▸ Zoom ▸, pinch | |
| View ▸ Status Bar | View ▸ Status Bar | |
| Help ▸ View Help | Help ▸ Keyboard Shortcuts | |
| Help ▸ About Notepad | Help ▸ About PlainText | |

### 6.4 Find / Replace bar

Find mode:

```
┌──────────────────────────────────────────┐
│ *notes.txt               ↶   💾   🔍   ⋮ │
├──────────────────────────────────────────┤
│ [ invoice_____________ ]  ↑  ↓  Aa  ⟲  ✕ │
├──────────────────────────────────────────┤
│Call Sam about the ████████               │  ← match selected
```

Replace mode (Ctrl+H or Edit ▸ Replace…):

```
├──────────────────────────────────────────┤
│ [ invoice_____________ ]  ↑  ↓  Aa  ⟲  ✕ │
│ [ bill________________ ] [Replace] [All] │
├──────────────────────────────────────────┤
```

- ↑ Find Previous, ↓ Find Next, **Aa** Match case (toggle), **⟲** Wrap around (toggle), ✕ close.
- Toggles show a filled background when on, and TalkBack reads "Match case, on".

### 6.5 Go To Line

```
┌──────────────────────────────────┐
│ Go To Line                       │
│                                  │
│ Line number:                     │
│ [ 12_______ ]                    │
│ The line number is beyond the    │  ← only when invalid (error color)
│ total number of lines            │
│                                  │
│               [Cancel]  [Go To]  │
└──────────────────────────────────┘
```

Numeric keyboard (`inputType="number"`); Enter = Go To.

### 6.6 Font

```
┌──────────────────────────────────────────┐
│ Font                                     │
│                                          │
│ Font:            Style:        Size:     │
│ ┌──────────────┐ ┌───────────┐ ┌──────┐  │
│ │●Monospace    │ │●Regular   │ │ 12   │  │
│ │ Sans Serif   │ │ Italic    │ │●14   │  │
│ │ Serif        │ │ Bold      │ │ 16   │  │
│ │ Sans Condens.│ │ Bold Ital.│ │ 18   │  │
│ │ Serif Mono   │ └───────────┘ │ ...  │  │
│ └──────────────┘               └──────┘  │
│                                          │
│ Sample                                   │
│ ┌──────────────────────────────────────┐ │
│ │ AaBbYyZz 0Oo1lI                      │ │
│ └──────────────────────────────────────┘ │
│                         [Cancel]  [OK]   │
└──────────────────────────────────────────┘
```

On phones narrower than 480 dp the three lists stack vertically inside a `ScrollView` (family list, then style and size as `Spinner`s).

### 6.7 Save options (encoding on Save / Save As)

Default-folder mode (Save of an Untitled document with a valid folder):

```
┌──────────────────────────────────────────┐
│ Save                                     │
│                                          │
│ Folder: Notes                            │
│ File name:                               │
│ [ Shopping list.txt______________ ]      │
│                                          │
│ Encoding:     [ UTF-8            ▾ ]     │
│ Line ending:  [ Windows (CRLF)   ▾ ]     │
│ ☐ Use for new files                      │
│                                          │
│ [Other location…]      [Cancel]  [Save]  │
└──────────────────────────────────────────┘
```

Save As mode (anywhere):

```
┌──────────────────────────────────────────┐
│ Save As                                  │
│                                          │
│ Encoding:     [ UTF-8 with BOM   ▾ ]     │
│ Line ending:  [ Unix (LF)        ▾ ]     │
│ ☐ Use for new files                      │
│                                          │
│ You'll choose the name and location next.│
│                                          │
│              [Cancel]  [Choose location…]│
└──────────────────────────────────────────┘
```

The name field pre-selects the part before the extension. Invalid characters show an inline error: `A file name can't contain any of these characters: \ / : * ? " < > |`.

### 6.8 Page Setup

```
┌──────────────────────────────────────────┐
│ Page Setup                               │
│                                          │
│ Margins (millimetres)                    │
│   Left  [ 19 ]        Right  [ 19 ]      │
│   Top   [ 25 ]        Bottom [ 25 ]      │
│                                          │
│ Header  [ &f____________________ ]       │
│ Footer  [ Page &p_______________ ]       │
│ Font size (pt)  [ 10 ▾ ]                 │
│                                          │
│ &f file name  &p page  &d date  &t time  │
│ &l left  &c center  &r right  && &       │
│                                          │
│ Paper size and orientation are set in    │
│ the print dialog.                        │
│                           [Cancel] [OK]  │
└──────────────────────────────────────────┘
```

Margins accept 0–100 mm; invalid values show inline errors and disable OK.

### 6.9 First-launch folder picker

```
┌──────────────────────────────────────────┐
│ Choose a folder for your notes           │
│                                          │
│ PlainText saves notes as ordinary .txt   │
│ files. Pick a folder once — for example  │
│ Documents/Notes — and new notes will be  │
│ saved there. You can see them in any     │
│ file manager or copy them to a computer. │
│                                          │
│ Tip: use "New folder" in the next screen │
│ to create a Notes folder. Android        │
│ doesn't allow the Download folder or the │
│ storage root.                            │
│                                          │
│ You can still open and save files        │
│ anywhere with Open and Save As.          │
│                                          │
│             [Not now]  [Choose folder]   │
└──────────────────────────────────────────┘
```

### 6.10 Unsaved-changes dialog

```
┌──────────────────────────────────────────┐
│ PlainText                                │
│                                          │
│ Do you want to save changes to           │
│ notes.txt?                               │
│                                          │
│       [Cancel]  [Don't Save]  [Save]     │
└──────────────────────────────────────────┘
```

Positive = Save, Negative = Don't Save, Neutral = Cancel. For an untitled document the text is "Do you want to save changes to Untitled?".

### 6.11 Settings (SharedPreferences, file `settings`)

| Key | Type | Default | Notes |
|---|---|---|---|
| `word_wrap` | Boolean | `true` | |
| `font_family` | String | `"monospace"` | One of the five family names |
| `font_style` | Int | `Typeface.NORMAL` | |
| `font_size_sp` | Int | `14` | |
| `zoom_percent` | Int | `100` | 10–500 |
| `status_bar` | Boolean | `true` | |
| `default_folder_uri` | String? | `null` | Tree URI |
| `default_encoding` | String | `"UTF8"` | `Encoding.name` for new files |
| `default_line_ending` | String | `"CRLF"` | `LineEnding.name` for new files |
| `find_match_case` | Boolean | `false` | |
| `find_wrap_around` | Boolean | `true` | |
| `page_margins_mm` | String | `"19,19,25,25"` | L,R,T,B |
| `page_header` | String | `"&f"` | |
| `page_footer` | String | `"Page &p"` | |
| `print_font_size_pt` | Int | `10` | |
| `recent_files` | String | `"[]"` | JSON array |
| `first_run_done` | Boolean | `false` | |

Default encoding and line ending for new files are set from the Save options dialog (§6.7): a checkbox "Use for new files" under the spinners writes `default_encoding` and `default_line_ending`. There is no separate settings screen; every other setting is changed where it is used (menus, Font dialog, Page Setup, find bar).

**Why CRLF by default.** PlainText recreates Windows Notepad, and CRLF files display correctly in every Windows editor including pre-2018 Notepad, while every Android and Unix tool reads CRLF fine. Users who prefer LF change it once with "Use for new files".

### 6.12 Other dialogs

All dialogs are framework `AlertDialog`s with the device default alert theme. Wording for errors is in §8. Dialogs that contain text fields show the IME automatically (`SOFT_INPUT_STATE_ALWAYS_VISIBLE` on the dialog window).

### 6.13 Window insets and edge-to-edge

The root view installs `setOnApplyWindowInsetsListener`:
- API 30+: padding = `insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())`, and bottom padding = max(that bottom, `insets.getInsets(WindowInsets.Type.ime()).bottom`) so the status bar and caret stay above the keyboard.
- API 26–29: padding = `insets.systemWindowInsets` (includes the IME with `adjustResize`).

`windowSoftInputMode="adjustResize|stateHidden"`. Status and navigation bar colors are transparent with light/dark icon appearance matching the theme (`WindowInsetsController.setSystemBarsAppearance` on API 30+, `SYSTEM_UI_FLAG_LIGHT_STATUS_BAR`/`LIGHT_NAVIGATION_BAR` on 26–29).

---

## 7. Intent filters and AndroidManifest outline

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- No <uses-permission> elements. Intentionally none. -->

    <application
        android:label="@string/app_name"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher"
        android:theme="@style/Theme.PlainText"
        android:allowBackup="false"
        android:supportsRtl="true"
        android:appCategory="productivity"
        android:enableOnBackInvokedCallback="true">

        <activity
            android:name=".EditorActivity"
            android:exported="true"
            android:launchMode="singleTask"
            android:resizeableActivity="true"
            android:windowSoftInputMode="adjustResize|stateHidden"
            android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout|keyboard|keyboardHidden|navigation">

            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>

            <!-- Open with / Edit with, by MIME type -->
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <action android:name="android.intent.action.EDIT" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:scheme="content" />
                <data android:scheme="file" />
                <data android:mimeType="text/*" />
                <data android:mimeType="application/json" />
                <data android:mimeType="application/xml" />
                <data android:mimeType="application/x-ini" />
                <data android:mimeType="application/x-yaml" />
            </intent-filter>

            <!-- Open with / Edit with, by extension, for senders that report
                 application/octet-stream or another unhelpful type.
                 pathPattern is matched against the decoded path; the extra
                 ".*\\..*\\." variants work around Android's non-greedy matcher
                 when earlier path segments contain dots. pathSuffix (API 31+)
                 is added alongside and ignored on older versions. -->
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <action android:name="android.intent.action.EDIT" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:scheme="content" />
                <data android:scheme="file" />
                <data android:host="*" />
                <data android:mimeType="*/*" />
                <!-- repeat this trio for: txt log ini md json csv cfg -->
                <data android:pathPattern=".*\\.txt" />
                <data android:pathPattern=".*\\..*\\.txt" />
                <data android:pathPattern=".*\\..*\\..*\\.txt" />
                <data android:pathSuffix=".txt" />
                <!-- … .log .ini .md .json .csv .cfg … -->
            </intent-filter>

            <!-- Shared text or a shared text file -->
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="text/plain" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

Notes:
- `singleTask`: there's only ever one editor instance, so two windows can't edit the same file with conflicting unsaved changes. A new VIEW/EDIT/SEND intent arrives in `onNewIntent` and goes through the unsaved prompt (§5.9).
- The launcher icon is an adaptive icon (`mipmap-anydpi-v26/ic_launcher.xml`) with a vector foreground, a color background, and a `<monochrome>` layer for themed icons on Android 13+. Because minSdk is 26, no raster fallbacks are shipped.
- No `<queries>` element is needed: we only launch system picker intents and the print service, which are visible without it.
- No `FileProvider`, services, receivers, or content providers.

**Handling in code** (`EditorActivity.handleIntent(intent)`), called from `onCreate` (when `savedInstanceState == null`) and `onNewIntent`:

| Action | Data | Result |
|---|---|---|
| `MAIN` | — | Nothing (blank Untitled or retained state). |
| `VIEW` | URI | Open pipeline; `READ_ONLY_FILE` unless a write grant exists. |
| `EDIT` | URI | Open pipeline; `READ_WRITE` if a write grant exists, else `READ_ONLY_FILE`. |
| `SEND` | `EXTRA_TEXT` | New Untitled document with the text. |
| `SEND` | `EXTRA_STREAM` | Open pipeline as a read-only copy. |

A processed intent is marked (`intent.putExtra("handled", true)` and `setIntent`) so recreation doesn't handle it twice.

---

## 8. Error handling

Principles: never lose the user's text; say what happened in plain words; offer the next useful action; never show exception class names. Errors that need a decision are dialogs; informational ones are toasts.

| Situation | Detected by | Message | Actions |
|---|---|---|---|
| Permission lost on the open file (grant revoked, provider app uninstalled) | `SecurityException` on read/write/stat | "PlainText no longer has permission to access notes.txt." | [Save As…] [Cancel] — text stays in the editor. |
| Default folder unavailable | `DefaultFolder.validate()` fails | "Your notes folder is no longer available. It may have been deleted, renamed, or on a removed SD card." | [Choose folder] [Save As…] [Cancel] |
| Storage full | `IOException` whose message or `ErrnoException.errno` is `ENOSPC`; or `SyncFailedException` | "There isn't enough space to save notes.txt. The file on the device may be incomplete. Your text is safe here — free up space and save again, or save somewhere else." | [Save As…] [OK] — document stays dirty; recovery buffer kept. |
| Write failed, other I/O | `IOException` | "Couldn't save notes.txt. Your text is safe here — try again or save somewhere else." | [Try again] [Save As…] [Cancel] |
| Truncation not supported and file got longer than written | Size re-query after `"w"` write (§3.4 step 4) | "notes.txt was saved, but the app that stores it may have left old text at the end. Use Save As to make a clean copy." | [Save As…] [OK] |
| File deleted externally | Missing row / `FileNotFoundException` on stat | Toast: "notes.txt no longer exists. Your text is still here — save to keep it." | Title shows `(deleted)`. |
| File modified externally | `DiskStamp` mismatch | See §5.33. | Reload / Keep / Overwrite / Save As |
| Read-only provider | §4.7 | "notes.txt can't be saved here. The app that provided this file doesn't allow changes. Save a copy somewhere else?" | [Save As…] [Cancel] |
| Can't open file | Exceptions on read | "Can't open notes.txt. It may have been moved or deleted, or access was removed." | [OK]; Recent entry removed. |
| File too large | Size > 10 MB | §5.31 | [Open preview] [Cancel] |
| Binary file | Detection | §5.32 | [Open read-only] [Cancel] |
| Invalid encoding on Reopen | Strict decode fails | "This file isn't valid UTF-16 LE." | [OK] |
| Unmappable in ANSI | `TextCodec.findUnmappable` | §5.24 | [Save as UTF-8] [Save as ANSI anyway] [Cancel] |
| No picker on device | `ActivityNotFoundException` | "This device doesn't have a file picker, so PlainText can't open or save files here." | [OK] |
| Out of memory while loading | `OutOfMemoryError` caught around decode | "notes.log is too large to open on this device." | [OK]; previous document kept. |
| Print failure | `onLayout`/`onWrite` failure callback | Toast: "Couldn't prepare the document for printing." | — |

Logging: `android.util.Log` calls exist only at debug level and are stripped from release builds by R8 (`-assumenosideeffects`, §11). Nothing is sent anywhere.

---

## 9. Performance and APK-size budget

### 9.1 Reference device and budgets

Reference: a mid-range 2022 phone (e.g. Pixel 6a class) running the latest Android release, plus an API 26 emulator for compatibility (not timed).

| Metric | Budget |
|---|---|
| Cold start to editable blank document | < 300 ms (`reportFullyDrawn`) |
| Open 1 MB file | < 500 ms |
| Open 10 MB file | < 3 s, progress shown after 300 ms |
| Keystroke latency, 1 MB document | < 16 ms per frame (no jank frames while typing) |
| Find Next, 10 MB document | < 200 ms |
| Replace All (10,000 matches), 1 MB | < 500 ms |
| Save 10 MB | < 1.5 s on internal storage |
| Rotation with 5 MB document | No recreation; relayout < 500 ms |
| Peak Java heap, 10 MB document | < 100 MB |
| Idle CPU | 0 (no timers except the 3 s recovery debounce, which runs only after edits) |

### 9.2 APK-size budget

| Component | Budget |
|---|---|
| `classes.dex` (app + shrunk Kotlin stdlib) | ≤ 150 KB |
| `resources.arsc` (English only) | ≤ 40 KB |
| `res/` (layouts, ~15 vector drawables, adaptive icon) | ≤ 40 KB |
| `AndroidManifest.xml` | ≤ 5 KB |
| Signing block, zip overhead | ≤ 15 KB |
| **Total (release APK)** | **≤ 250 KB target** |

- **Alarm** at 500 KB: CI prints a warning and the PR must explain the growth.
- **Hard limit** at 2 MB: CI fails the build.

**Why this is achievable.** There are no libraries, so nearly all bytes are our own code and a handful of XML resources. There are no raster images (vectors only, adaptive icon at minSdk 26), no fonts (system families), no native code (no ABI splits needed), and one locale. R8 removes unused Kotlin stdlib code, and the disabled null-check intrinsics remove thousands of small call sites.

**Measuring.** `./gradlew :app:assembleRelease` then `apkanalyzer apk file-size app/build/outputs/apk/release/app-release.apk` and `apkanalyzer apk download-size …`. The CI script `scripts/check-apk-size.sh` enforces the limits. For the Play Store, the AAB is uploaded; Play's per-device APK will be the same size or smaller.

---

## 10. Testing plan

### 10.1 JVM unit tests (`app/src/test`, pure Kotlin, JUnit 4)

**Encoding and line-ending round-trips (the most important tests).**

- `TextCodecRoundTripTest` — for each `Encoding` × each `LineEnding` × {trailing newline, no trailing newline} × {empty, ASCII, Latin-1 accents, CJK, emoji with surrogate pairs (skipped for ANSI)}:
  `bytes = encode(denormalize(text, eol), enc)` → `detect(bytes)` gives `enc` (except ASCII-only, where UTF8/ANSI are equivalent) → `decode` → `normalize` gives `text` and `eol` → re-encode gives identical `bytes`.
- `Windows1252Test` — all 256 byte values decode to distinct chars and re-encode to the same byte; the five undefined bytes map to U+0081, U+008D, U+008F, U+0090, U+009D.
- `EncodingDetectorTest` — BOMs (UTF-8, UTF-16 LE/BE, UTF-32 LE → binary); BOM-less UTF-16 LE/BE English text detected; BOM-less UTF-16 with invalid surrogates falls through; valid UTF-8 without BOM; invalid UTF-8 (`café` in 1252) → ANSI; NUL byte → binary; >10% control chars → binary; ESC sequences (ANSI color logs) are not binary; empty file → default.
- `LineEndingsTest` — counts for pure CRLF/LF/CR; mixed with correct dominant choice and tie-break; `\r\r\n` counted as CR + CRLF; normalize/denormalize inverse on non-mixed input; file ending with lone `\r`.
- `UnmappableTest` — `findUnmappable` returns the first non-1252 char or null; `€` (0x80) is mappable.

**Other units.**
- `LineIndexTest` — randomized fuzz: 10,000 random inserts/deletes (including newlines) applied both to a `StringBuilder` and to `LineIndex`; after each, `LineIndex` equals a brute-force recomputation; line/column lookups match.
- `UndoManagerTest` — coalescing rules (contiguity, kind, 2 s pause with an injected clock, newline break, composing merge), undo/redo sequence correctness against a model, redo cleared on new edit, save-point tracking, caps.
- `SearchEngineTest` — match case on/off, wrap on/off, forward/backward from various selections, not found, term at document start/end, overlapping matches ("aa" in "aaa" → Find Next finds 0 then 1 then wraps), Replace-then-find semantics, Replace All counts.
- `LogFeatureTest` — `.LOG` exact, `.LOG` alone with no newline, `.log` lowercase (no), ` .LOG` (no), `.LOGGING` (no), timestamp appended with and without trailing newline.
- `HeaderFooterTest` — `&f`, `&p`, `&d`, `&t`, `&&`, unknown `&x`, alignment sections `&l…&c…&r…`, empty string.
- `FileNamesTest` — sanitizing, 255-byte limit with multibyte names, proposed name from first line, `notes (2).txt` sequence, extension appended, MIME mapping (`md` → `text/markdown`, unknown → `application/octet-stream`).
- `RecentFilesTest` (JSON model part) — ordering, dedupe by URI, cap at 10, evicted list returned for grant release.

### 10.2 Instrumented tests (`app/src/androidTest`)

**Test infrastructure.**
- `TestDocumentsProvider` (in the androidTest APK) — a `DocumentsProvider` backed by a temp directory with switchable behaviors: read-only documents (`FLAG_SUPPORTS_WRITE` off), rejecting mode `"wt"`, non-truncating `"w"`, missing `COLUMN_LAST_MODIFIED`, throwing `ENOSPC` after N bytes, deleting or modifying a file on command. This makes SAF tests deterministic without depending on the OEM picker.
- Espresso-Intents stubs `ACTION_OPEN_DOCUMENT`, `ACTION_CREATE_DOCUMENT`, and `ACTION_OPEN_DOCUMENT_TREE` results with URIs from `TestDocumentsProvider`.
- A small number of end-to-end tests drive the real DocumentsUI picker with UI Automator on the emulator matrix, to catch platform changes.

**Emulator matrix:** API 26, 29, 30, 33, 34, 36. Phone and tablet/desktop window sizes.

**SAF flows.**
1. First run: choose tree → grant persisted; restart → no dialog.
2. New + Save into default folder → file bytes exact; title updates.
3. Name collision → Replace / Keep both / Cancel each behave per §4.6.
4. Default folder revoked (`releasePersistableUriPermission` in test) → dialog; choose new folder → save completes.
5. Default folder deleted in provider → same dialog.
6. Save As with each encoding and EOL → exact bytes.
7. Provider renames on create → toast and correct title.
8. Open read-only document → edit → Save → "can't be saved here" → Save As.
9. Provider rejects `"wt"` → fallback path writes correct, truncated content.
10. Non-truncating `"w"` provider → warning dialog.
11. `ENOSPC` → error dialog, document still dirty, recovery buffer present.
12. External modification while paused → auto-reload when clean; prompt when dirty; overwrite prompt at save.
13. External deletion → `(deleted)` and save recreates in default folder.
14. Recent files: open, reopen, entry removal on failure, grant released on eviction.

**Intents.** VIEW/EDIT with and without write grants; `ACTION_SEND` text (with subject); `ACTION_SEND` stream; `file://` failure message; `onNewIntent` while dirty triggers the prompt.

**Lifecycle.**
- Rotation, window resize, keyboard attach: no activity recreation (assert same instance), state intact.
- Dark mode and font scale changes: recreation with text, selection, undo history, find bar, open dialog preserved.
- Process death: `am kill` while backgrounded → silent restore; task removal → restore prompt; save → recovery directory empty.
- Pending action survives rotation while the Save As picker is open.

**Features.** `.LOG` on open; F5; Go To Line bounds; Replace All undo; zoom keys and clamps; word wrap toggle keeps caret; print adapter layout and page contents via `PdfRenderer` text-less check of page count plus header rendering in a bitmap region; keyboard shortcuts via `sendKeySync`; Tab inserts `\t`; paste strips spans.

**Performance (macro, run nightly, not per-PR).** Open 1 MB/10 MB fixtures, typing jank with `FrameMetricsAggregator`-style frame capture (framework `Window.OnFrameMetricsAvailableListener`), Find Next timing. Fail if budgets in §9.1 are exceeded by > 20%.

### 10.3 Manual checks before release

- TalkBack walk-through of every screen and dialog, including a 10 MB document (verify TalkBack remains responsive when focusing the editor).
- Samsung DeX or ChromeOS: resize window, mouse right-click, Ctrl+scroll zoom, all shortcuts.
- SD card and a cloud provider (Drive) open/save round-trip.
- 200% font scale: all dialogs usable.
- Airplane mode: no behavior change (sanity check that nothing tries the network).

### 10.4 CI

On every PR: `./gradlew lint testDebugUnitTest assembleRelease` + `scripts/check-apk-size.sh` + instrumented tests on API 30 and 36 emulators. Nightly: full emulator matrix and performance tests.

---

## 11. Build and release configuration

### 11.1 Project layout

```
PlainText-Android-App/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/wrapper/…
├── scripts/check-apk-size.sh
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── java/io/github/kaustubhowmick/plaintext/…
        │   └── res/
        │       ├── drawable/            (vector icons only)
        │       ├── layout/              (activity_editor.xml, find_bar.xml, dialog_*.xml)
        │       ├── menu/editor.xml
        │       ├── mipmap-anydpi-v26/ic_launcher.xml
        │       ├── values/ values-night/ values-v31/
        ├── test/java/…                  (JVM unit tests)
        └── androidTest/java/…           (instrumented tests, TestDocumentsProvider)
```

One application module; no library modules. (A single module avoids per-module R8 boundaries and keeps the build simple.)

### 11.2 `settings.gradle.kts`

```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "PlainText"
include(":app")
```

### 11.3 Root `build.gradle.kts`

Use the latest stable Android Gradle Plugin and Kotlin at implementation time. On AGP 9+, Kotlin support is built in: omit the `org.jetbrains.kotlin.android` plugin lines and keep the `kotlin { compilerOptions { … } }` block.

```kotlin
plugins {
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
}
```

### 11.4 `gradle.properties`

```properties
org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8
org.gradle.caching=true
org.gradle.configuration-cache=true
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
```

`android.useAndroidX=true` is required only because the instrumented-test artifacts are AndroidX; it adds nothing to the APK. Runtime dependencies are policed by the guard in §11.5.

### 11.5 `app/build.gradle.kts`

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.kaustubhowmick.plaintext"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.kaustubhowmick.plaintext"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // English only for v1. (On older AGP: defaultConfig { resourceConfigurations += "en" }.)
    androidResources {
        localeFilters += listOf("en")
    }

    signingConfigs {
        create("release") {
            // Supplied by CI; never committed.
            storeFile = System.getenv("PT_KEYSTORE")?.let { file(it) }
            storePassword = System.getenv("PT_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("PT_KEY_ALIAS")
            keyPassword = System.getenv("PT_KEY_PASSWORD")
            enableV1Signing = false   // minSdk 26: v2/v3 only, no META-INF signature files
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true        // R8: shrink, optimize, obfuscate
            isShrinkResources = true      // remove unused resources
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            vcsInfo.include = false       // no version-control-info.textproto in the APK
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        buildConfig = false               // version read from PackageManager
        resValues = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/*.kotlin_module",
                "META-INF/*.version",
                "META-INF/**/LICENSE*",
                "META-INF/**/NOTICE*",
                "kotlin/**",
                "DebugProbesKt.bin",
            )
        }
    }

    // No dependency metadata block: there are no third-party dependencies to report.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // Drop Intrinsics null-check calls from generated bytecode: smaller dex.
        freeCompilerArgs.addAll(
            "-Xno-param-assertions",
            "-Xno-call-assertions",
            "-Xno-receiver-assertions",
        )
    }
}

dependencies {
    // Runtime: none (kotlin-stdlib is added by the Kotlin plugin and shrunk by R8).

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-accessibility:3.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
```

Add this guard to `app/build.gradle.kts` so a runtime dependency (AndroidX or otherwise) can never slip in unnoticed. It fails the release build if anything other than the Kotlin standard library (and its JetBrains annotations) is on the release runtime classpath:

```kotlin
configurations.matching { it.name == "releaseRuntimeClasspath" }.configureEach {
    incoming.afterResolve {
        val extra = resolutionResult.allComponents
            .map { it.id }
            .filterIsInstance<org.gradle.api.artifacts.component.ModuleComponentIdentifier>()
            .filterNot { it.group == "org.jetbrains.kotlin" || it.group == "org.jetbrains" }
        check(extra.isEmpty()) { "Unexpected runtime dependencies: $extra — update design.md §2.3 first" }
    }
}
```

### 11.6 `app/proguard-rules.pro`

```proguard
# The app uses no reflection, serialization, or JNI; AAPT generates keep rules
# for the manifest's activity and for views referenced from layouts.

# Strip debug/verbose/info logging from release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Allow aggressive repackaging into a single package for shorter names.
-repackageclasses ''
-allowaccessmodification
```

`EditorView` and `FastScroller`, `FindBar`, `StatusBarView` are referenced from layout XML, so AAPT's generated rules keep their constructors. No other keep rules are needed.

### 11.7 `scripts/check-apk-size.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail
APK="${1:-app/build/outputs/apk/release/app-release.apk}"
SIZE=$(stat -c %s "$APK")
echo "Release APK: $SIZE bytes"
WARN=$((500 * 1024)); FAIL=$((2 * 1024 * 1024))
if (( SIZE > FAIL )); then echo "FAIL: APK exceeds 2 MB"; exit 1; fi
if (( SIZE > WARN )); then echo "WARNING: APK exceeds 500 KB budget"; fi
```

### 11.8 Release

- Store releases: `./gradlew :app:bundleRelease` → upload the AAB. Play generates per-device APKs; with no native code or density-specific resources, they are essentially the same size as the universal APK.
- Sideload / F-Droid-style releases: `./gradlew :app:assembleRelease` universal APK (same budget applies).
- Play Data safety form: no data collected, no data shared.
- `versionCode` increments on every release; `versionName` follows semver.

---

## 12. Future ideas (not in v1)

These are explicitly out of scope for v1. Each would need its own size and complexity review.

- **More encodings**: ISO-8859-x, Windows-125x, Shift-JIS, GB18030, EUC-KR, with "Reopen with encoding" choosing among them. (Uses `java.nio.charset`, so no size cost; the cost is detection ambiguity.)
- **Regular-expression search** and "Highlight all matches".
- **Line numbers gutter** (a custom view synchronized with `EditText` scrolling).
- **Tabs or multi-window** (`FLAG_ACTIVITY_NEW_DOCUMENT` / `documentLaunchMode`) for editing several files side by side on tablets and DeX, with per-document recovery slots.
- **"Edit with PlainText" in the text selection menu** (`ACTION_PROCESS_TEXT`).
- **Quick Settings tile / launcher shortcut** "New note" that opens a blank document ready to save into the default folder.
- **File properties** dialog (tap the title): location, size, dates, encoding, line count.
- **Opt-in autosave to the file** after an idle period, off by default and clearly labeled.
- **Localization** of all strings.
- **Hex view** for binary files.
- **Editing files larger than 10 MB** with a piece-table backed custom text view (a large project; only if users need it).
- **Print preview inside the app** (the system dialog already provides one).
