package cloudstream.desktop.app

import cloudstream.desktop.app.domain.EpisodeItem
import cloudstream.desktop.app.domain.MediaCard
import cloudstream.desktop.app.domain.MediaDetails
import cloudstream.desktop.app.domain.Outcome
import cloudstream.desktop.app.domain.Provider
import cloudstream.desktop.app.domain.SourceLink
import cloudstream.desktop.app.domain.SubtitleItem
import cloudstream.desktop.app.domain.guarded
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicLong

/** A link that can be saved to disk (not DASH, not a file that is already local). */
fun SourceLink.downloadable(): Boolean = playable && !isDash && !isLocalFile

/** The source picked for the first episode of a "download season", applied to the others. */
data class SourceChoice(val source: String, val name: String, val quality: Int, val subtitleLangs: Set<String>) {
    companion object {
        fun of(l: SourceLink, subs: List<SubtitleItem>) = SourceChoice(l.source.ifBlank { l.name }, l.name, l.quality, subs.map { it.lang }.toSet())
    }

    /** The same source among [links]: the same link name and quality, else the same quality, else the best fit. */
    fun match(links: List<SourceLink>, preferredQuality: Int): SourceLink? {
        val same = links.filter { it.downloadable() && it.source.ifBlank { it.name }.equals(source, true) }
        if (same.isEmpty()) return null
        return same.firstOrNull { it.name == name && it.quality == quality }
            ?: same.firstOrNull { it.quality == quality }
            ?: pickBest(same, quality.takeIf { it > 0 && it != 400 } ?: preferredQuality) { it.quality }
    }

    /** True when [l] is exactly the link picked before (no need to wait for more). */
    fun exact(l: SourceLink) = l.downloadable() && l.source.ifBlank { l.name }.equals(source, true) && l.name == name && l.quality == quality

    /** One subtitle per language picked before. */
    fun subtitles(subs: List<SubtitleItem>): List<SubtitleItem> =
        subtitleLangs.mapNotNull { lang -> subs.firstOrNull { it.lang.equals(lang, true) } }
}

/**
 * "Download season": every aired episode not downloaded yet (at most [MAX_EPISODES] per go), all from the source
 * the user picked for the first one. Each episode's links are looked up ([PARALLEL_LOOKUPS] at a time); an episode
 * whose links lack that source becomes a [Prompt] for the user to pick another (or skip).
 */
class SeasonDownloads(
    private val scope: CoroutineScope,
    private val downloads: Downloads,
    private val provider: (String) -> Provider?,
    private val preferredQuality: () -> Int,
    private val order: (List<SourceLink>) -> List<SourceLink>,
    private val toast: (String) -> Unit,
) {
    private val log = AppLog.get("downloads")

    /** An episode the chosen source was not found for: its links, to pick another. */
    data class Prompt(
        val id: Long, val card: MediaCard, val details: MediaDetails, val episode: EpisodeItem,
        val wanted: String, val links: List<SourceLink>, val subtitles: List<SubtitleItem>, val choice: SourceChoice,
    )

    private val ids = AtomicLong()
    private val _prompts = MutableStateFlow<List<Prompt>>(emptyList())
    val prompts: StateFlow<List<Prompt>> = _prompts.asStateFlow()

    /** Episode ids whose links are being looked up for a batch (the row shows it as busy). */
    private val _looking = MutableStateFlow<Set<String>>(emptySet())
    val looking: StateFlow<Set<String>> = _looking.asStateFlow()

    private val lookups = Semaphore(PARALLEL_LOOKUPS)

    /** Downloads [episodes] of [card] from the source in [choice]. The first episode is usually started already. */
    fun start(card: MediaCard, details: MediaDetails, episodes: List<EpisodeItem>, choice: SourceChoice) {
        if (episodes.isEmpty()) return
        val p = provider(card.provider) ?: run { toast("${card.provider} is not loaded"); return }
        val idOf = { ep: EpisodeItem -> cloudstream.desktop.app.vm.episodeId(card.provider, ep.data) }
        _looking.update { it + episodes.map(idOf) }
        for (ep in episodes) scope.launch {
            try {
                lookups.withPermit { one(p, card, details, ep, choice) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                log.warning("Season download of ${details.name} ${ep.label}: $t")
            } finally {
                _looking.update { it - idOf(ep) }
            }
        }
    }

    private suspend fun one(p: Provider, card: MediaCard, details: MediaDetails, ep: EpisodeItem, choice: SourceChoice) {
        val links = ArrayList<SourceLink>()
        val subs = ArrayList<SubtitleItem>()
        val exact = CompletableDeferred<Unit>()
        // Stop looking as soon as the very link picked before shows up; else wait for the lookup to finish.
        val job = scope.launch {
            guarded(p.linksTimeoutMs) {
                p.loadLinks(ep.data, { l ->
                    synchronized(links) { if (links.none { it.url == l.url }) links += l }
                    if (choice.exact(l)) exact.complete(Unit)
                }, { s -> synchronized(subs) { if (subs.none { it.url == s.url }) subs += s } })
            }.let { if (it is Outcome.Failed) log.info("${details.name} ${ep.label}: link lookup failed: ${it.message}") }
            exact.complete(Unit)
        }
        exact.await()
        job.cancel()
        val found = order(synchronized(links) { links.toList() })
        val foundSubs = synchronized(subs) { subs.toList() }
        val pick = choice.match(found, preferredQuality())
        if (pick != null) {
            downloads.start(card, details.name, details.isMovie, ep, pick, choice.subtitles(foundSubs), maxQuality = preferredQuality())
            log.info("Season download: ${details.name} ${ep.label} from ${pick.source.ifBlank { pick.name }} (${pick.qualityLabel})")
        } else {
            log.info("Season download: ${details.name} ${ep.label}: ${choice.source} not found among ${found.size} link(s)")
            _prompts.update { it + Prompt(ids.incrementAndGet(), card, details, ep, choice.source, found, foundSubs, choice) }
        }
    }

    /** The user dealt with [prompt] (picked a source or skipped it). */
    fun dismiss(prompt: Prompt) { _prompts.update { l -> l.filterNot { it.id == prompt.id } } }

    companion object {
        const val MAX_EPISODES = 12
        const val PARALLEL_LOOKUPS = 3

        /**
         * What "download season" takes from [episodes] (the season shown): aired ones not downloaded or downloading
         * yet (a failed download counts as not downloaded), in order, at most [MAX_EPISODES].
         */
        fun eligible(episodes: List<EpisodeItem>, now: Long = System.currentTimeMillis(), downloadOf: (EpisodeItem) -> DownloadItem?): List<EpisodeItem> =
            episodes.filter { !it.upcoming(now) && downloadOf(it).let { d -> d == null || d.status == DownloadItem.Status.Failed } }.take(MAX_EPISODES)
    }
}
