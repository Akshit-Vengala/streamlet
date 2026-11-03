package cloudstream.desktop.player

import cloudstream.desktop.contracts.PlaybackSource
import cloudstream.desktop.contracts.SubtitleSource

/** Pure mapping of a [PlaybackSource] to mpv settings; testable without the DLL. */
data class MpvConfig(
    val url: String,
    /** Option name to value; list values already in mpv string-list syntax. */
    val options: Map<String, String>,
    val subtitles: List<SubtitleSource>,
) {
    /** Command-line args for an external mpv process. */
    fun toCommandLine(): List<String> =
        options.map { (k, v) -> "--$k=$v" } + subtitles.map { "--sub-file=${it.url}" } + url
}

object MpvOptions {
    /**
     * Used only when a link supplies no User-Agent of its own (plugins pass one in the link headers when a host needs it).
     * Deliberately mpv's own, not a browser's: a browser UA without a browser's TLS/header fingerprint is rejected (HTTP 403,
     * Cloudflare bot block) by some segment CDNs, e.g. one movie extension's segment host, while `libmpv`/`Lavf`/`curl` pass.
     */
    const val DEFAULT_UA = "libmpv"

    /** "English" / "en" / "eng" -> codes mpv's slang matches against track languages (e.g. en,eng,English). */
    fun langCodes(lang: String): List<String> {
        val t = lang.trim()
        val loc = java.util.Locale.getAvailableLocales().firstOrNull { it.getDisplayLanguage(java.util.Locale.ENGLISH).equals(t, true) }
            ?: java.util.Locale.forLanguageTag(t)
        return listOfNotNull(loc.language.takeIf { it.isNotBlank() }, runCatching { loc.isO3Language }.getOrNull()?.takeIf { it.isNotBlank() }, t)
            .filter { it.isNotBlank() && it.none { c -> c == ',' } }.distinct()
    }

    /** mpv string-list syntax: each item as `%<bytes>%<value>` so commas are safe. */
    fun listItem(s: String): String = "%${s.toByteArray(Charsets.UTF_8).size}%$s"

    fun map(src: PlaybackSource): MpvConfig {
        val opts = LinkedHashMap<String, String>()
        var referer = src.referer
        var ua: String? = null
        val other = ArrayList<String>()
        for ((k, v) in src.headers) {
            when {
                k.equals("referer", true) || k.equals("referrer", true) -> if (referer == null) referer = v
                k.equals("user-agent", true) -> ua = v
                else -> other += "$k: $v"
            }
        }
        opts["http-header-fields"] = other.joinToString(",") { listItem(it) }
        opts["referrer"] = referer ?: ""
        opts["user-agent"] = ua ?: DEFAULT_UA
        opts["start"] = if (src.startMs > 0) "%.3f".format(java.util.Locale.ROOT, src.startMs / 1000.0) else "0"
        return MpvConfig(src.url, opts, src.subtitles)
    }
}
