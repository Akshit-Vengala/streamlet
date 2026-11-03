package cloudstream.desktop.contracts

/** Track H implements this; also backs android-shim SharedPreferences and upstream getKey/setKey. */
interface KeyValueStore {
    fun <T> get(key: String, type: Class<T>): T?
    fun set(key: String, value: Any?)
    fun remove(key: String)
    fun keys(prefix: String): List<String>
}

/** Track H. */
interface LibraryStore {
    fun bookmarks(): List<Bookmark>
    fun setBookmark(b: Bookmark)
    fun removeBookmark(id: String)
    fun resume(id: String): ResumePoint?
    fun setResume(p: ResumePoint)
    fun history(): List<HistoryEntry>
    /** Removes a title from Continue Watching (its resume points stay). */
    fun removeHistory(id: String)
}

/** CloudStream's WatchType (ids as in its backups: `result_watch_state`); "None" = not in the library. */
enum class WatchStatus(val label: String, val cloudstreamId: Int) {
    Watching("Watching", 0),
    OnHold("On-Hold", 2),
    PlanToWatch("Plan to Watch", 4),
    Completed("Completed", 1),
    Dropped("Dropped", 3);

    companion object { fun fromCloudStream(id: Int): WatchStatus? = entries.firstOrNull { it.cloudstreamId == id } }
}

data class Bookmark(
    val id: String, val name: String, val apiName: String, val url: String,
    val posterUrl: String?, val type: String?, val addedAt: Long,
    /** Older "My List" entries had no status: they read as Plan to Watch. */
    val status: WatchStatus = WatchStatus.PlanToWatch,
)
data class ResumePoint(val id: String, val positionMs: Long, val durationMs: Long, val updatedAt: Long)
data class HistoryEntry(
    val id: String, val name: String, val apiName: String, val url: String,
    val posterUrl: String?, val episode: Int?, val season: Int?, val watchedAt: Long,
)
