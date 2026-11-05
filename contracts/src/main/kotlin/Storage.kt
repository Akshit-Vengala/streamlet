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
data class ResumePoint(val id: String, val positionMs: Long, val durationMs: Long, val updatedAt: Long) {
    /** Watched to the end (credits count): see [isWatchedAt]. Manually marked episodes are stored as 1 / 1. */
    val watched: Boolean get() = isWatchedAt(positionMs, durationMs)
    /** Where to start playing: the saved position, or the beginning once watched. */
    val startMs: Long get() = if (watched) 0 else positionMs.coerceAtLeast(0)

    companion object {
        /** A watched point for an episode marked by hand (duration unknown). */
        fun markedWatched(id: String, now: Long) = ResumePoint(id, 1, 1, now)
        /** Cleared: no progress, not watched. */
        fun cleared(id: String, now: Long) = ResumePoint(id, 0, 0, now)
    }
}

/**
 * Watched once only the credits can be left: the last 5%, or the last min(10%, 4 min) when that is longer (a 24 min
 * episode: last 2.4 min; 45 min: last 4 min; a 2 h film: last 6 min).
 */
fun isWatchedAt(positionMs: Long, durationMs: Long): Boolean {
    if (durationMs <= 0 || positionMs <= 0) return false
    val tail = maxOf(durationMs / 20, minOf(durationMs / 10, 240_000L))
    return positionMs >= durationMs - tail
}
data class HistoryEntry(
    val id: String, val name: String, val apiName: String, val url: String,
    val posterUrl: String?, val episode: Int?, val season: Int?, val watchedAt: Long,
)
