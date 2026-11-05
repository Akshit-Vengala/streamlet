package cloudstream.desktop.app

import cloudstream.desktop.app.domain.DemoProvider
import cloudstream.desktop.app.domain.MediaCard
import cloudstream.desktop.app.domain.SourceLink
import cloudstream.desktop.app.vm.DetailsViewModel
import cloudstream.desktop.contracts.Bookmark
import cloudstream.desktop.contracts.fakes.InMemoryKeyValueStore
import cloudstream.desktop.contracts.fakes.InMemoryLibraryStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceOrderTest {
    private fun l(source: String, q: Int, url: String = "https://$source/$q", playable: Boolean = true) =
        SourceLink(source, "$source $q", if (playable) url else "magnet:$source$q", "", q, emptyMap(), true, false)

    @Test fun `ranked sources come first in rank order, then quality fit`() {
        val links = listOf(l("A", 1080), l("B", 720), l("C", 2160), l("B", 1080), l("D", 480))
        val out = orderSources(links, priority = listOf("b", "D"), hidden = emptySet(), preferred = 1080)
        assertEquals(listOf("B 1080", "B 720", "D 480", "A 1080", "C 2160"), out.map { it.name })
    }

    @Test fun `early start counts playable links at the preferred quality`() {
        val links = listOf(l("A", 1080), l("B", 720), l("C", 0), l("D", 1080, playable = false), l("E", 2160), l("F", 1080))
        assertEquals(3, countPreferred(links, 1080))                                 // A, C (Auto), F
        assertEquals(2, countPreferred(links, 1080, failed = setOf("https://F/1080")))
        assertEquals(2, countPreferred(links, 720))                                  // B, C (Auto)
        assertEquals(0, countPreferred(links, 0))                                    // "Best available": no target
    }

    @Test fun `hiding one server keeps the rest of its source, hiding the source drops all`() {
        fun a(server: String, q: Int) = SourceLink("Mirror", "Mirror [$server]", "https://mirror/$server/$q", "", q, emptyMap(), true, false)
        val links = listOf(a("North", 1080), a("South", 720), a("North", 480), l("Other", 720))
        assertEquals(listOf("Mirror [South]", "Other 720"),
            orderSources(links, emptyList(), hidden = setOf("mirror [north]"), preferred = 1080).map { it.name })
        assertEquals(listOf("Other 720"), orderSources(links, emptyList(), hidden = setOf("Mirror"), preferred = 1080).map { it.name })
    }

    @Test fun `a hidden server stays hidden when its quality or episode details change`() {
        fun a(name: String) = SourceLink("Mirror", name, "https://mirror/${name.hashCode()}", "", 1080, emptyMap(), true, false)
        val links = listOf(a("Mirror [North - 720p]"), a("Mirror [North - S01E02 Direct MP4 - 1080p]"), a("Mirror [North · Relay - 1080p]"), a("Mirror [South - 1080p]"))
        // Hidden while watching another episode at another quality (the full name as it was saved before).
        assertEquals(listOf("Mirror [North · Relay - 1080p]", "Mirror [South - 1080p]"),
            orderSources(links, emptyList(), hidden = setOf("Mirror [North - S01E01 Direct MP4 - 1080p]"), preferred = 1080).map { it.name })
        assertEquals("Mirror [North]", serverKey("Mirror [North - 1080p / Auto]"))
        assertEquals("Plain name", serverKey("Plain name"))
    }

    @Test fun `hidden sources are removed and unplayable links go last`() {
        val links = listOf(l("X", 1080, playable = false), l("A", 720), l("Bad", 1080))
        val out = orderSources(links, emptyList(), hidden = setOf("bad"), preferred = 0)
        assertEquals(listOf("A 720", "X 1080"), out.map { it.name })
    }

    @Test fun `playback speed is kept across restarts and clamped`() {
        val kv = InMemoryKeyValueStore()
        assertEquals(1.0, AppSettings(kv).playbackSpeed.value)
        AppSettings(kv).setPlaybackSpeed(1.35)
        assertEquals(1.35, AppSettings(kv).playbackSpeed.value)
        AppSettings(kv).setPlaybackSpeed(9.0)
        assertEquals(4.0, AppSettings(kv).playbackSpeed.value)
    }

    @Test fun `moveSource ranks unranked names and reorders`() {
        val s = AppSettings(InMemoryKeyValueStore())
        s.moveSource("Server A", 0); s.moveSource("Server B", 0); s.moveSource("Server B", -1)
        assertEquals(listOf("Server B", "Server A"), s.sourcePriority.value)
        s.unrankSource("server b")
        assertEquals(listOf("Server A"), s.sourcePriority.value)
        val kv = InMemoryKeyValueStore()
        AppSettings(kv).apply { moveSource("A", 0); setSourceHidden("Z", true); setAutoFallback(false); noteSources(listOf("A", "Q")) }
        val r = AppSettings(kv)
        assertEquals(listOf("A"), r.sourcePriority.value); assertTrue(r.isHidden("z")); assertFalse(r.autoFallback.value)
        assertEquals(setOf("A", "Q"), r.knownSources.value)
    }

    @Test fun `details links follow the profile priority and record seen sources`() = runTest {
        val settings = AppSettings(InMemoryKeyValueStore())
        val card = MediaCard("Demo Movies", "Film", "demo://film/1", null)
        val vm = DetailsViewModel(backgroundScope, { DemoProvider("Demo Movies") }, InMemoryLibraryStore(), settings, card)
        vm.load().join()
        vm.findSources(vm.visibleEpisodes().first()).join()
        val sources = vm.state.value.links.links.map { it.source }.distinct()
        assertTrue(settings.knownSources.value.containsAll(sources))
        val last = sources.last()
        settings.moveSource(last, -1_000); vm.reorder()
        assertEquals(last, vm.state.value.links.links.first().source)
        settings.setSourceHidden(last, true); vm.reorder()
        assertTrue(vm.state.value.links.links.none { it.source == last })
        assertTrue(vm.state.value.links.hiddenCount > 0)
    }
}

