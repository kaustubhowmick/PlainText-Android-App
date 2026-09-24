package io.github.kaustubhowmick.plaintext.editor

/**
 * Multi-level undo/redo with Notepad-like coalescing (design.md §5.10).
 *
 * Pure Kotlin: edits are fed in by the editor's TextWatcher and applied back
 * through [Target], so the class is unit-testable without Android.
 *
 * Dirty tracking uses state ids: every undo group has a unique id, the current
 * state is the id of the group on top of the undo stack (or [baseId] when the
 * stack is empty), and [markSaved] remembers the state at save time.
 */
class UndoManager(private val clock: () -> Long = System::currentTimeMillis) {

    fun interface Target {
        /** Replace `[start, end)` of the document with [text]. */
        fun replace(start: Int, end: Int, text: String)
    }

    private class Edit(var start: Int, var removed: String, var inserted: String)

    private enum class Kind { INSERT, DELETE, REPLACE }

    private class Group(val id: Long, val caretBefore: Int) {
        val edits = ArrayList<Edit>(2)
        var caretAfter = caretBefore
        var kind = Kind.REPLACE
        var lastTime = 0L
        var chars = 0L
    }

    private val undoStack = ArrayDeque<Group>()
    private val redoStack = ArrayDeque<Group>()
    private var nextId = 1L
    private var baseId = 0L
    private var savePoint = 0L
    private var forceBreak = false
    private var storedChars = 0L

    /** True while [undo]/[redo] apply edits, so the watcher must not record them. */
    var isApplying = false
        private set

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    val stateId: Long get() = undoStack.lastOrNull()?.id ?: baseId

    val isDirty: Boolean get() = stateId != savePoint

    /** The caret position the last recorded edit left behind, or -1. */
    val expectedCaret: Int get() = undoStack.lastOrNull()?.caretAfter ?: -1

    /** The next edit starts a new group (caret moved, paste, menu command, …). */
    fun breakGroup() {
        forceBreak = true
    }

    /**
     * Records one text change: `[start, start + removed.length)` was replaced by
     * [inserted]. [caretBefore] is the selection start before the change;
     * [composing] is true while the IME has an active composing region.
     */
    fun record(start: Int, removed: String, inserted: String, caretBefore: Int, composing: Boolean) {
        // A no-op replace (e.g. an edit rejected by the view-only input filter) is not an edit.
        if (isApplying || removed == inserted) return
        val now = clock()
        redoStack.forEach { storedChars -= it.chars }
        redoStack.clear()

        val kind = when {
            removed.isEmpty() -> Kind.INSERT
            inserted.isEmpty() -> Kind.DELETE
            else -> Kind.REPLACE
        }
        val top = undoStack.lastOrNull()
        val newline = inserted.indexOf('\n') >= 0
        val merge = top != null && !forceBreak && !newline &&
            now - top.lastTime < MERGE_WINDOW_MS &&
            (composing || (kind == top.kind && kind != Kind.REPLACE && contiguous(top, start, removed, kind)))
        forceBreak = false

        val group = if (merge) top!! else Group(nextId++, caretBefore).also {
            it.kind = kind
            undoStack.addLast(it)
        }
        val last = group.edits.lastOrNull()
        when {
            merge && kind == Kind.INSERT && last != null && !composing &&
                start == last.start + last.inserted.length -> last.inserted += inserted
            merge && kind == Kind.DELETE && last != null && !composing &&
                start + removed.length == last.start && last.inserted.isEmpty() -> {
                last.removed = removed + last.removed
                last.start = start
            }
            merge && kind == Kind.DELETE && last != null && !composing &&
                start == last.start && last.inserted.isEmpty() -> last.removed += removed
            else -> group.edits.add(Edit(start, removed, inserted))
        }
        val added = (removed.length + inserted.length).toLong()
        group.chars += added
        storedChars += added
        group.caretAfter = start + inserted.length
        group.lastTime = now
        trim()
    }

    private fun contiguous(top: Group, start: Int, removed: String, kind: Kind): Boolean {
        val last = top.edits.lastOrNull() ?: return false
        return when (kind) {
            Kind.INSERT -> start == last.start + last.inserted.length
            Kind.DELETE -> last.inserted.isEmpty() &&
                (start + removed.length == last.start || start == last.start)
            Kind.REPLACE -> false
        }
    }

    /** Drops the oldest groups beyond the caps; the newest group is always kept. */
    private fun trim() {
        while (undoStack.size > 1 && (undoStack.size > MAX_GROUPS || storedChars > MAX_CHARS)) {
            val dropped = undoStack.removeFirst()
            storedChars -= dropped.chars
            baseId = dropped.id
        }
    }

    /** Undoes the newest group; returns the caret to restore, or -1 if nothing happened. */
    fun undo(target: Target): Int {
        val group = undoStack.removeLastOrNull() ?: return -1
        isApplying = true
        try {
            for (i in group.edits.indices.reversed()) {
                val e = group.edits[i]
                target.replace(e.start, e.start + e.inserted.length, e.removed)
            }
        } finally {
            isApplying = false
        }
        redoStack.addLast(group)
        forceBreak = true
        return group.caretBefore
    }

    /** Redoes the newest undone group; returns the caret to restore, or -1. */
    fun redo(target: Target): Int {
        val group = redoStack.removeLastOrNull() ?: return -1
        isApplying = true
        try {
            for (e in group.edits) target.replace(e.start, e.start + e.removed.length, e.inserted)
        } finally {
            isApplying = false
        }
        undoStack.addLast(group)
        forceBreak = true
        return group.caretAfter
    }

    /** The current state is now what's on disk. Later typing starts a new group. */
    fun markSaved(state: Long = stateId) {
        savePoint = state
        forceBreak = true
    }

    /** Forget everything; the document is clean (just opened or created). */
    fun reset() {
        clearStacks()
        baseId = nextId++
        savePoint = baseId
    }

    /** Forget history but stay dirty (recovered text, Replace All on a huge file). */
    fun discardHistory() {
        clearStacks()
        baseId = nextId++
    }

    /** Keep the history but treat the document as never saved (e.g. file deleted on disk). */
    fun markUnsaved() {
        savePoint = -1
    }

    private fun clearStacks() {
        undoStack.clear()
        redoStack.clear()
        storedChars = 0
        forceBreak = true
    }

    companion object {
        const val MERGE_WINDOW_MS = 2000L
        const val MAX_GROUPS = 1000
        const val MAX_CHARS = 4_000_000L
    }
}
