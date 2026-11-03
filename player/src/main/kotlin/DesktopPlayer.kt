package cloudstream.desktop.player

import cloudstream.desktop.contracts.Player

/** [external]: added from a separate file/url ([source] = that url), not part of the stream itself. */
data class MediaTrack(
    val id: Int, val lang: String?, val title: String?, val selected: Boolean,
    val external: Boolean = false, val forced: Boolean = false, val source: String? = null,
)

/** Player plus the extra controls the UI needs. External fallbacks implement what they can. */
interface DesktopPlayer : Player {
    fun setVolume(percent: Int)
    fun setSpeed(speed: Double)
    fun audioTracks(): List<MediaTrack>
    fun subtitleTracks(): List<MediaTrack>
    fun setAudioTrack(id: Int)
    fun setSubtitleTrack(id: Int?)
    fun toggleFullscreen()
    fun release()
}

object PlayerFactory {
    /** libmpv if loadable, otherwise external mpv.exe, otherwise VLC. Throws if none exist. */
    fun create(): DesktopPlayer {
        val failures = ArrayList<String>()
        try { return LibMpvPlayer() } catch (t: Throwable) { failures += "libmpv: ${t.message}" }
        if (MpvLocator.findExe() != null) return ExternalPlayer(ExternalPlayer.Kind.MPV)
        if (MpvLocator.findVlc() != null) return ExternalPlayer(ExternalPlayer.Kind.VLC)
        error("No player backend available. " + failures.joinToString("; ") +
            ". Put libmpv-2.dll in player/native/ or set CLOUDSTREAM_MPV_DLL.")
    }
}
