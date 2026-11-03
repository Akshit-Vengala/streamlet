package cloudstream.desktop.app

import cloudstream.desktop.app.domain.DemoBackend
import cloudstream.desktop.app.domain.DemoProvider
import cloudstream.desktop.app.domain.EpisodeItem
import cloudstream.desktop.app.domain.MediaCard
import cloudstream.desktop.app.domain.SourceLink
import cloudstream.desktop.app.domain.SubtitleItem
import cloudstream.desktop.app.vm.DetailsLoad
import cloudstream.desktop.app.vm.DetailsViewModel
import cloudstream.desktop.app.vm.ExtensionsViewModel
import cloudstream.desktop.app.vm.HomeViewModel
import cloudstream.desktop.app.vm.Navigator
import cloudstream.desktop.app.vm.PlayRequest
import cloudstream.desktop.app.vm.ProviderResult
import cloudstream.desktop.app.vm.Screen
import cloudstream.desktop.app.vm.SearchViewModel
import cloudstream.desktop.app.vm.buildPlaybackSource
import cloudstream.desktop.app.vm.episodeId
import cloudstream.desktop.app.vm.nextEpisode
import cloudstream.desktop.contracts.LoadResult
import cloudstream.desktop.contracts.ResumePoint
import cloudstream.desktop.contracts.fakes.InMemoryKeyValueStore
import cloudstream.desktop.contracts.fakes.InMemoryLibraryStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private fun providers() = listOf(
        DemoProvider("Good"),
        DemoProvider("Hangs", behaviour = DemoProvider.Behaviour.Hangs, searchTimeoutMs = 1_000),
        DemoProvider("Broken", behaviour = DemoProvider.Behaviour.Throws),
        DemoProvider("Empty", behaviour = DemoProvider.Behaviour.Empty),
        DemoProvider("Disabled"),
    )

    @Test fun `each provider is isolated - error and timeout do not block others`() = runTest {
        val vm = SearchViewModel(backgroundScope, { providers() }, { it != "Disabled" })
        vm.setQuery("matrix")
        vm.search()!!.join()
        advanceUntilIdle()
        val byName = vm.state.value.results.associateBy { it.provider }
        assertEquals(setOf("Good", "Hangs", "Broken", "Empty"), byName.keys)
        assertEquals(ProviderResult.Status.Done, byName["Good"]!!.status)
        assertEquals(6, byName["Good"]!!.items.size)
        assertEquals(ProviderResult.Status.Timeout, byName["Hangs"]!!.status)
        assertEquals(ProviderResult.Status.Error, byName["Broken"]!!.status)
        assertTrue(byName["Broken"]!!.message!!.contains("NotImplementedError"))
        assertEquals(ProviderResult.Status.Done, byName["Empty"]!!.status)
        assertFalse(vm.state.value.running)
    }

    @Test fun `blank query does nothing and no providers is reported`() = runTest {
        val vm = SearchViewModel(backgroundScope, { emptyList() }, { true })
        assertNull(vm.search("   "))
        vm.search("x")!!.join()
        assertTrue(vm.state.value.noProviders)
    }

    @Test fun `new search cancels the previous one`() = runTest {
        val vm = SearchViewModel(backgroundScope, { listOf(DemoProvider("Good", delayMs = 500)) }, { true })
        vm.search("first")
        vm.search("second")!!.join()
        assertEquals("second", vm.state.value.submitted)
        assertEquals("Second 1", vm.state.value.results.single().items.first().name)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ExtensionsViewModelTest {
    @Test fun `add repo install load and statuses`() = runTest {
        val backend = DemoBackend()
        var changed = 0
        val vm = ExtensionsViewModel(backgroundScope, backend) { changed++ }
        vm.addRepo("anything").join()
        val repo = vm.state.value.repos.single()
        assertEquals(5, repo.plugins.size)
        assertEquals(listOf("en", "es", "ja"), vm.state.value.languages)
        assertTrue(vm.state.value.types.containsAll(listOf("Anime", "Movie", "TvSeries")))

        vm.install(repo.plugins.first { it.internalName == "DemoMovies" }).join()
        vm.install(repo.plugins.first { it.internalName == "DemoSpanish" }).join()
        vm.install(repo.plugins.first { it.internalName == "BrokenOne" }).join()
        val s = vm.state.value
        assertEquals(3, s.installed.size)
        assertTrue(s.installed.first { it.info.internalName == "DemoMovies" }.load is LoadResult.Ok)
        assertTrue(s.installed.first { it.info.internalName == "DemoSpanish" }.hasSettingsUi)
        val failed = s.installed.first { it.info.internalName == "BrokenOne" }.load as LoadResult.Failed
        assertEquals(listOf("androidx.fragment.app.FragmentX", "android.view.Foo"), failed.missingClasses)
        assertTrue(changed >= 3)
        assertTrue(s.busy.isEmpty())
    }

    @Test fun `install failure surfaces an error and leaves nothing installed`() = runTest {
        val backend = DemoBackend().apply { failInstallFor = setOf("DemoMovies") }
        val vm = ExtensionsViewModel(backgroundScope, backend)
        vm.addRepo("x").join()
        vm.install(vm.state.value.repos.single().plugins.first { it.internalName == "DemoMovies" }).join()
        assertTrue(vm.state.value.error!!.contains("hash mismatch"))
        assertTrue(vm.state.value.installed.isEmpty())
        assertTrue(vm.state.value.busy.isEmpty())
    }

    @Test fun `filters narrow the visible plugins`() = runTest {
        val vm = ExtensionsViewModel(backgroundScope, DemoBackend())
        vm.addRepo("x").join()
        val repo = { vm.state.value.repos.single() }
        assertEquals(5, vm.state.value.visible(repo()).size)
        vm.setLanguageFilter("es"); assertEquals(listOf("DemoSpanish"), vm.state.value.visible(repo()).map { it.internalName })
        vm.setLanguageFilter(null); vm.setTypeFilter("Anime"); assertEquals(listOf("DemoAnime"), vm.state.value.visible(repo()).map { it.internalName })
        vm.setTypeFilter(null); vm.setQuery("down"); assertEquals(listOf("DownPlugin"), vm.state.value.visible(repo()).map { it.internalName })
    }

    @Test fun `update check uninstall and repo removal`() = runTest {
        val backend = DemoBackend().apply { seed() }
        val vm = ExtensionsViewModel(backgroundScope, backend)
        vm.start().join()
        assertEquals(4, vm.state.value.installed.size)
        vm.checkUpdates().join()
        assertEquals("DemoAnime", vm.state.value.installed.single { it.updateTo != null }.info.internalName)
        vm.update("DemoAnime").join()
        vm.uninstall("DemoSpanish").join()
        assertEquals(3, vm.state.value.installed.size)
        vm.removeRepo(vm.state.value.repos.single().repo.url).join()
        assertTrue(vm.state.value.installed.isEmpty() && vm.state.value.repos.isEmpty())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DetailsViewModelTest {
    private val card = MediaCard("Demo Movies", "Show 2", "demo://Demo Movies/Show-2", null, "TvSeries")

    @Test fun `details load sources stream and bookmark`() = runTest {
        val lib = InMemoryLibraryStore()
        val settings = AppSettings(InMemoryKeyValueStore())
        val p = DemoProvider("Demo Movies")
        val vm = DetailsViewModel(backgroundScope, { p }, lib, settings, card)
        vm.load().join()
        val d = (vm.state.value.load as DetailsLoad.Loaded).details
        assertEquals(12, d.episodes.size)
        assertEquals(1, vm.state.value.season)
        assertEquals(6, vm.visibleEpisodes().size)
        vm.selectSeason(2)
        assertEquals(7, vm.visibleEpisodes().first().episode)

        vm.findSources(vm.visibleEpisodes().first()).join()
        val ls = vm.state.value.links
        assertFalse(ls.loading)
        assertEquals(listOf(1080, 720, 480), ls.links.map { it.quality })
        assertEquals(1, ls.subtitles.size)

        vm.toggleBookmark()
        assertEquals(1, lib.bookmarks().size)
        vm.toggleBookmark()
        assertEquals(0, lib.bookmarks().size)
    }

    @Test fun `auto pick chooses the best match for the default quality`() = runTest {
        val settings = AppSettings(InMemoryKeyValueStore()).apply { setAutoPick(true); setDefaultQuality(720) }
        val vm = DetailsViewModel(backgroundScope, { DemoProvider("Demo Movies") }, InMemoryLibraryStore(), settings, card)
        vm.load().join()
        vm.findSources(vm.visibleEpisodes().first()).join()
        assertEquals(720, vm.state.value.links.autoPicked!!.quality)
    }

    @Test fun `missing provider and provider failure are reported not thrown`() = runTest {
        val vm1 = DetailsViewModel(backgroundScope, { null }, InMemoryLibraryStore(), AppSettings(InMemoryKeyValueStore()), card)
        vm1.load().join()
        assertTrue((vm1.state.value.load as DetailsLoad.Failed).message.contains("not loaded"))
        val vm2 = DetailsViewModel(backgroundScope, { DemoProvider("X", behaviour = DemoProvider.Behaviour.Throws) }, InMemoryLibraryStore(), AppSettings(InMemoryKeyValueStore()), card)
        vm2.load().join()
        assertTrue((vm2.state.value.load as DetailsLoad.Failed).message.contains("NotImplementedError"))
    }

    @Test fun `resume points are shown per episode`() = runTest {
        val lib = InMemoryLibraryStore()
        lib.setResume(ResumePoint(episodeId("Demo Movies", "demo://Demo Movies/Show-2#e3"), 90_000, 600_000, 1))
        val vm = DetailsViewModel(backgroundScope, { DemoProvider("Demo Movies") }, lib, AppSettings(InMemoryKeyValueStore()), card)
        vm.load().join()
        assertEquals(90_000L, vm.state.value.resumeMs["demo://Demo Movies/Show-2#e3"])
        vm.markResume(vm.visibleEpisodes().first(), 5_000, 100_000)
        assertNotNull(lib.resume(episodeId("Demo Movies", vm.visibleEpisodes().first().data)))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @Test fun `sections load lazily and paginate`() = runTest {
        val p = DemoProvider("Demo Movies")
        val vm = HomeViewModel(this) { listOf(p) }
        vm.selectProvider("Demo Movies")
        assertEquals(2, vm.state.value.sections.size)
        assertFalse(vm.state.value.sections[0].loadedOnce) // nothing is fetched until a row is shown
        vm.ensureLoaded(0); advanceUntilIdle()
        assertEquals(10, vm.state.value.sections[0].items.size)
        assertTrue(vm.state.value.sections[0].hasNext)
        vm.loadMore(0); advanceUntilIdle()
        assertEquals(20, vm.state.value.sections[0].items.size)
        assertEquals(2, vm.state.value.sections[0].page)
        assertFalse(vm.state.value.sections[1].loadedOnce)
    }

    @Test fun `failing section shows its error`() = runTest {
        val vm = HomeViewModel(this) { listOf(DemoProvider("B", behaviour = DemoProvider.Behaviour.Throws)) }
        vm.selectProvider("B")
        vm.ensureLoaded(0); advanceUntilIdle()
        assertTrue(vm.state.value.sections[0].error!!.contains("NotImplementedError"))
    }
}

class PureLogicTest {
    private fun link(q: Int, url: String = "https://x/$q", headers: Map<String, String> = emptyMap(), referer: String = "") =
        SourceLink("S", "n$q", url, referer, q, headers, isM3u8 = true, isDash = false)

    @Test fun `pickBest honours preferred quality`() {
        val l = listOf(link(480), link(720), link(1080), link(400))
        assertEquals(1080, pickBest(l, 0) { it.quality }!!.quality)
        assertEquals(1080, pickBest(l, 1080) { it.quality }!!.quality)
        assertEquals(720, pickBest(l, 720) { it.quality }!!.quality)
        assertEquals(480, pickBest(l, 600) { it.quality }!!.quality)
        assertEquals(1080, pickBest(listOf(link(1080), link(2160)), 720) { it.quality }!!.quality) // nothing below: lowest above
        assertNull(pickBest(emptyList<SourceLink>(), 720) { it.quality })
    }

    @Test fun `playback source maps headers referer and subtitle preference`() {
        val ep = EpisodeItem("d", null, null, null, null, null)
        val req = PlayRequest(
            MediaCard("P", "T", "u", null), "T", ep,
            link(720, headers = mapOf("User-Agent" to "ua", "Referer" to "https://ref/"), referer = ""),
            listOf(SubtitleItem("French", "https://s/fr.vtt", emptyMap()), SubtitleItem("English", "https://s/en.vtt", mapOf("a" to "b"))),
            startMs = 42_000,
        )
        val src = buildPlaybackSource(req, "english")
        assertEquals("https://ref/", src.referer)
        assertEquals(mapOf("User-Agent" to "ua"), src.headers)
        assertEquals("English", src.subtitles.first().lang)
        assertEquals(42_000, src.startMs)
        assertTrue(src.isM3u8 && !src.isDash)
        assertEquals("https://explicit/", buildPlaybackSource(req.copy(link = link(720, referer = "https://explicit/")), "").referer)
    }

    @Test fun `next episode`() {
        val eps = (1..3).map { EpisodeItem("e$it", null, 1, it, null, null) }
        assertEquals("e2", nextEpisode(eps, eps[0])!!.data)
        assertNull(nextEpisode(eps, eps[2]))
    }

    @Test fun `navigator keeps a back stack`() {
        val n = Navigator()
        val card = MediaCard("P", "T", "u", null)
        n.goTo(Screen.Search); n.push(Screen.Details(card))
        assertEquals(Screen.Search, n.root)
        assertTrue(n.back()); assertEquals(Screen.Search, n.current)
        assertFalse(n.back())
    }

    @Test fun `settings persist through the key value store`() {
        val kv = InMemoryKeyValueStore()
        AppSettings(kv).apply { setDefaultQuality(720); setSubtitleLang("French"); setProviderEnabled("X", false); setPlayerChoice(PlayerChoice.External); saveWindow(WindowPrefs(900, 700, 10, 20, true)) }
        val s = AppSettings(kv)
        assertEquals(720, s.defaultQuality.value)
        assertEquals("French", s.subtitleLang.value)
        assertFalse(s.isEnabled("X"))
        assertEquals(PlayerChoice.External, s.playerChoice.value)
        assertEquals(WindowPrefs(900, 700, 10, 20, true), s.window())
    }

    @Test fun `shadowing modules win on the test classpath`() {
        // Guards the REQUIRED classpath order (:android-shim, :webview-desktop before library-jvm).
        val bad = ClasspathCheck.run().filterNot { it.ok }
        assertTrue(bad.isEmpty(), "classpath order violated: $bad")
    }
}
