package cloudstream.desktop.app

import cloudstream.desktop.app.sync.AniListTracker
import cloudstream.desktop.app.sync.Http

/** A part of an episode the player offers to skip. */
data class SkipSegment(val kind: Kind, val startMs: Long, val endMs: Long) {
    enum class Kind(val label: String) { Recap("Skip recap"), Intro("Skip intro"), Credits("Skip credits") }
    operator fun contains(ms: Long) = ms in startMs until endMs
}

/**
 * Where the intro / recap / credits are. Two sources, merged (chapters win where both have one):
 *  - the file's own chapters ("Opening", "Intro", "OP", "Ending", "Credits", "ED", "Recap", ...), any video;
 *  - AniSkip (community timestamps) for anime with a MAL id (or an AniList id it can be mapped from).
 */
object SkipTimes {
    private val log = AppLog.get("skip")
    private val intro = Regex("""^(intro|opening|op\d*|opening credits|opening song|title sequence)\b""", RegexOption.IGNORE_CASE)
    private val credits = Regex("""^(credits|ending|ed\d*|end credits|ending song|outro|closing credits)\b""", RegexOption.IGNORE_CASE)
    private val recap = Regex("""^(recap|previously|prologue)\b""", RegexOption.IGNORE_CASE)

    /** Segments from chapters: each named chapter runs to the next chapter (the last one to [durationMs]). */
    fun fromChapters(chapters: List<Pair<Double, String>>, durationMs: Long): List<SkipSegment> {
        val sorted = chapters.sortedBy { it.first }
        return sorted.mapIndexedNotNull { i, (t, title) ->
            val kind = when {
                intro.containsMatchIn(title.trim()) -> SkipSegment.Kind.Intro
                credits.containsMatchIn(title.trim()) -> SkipSegment.Kind.Credits
                recap.containsMatchIn(title.trim()) -> SkipSegment.Kind.Recap
                else -> return@mapIndexedNotNull null
            }
            val start = (t * 1000).toLong()
            val end = sorted.getOrNull(i + 1)?.first?.let { (it * 1000).toLong() } ?: durationMs
            SkipSegment(kind, start, end).takeIf { end - start in 5_000..15 * 60_000L }
        }
    }

    /** AniSkip times for episode [episode] of the anime with these sync ids; empty if none (or offline). */
    suspend fun aniSkip(syncIds: Map<String, String>, episode: Int, durationMs: Long): List<SkipSegment> {
        fun num(k: String) = syncIds[k]?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        val mal = num("mal") ?: num("anilist")?.let { AniListTracker.malIdFor(it) } ?: return emptyList()
        val types = listOf("op", "ed", "recap", "mixed-op", "mixed-ed").joinToString("&") { "types=$it" }
        val url = "https://api.aniskip.com/v2/skip-times/$mal/$episode?$types&episodeLength=${durationMs / 1000}"
        return runCatching {
            val r = Http.get(url)
            if (!r.ok) return emptyList()
            r.obj().getAsJsonArray("results")?.mapNotNull { e ->
                val o = e.asJsonObject
                val iv = o.getAsJsonObject("interval") ?: return@mapNotNull null
                val kind = when (o.get("skipType")?.asString) {
                    "op", "mixed-op" -> SkipSegment.Kind.Intro
                    "ed", "mixed-ed" -> SkipSegment.Kind.Credits
                    "recap" -> SkipSegment.Kind.Recap
                    else -> return@mapNotNull null
                }
                SkipSegment(kind, (iv.get("startTime").asDouble * 1000).toLong(), (iv.get("endTime").asDouble * 1000).toLong())
            }.orEmpty()
        }.onFailure { log.info("AniSkip lookup failed: $it") }.getOrDefault(emptyList())
    }

    /** One segment per kind: chapters first, then AniSkip for the kinds chapters did not cover. */
    fun merge(chapters: List<SkipSegment>, online: List<SkipSegment>): List<SkipSegment> {
        val kinds = chapters.map { it.kind }.toSet()
        return (chapters + online.filter { it.kind !in kinds }).distinctBy { it.kind }.sortedBy { it.startMs }
    }
}
