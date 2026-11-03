package cloudstream.desktop.app.vm

import cloudstream.desktop.contracts.PlaybackSource
import cloudstream.desktop.contracts.SubtitleSource

/** Maps a UI [PlayRequest] to the player contract. Subtitles matching the preferred language come first. */
fun buildPlaybackSource(req: PlayRequest, preferredSubtitleLang: String): PlaybackSource {
    val l = requireNotNull(req.link) { "no source chosen yet" }
    val pref = preferredSubtitleLang.trim()
    val subs = req.subtitles
        .distinctBy { it.url }
        .sortedByDescending { pref.isNotEmpty() && it.lang.contains(pref, ignoreCase = true) }
        .map { SubtitleSource(it.url, it.lang, it.headers) }
    // The referer travels in `referer`; keep any explicit Referer header out of the generic map to avoid duplicates.
    val headers = l.headers.filterKeys { !it.equals("referer", ignoreCase = true) }
    val referer = l.referer.takeIf { it.isNotBlank() } ?: l.headers.entries.firstOrNull { it.key.equals("referer", true) }?.value
    return PlaybackSource(
        url = l.url, headers = headers, referer = referer,
        isM3u8 = l.isM3u8, isDash = l.isDash, subtitles = subs, startMs = req.startMs,
    )
}

/** The episode after [current] in [list] (same list order), or null. */
fun nextEpisode(list: List<cloudstream.desktop.app.domain.EpisodeItem>, current: cloudstream.desktop.app.domain.EpisodeItem) =
    list.indexOfFirst { it.data == current.data }.takeIf { it >= 0 }?.let { list.getOrNull(it + 1) }
