package cloudstream.desktop.app

import cloudstream.desktop.contracts.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PlayerChoice(val label: String) { Auto("Automatic (libmpv, then mpv.exe, then VLC)"), Embedded("Embedded libmpv only"), External("External mpv / VLC") }

enum class ExtUpdateMode(val label: String) {
    Auto("Install updates automatically"), Notify("Only tell me about updates"), Off("Don't check")
}

data class WindowPrefs(val width: Int, val height: Int, val x: Int?, val y: Int?, val maximized: Boolean)

/** User preferences persisted in a [KeyValueStore] namespace (JSON on disk when backed by `:storage`). */
class AppSettings(
    /** The profile's settings store; also holds the per-profile tracker / subtitle accounts (see sync.SyncManager). */
    val kv: KeyValueStore,
) {
    private fun str(key: String) = kv.get("ui/$key", String::class.java)
    private fun int(key: String) = kv.get("ui/$key", Int::class.javaObjectType)

    private val _defaultQuality = MutableStateFlow(int("defaultQuality") ?: 1080)
    /** Preferred max pixel height used to pre-select a source; 0 = best available. */
    val defaultQuality: StateFlow<Int> = _defaultQuality.asStateFlow()

    private val _subtitleLang = MutableStateFlow(str("subtitleLang") ?: "English")
    val subtitleLang: StateFlow<String> = _subtitleLang.asStateFlow()

    private val _player = MutableStateFlow(str("player")?.let { n -> PlayerChoice.entries.firstOrNull { it.name == n } } ?: PlayerChoice.Auto)
    val playerChoice: StateFlow<PlayerChoice> = _player.asStateFlow()

    private val _disabled = MutableStateFlow(str("disabledProviders")?.split('\n')?.filter { it.isNotBlank() }?.toSet() ?: emptySet())
    val disabledProviders: StateFlow<Set<String>> = _disabled.asStateFlow()

    private val _autoPlayBest = MutableStateFlow((kv.get("ui/autoPick", Boolean::class.javaObjectType)) ?: false)
    /** Skip the source picker and play the best match for [defaultQuality] automatically. */
    val autoPickSource: StateFlow<Boolean> = _autoPlayBest.asStateFlow()

    private fun lines(key: String) = str(key)?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    private val _priority = MutableStateFlow(lines("sourcePriority"))
    /** Source names (extractor / server, e.g. "Server A") in preferred order; unlisted sources come after these. */
    val sourcePriority: StateFlow<List<String>> = _priority.asStateFlow()

    private val _hidden = MutableStateFlow(lines("hiddenSources").toSet())
    /** Source names never offered or auto-played. */
    val hiddenSources: StateFlow<Set<String>> = _hidden.asStateFlow()

    private val _known = MutableStateFlow(lines("knownSources").toSet())
    /** Every source name seen so far, so Settings can offer them for ranking. */
    val knownSources: StateFlow<Set<String>> = _known.asStateFlow()

    private val _fallback = MutableStateFlow(kv.get("ui/autoFallback", Boolean::class.javaObjectType) ?: true)
    /** When a source fails (error, or no video within [fallbackSeconds]), switch to the next one automatically. */
    val autoFallback: StateFlow<Boolean> = _fallback.asStateFlow()

    private val _fallbackSec = MutableStateFlow(int("fallbackSeconds") ?: 20)
    val fallbackSeconds: StateFlow<Int> = _fallbackSec.asStateFlow()

    private val _seekBack = MutableStateFlow(int("seekBackSec") ?: 10)
    private val _seekForward = MutableStateFlow(int("seekForwardSec") ?: 10)
    /** Player rewind / fast-forward step in seconds (buttons and arrow keys). */
    val seekBackSeconds: StateFlow<Int> = _seekBack.asStateFlow()
    val seekForwardSeconds: StateFlow<Int> = _seekForward.asStateFlow()
    fun setSeekBackSeconds(s: Int) { _seekBack.value = s; kv.set("ui/seekBackSec", s) }
    fun setSeekForwardSeconds(s: Int) { _seekForward.value = s; kv.set("ui/seekForwardSec", s) }

    private val _speed = MutableStateFlow(kv.get("ui/playbackSpeed", Double::class.javaObjectType)?.takeIf { it in 0.1..4.0 } ?: 1.0)
    /** Playback speed: kept from the last time the user set it (panel, keys), across episodes and restarts. */
    val playbackSpeed: StateFlow<Double> = _speed.asStateFlow()
    fun setPlaybackSpeed(s: Double) { val v = s.coerceIn(0.1, 4.0); _speed.value = v; kv.set("ui/playbackSpeed", v) }

    private val _extUpdates = MutableStateFlow(str("extUpdates")?.let { n -> ExtUpdateMode.entries.firstOrNull { it.name == n } } ?: ExtUpdateMode.Auto)
    /** Extension updates (machine-wide; read from the default profile's settings). */
    val extUpdates: StateFlow<ExtUpdateMode> = _extUpdates.asStateFlow()
    fun setExtUpdates(m: ExtUpdateMode) { _extUpdates.value = m; kv.set("ui/extUpdates", m.name) }

    private val _appUpdateCheck = MutableStateFlow(kv.get("ui/appUpdateCheck", Boolean::class.javaObjectType) ?: true)
    /** Check the app's GitHub Releases for a newer version (machine-wide; read from the default profile's settings). */
    val appUpdateCheck: StateFlow<Boolean> = _appUpdateCheck.asStateFlow()
    fun setAppUpdateCheck(b: Boolean) { _appUpdateCheck.value = b; kv.set("ui/appUpdateCheck", b) }

    private val _home = MutableStateFlow(str("homeProvider"))
    /** Provider shown on Home (remembered across restarts); null = first provider with a home page. */
    val homeProvider: StateFlow<String?> = _home.asStateFlow()
    fun setHomeProvider(name: String?) { _home.value = name; if (name == null) kv.remove("ui/homeProvider") else kv.set("ui/homeProvider", name) }

    private val _raw = MutableStateFlow(kv.get("ui/rawSources", Boolean::class.javaObjectType) ?: false)
    /** Show every link exactly as the plugin returned it (plugin order, hidden/unsupported included, full URL/headers). */
    val showRawSources: StateFlow<Boolean> = _raw.asStateFlow()
    fun setShowRawSources(b: Boolean) { _raw.value = b; kv.set("ui/rawSources", b) }

    fun setAutoFallback(b: Boolean) { _fallback.value = b; kv.set("ui/autoFallback", b) }
    fun setFallbackSeconds(s: Int) { _fallbackSec.value = s; kv.set("ui/fallbackSeconds", s) }

    fun setSourcePriority(l: List<String>) {
        val clean = l.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        _priority.value = clean; kv.set("ui/sourcePriority", clean.joinToString("\n"))
    }
    /** Moves [name] up (delta < 0) or down in the priority list, adding it at the end first if it is not ranked yet. */
    fun moveSource(name: String, delta: Int) {
        val l = _priority.value.toMutableList()
        var i = l.indexOfFirst { it.equals(name, true) }
        if (i < 0) { l += name; i = l.lastIndex }
        val j = (i + delta).coerceIn(0, l.lastIndex)
        l.add(j, l.removeAt(i))
        setSourcePriority(l)
    }
    fun unrankSource(name: String) = setSourcePriority(_priority.value.filterNot { it.equals(name, true) })
    fun setSourceHidden(name: String, hidden: Boolean) {
        val next = if (hidden) _hidden.value + name else _hidden.value.filterNot { it.equals(name, true) }.toSet()
        _hidden.value = next; kv.set("ui/hiddenSources", next.joinToString("\n"))
    }
    fun isHidden(name: String) = _hidden.value.any { it.equals(name, true) }
    fun noteSources(names: Collection<String>) {
        val add = names.map { it.trim() }.filter { it.isNotEmpty() && _known.value.none { k -> k.equals(it, true) } }
        if (add.isEmpty()) return
        val next = _known.value + add
        _known.value = next; kv.set("ui/knownSources", next.sorted().joinToString("\n"))
    }

    /** [links] in play order for this profile: hidden sources removed, then priority rank, then quality fit. */
    fun order(links: List<cloudstream.desktop.app.domain.SourceLink>) =
        if (_raw.value) links else orderSources(links, _priority.value, _hidden.value, _defaultQuality.value)

    fun setDefaultQuality(q: Int) { _defaultQuality.value = q; kv.set("ui/defaultQuality", q) }
    fun setSubtitleLang(l: String) { _subtitleLang.value = l; kv.set("ui/subtitleLang", l) }
    fun setPlayerChoice(c: PlayerChoice) { _player.value = c; kv.set("ui/player", c.name) }
    fun setAutoPick(b: Boolean) { _autoPlayBest.value = b; kv.set("ui/autoPick", b) }
    fun setProviderEnabled(name: String, enabled: Boolean) {
        val next = if (enabled) _disabled.value - name else _disabled.value + name
        _disabled.value = next
        kv.set("ui/disabledProviders", next.joinToString("\n"))
    }
    fun isEnabled(name: String) = name !in _disabled.value

    private val _searchExcluded = MutableStateFlow(lines("searchExcluded").toSet())
    /** Providers this profile leaves out of Search (the Search screen's filter); Home is not affected. */
    val searchExcluded: StateFlow<Set<String>> = _searchExcluded.asStateFlow()
    fun setSearchExcluded(names: Set<String>) { _searchExcluded.value = names; kv.set("ui/searchExcluded", names.sorted().joinToString("\n")) }
    fun setSearchIncluded(name: String, included: Boolean) =
        setSearchExcluded(if (included) _searchExcluded.value - name else _searchExcluded.value + name)
    fun searchIncludes(name: String) = name !in _searchExcluded.value

    fun window(): WindowPrefs? {
        val w = int("win.w") ?: return null
        val h = int("win.h") ?: return null
        return WindowPrefs(w, h, int("win.x"), int("win.y"), kv.get("ui/win.max", Boolean::class.javaObjectType) ?: false)
    }
    fun saveWindow(p: WindowPrefs) {
        kv.set("ui/win.w", p.width); kv.set("ui/win.h", p.height)
        if (p.x != null) kv.set("ui/win.x", p.x)
        if (p.y != null) kv.set("ui/win.y", p.y)
        kv.set("ui/win.max", p.maximized)
    }

    companion object {
        val qualityChoices = listOf(0 to "Best available", 2160 to "4K", 1080 to "1080p", 720 to "720p", 480 to "480p", 360 to "360p")
    }
}

