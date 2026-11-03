package cloudstream.desktop.app.vm

import cloudstream.desktop.app.AppSettings
import cloudstream.desktop.app.domain.EpisodeItem
import cloudstream.desktop.app.domain.MediaCard
import cloudstream.desktop.app.domain.MediaDetails
import cloudstream.desktop.app.domain.Outcome
import cloudstream.desktop.app.domain.Provider
import cloudstream.desktop.app.domain.SourceLink
import cloudstream.desktop.app.domain.SubtitleItem
import cloudstream.desktop.app.domain.guarded
import cloudstream.desktop.app.pickBest
import cloudstream.desktop.contracts.Bookmark
import cloudstream.desktop.contracts.HistoryEntry
import cloudstream.desktop.contracts.LibraryStore
import cloudstream.desktop.contracts.ResumePoint
import cloudstream.desktop.contracts.WatchStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Collections

fun titleId(provider: String, url: String) = "$provider|$url"
fun episodeId(provider: String, data: String) = "$provider|$data"

sealed interface DetailsLoad {
    data object Loading : DetailsLoad
    data class Loaded(val details: MediaDetails) : DetailsLoad
    data class Failed(val message: String) : DetailsLoad
}

/** State of the "find sources for this episode" step. */
data class LinksState(
    val episode: EpisodeItem? = null,
    val loading: Boolean = false,
    val links: List<SourceLink> = emptyList(),
    val subtitles: List<SubtitleItem> = emptyList(),
    val message: String? = null,
    /** Non-null when auto-pick is on and a link was chosen; the UI should start playback. */
    val autoPicked: SourceLink? = null,
    /** False once playback started: the lookup keeps running (the player offers the late links) but the dialog is hidden. */
    val dialogOpen: Boolean = true,
    /** Links dropped because their source is hidden in Settings. */
    val hiddenCount: Int = 0,
    /** Auto-pick already fired for this episode (never fire twice). */
    val autoDone: Boolean = false,
)

data class DetailsState(
    val card: MediaCard,
    val load: DetailsLoad = DetailsLoad.Loading,
    val season: Int? = null,
    val group: String? = null,
    /** Library status of this title; null = not in the library. */
    val status: WatchStatus? = null,
    val links: LinksState = LinksState(),
    val resumeMs: Map<String, Long> = emptyMap(),
) {
    val bookmarked: Boolean get() = status != null
}

