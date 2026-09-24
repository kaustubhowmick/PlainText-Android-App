#!/usr/bin/env python3
"""Generates the editor stress-test files in files/ and writes checksums.txt.

Output is deterministic: running this again produces byte-identical files, so
checksums.txt stays valid and `sha256sum -c checksums.txt` verifies a fresh
run. Only the Python standard library is used.

Usage: python3 stress-test-files/generate.py
"""

import hashlib
import struct
import zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT = HERE / "files"

KIB = 1024
MIB = 1024 * KIB

WORDS = (
    "the quick brown fox jumps over lazy dog notepad plain text editor file "
    "line column save open encoding window paper ink letter word sentence "
    "paragraph margin cursor select copy paste find replace undo redo wrap "
    "font zoom status bar menu print page header footer date time log entry"
).split()


class Lcg:
    """Tiny fixed LCG so output never depends on the Python version."""

    def __init__(self, seed):
        self.state = seed

    def next(self, bound):
        self.state = (self.state * 6364136223846793005 + 1442695040888963407) % 2**64
        return (self.state >> 33) % bound


def prose(size, seed=1, eol="\n"):
    """ASCII prose of exactly `size` bytes, lines of ~40-100 chars, ending in `eol`."""
    rng = Lcg(seed)
    chunks = []
    total = 0
    line = []
    line_len = 0
    target = 40 + rng.next(61)
    while total < size:
        word = WORDS[rng.next(len(WORDS))]
        if not line:
            word = word.capitalize()
        line.append(word)
        line_len += len(word) + 1
        if line_len >= target:
            text = " ".join(line) + "." + eol
            chunks.append(text)
            total += len(text)
            line, line_len = [], 0
            target = 40 + rng.next(61)
    data = "".join(chunks).encode("ascii")[:size]
    # Cut to the exact size, then make sure the last byte is the line ending.
    return data[: size - len(eol)] + eol.encode("ascii")


def png_bytes(width=16, height=16):
    """A valid RGB PNG: a small colour gradient."""

    def chunk(tag, body):
        return (
            struct.pack(">I", len(body))
            + tag
            + body
            + struct.pack(">I", zlib.crc32(tag + body) & 0xFFFFFFFF)
        )

    raw = b"".join(
        b"\x00" + b"".join(bytes((x * 16, y * 16, 128)) for x in range(width))
        for y in range(height)
    )
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


SAMPLE = (
    "PlainText encoding test\n"
    "ASCII: The quick brown fox jumps over the lazy dog.\n"
    "Latin-1: café, naïve, über, señor, Ærøskøbing\n"
    "Euro and quotes: € “smart quotes” ‘single’ – en dash — em dash …\n"
    "Greek: Καλημέρα κόσμε\n"
    "Cyrillic: Привет, мир\n"
    "CJK: 你好世界 こんにちは 안녕하세요\n"
    "Emoji: 😀 👍🏽 👨‍👩‍👧\n"
)

EMOJI = (
    "Single code point: 😀 🎉 🚀 ❤ ✅\n"
    "With variation selector (U+FE0F): ❤️ ☺️ ✔️\n"
    "Skin tones: 👍 👍🏻 👍🏼 👍🏽 👍🏾 👍🏿\n"
    "ZWJ family: 👨‍👩‍👧 👨‍👩‍👧‍👦 👩‍👩‍👦\n"
    "ZWJ with skin tone: 🧑🏽‍💻 👩🏿‍🚀 🤝🏻\n"
    "ZWJ professions: 👩‍⚕️ 👨‍🍳 🧑‍🔬\n"
    "Flags (regional indicator pairs): 🇮🇳 🇺🇸 🇯🇵 🇩🇪 🇧🇷\n"
    "Keycaps: 1️⃣ 2️⃣ #️⃣\n"
    "Rainbow flag: 🏳️‍🌈\n"
    "Emoji between letters: a😀b👨‍👩‍👧c👍🏽d\n"
    "Line of 40 family emoji: " + "👨‍👩‍👧" * 40 + "\n"
    "Surrogate pairs next to a caret stop: x😀x😀x😀x\n"
)

