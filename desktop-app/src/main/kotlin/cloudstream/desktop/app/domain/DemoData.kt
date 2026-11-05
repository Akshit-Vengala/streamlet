package cloudstream.desktop.app.domain

import cloudstream.desktop.contracts.InstalledPlugin
import cloudstream.desktop.contracts.LoadResult
import cloudstream.desktop.contracts.PluginInfo
import cloudstream.desktop.contracts.RepoInfo
import kotlinx.coroutines.delay
import java.util.concurrent.CopyOnWriteArrayList

/** Offline demo/fake implementations used by `--fakes`, screenshots and unit tests. No network, no plugins. */

class DemoProvider(
    override val name: String,
    override val lang: String = "en",
    override val types: Set<String> = setOf("Movie", "TvSeries"),
    private val behaviour: Behaviour = Behaviour.Works,
    override val searchTimeoutMs: Long = 5_000,
    private val delayMs: Long = 50,
    /** Pause before each link (simulates extractors answering one by one). */
    private val linkDelayMs: Long = 0,
) : Provider {
    enum class Behaviour { Works, Throws, Hangs, Empty }

    override val loadTimeoutMs: Long = 5_000
    override val linksTimeoutMs: Long = 5_000
    override val hasMainPage: Boolean get() = behaviour == Behaviour.Works
    override val mainPageSections = listOf(
        MainPageSection("Trending", "trending", false),
        MainPageSection("Recently added", "recent", true),
    )

    private fun cards(prefix: String, n: Int, offset: Int = 0) =
        (1 + offset..n + offset).map {
            // Most demo titles have a score (badge), every third has none - like real providers.
            MediaCard(name, "$prefix $it", "demo://$name/$prefix-$it", null, types.first(), score = if (it % 3 == 0) null else 5.5 + (it * 0.7) % 4)
        }

    private suspend fun gate() {
        when (behaviour) {
            Behaviour.Throws -> throw NotImplementedError("demo provider $name is broken")
            Behaviour.Hangs -> delay(Long.MAX_VALUE)
            else -> delay(delayMs)
        }
    }

    override suspend fun search(query: String, page: Int): List<MediaCard> {
        gate()
        return if (behaviour == Behaviour.Empty) emptyList() else cards(query.replaceFirstChar { it.uppercase() }, 6)
    }

    override suspend fun mainPage(section: MainPageSection, page: Int): HomePage {
        gate()
        return HomePage(listOf(HomeRow(section.name, cards(section.name, 10, (page - 1) * 10), section.horizontal)), hasNext = page < 3)
    }

    override suspend fun load(url: String): MediaDetails {
        gate()
        val title = url.substringAfterLast('/')
        val series = types.contains("TvSeries") && title.length % 2 == 0
        return MediaDetails(
            provider = name, name = title, url = url, posterUrl = null, backdropUrl = null,
            plot = "A demo plot for $title. Lorem ipsum dolor sit amet, consectetur adipiscing elit.",
            year = 2024, tags = listOf("Drama", "Demo"), score = 7.8, type = types.first(), durationMin = 110,
            episodes = if (series) (1..12).map {
                // Weekly episodes; 5 and 6 are not out yet (countdowns), the rest aired.
                val now = System.currentTimeMillis()
                val air = when (it) { 5 -> now + (3 * 3600 + 13 * 60) * 1000L; 6 -> now + ((2 * 24 + 4) * 3600 + 13 * 60) * 1000L; else -> now - (5 - it) * 7 * 86_400_000L }
                EpisodeItem("$url#e$it", if (it >= 5) "Upcoming" else "Episode $it", 1 + (it - 1) / 6, it, null, if (it >= 5) null else "Plot of episode $it", airDate = air)
            } else listOf(EpisodeItem("$url#movie", "Movie", null, null, null, null)),
            recommendations = cards("Similar", 4),
            isMovie = !series,
        )
    }

    override suspend fun loadLinks(data: String, onLink: (SourceLink) -> Unit, onSubtitle: (SubtitleItem) -> Unit): Boolean {
        gate()
        for ((q, host) in listOf(1080 to "alpha", 720 to "beta", 480 to "gamma")) {
            if (linkDelayMs > 0) delay(linkDelayMs)
            onLink(SourceLink(host.replaceFirstChar { it.uppercase() }, "$host server", "https://example.invalid/$host/$q.m3u8",
                "https://example.invalid/", q, mapOf("User-Agent" to "demo"), isM3u8 = true, isDash = false))
        }
        onSubtitle(SubtitleItem("English", "https://example.invalid/en.vtt", emptyMap()))
        return true
    }
}

