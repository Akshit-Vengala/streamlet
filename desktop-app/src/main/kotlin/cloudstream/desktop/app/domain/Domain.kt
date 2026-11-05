package cloudstream.desktop.app.domain

import cloudstream.desktop.contracts.InstalledPlugin
import cloudstream.desktop.contracts.LoadResult
import cloudstream.desktop.contracts.PluginInfo
import cloudstream.desktop.contracts.PluginRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/*
 * UI-facing domain model. The Compose layer and the view models only see these types, so they can be tested with
 * fakes and without the upstream `library` (MainAPI & friends) on the classpath. [RealCatalog] adapts MainAPI.
 */

data class MediaCard(
    val provider: String,
    val name: String,
    val url: String,
    val posterUrl: String?,
    val type: String? = null,
    val quality: String? = null,
    /** The provider's score out of 10 (CloudStream's SearchResponse.score), shown as a small badge; null = none. */
    val score: Double? = null,
)

data class EpisodeItem(
    /** The `data` string handed back to loadLinks. */
    val data: String,
    val name: String?,
    val season: Int?,
    val episode: Int?,
    val posterUrl: String?,
    val description: String?,
    /** e.g. "Dubbed" / "Subbed" for anime providers. */
    val group: String? = null,
    /** When it airs / aired (epoch ms), if the plugin knows. */
    val airDate: Long? = null,
) {
    /** Not out yet: airs after [now]. */
    fun upcoming(now: Long = System.currentTimeMillis()) = airDate != null && airDate > now
    val label: String
        get() = buildString {
            if (season != null) append("S$season ")
            if (episode != null) append("E$episode")
            if (!name.isNullOrBlank()) { if (isNotEmpty()) append(" - "); append(name) }
            if (isEmpty()) append("Play")
        }.trim()
}

data class MediaDetails(
    val provider: String,
    val name: String,
    val url: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val plot: String?,
    val year: Int?,
    val tags: List<String>,
    val score: Double?,
    val type: String,
    val durationMin: Int?,
    val episodes: List<EpisodeItem>,
    val recommendations: List<MediaCard>,
    val isMovie: Boolean,
    /** External ids the plugin attached (keys: anilist, mal, kitsu, imdb, tmdb) for tracking and subtitle search. */
    val syncIds: Map<String, String> = emptyMap(),
    /** The next episode's air time, when the plugin knows it (often not in [episodes] yet). */
    val nextAiring: NextAiring? = null,
) {
    val isAnime: Boolean get() = type.startsWith("Anime", true) || type.equals("OVA", true)
}

data class SourceLink(
    val source: String,
    val name: String,
    val url: String,
    val referer: String,
    /** Pixel height (e.g. 1080), 0 = auto, 400 = unknown (CloudStream convention). */
    val quality: Int,
    val headers: Map<String, String>,
    val isM3u8: Boolean,
    val isDash: Boolean,
) {
    val qualityLabel: String
        get() = when (quality) { 0 -> "Auto"; 400 -> "?"; 2160 -> "4K"; else -> "${quality}p" }
    val playable: Boolean get() = url.startsWith("http", ignoreCase = true) || isLocalFile
    /** A downloaded file (absolute path). */
    val isLocalFile: Boolean get() = !url.contains("://") && java.io.File(url).isAbsolute
}

data class SubtitleItem(val lang: String, val url: String, val headers: Map<String, String>)

data class MainPageSection(val name: String, val data: String, val horizontal: Boolean)
data class HomeRow(val name: String, val items: List<MediaCard>, val horizontal: Boolean)
data class HomePage(val rows: List<HomeRow>, val hasNext: Boolean)

/** One content provider (MainAPI). */
interface Provider {
    val name: String
    val lang: String
    val types: Set<String>
    val hasMainPage: Boolean
    val mainPageSections: List<MainPageSection>
    val searchTimeoutMs: Long
    val loadTimeoutMs: Long
    val linksTimeoutMs: Long

    suspend fun search(query: String, page: Int = 1): List<MediaCard>
    suspend fun mainPage(section: MainPageSection, page: Int): HomePage?
    suspend fun load(url: String): MediaDetails?