RTL = (
    "Arabic: مرحبا بالعالم. هذا نص تجريبي لمحرر النصوص.\n"
    "Hebrew: שלום עולם. זהו טקסט לבדיקת עורך הטקסט.\n"
    "Arabic with digits: الصفحة 12 من 345\n"
    "Hebrew with digits: עמוד 12 מתוך 345\n"
    "Mixed LTR/RTL: Open the file ملف.txt then save it as קובץ.txt\n"
    "Mixed with punctuation: (مرحبا) [שלום] \"quoted\" - end.\n"
    "Arabic presentation/ligature: لا ﻻ ﷲ\n"
    "Explicit marks: ‏RLM‏ ‎LRM‎\n"
    "Embedding: ‫RLE embedded‬ and ‪LRE embedded‬\n"
    "Override: ‮This text is overridden right-to-left‬\n"
    "Isolates: ⁧RLI isolate⁩ and ⁦LRI isolate⁩\n"
    + "نص عربي طويل " * 30 + "\n"
    + "טקסט עברי ארוך " * 30 + "\n"
)

CJK = (
    "Simplified Chinese: 你好，世界。这是一个纯文本编辑器的测试文件。\n"
    "Traditional Chinese: 你好，世界。這是一個純文字編輯器的測試檔案。\n"
    "Japanese (kana + kanji): こんにちは世界。これはテキストエディタのテストです。\n"
    "Katakana: カタカナ テキスト エディタ\n"
    "Half-width katakana: ｶﾀｶﾅ ﾃｷｽﾄ\n"
    "Korean: 안녕하세요 세계\n"
    "Full-width ASCII: ＡＢＣ１２３！？\n"
    "Ideographic space: 前　後\n"
    "CJK Extension B (surrogate pairs): 𠀀 𠀁 𠮷 𡈽\n"
    "Line with no spaces (wrap test): "
    + "日本語の文章には単語の間にスペースがありません。" * 20
    + "\n"
)

ZERO_WIDTH = (
    "Zero-width space between letters: a​b​c​d\n"
    "Zero-width non-joiner: a‌b, Persian: می‌خواهم\n"
    "Zero-width joiner: a‍b\n"
    "Word joiner: a⁠b\n"
    "BOM as ZWNBSP mid-line: a﻿b\n"
    "Soft hyphen: super­cali­fragi­listic\n"
    "Invisible separators: x⁡y⁢z⁣w⁤v\n"
    "Search trap: pass​word (looks like 'password')\n"
    "Line containing only zero-width chars: ​‌‍⁠\n"
    "​​​\n"
    "Combining marks: é ä ñ (decomposed)\n"
    "Stacked combining marks: Z̶̵̴a̷̸l̡̢ģǫ\n"
    "Non-breaking spaces: a b c d\n"
)

TABS = (
    "\tone leading tab\n"
    "\t\ttwo leading tabs\n"
    "\t\t\t\t\t\t\t\t\t\tten leading tabs\n"
    "trailing tab\t\n"
    "a\tb\tc\td\te\n"
    "col1\tcol2\tcol3\n"
    "longer value\tx\ty\n"
    "x\tlonger value\ty\n"
    "\t\n"
    "Tabs and spaces:\t  \t  \tmixed\n"
    "Tab after emoji: 😀\tx\n"
    "Tab after CJK: 日本\tx\n"
    + "\t" * 200
    + "200 tabs then text\n"
)


def long_words():
    lines = [
        "A" * 10_000,
        "Pneumonoultramicroscopicsilicovolcanoconiosis " * 3,
        "https://example.com/" + "/".join("segment%d" % i for i in range(500)),
        "0123456789" * 2_000,
        "short " + "x" * 50_000 + " short",
        "é" * 5_000,
        "😀" * 5_000,
        "日本語" * 3_000,
        "a" * 100_000,
    ]
    return "\n".join(lines) + "\n"


def mixed_endings():
    parts = []
    endings = ["\r\n", "\n", "\r"]
    for i in range(30):
        eol = endings[i % 3]
        name = {"\r\n": "CRLF", "\n": "LF", "\r": "CR"}[eol]
        parts.append("Line %d ends with %s%s" % (i + 1, name, eol))
    parts.append("Edge case: CR CR LF (counts as CR + CRLF)\r\r\n")
    parts.append("Edge case: LF CR (counts as LF + CR)\n\r")
    parts.append("Last line has no line break")
    return "".join(parts)


def eol_sample(eol):
    lines = ["Line %d of %d" % (i + 1, 20) for i in range(20)]
    lines.insert(10, "")
    lines.insert(11, "")
    return eol.join(lines) + eol


