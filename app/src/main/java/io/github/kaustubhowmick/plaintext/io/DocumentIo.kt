package io.github.kaustubhowmick.plaintext.io

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import io.github.kaustubhowmick.plaintext.editor.DiskStamp
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * All ContentResolver I/O (design.md §3.4). Every method blocks and must run on
 * the IO executor, never on the main thread.
 */
class DocumentIo(private val context: Context) {
    private val cr: ContentResolver = context.contentResolver


    class Meta(
        val name: String?,
        val size: Long?,
        val flags: Int?,
        val lastModified: Long?,
        val mime: String?,
        val exists: Boolean,
    ) {
        val stamp: DiskStamp get() = DiskStamp(size, lastModified)
        val supportsWrite: Boolean? get() = flags?.let { it and DocumentsContract.Document.FLAG_SUPPORTS_WRITE != 0 }
    }

    enum class WriteResult { OK, MAYBE_LEFTOVER }

    /** Queries name, size and (for document URIs) flags, last-modified and MIME type. */
    fun queryMeta(uri: Uri): Meta {
        val isDoc = isDocumentUri(uri)
        val projection = if (isDoc) {
            arrayOf(
                OpenableColumns.DISPLAY_NAME,
                OpenableColumns.SIZE,
                DocumentsContract.Document.COLUMN_FLAGS,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
        } else {
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        }
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val f = java.io.File(uri.path ?: "")
            return Meta(f.name, if (f.exists()) f.length() else null, null, if (f.exists()) f.lastModified() else null, null, f.exists())
        }
        val cursor = try {
            cr.query(uri, projection, null, null, null)
        } catch (e: FileNotFoundException) {
            return Meta(null, null, null, null, null, exists = false)
        } catch (e: IllegalArgumentException) {
            // Some providers reject unknown columns; retry with the basics.
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        } ?: return Meta(fallbackName(uri), null, null, null, null, exists = true)
        cursor.use { c ->
            if (!c.moveToFirst()) return Meta(null, null, null, null, null, exists = false)
            fun str(col: String): String? = c.getColumnIndex(col).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getString(it) }
            fun long(col: String): Long? = c.getColumnIndex(col).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) }
            return Meta(
                name = str(OpenableColumns.DISPLAY_NAME) ?: fallbackName(uri),
                size = long(OpenableColumns.SIZE),
                flags = long(DocumentsContract.Document.COLUMN_FLAGS)?.toInt(),
                lastModified = long(DocumentsContract.Document.COLUMN_LAST_MODIFIED)?.takeIf { it > 0 },
                mime = str(DocumentsContract.Document.COLUMN_MIME_TYPE),
                exists = true,
            )
        }
    }

    /** Reads up to [limit] + 1 bytes, so a result longer than [limit] means "too large". */
    fun read(uri: Uri, limit: Int): ByteArray {
        val input = cr.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
        input.use { stream ->
            var buf = ByteArray(minOf(limit + 1, 64 * 1024))
            var len = 0
            while (len < limit + 1) {
                if (len == buf.size) buf = buf.copyOf(minOf(limit + 1, buf.size * 2))
                val r = stream.read(buf, len, buf.size - len)
                if (r < 0) break
                len += r
            }
            return if (len == buf.size) buf else buf.copyOf(len)
        }
    }

    /** Writes [bytes] in place: "wt", then "rw"+truncate, then "w" with a size check (§3.4). */
    fun write(uri: Uri, bytes: ByteArray): WriteResult {
        // 1. Write + truncate.
        val first: Exception = try {
            val out = cr.openOutputStream(uri, "wt") ?: throw FileNotFoundException(uri.toString())
            writeAndClose(out, bytes)
            return WriteResult.OK
        } catch (e: FileNotFoundException) {
            e
        } catch (e: IllegalArgumentException) {
            e
        } catch (e: UnsupportedOperationException) {
            e
        }

        // 2. Read-write descriptor, truncated explicitly.
        try {
            val pfd = cr.openFileDescriptor(uri, "rw") ?: throw FileNotFoundException(uri.toString())
            pfd.use {
                FileOutputStream(it.fileDescriptor).use { fos ->
                    fos.write(bytes)
                    fos.flush()
                    fos.channel.truncate(bytes.size.toLong())
                    try {
                        fos.fd.sync()
                    } catch (e: IOException) {
                        // Pipes and some providers can't sync; the data was written.
                    }
                }
            }
            return WriteResult.OK
        } catch (e: FileNotFoundException) {
            // fall through
        } catch (e: IllegalArgumentException) {
            // fall through
        } catch (e: UnsupportedOperationException) {
            // fall through
        } catch (e: IOException) {
            // e.g. truncate unsupported on a pipe
        }

        // 3. Plain "w", then check the size didn't stay larger than what we wrote.
        val out = try {
            cr.openOutputStream(uri, "w")
        } catch (e: FileNotFoundException) {
            throw if (first is FileNotFoundException) first else e
        } ?: throw first
        writeAndClose(out, bytes)
        val size = try {
            queryMeta(uri).size
        } catch (e: Exception) {
            null
        }
        return if (size != null && size > bytes.size) WriteResult.MAYBE_LEFTOVER else WriteResult.OK
    }

    private fun writeAndClose(out: OutputStream, bytes: ByteArray) {
        out.use {
            it.write(bytes)
            it.flush()
            if (it is FileOutputStream) {
                try {
                    it.fd.sync()
                } catch (e: IOException) {
                    // Not every descriptor supports fsync; the write itself succeeded.
                }
            }
        }
    }

    /** Cheap size + last-modified query; null meta.exists == false means deleted. */
    fun stat(uri: Uri): Meta = queryMeta(uri)

    fun isDocumentUri(uri: Uri): Boolean = try {
        uri.scheme == ContentResolver.SCHEME_CONTENT && DocumentsContract.isDocumentUri(context, uri)
    } catch (e: Exception) {
        false
    }

    private fun fallbackName(uri: Uri): String? = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
}
