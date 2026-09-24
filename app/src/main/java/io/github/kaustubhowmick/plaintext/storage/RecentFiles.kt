package io.github.kaustubhowmick.plaintext.storage

import org.json.JSONArray
import org.json.JSONObject

/**
 * Most-recently-used files (design.md §5.6), stored as a JSON array in settings.
 * Pure model: the caller releases URI grants for returned evictions.
 */
class RecentFiles(private val load: () -> String, private val store: (String) -> Unit) {

    data class Entry(val uri: String, val name: String, val time: Long)

    fun list(): List<Entry> = try {
        val arr = JSONArray(load())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val uri = o.optString("uri").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Entry(uri, o.optString("name", uri), o.optLong("time"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** Moves [uri] to the top; returns entries evicted by the cap of [MAX]. */
    fun add(uri: String, name: String, time: Long = System.currentTimeMillis()): List<Entry> {
        val entries = list().filterNot { it.uri == uri }.toMutableList()
        entries.add(0, Entry(uri, name, time))
        val evicted = if (entries.size > MAX) entries.subList(MAX, entries.size).toList() else emptyList()
        save(entries.take(MAX))
        return evicted
    }

    fun remove(uri: String) = save(list().filterNot { it.uri == uri })

    /** Clears the list; returns what was removed. */
    fun clear(): List<Entry> = list().also { save(emptyList()) }

    private fun save(entries: List<Entry>) {
        val arr = JSONArray()
        entries.forEach { arr.put(JSONObject().put("uri", it.uri).put("name", it.name).put("time", it.time)) }
        store(arr.toString())
    }

    companion object {
        const val MAX = 10
    }
}