class ProfilesTest {
    private fun profiles(index: InMemoryKeyValueStore = InMemoryKeyValueStore()) = Profiles(index, open = { id ->
        val lib = InMemoryLibraryStore()
        ProfileData(lib, object : HistoryWriter { override fun record(e: cloudstream.desktop.contracts.HistoryEntry) {} }, AppSettings(InMemoryKeyValueStore()))
    }, wipe = {})

    @Test fun `default profile exists and profiles have separate data`() {
        val pm = profiles()
        assertEquals(listOf(Profiles.DEFAULT_ID), pm.list.value.map { it.id })
        assertFalse(pm.askOnStart)
        val kid = pm.create("Kids")
        pm.data().library.setBookmark(Bookmark("x", "X", "P", "u", null, null, 1))
        pm.switchTo(kid.id)
        assertEquals("Kids", pm.current.value.name)
        assertTrue(pm.data().library.bookmarks().isEmpty())
        assertTrue(pm.askOnStart)
    }

    @Test fun `profiles persist, rename and delete`() {
        val index = InMemoryKeyValueStore()
        val a = profiles(index)
        val p = a.create("  Me  ")
        a.rename(p.id, "Akshit"); a.switchTo(p.id)
        val b = profiles(index)
        assertEquals(listOf("Default", "Akshit"), b.list.value.map { it.name })
        assertEquals(p.id, b.current.value.id)
        assertTrue(b.delete(p.id))
        assertEquals(Profiles.DEFAULT_ID, b.current.value.id)
        assertFalse(b.delete(Profiles.DEFAULT_ID)) // never delete the last one
    }

    @Test fun `services read settings and library from the current profile`() {
        val s = AppServices.fakes()
        val c = AppController(s)
        s.settings.setDefaultQuality(480)
        val p = s.profiles.create("Second")
        c.switchProfile(p.id)
        assertEquals(1080, s.settings.defaultQuality.value)
        c.switchProfile(Profiles.DEFAULT_ID)
        assertEquals(480, s.settings.defaultQuality.value)
        s.close()
    }

    @Test fun `the Search filter is per profile and only searches the chosen extensions`() = runTest {
        val s = AppServices.fakes(seedInstalled = true)
        val c = AppController(s)
        val names = c.providers().map { it.name }
        assertTrue(names.size >= 2, "fake catalog has several providers: $names")
        s.settings.setSearchIncluded(names[0], false)
        c.search.search("the")?.join()
        assertFalse(c.search.state.value.results.any { it.provider == names[0] }, "filtered-out provider not searched")
        assertTrue(c.search.state.value.results.any { it.provider == names[1] })
        val p = s.profiles.create("Second")
        c.switchProfile(p.id)
        assertTrue(s.settings.searchIncludes(names[0]), "another profile has its own filter")
        c.switchProfile(Profiles.DEFAULT_ID)
        assertFalse(s.settings.searchIncludes(names[0]))
        s.close()
    }
}