class DemoCatalog(private val list: List<Provider> = defaultProviders()) : ProviderCatalog {
    override fun providers(): List<Provider> = list

    companion object {
        fun defaultProviders(): List<Provider> = listOf(
            DemoProvider("Demo Movies"),
            DemoProvider("Demo Anime", types = setOf("Anime", "TvSeries"), delayMs = 300),
            DemoProvider("Slow Provider", behaviour = DemoProvider.Behaviour.Hangs, searchTimeoutMs = 1_500),
            DemoProvider("Broken Provider", behaviour = DemoProvider.Behaviour.Throws),
            DemoProvider("Empty Provider", behaviour = DemoProvider.Behaviour.Empty),
        )
    }
}

class DemoBackend : ExtensionsBackend {
    private val repos = CopyOnWriteArrayList<RepoInfo>()
    private val installedList = CopyOnWriteArrayList<InstalledPlugin>()
    var failInstallFor: Set<String> = emptySet()

    private val catalog = listOf(
        PluginInfo("DemoMovies", "Demo Movies", 3, "https://example.invalid/DemoMovies.cs3", "https://example.invalid/repo.json", "en", listOf("Movie", "TvSeries"), null, 1),
        PluginInfo("DemoAnime", "Demo Anime", 1, "https://example.invalid/DemoAnime.cs3", "https://example.invalid/repo.json", "ja", listOf("Anime"), null, 1),
        PluginInfo("DemoSpanish", "Demo Spanish", 2, "https://example.invalid/DemoSpanish.cs3", "https://example.invalid/repo.json", "es", listOf("Movie"), null, 1),
        PluginInfo("BrokenOne", "Broken One", 1, "https://example.invalid/BrokenOne.cs3", "https://example.invalid/repo.json", "en", listOf("Movie"), null, 1),
        PluginInfo("DownPlugin", "Down Plugin", 1, "https://example.invalid/DownPlugin.cs3", "https://example.invalid/repo.json", "en", listOf("Movie"), null, 0),
    )
    override val warnings: List<String> get() = emptyList()

    override suspend fun addRepo(url: String): RepoInfo {
        require(url.isNotBlank()) { "empty url" }
        val info = RepoInfo("https://example.invalid/repo.json", "Demo repository", listOf("https://example.invalid/plugins.json"))
        if (repos.none { it.url == info.url }) repos += info
        return info
    }
    override suspend fun listRepos(): List<RepoInfo> = repos.toList()
    override suspend fun removeRepo(url: String) { repos.removeAll { it.url == url }; installedList.removeAll { it.info.repoUrl == url } }
    override suspend fun listAvailable(repoUrl: String): List<PluginInfo> = catalog.filter { it.repoUrl == repoUrl }
    override suspend fun install(plugin: PluginInfo): InstalledPlugin {
        if (plugin.internalName in failInstallFor) error("hash mismatch for ${plugin.internalName}")
        installedList.removeAll { it.info.internalName == plugin.internalName }
        return InstalledPlugin(plugin, "${plugin.internalName}.cs3", "${plugin.internalName}.jar").also { installedList += it }
    }
    override suspend fun uninstall(internalName: String) { installedList.removeAll { it.info.internalName == internalName } }
    override suspend fun update(internalName: String): InstalledPlugin {
        val latest = catalog.first { it.internalName == internalName }
        return install(latest)
    }
    override fun installed(): List<InstalledPlugin> = installedList.toList()
    override suspend fun checkUpdates(): List<PluginInfo> =
        installedList.mapNotNull { i -> catalog.firstOrNull { it.internalName == i.info.internalName && it.version != i.info.version } }

    override suspend fun loadOne(internalName: String): LoadResult = when (internalName) {
        "BrokenOne" -> LoadResult.Failed("Broken One", "NoClassDefFoundError: androidx.fragment.app.FragmentX",
            listOf("androidx.fragment.app.FragmentX", "android.view.Foo"))
        "DemoSpanish" -> LoadResult.Ok("DemoSpanish", listOf("DemoSpanish"), hasSettingsUi = true)
        else -> LoadResult.Ok(internalName, listOf(catalog.first { it.internalName == internalName }.name))
    }
    override fun unloadOne(internalName: String) = true
    override suspend fun loadAll(): List<LoadResult> = installedList.map { loadOne(it.info.internalName) }

    /** Seeds an already-installed state for screenshots. */
    suspend fun seed() {
        val repo = addRepo("demo")
        for (p in listAvailable(repo.url).take(4)) install(if (p.internalName == "DemoAnime") p.copy(version = 0) else p)
    }
}