/** A hidden entry matches a whole source ("Source") or one of its servers by link name ("Source [Server 1]"). */
fun isHiddenSource(l: cloudstream.desktop.app.domain.SourceLink, hidden: Set<String>): Boolean =
    hidden.any { it.equals(l.source, true) || (l.name.isNotBlank() && it.equals(l.name, true)) }

/**
 * Play order: drop [hidden] sources (see [isHiddenSource], case-insensitive), then sort by position in
 * [priority] (unlisted after all listed), then by how well the quality fits [preferred] (best fit first, see
 * [pickBest]), then playable links before unsupported ones. Stable, so the plugin's own order breaks ties.
 */
fun orderSources(
    links: List<cloudstream.desktop.app.domain.SourceLink>, priority: List<String>, hidden: Set<String>, preferred: Int,
): List<cloudstream.desktop.app.domain.SourceLink> {
    val rankOf = priority.withIndex().associate { (i, n) -> n.lowercase() to i }
    fun q(l: cloudstream.desktop.app.domain.SourceLink) =
        l.quality.let { if (it == 400) 480 else if (it == 0) (if (preferred == 0) 100_000 else preferred) else it }
    // Lower is better: at-or-below the preference first (highest of those), then above it (lowest of those).
    fun fit(l: cloudstream.desktop.app.domain.SourceLink): Long {
        val v = q(l)
        return if (preferred == 0 || v <= preferred) -v.toLong() else 1_000_000L + v
    }
    return links.filterNot { l -> isHiddenSource(l, hidden) }
        .sortedWith(compareBy({ !it.playable }, { rankOf[it.source.lowercase()] ?: Int.MAX_VALUE }, { fit(it) }))
}

