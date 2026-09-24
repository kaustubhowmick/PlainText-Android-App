package io.github.kaustubhowmick.plaintext.storage

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/**
 * The persisted default notes folder: a SAF tree URI (design.md §4.2).
 * Methods that query the provider block and run on the IO thread.
 */
class DefaultFolder(private val cr: ContentResolver, private val settings: Settings) {

    val treeUri: Uri? get() = settings.defaultFolderUri?.let(Uri::parse)

    /** The tree's root document, or null when no folder is set. */
    val rootDocument: Uri?
        get() = treeUri?.let { DocumentsContract.buildDocumentUriUsingTree(it, DocumentsContract.getTreeDocumentId(it)) }

    /** Persisted grant with read + write, and the root still exists as a directory. */
    fun validate(): Boolean {
        val tree = treeUri ?: return false
        val granted = cr.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission }
        if (!granted) return false
        return try {
            cr.query(rootDocument!!, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                c.moveToFirst() && c.getString(0) == DocumentsContract.Document.MIME_TYPE_DIR
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    fun displayName(): String? {
        val root = rootDocument ?: return null
        return try {
            cr.query(root, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        } ?: treeUri?.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')
    }

    /** Lower-cased display names of the folder's children → document URI. */
    fun children(): Map<String, Uri> {
        val tree = treeUri ?: return emptyMap()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val result = HashMap<String, Uri>()
        cr.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                result[name.lowercase()] = DocumentsContract.buildDocumentUriUsingTree(tree, id)
            }
        }
        return result
    }

    /** Case-insensitive lookup (FAT/exFAT and most providers ignore case). */
    fun findChild(name: String): Uri? = children()[name.lowercase()]

    fun create(name: String, mime: String): Uri? {
        val root = rootDocument ?: return null
        return DocumentsContract.createDocument(cr, root, mime, name)
    }

    /** Stores a newly picked tree, taking its grant and releasing the old one. */
    fun set(tree: Uri, grantFlags: Int) {
        val flags = grantFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        cr.takePersistableUriPermission(tree, flags)
        val old = treeUri
        settings.defaultFolderUri = tree.toString()
        if (old != null && old != tree) release(old)
    }

    fun clear() {
        treeUri?.let(::release)
        settings.defaultFolderUri = null
    }

    private fun release(uri: Uri) {
        try {
            cr.releasePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            // Already gone.
        }
    }
}