def short_lines(count):
    # Lines of 0 to 3 characters, cycling so the file mixes empty and tiny lines.
    pattern = ["a", "bc", "", "def", "g", "", "hi", "j"]
    return "".join(pattern[i % len(pattern)] + "\n" for i in range(count))


def log_file():
    entries = [
        ".LOG",
        "10:15 AM 3/2/2026",
        "Started the stress test.",
        "",
        "2:47 PM 3/2/2026",
        "Opened the 50 MB file; preview dialog shown.",
        "",
        "9:03 AM 3/3/2026",
        "Checked the .LOG timestamp is appended on open.",
    ]
    return "\r\n".join(entries) + "\r\n"


def build():
    files = {}

    # Sizes
    files["empty.txt"] = b""
    files["normal-1MB.txt"] = prose(1 * MIB, seed=1)
    files["normal-10MB.txt"] = prose(10 * MIB, seed=10)
    files["normal-50MB.txt"] = prose(50 * MIB, seed=50)
    files["single-line-5MB.txt"] = prose(5 * MIB, seed=5, eol=" ")
    files["500000-short-lines.txt"] = short_lines(500_000).encode("ascii")

    # Encodings
    files["encoding-utf8.txt"] = SAMPLE.encode("utf-8")
    files["encoding-utf8-bom.txt"] = b"\xef\xbb\xbf" + SAMPLE.encode("utf-8")
    files["bom-only.txt"] = b"\xef\xbb\xbf"
    files["encoding-utf16le-bom.txt"] = b"\xff\xfe" + SAMPLE.encode("utf-16-le")
    files["encoding-utf16be-bom.txt"] = b"\xfe\xff" + SAMPLE.encode("utf-16-be")
    files["encoding-windows1252.txt"] = (
        "Windows-1252 (ANSI) test\r\n"
        "e acute: é  É\r\n"
        "u umlaut: ü  Ü\r\n"
        "n tilde: ñ  Ñ\r\n"
        "Euro sign (byte 0x80): €\r\n"
        "Words: café, über, mañana, 100 €\r\n"
        "Other 0x80-0x9F: ‚ ƒ „ … † ‡ ˆ ‰ Š ‹ Œ Ž ‘ ’ “ ” • – — ˜ ™ š › œ ž Ÿ\r\n"
    ).encode("cp1252")

    # Line endings
    files["line-endings-crlf.txt"] = eol_sample("\r\n").encode("ascii")
    files["line-endings-lf.txt"] = eol_sample("\n").encode("ascii")
    files["line-endings-cr.txt"] = eol_sample("\r").encode("ascii")
    files["line-endings-mixed.txt"] = mixed_endings().encode("ascii")

    # Content
    files["content-emoji.txt"] = EMOJI.encode("utf-8")
    files["content-rtl-arabic-hebrew.txt"] = RTL.encode("utf-8")
    files["content-chinese-japanese.txt"] = CJK.encode("utf-8")
    files["content-zero-width.txt"] = ZERO_WIDTH.encode("utf-8")
    files["content-tabs.txt"] = TABS.encode("utf-8")
    files["content-long-words.txt"] = long_words().encode("utf-8")

    # .LOG (Notepad appends a timestamp when the first line is exactly ".LOG")
    files["journal.LOG"] = log_file().encode("ascii")

    # Binary disguised as text
    files["binary-png-renamed.txt"] = png_bytes()

    # Filenames
    name_body = "This file tests how the file name is shown in the title, Recent files, and the picker.\n"
    files["file name with spaces.txt"] = name_body.encode("utf-8")
    files["emoji 📝 notes 🚀.txt"] = name_body.encode("utf-8")
    files["日本語のメモ.txt"] = name_body.encode("utf-8")
    files["Ελληνικά ñandú café.txt"] = name_body.encode("utf-8")
    files["заметки на русском.txt"] = name_body.encode("utf-8")
    files["ملاحظات عربية.txt"] = name_body.encode("utf-8")

    return files


def main():
    OUT.mkdir(exist_ok=True)
    files = build()
    for stale in OUT.iterdir():
        if stale.name not in files:
            stale.unlink()
    lines = []
    for name in sorted(files):
        data = files[name]
        (OUT / name).write_bytes(data)
        lines.append("%s  files/%s\n" % (hashlib.sha256(data).hexdigest(), name))
        print("%12d  %s" % (len(data), name))
    (HERE / "checksums.txt").write_text("".join(lines), encoding="utf-8", newline="\n")
    print("Wrote %d files and checksums.txt" % len(files))


if __name__ == "__main__":
    main()
