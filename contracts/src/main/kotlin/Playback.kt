package cloudstream.desktop.contracts

import kotlinx.coroutines.flow.StateFlow

/** Track F implements this. */
interface Player {
    fun open(source: PlaybackSource)
    fun play()
    fun pause()
    fun seek(ms: Long)
    fun stop()
    fun setSubtitle(track: SubtitleSource?)
    val state: StateFlow<PlayerState>
    /** Native window embedding. */
    fun attachTo(component: java.awt.Component)
}

data class PlaybackSource(
    val url: String,
    val headers: Map<String, String>,
    val referer: String?,
    val isM3u8: Boolean,
    val isDash: Boolean,
    val subtitles: List<SubtitleSource>,
    val startMs: Long = 0,
)

data class SubtitleSource(val url: String, val lang: String, val headers: Map<String, String>)

data class PlayerState(
    val positionMs: Long,
    val durationMs: Long,
    val playing: Boolean,
    val buffering: Boolean,
    val error: String?,
    /** While [buffering]: how full the cache is, 0..100 (null = unknown). */
    val bufferPercent: Int? = null,
    /** The file played to its end (mpv keeps the last frame: keep-open). */
    val ended: Boolean = false,
)
