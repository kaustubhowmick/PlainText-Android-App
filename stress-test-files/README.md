# Stress-test files

Files for manually stress-testing the editor. Nothing here is part of the Gradle build or the APK.

```bash
python3 stress-test-files/generate.py                  # (re)creates files/ and checksums.txt
cd stress-test-files && sha256sum -c checksums.txt     # verify
```

The generator is deterministic, so a fresh run matches the committed `checksums.txt`. The three largest files (`normal-10MB.txt`, `normal-50MB.txt`, `single-line-5MB.txt`) are git-ignored to keep the repository small. Run the script to create them, or download the **stress-test-files** artifact from any **Build** run in the Actions tab. That artifact has every file plus `checksums.txt`.

The **Emulator stress run** workflow (Actions tab, **Run workflow**) opens these files on an Android emulator and logs crashes, "not responding" errors, and how long each step takes. The script is `.github/emulator/stress-smoke.sh`.

To get the files onto a phone: `adb push stress-test-files/files /sdcard/Download/stress-test-files`

## What each file tests

"MB" means MiB here. The editor's limit is `MAX_FILE_BYTES = 10 * 1024 * 1024`.

| File | Tests | Expected (design.md) |
|---|---|---|
| `empty.txt` | 0 bytes | Opens empty; UTF-8 fallback; nothing written on Save without edits. |
| `normal-1MB.txt` | 1,048,576 bytes of LF prose | Above the 1,000,000-char large-file threshold: progress bar + "Large file" toast (§5.31). |
| `normal-10MB.txt` | 10,485,760 bytes, exactly the limit | Still editable (the limit is "> 10 MB"). |
| `normal-50MB.txt` | 52,428,800 bytes | Oversized dialog → read-only preview of the first 10 MB. |
| `single-line-5MB.txt` | 5 MB with no line breaks | "Very long lines" dialog: Open read-only / Edit anyway (each edit re-measures the whole line). Word Wrap on/off, horizontal scroll, End key. |
| `500000-short-lines.txt` | 500,000 lines of 0–3 chars | Opens without running out of memory (text is laid out in slices). Zoom, Word Wrap, and rotation too. Go To Line 500000, fast scroller. |
| `encoding-utf8.txt` | UTF-8, no BOM | Status bar `UTF-8`. |
| `encoding-utf8-bom.txt` | UTF-8 with BOM | `UTF-8 with BOM`; BOM kept on save. |
| `bom-only.txt` | Just `EF BB BF` | Empty document, `UTF-8 with BOM`. |
| `encoding-utf16le-bom.txt` / `encoding-utf16be-bom.txt` | UTF-16 with BOM | `UTF-16 LE` / `UTF-16 BE`; emoji survive as surrogate pairs. |
| `encoding-windows1252.txt` | Windows-1252, CRLF: é ü ñ €, and every other 0x80–0x9F glyph | `ANSI`; € (0x80) and … (0x85, NEL in Latin-1) decode correctly. |
| `line-endings-crlf.txt` / `-lf` / `-cr` | One line ending each | Status bar shows the matching kind. |
| `line-endings-mixed.txt` | All three, plus `\r\r\n` and `\n\r` (CR 12, CRLF 11, LF 11) | "Mixed line endings" toast; status bar `Mixed → Macintosh (CR)`; opening and closing without edits leaves bytes unchanged. No trailing newline. |
| `content-emoji.txt` | Skin tones, ZWJ families, flags, keycaps | Caret and Backspace move over whole clusters; no broken surrogates. |
| `content-rtl-arabic-hebrew.txt` | Arabic, Hebrew, mixed direction, bidi controls | Right-to-left layout, selection, and caret movement. |
| `content-chinese-japanese.txt` | Chinese, Japanese, Korean, full-width, and CJK Extension B characters | Wrapping a long line that has no spaces. |
| `content-zero-width.txt` | ZWSP, ZWNJ, ZWJ, WJ, soft hyphen, combining marks | Find, caret movement, and Col count around invisible characters. |
| `content-tabs.txt` | Leading, trailing, and 200 consecutive tabs | Tab rendering and column count. |
| `content-long-words.txt` | Unbroken runs up to 100,000 chars | Word Wrap breaking inside a word. |
| `journal.LOG` | First line `.LOG`, CRLF | Timestamp appended on open; saves with CRLF (§5.27). |
| `binary-png-renamed.txt` | A 16×16 PNG | Binary dialog → view-only (§5.32). |
| `file name with spaces.txt`, `emoji 📝 notes 🚀.txt`, `日本語のメモ.txt`, `Ελληνικά ñandú café.txt`, `заметки на русском.txt`, `ملاحظات عربية.txt` | Unusual file names | Title bar, Recent files, Save As name field, and the oversized-dialog text. |
