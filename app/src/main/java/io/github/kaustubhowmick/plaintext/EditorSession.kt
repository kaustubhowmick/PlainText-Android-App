package io.github.kaustubhowmick.plaintext

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import io.github.kaustubhowmick.plaintext.editor.DiskStamp
import io.github.kaustubhowmick.plaintext.editor.Document
import io.github.kaustubhowmick.plaintext.editor.LineIndex
import io.github.kaustubhowmick.plaintext.editor.UndoManager
import io.github.kaustubhowmick.plaintext.io.Encoding
import io.github.kaustubhowmick.plaintext.io.LineEnding
import io.github.kaustubhowmick.plaintext.util.Work

/**
 * Everything that must outlive an activity recreation (design.md §3.2, §5.29).
 * Retained through onRetainNonConfigurationInstance().
 */
class EditorSession(var doc: Document) {
    val work = Work()
    val undo = UndoManager()
    val lines = LineIndex()

    /** Increments on every text change; background results carry the revision they used. */
    var revision = 0L

    /** What to do after the unsaved-changes prompt (and a successful save) completes. */
    var pending: PendingAction? = null

    /** True while an open or save is running; commands that touch the file wait. */
    var busy = false

    // View state carried across recreation.
    var retainedText: String? = null
    var selStart = 0
    var selEnd = 0
    var scrollX = 0
    var scrollY = 0

    // Find bar state.
    var findVisible = false
    var replaceVisible = false
    var findTerm = ""
    var replaceTerm = ""
    var lastSearchForward = true

    // Choices made in the Save As options dialog, used when the picker returns.
    var saveAsEncoding: Encoding? = null
    var saveAsLineEnding: LineEnding? = null

    /** Suggested file name for shared text (EXTRA_SUBJECT). */
    var proposedName: String? = null

    /** A disk change the user chose to ignore ("Keep my version"). */
    var ignoredStamp: DiskStamp? = null
    var lastStatCheck = 0L

    /** Whether a recovery buffer has been written for the current dirty state. */
    var recoveryWritten = false

    private var snapshot: String? = null
    private var snapshotRevision = -1L

    /** A String copy of the text, reused until the next edit (design.md §5.12). */
    fun snapshot(text: CharSequence): String {
        val cached = snapshot
        if (cached != null && snapshotRevision == revision) return cached
        return text.toString().also {
            snapshot = it
            snapshotRevision = revision
        }
    }

    fun clearSnapshot() {
        snapshot = null
    }
}

/** An action deferred behind the unsaved-changes prompt (design.md §5.9). */
class PendingAction(
    val kind: Kind,
    val uri: Uri? = null,
    val intent: Intent? = null,
    val extra: String? = null,
) {
    enum class Kind { NEW, OPEN_PICKER, OPEN_URI, HANDLE_INTENT, EXIT, REOPEN_WITH }

    fun toBundle() = Bundle().apply {
        putString("kind", kind.name)
        putParcelable("uri", uri)
        putParcelable("intent", intent)
        putString("extra", extra)
    }

    companion object {
        @Suppress("DEPRECATION")
        fun fromBundle(b: Bundle?): PendingAction? {
            if (b == null) return null
            val kind = Kind.entries.firstOrNull { it.name == b.getString("kind") } ?: return null
            return PendingAction(kind, b.getParcelable("uri"), b.getParcelable("intent"), b.getString("extra"))
        }
    }
}