class DetailsViewModel(
    private val scope: CoroutineScope,
    private val provider: () -> Provider?,
    private val library: LibraryStore,
    private val settings: AppSettings,
    val card: MediaCard,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(DetailsState(card, status = storedStatus()))
    private fun storedStatus() = library.bookmarks().firstOrNull { it.id == titleId(card.provider, card.url) }?.status
    val state: StateFlow<DetailsState> = _state.asStateFlow()
    private var linksJob: Job? = null

    fun load(): Job = scope.launch {
        _state.update { it.copy(load = DetailsLoad.Loading) }
        val p = provider()
        if (p == null) {
            _state.update { it.copy(load = DetailsLoad.Failed("Provider \"${card.provider}\" is not loaded (extension disabled or unloaded)")) }
            return@launch
        }
        when (val out = guarded(p.loadTimeoutMs) { p.load(card.url) }) {
            is Outcome.Ok -> {
                val d = out.value
                if (d == null) _state.update { it.copy(load = DetailsLoad.Failed("Provider returned no details for this title")) }
                else _state.update { s ->
                    val seasons = d.episodes.mapNotNull { it.season }.distinct().sorted()
                    s.copy(
                        load = DetailsLoad.Loaded(d),
                        season = seasons.firstOrNull(),
                        group = d.episodes.mapNotNull { it.group }.distinct().firstOrNull(),
                        resumeMs = d.episodes.mapNotNull { e -> library.resume(episodeId(card.provider, e.data))?.let { e.data to it.positionMs } }.toMap(),
                    )
                }
            }
            is Outcome.Failed -> _state.update { it.copy(load = DetailsLoad.Failed(out.message)) }
            is Outcome.TimedOut -> _state.update { it.copy(load = DetailsLoad.Failed("Timed out after ${out.ms / 1000}s")) }
        }
    }

    fun selectSeason(s: Int?) = _state.update { it.copy(season = s) }
    fun selectGroup(g: String?) = _state.update { it.copy(group = g) }

    fun visibleEpisodes(s: DetailsState = _state.value): List<EpisodeItem> {
        val d = (s.load as? DetailsLoad.Loaded)?.details ?: return emptyList()
        return d.episodes.filter { (s.season == null || it.season == null || it.season == s.season) && (s.group == null || it.group == null || it.group == s.group) }
    }

    /** The episode "Play" should start: the last one with a resume point (in list order), else the first visible one. */
    fun playTarget(s: DetailsState = _state.value): EpisodeItem? {
        val eps = visibleEpisodes(s)
        return eps.lastOrNull { (s.resumeMs[it.data] ?: 0L) > 0L } ?: eps.firstOrNull()
    }

    fun toggleBookmark() = setStatus(if (state.value.bookmarked) null else WatchStatus.PlanToWatch)

    /** Puts the title in the library with [status] (kept "added" time), or removes it (null). */
    fun setStatus(status: WatchStatus?) {
        val d = (state.value.load as? DetailsLoad.Loaded)?.details
        setLibraryStatus(library, card, status, now(), d?.name, d?.posterUrl, d?.type)
        _state.update { it.copy(status = status) }
    }

    /** Re-reads the status (it was changed elsewhere, e.g. a card's right-click menu). */
    fun syncStatus() = _state.update { it.copy(status = storedStatus()) }

    /** Runs loadLinks for [episode]; links stream into [DetailsState.links] as the plugin emits them. */
    fun findSources(episode: EpisodeItem): Job {
        linksJob?.cancel()
        rawLinks = emptyList()
        _state.update { it.copy(links = LinksState(episode = episode, loading = true)) }
        val p = provider() ?: run {
            _state.update { it.copy(links = LinksState(episode, message = "Provider is not loaded")) }
            return scope.launch { }
        }
        val links = Collections.synchronizedList(ArrayList<SourceLink>())
        val subs = Collections.synchronizedList(ArrayList<SubtitleItem>())
        var firstPlayableAt = 0L
        fun publish(loading: Boolean, msg: String? = null) {
            val distinct = synchronized(links) { links.distinctBy { it.url } }
            rawLinks = distinct
            settings.noteSources(distinct.map { it.source }.distinct())
            val ordered = settings.order(distinct)
            _state.update { s ->
                if (s.links.episode?.data != episode.data) return@update s // a newer lookup replaced this one
                s.copy(links = s.links.copy(
                    loading = loading, links = ordered, subtitles = synchronized(subs) { subs.distinctBy { it.url } },
                    message = msg, hiddenCount = distinct.size - ordered.size,
                ))
            }
            // Auto-pick: once the best-ranked playable link has been around for a few seconds (better ones may still
            // arrive) or the lookup is over. Aggregators can take minutes to finish, so do not wait for the end.
            if (settings.autoPickSource.value && !state.value.links.autoDone) {
                val best = ordered.firstOrNull { it.playable }
                if (best != null && firstPlayableAt == 0L) firstPlayableAt = now()
                if (best != null && (!loading || now() - firstPlayableAt >= AUTO_PICK_GRACE_MS))
                    _state.update { it.copy(links = it.links.copy(autoPicked = best, autoDone = true)) }
            }
        }
        linksJob = scope.launch {
            // Throttle UI updates: callbacks can arrive in bursts from many extractors.
            val ticker = launch {
                while (true) { kotlinx.coroutines.delay(250); publish(true) }
            }
            val out = try {
                guarded(p.linksTimeoutMs) { p.loadLinks(episode.data, { links += it }, { subs += it }) }
            } finally { ticker.cancel() }
            val msg = when (out) {
                is Outcome.Ok -> if (links.isEmpty()) "No sources found" else null
                is Outcome.Failed -> "Source lookup failed: ${out.message}" + if (links.isNotEmpty()) " (partial results shown)" else ""
                is Outcome.TimedOut -> "Timed out after ${out.ms / 1000}s" + if (links.isNotEmpty()) " (partial results shown)" else ""
            }
            publish(false, msg)
        }
        return linksJob!!
    }

    fun dismissSources() { linksJob?.cancel(); _state.update { it.copy(links = LinksState()) } }

    /** Stops the lookup if it is still the one for [episode] (Next episode may already have started another). */
    fun cancelSources(episode: EpisodeItem) {
        if (state.value.links.episode?.data == episode.data) dismissSources()
    }

    /** Playback started: hide the dialog but keep the lookup running so the player can offer late links. */
    fun closeDialog() = _state.update { it.copy(links = it.links.copy(dialogOpen = false, autoPicked = null)) }

    /** Re-open the source list for the current episode (results so far are kept). */
    fun reopenDialog() = _state.update { it.copy(links = it.links.copy(dialogOpen = true)) }

    /** Re-sort after the priority / hidden settings changed. */
    fun reorder() = _state.update { s ->
        val ordered = settings.order(rawLinks)
        s.copy(links = s.links.copy(links = ordered, hiddenCount = rawLinks.size - ordered.size))
    }

    /** All links of the current lookup, hidden ones included, in arrival order. */
    @Volatile private var rawLinks: List<SourceLink> = emptyList()

    companion object { const val AUTO_PICK_GRACE_MS = 8_000L }

    /** Records history for the title when playback of [episode] starts. */
    fun recordHistory(history: (HistoryEntry) -> Unit, episode: EpisodeItem) {
        val d = (state.value.load as? DetailsLoad.Loaded)?.details
        history(HistoryEntry(titleId(card.provider, card.url), d?.name ?: card.name, card.provider, card.url,
            d?.posterUrl ?: card.posterUrl, episode.episode, episode.season, now()))
    }

    fun markResume(episode: EpisodeItem, positionMs: Long, durationMs: Long) {
        library.setResume(ResumePoint(episodeId(card.provider, episode.data), positionMs, durationMs, now()))
    }
}
