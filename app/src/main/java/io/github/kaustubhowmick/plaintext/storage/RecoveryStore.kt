package io.github.kaustubhowmick.plaintext.storage

import org.json.JSONObject
import java.io.File

/**
 * The single temporary recovery slot (design.md §5.30).
 *
 * Holds at most one text snapshot of the one open, dirty document plus its
 * metadata, in app-private no-backup storage. It is never a history and is
 * deleted as soon as the document is saved, discarded, or replaced.
 */
class RecoveryStore(baseDir: File) {

    class Meta(
        val uri: String?,
        val name: String,
        val encoding: String,
        val lineEnding: String,
        val mixed: Boolean,
        val access: String,
        val origin: String,
        val hadBom: Boolean,
        val selStart: Int,
        val selEnd: Int,
        val diskSize: Long?,
        val diskModified: Long?,
        val time: Long,
    ) {
        fun toJson(): String = JSONObject().apply {
            put("uri", uri ?: JSONObject.NULL)
            put("name", name)
            put("encoding", encoding)
            put("lineEnding", lineEnding)
            put("mixed", mixed)
            put("access", access)
            put("origin", origin)
            put("hadBom", hadBom)
            put("selStart", selStart)
            put("selEnd", selEnd)
            put("diskSize", diskSize ?: JSONObject.NULL)
            put("diskModified", diskModified ?: JSONObject.NULL)
            put("time", time)
        }.toString()

        companion object {
            fun fromJson(s: String): Meta {
                val o = JSONObject(s)
                fun optLong(key: String): Long? = if (o.isNull(key)) null else o.optLong(key)
                return Meta(
                    uri = if (o.isNull("uri")) null else o.optString("uri"),
                    name = o.optString("name"),
                    encoding = o.optString("encoding"),
                    lineEnding = o.optString("lineEnding"),
                    mixed = o.optBoolean("mixed"),
                    access = o.optString("access"),
                    origin = o.optString("origin"),
                    hadBom = o.optBoolean("hadBom", true),
                    selStart = o.optInt("selStart"),
                    selEnd = o.optInt("selEnd"),
                    diskSize = optLong("diskSize"),
                    diskModified = optLong("diskModified"),
                    time = o.optLong("time"),
                )
            }
        }
    }

    private val dir = File(baseDir, "recovery")
    private val textFile = File(dir, "buffer.txt")
    private val metaFile = File(dir, "buffer.json")

    fun exists(): Boolean = textFile.isFile && metaFile.isFile

    /** Atomically replaces the slot: write `*.tmp`, then rename. Runs on the IO thread. */
    fun write(text: String, meta: Meta) {
        dir.mkdirs()
        val textTmp = File(dir, "buffer.txt.tmp")
        val metaTmp = File(dir, "buffer.json.tmp")
        textTmp.writeText(text, Charsets.UTF_8)
        metaTmp.writeText(meta.toJson(), Charsets.UTF_8)
        if (!textTmp.renameTo(textFile) || !metaTmp.renameTo(metaFile)) {
            throw java.io.IOException("Couldn't write the recovery buffer")
        }
    }

    fun readMeta(): Meta? = try {
        if (exists()) Meta.fromJson(metaFile.readText(Charsets.UTF_8)) else null
    } catch (e: Exception) {
        null
    }

    fun readText(): String? = try {
        if (exists()) textFile.readText(Charsets.UTF_8) else null
    } catch (e: Exception) {
        null
    }

    fun delete() {
        dir.listFiles()?.forEach { it.delete() }
    }

    /** Removes a buffer older than [maxAgeMs] (7 days per design) or an unreadable one. */
    fun deleteIfStale(maxAgeMs: Long, now: Long = System.currentTimeMillis()) {
        if (!exists()) {
            delete()
            return
        }
        val meta = readMeta()
        if (meta == null || now - meta.time > maxAgeMs) delete()
    }
}