    /** Streams links/subtitles through the callbacks (any thread). Returns the plugin's success flag. */
    suspend fun loadLinks(data: String, onLink: (SourceLink) -> Unit, onSubtitle: (SubtitleItem) -> Unit): Boolean
}

interface ProviderCatalog {
    /** Currently registered providers (changes as extensions load/unload). */
    fun providers(): List<Provider>
}

/** Extension management beyond [PluginRepository]. */
interface ExtensionsBackend : PluginRepository {
    suspend fun removeRepo(url: String)
    suspend fun checkUpdates(): List<PluginInfo>
    suspend fun loadOne(internalName: String): LoadResult
    fun unloadOne(internalName: String): Boolean
    val warnings: List<String>
    /** The safety check's findings for an installed extension (null = not checked, e.g. demo mode). Blocking: call off the UI thread. */
    fun safety(internalName: String): ExtensionSafety? = null
    /** Allows these capability ids for the extension; load or update it afterwards. */
    fun allowCapabilities(internalName: String, ids: List<String>) {}
    fun revokeCapabilities(internalName: String) {}
}

/** One thing an extension's code can do (see plugin-loader's Capability). */
data class SafetyItem(val id: String, val label: String, val dangerous: Boolean, val allowed: Boolean, val example: String)

data class ExtensionSafety(
    val items: List<SafetyItem>,
    /** A newer version was refused because it gained these; "Allow and update" offers it again. */
    val pendingUpdate: List<SafetyItem> = emptyList(),
) {
    val blocked: List<SafetyItem> get() = items.filter { it.dangerous && !it.allowed }
}

/** Outcome of a guarded call into plugin code. */
sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data class Failed(val error: Throwable) : Outcome<Nothing> {
        val message: String get() = describe(error)
    }
    data class TimedOut(val ms: Long) : Outcome<Nothing>
}

fun describe(t: Throwable): String {
    val root = generateSequence(t) { it.cause?.takeIf { c -> c !== it } }.last()
    val base = "${t::class.simpleName}: ${t.message ?: ""}".trim().removeSuffix(":")
    return if (root !== t) "$base (caused by ${root::class.simpleName}: ${root.message})" else base
}

/**
 * Runs plugin code with a timeout, converting every failure (including Errors such as NotImplementedError,
 * NoClassDefFoundError) into an [Outcome] so that one broken provider never takes down the others.
 */
suspend fun <T> guarded(timeoutMs: Long, block: suspend () -> T): Outcome<T> =
    try {
        Outcome.Ok(withTimeout(timeoutMs) { block() })
    } catch (e: TimeoutCancellationException) {
        Outcome.TimedOut(timeoutMs)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Outcome.Failed(t)
    }

data class InstalledView(val plugin: InstalledPlugin, val load: LoadResult?, val updateAvailable: PluginInfo?)

/** Episode [episode] (of [season]) airs at [atMs] (epoch ms). */
data class NextAiring(val episode: Int, val season: Int?, val atMs: Long)

/** Plugins give epoch ms; a few give seconds. */
fun epochMs(t: Long?): Long? = t?.takeIf { it > 0 }?.let { if (it < 100_000_000_000L) it * 1000 else it }

/**
 * Some plugins store a JSON object as an id (e.g. the MAL slot holding {"AniList":"172192"}): unpack such values into
 * the ids they name, keep plain ids as they are, and drop anything that is not an id.
 */
fun normalizeSyncIds(pairs: List<Pair<String, String>>): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    val keyOf = mapOf("mal" to "mal", "myanimelist" to "mal", "anilist" to "anilist", "kitsu" to "kitsu", "imdb" to "imdb", "tmdb" to "tmdb")
    pairs.forEach { (k, v) ->
        val t = v.trim()
        if (t.startsWith("{")) runCatching {
            com.google.gson.JsonParser.parseString(t).asJsonObject.entrySet().forEach { (jk, jv) ->
                val key = keyOf[jk.lowercase()] ?: return@forEach
                val id = runCatching { jv.asString }.getOrNull()?.trim().orEmpty()
                if (id.isNotEmpty() && id != "null") out.putIfAbsent(key, id)
            }
        } else if (t.isNotEmpty()) out[k] = t
    }
    return out
}
