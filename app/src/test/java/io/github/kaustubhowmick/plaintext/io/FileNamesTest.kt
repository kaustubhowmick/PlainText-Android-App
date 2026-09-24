package io.github.kaustubhowmick.plaintext.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNamesTest {

    @Test
    fun sanitize() {
        assertEquals("ab c", FileNames.sanitize("a/b:  c*?"))
        assertEquals("notes", FileNames.sanitize("notes. . "))
        assertEquals("tabhere", FileNames.sanitize("tab\there")) // control characters are removed
    }

    @Test
    fun limitIs255Utf8Bytes() {
        val long = "你".repeat(200) + ".txt"
        val cut = FileNames.limitBytes(long)
        assertTrue(cut.toByteArray().size <= 255)
        assertTrue(cut.endsWith(".txt"))
    }

    @Test
    fun proposedName() {
        assertEquals("Shopping list.txt", FileNames.proposeName("\n  Shopping list  \n- milk"))
        assertEquals("Untitled.txt", FileNames.proposeName("   \n\n"))
        assertEquals("a".repeat(40) + ".txt", FileNames.proposeName("a".repeat(60)))
    }

    @Test
    fun extensionsAndCollisions() {
        assertEquals("notes.txt", FileNames.ensureExtension("notes"))
        assertEquals("notes.md", FileNames.ensureExtension("notes.md"))
        val existing = setOf("notes (2).txt", "notes (3).txt")
        assertEquals("notes (4).txt", FileNames.nextFreeName("notes.txt") { it in existing })
        assertEquals("readme (2)", FileNames.nextFreeName("readme") { false })
    }

    @Test
    fun mimeFromExtension() {
        assertEquals("text/plain", FileNames.mimeFor("a.txt"))
        assertEquals("text/markdown", FileNames.mimeFor("a.md"))
        assertEquals("application/octet-stream", FileNames.mimeFor("a.cfg"))
        assertEquals("text/x-log", FileNames.mimeFor("a.log") { if (it == "log") "text/x-log" else null })
    }
}
