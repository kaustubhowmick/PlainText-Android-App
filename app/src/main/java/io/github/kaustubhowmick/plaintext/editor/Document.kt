package io.github.kaustubhowmick.plaintext.editor

import android.net.Uri
import io.github.kaustubhowmick.plaintext.io.Encoding
import io.github.kaustubhowmick.plaintext.io.LineEnding

enum class Origin { NEW, PICKER_OPEN, PICKER_CREATE, DEFAULT_FOLDER, VIEW_INTENT, EDIT_INTENT, SHARE, RECENT, RECOVERED }

enum class AccessMode {
    READ_WRITE,        // normal
    READ_ONLY_FILE,    // provider or grant disallows writing; user may edit, Save routes to Save As
    VIEW_ONLY_BINARY,  // binary file opened read-only; editing disabled, Save/Save As disabled
    VIEW_ONLY_PREVIEW, // first 10 MB of an oversized file; editing disabled, Save/Save As disabled
    VIEW_ONLY_LONG_LINES; // a line too long to edit smoothly, opened read-only by choice; like VIEW_ONLY_BINARY

    val isViewOnly: Boolean get() = this == VIEW_ONLY_BINARY || this == VIEW_ONLY_PREVIEW || this == VIEW_ONLY_LONG_LINES
}

/** Size and last-modified time at last open/save; null = provider did not report it. */
data class DiskStamp(val size: Long?, val lastModified: Long?) {
    /** True when [other] shows a change we can actually detect. */
    fun differsFrom(other: DiskStamp): Boolean =
        (size != null && other.size != null && size != other.size) ||
            (lastModified != null && other.lastModified != null && lastModified != other.lastModified)
}

/** Metadata of the open document (design.md §3.3). The text lives in the EditorView. */
class Document(
    var uri: Uri? = null,
    var displayName: String,
    var encoding: Encoding,
    var lineEnding: LineEnding,
    var mixedLineEndings: Boolean = false,
    var access: AccessMode = AccessMode.READ_WRITE,
    var origin: Origin = Origin.NEW,
    var hadBom: Boolean = true,
    var diskStamp: DiskStamp? = null,
    var metadataDirty: Boolean = false,
    var deletedOnDisk: Boolean = false,
)