/** Playable links found at which the loading screen starts playing without waiting for the lookup to end. */
const val EARLY_START_COUNT = 3

/**
 * Playable links (not in [failed]) at exactly the [preferred] quality; an "Auto" (0, adaptive) stream counts as that
 * quality, as in [orderSources]. 0 for "Best available" (preferred = 0): there is no target to match.
 */
fun countPreferred(links: List<cloudstream.desktop.app.domain.SourceLink>, preferred: Int, failed: Set<String> = emptySet()): Int =
    if (preferred <= 0) 0 else links.count { it.playable && it.url !in failed && (it.quality == preferred || it.quality == 0) }

/** Picks the preferred source: the highest quality not above [preferred] (0 = highest), falling back to the lowest above it. */
fun <T> pickBest(items: List<T>, preferred: Int, quality: (T) -> Int): T? {
    if (items.isEmpty()) return null
    // 400 = "unknown" (CloudStream convention) sorts as 480p-ish so known qualities win ties.
    // 0 = "Auto" (adaptive master playlist): counts as exactly the preferred quality, or as the best when no preference.
    fun q(t: T) = quality(t).let { if (it == 400) 480 else if (it == 0) (if (preferred == 0) 100_000 else preferred) else it }
    if (preferred == 0) return items.maxByOrNull { q(it) }
    val atOrBelow = items.filter { q(it) <= preferred }
    return atOrBelow.maxByOrNull { q(it) } ?: items.minByOrNull { q(it) }
}
