package cloudstream.desktop.app.domain

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TorrentLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.utils.ExtractorLink

/** Adapts upstream [MainAPI]s registered in [APIHolder] (by the plugin loader) to the UI-facing [Provider]. */
class RealCatalog : ProviderCatalog {
    override fun providers(): List<Provider> =
        APIHolder.allProviders.toList().distinctBy { it.name }.sortedBy { it.name.lowercase() }.map { RealProvider(it) }
}

class RealProvider(val api: MainAPI) : Provider {
    override val name: String get() = api.name
    override val lang: String get() = api.lang
    override val types: Set<String> get() = api.supportedTypes.map { it.name }.toSet()
    override val hasMainPage: Boolean get() = api.hasMainPage
    override val mainPageSections: List<MainPageSection>
        get() = api.mainPage.map { MainPageSection(it.name, it.data, it.horizontalImages) }
    // Same guardrails as upstream's APIRepository: provider timeouts default to 2 minutes and are clamped to
    // [5 s, 8 min], so a bad extension cannot hog resources and a provider asking for more time gets it.
    override val searchTimeoutMs: Long get() = Guardrails.timeout(api.searchTimeoutMs)
    override val loadTimeoutMs: Long get() = Guardrails.timeout(api.loadTimeoutMs)
    override val linksTimeoutMs: Long get() = Guardrails.timeout(api.loadLinksTimeoutMs)
    val mainPageTimeoutMs: Long get() = Guardrails.timeout(api.getMainPageTimeoutMs)

    override suspend fun search(query: String, page: Int): List<MediaCard> {
        if (query.isEmpty()) return emptyList()
        // The paginated variant falls back to search(query) in MainAPI, so always call it.
        return api.search(query, page)?.items.orEmpty().map { it.toCard() }
    }

    override suspend fun mainPage(section: MainPageSection, page: Int): HomePage? {
        // Providers that ask for it get their home rows one at a time, spaced by their own delays (upstream behaviour).
        val res = Guardrails.homeRequest(api) {
            api.getMainPage(page, MainPageRequest(section.name, section.data, section.horizontal))
        } ?: return null
        return HomePage(
            res.items.map { row -> HomeRow(row.name, row.list.map { it.toCard() }, row.isHorizontalImages) },
            res.hasNext,
        )
    }

    override suspend fun load(url: String): MediaDetails? {
        if (Guardrails.isInvalidData(url)) error("Invalid data") // upstream: ErrorLoadingException
        val fixed = api.fixUrl(url)
        Guardrails.cachedLoad(api.name, fixed)?.let { return it.toDetails() }
        val r = api.load(fixed) ?: return null
        r.tags = r.tags?.filter { it.isNotBlank() } // upstream removes blank tags as early as possible
        Guardrails.cacheLoad(api.name, fixed, r)
        return r.toDetails()
    }

    override suspend fun loadLinks(data: String, onLink: (SourceLink) -> Unit, onSubtitle: (SubtitleItem) -> Unit): Boolean =
        if (Guardrails.isInvalidData(data)) false else api.loadLinks(
            data, false,
            { s -> onSubtitle(SubtitleItem(s.lang, s.url, s.headers.orEmpty())) },
            { l -> onLink(l.toSource()) },
        )

    private fun SearchResponse.toCard() =
        MediaCard(this@RealProvider.name, this.name, url, posterUrl, type?.name, quality?.name,
            // CloudStream shows it like Score.toStringNull(0.1, 10, 1): nothing below 0.1.
            score = runCatching { score?.toDouble(10) }.getOrNull()?.takeIf { it >= 0.1 })

    private fun ExtractorLink.toSource() = SourceLink(
        source = source, name = name, url = url, referer = referer, quality = quality,
        headers = headers, isM3u8 = isM3u8, isDash = isDash,
    )

    private fun Episode.toItem(group: String? = null) = EpisodeItem(
        data = data, name = name, season = season, episode = episode, posterUrl = posterUrl,
        description = description, group = group, airDate = epochMs(date),
    )

    private fun LoadResponse.toDetails(): MediaDetails {
        val eps: List<EpisodeItem> = when (this) {
            is MovieLoadResponse -> listOf(EpisodeItem(dataUrl, "Movie", null, null, null, null))
            is LiveStreamLoadResponse -> listOf(EpisodeItem(dataUrl, "Live", null, null, null, null))
            is TorrentLoadResponse -> listOfNotNull(magnet ?: torrent).map { EpisodeItem(it, "Torrent (unsupported)", null, null, null, null) }
            is TvSeriesLoadResponse -> episodes.map { it.toItem() }
            is AnimeLoadResponse -> episodes.flatMap { (dub, list) ->
                val g = when (dub) { DubStatus.Dubbed -> "Dubbed"; DubStatus.Subbed -> "Subbed"; else -> null }
                list.map { it.toItem(g) }
            }
            else -> emptyList()
        }
        val isMovie = this is MovieLoadResponse || this is LiveStreamLoadResponse
        return MediaDetails(
            provider = this@RealProvider.name,
            name = name, url = url, posterUrl = posterUrl, backdropUrl = backgroundPosterUrl,
            plot = plot, year = year, tags = tags.orEmpty(), score = score?.toDouble(10),
            type = type.name, durationMin = duration, episodes = eps,
            recommendations = recommendations.orEmpty().map { it.toCard() },
            isMovie = isMovie,
            syncIds = runCatching {
                with(LoadResponse.Companion) {
                    val r = this@toDetails
                    listOfNotNull(
                        r.getAniListId()?.let { "anilist" to it }, r.getMalId()?.let { "mal" to it }, r.getKitsuId()?.let { "kitsu" to it },
                        r.getImdbId()?.let { "imdb" to it }, r.getTMDbId()?.let { "tmdb" to it },
                    ).filter { it.second.isNotBlank() && it.second != "null" }.let(::normalizeSyncIds)
                }
            }.getOrDefault(emptyMap()),
            nextAiring = (this as? com.lagradost.cloudstream3.EpisodeResponse)?.nextAiring?.let { n ->
                epochMs(n.unixTime)?.let { NextAiring(n.episode, n.season, it) }
            },
        )
    }
}
