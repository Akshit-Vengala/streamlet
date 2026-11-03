package cloudstream.desktop.app.sync

import cloudstream.desktop.app.domain.EpisodeItem
import cloudstream.desktop.app.domain.MediaDetails
import cloudstream.desktop.contracts.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-profile tracking (AniList, MyAnimeList, Simkl, Kitsu) and online subtitles (OpenSubtitles, SubDL, SubSource,
 * Addic7ed). Accounts, tokens and title matches live in the profile's settings store under "sync/".
 */
class SyncManager(private val kv: KeyValueStore) {
    private fun ns(id: String) = ServiceStore(kv, "sync/$id")

    val trackers: List<Tracker> = listOf(AniListTracker(ns("anilist")), MalTracker(ns("mal")), SimklTracker(ns("simkl")), KitsuTracker(ns("kitsu")))
    val subtitles: List<SubtitleService> = listOf(
        OpenSubtitlesService(ns("opensubtitles")), SubDlService(ns("subdl")), SubSourceService(ns("subsource")), Addic7edService(ns("addic7ed")),
    )

    /** Bumped on login/logout so the UI re-reads account state. */
    private val _rev = MutableStateFlow(0)
    val revision: StateFlow<Int> = _rev.asStateFlow()
    fun changed() { _rev.value++ }

    var autoUpdate: Boolean
        get() = kv.get("sync/autoUpdate", Boolean::class.javaObjectType) ?: true
        set(v) { kv.set("sync/autoUpdate", v); changed() }

    fun active(d: MediaDetails): List<Tracker> = trackers.filter { it.loggedIn && (!it.animeOnly || d.isAnime) }

    fun query(d: MediaDetails) = MatchQuery(d.name, d.year, d.isAnime, d.isMovie, d.syncIds)

    private fun matchKey(t: Tracker, d: MediaDetails) = "sync/match/${t.id}/${Integer.toHexString("${d.provider}|${d.url}".hashCode())}"

    /** The tracker's media id for this title: a manual/cached match first, then ids from the plugin, then a title search. */
    suspend fun resolve(t: Tracker, d: MediaDetails): String? {
        kv.get(matchKey(t, d), String::class.java)?.let { return it.ifBlank { null } }
        val id = t.find(query(d)) ?: return null
        kv.set(matchKey(t, d), id)
        return id
    }

    /** Overrides the automatic match (null = unlink). */
    fun setMatch(t: Tracker, d: MediaDetails, mediaId: String?) { kv.set(matchKey(t, d), mediaId?.trim() ?: "") }

    /**
     * Called when an episode was watched to the end. Raises progress to that episode (never lowers it) and sets
     * Watching / Completed. Returns one message per tracker (for toasts); errors are reported, not thrown.
     */
    suspend fun episodeFinished(d: MediaDetails, ep: EpisodeItem): List<String> {
        if (!autoUpdate) return emptyList()
        return active(d).mapNotNull { t ->
            runCatching {
                val id = resolve(t, d) ?: return@runCatching "${t.name}: no match for \"${d.name}\" (link it on the title page)"
                val cur = t.entry(id)
                if (d.isMovie) {
                    if (cur?.status != TrackStatus.Completed) { t.update(id, TrackStatus.Completed, 1, null); "${t.name}: marked completed" } else null
                } else {
                    val n = ep.episode ?: return@runCatching null
                    if (cur != null && cur.progress >= n) return@runCatching null
                    val done = cur?.total?.let { n >= it } == true
                    t.update(id, if (done) TrackStatus.Completed else TrackStatus.Watching, n, null, ep.season)
                    "${t.name}: progress $n${cur?.total?.let { "/$it" } ?: ""}${if (done) " (completed)" else ""}"
                }
            }.getOrElse { "${t.name} sync failed: ${it.message}" }
        }
    }

    fun subtitleQuery(d: MediaDetails?, title: String, ep: EpisodeItem?, lang: String) = SubQuery(
        title = d?.name ?: title, lang = SubtitleService.langCode(lang),
        imdbId = d?.syncIds?.get("imdb"), tmdbId = d?.syncIds?.get("tmdb"),
        season = ep?.season, episode = ep?.episode, year = d?.year,
    )
}
