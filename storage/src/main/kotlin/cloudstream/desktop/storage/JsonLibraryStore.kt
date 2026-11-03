package cloudstream.desktop.storage

import cloudstream.desktop.contracts.Bookmark
import cloudstream.desktop.contracts.HistoryEntry
import cloudstream.desktop.contracts.LibraryStore
import cloudstream.desktop.contracts.ResumePoint

/**
 * LibraryStore on a JsonFileStore namespace (keys `bookmark/<id>`, `resume/<id>`, `history/<id>`).
 * JSON chosen over SQLite: data is small, no native lib/JDBC to package, same atomic+backup
 * durability as settings, trivial to import from Android backups.
 */
class JsonLibraryStore(private val kv: JsonFileStore) : LibraryStore {
    override fun bookmarks(): List<Bookmark> =
        kv.keys("bookmark/").mapNotNull { kv.get(it, Bookmark::class.java) }.sortedByDescending { it.addedAt }

    override fun setBookmark(b: Bookmark) = kv.set("bookmark/${b.id}", b)
    override fun removeBookmark(id: String) = kv.remove("bookmark/$id")

    override fun resume(id: String): ResumePoint? = kv.get("resume/$id", ResumePoint::class.java)
    override fun setResume(p: ResumePoint) = kv.set("resume/${p.id}", p)

    override fun history(): List<HistoryEntry> =
        kv.keys("history/").mapNotNull { kv.get(it, HistoryEntry::class.java) }.sortedByDescending { it.watchedAt }

    /** One entry per title id; re-watching replaces and bumps it. */
    fun recordHistory(e: HistoryEntry) = kv.set("history/${e.id}", e)
    override fun removeHistory(id: String) = kv.remove("history/$id")
    fun clearHistory() = kv.keys("history/").forEach { kv.remove(it) }
    fun flush() = kv.flush()
}
