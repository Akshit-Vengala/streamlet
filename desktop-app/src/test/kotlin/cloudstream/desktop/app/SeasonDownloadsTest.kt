package cloudstream.desktop.app

import cloudstream.desktop.app.domain.EpisodeItem
import cloudstream.desktop.app.domain.HomePage
import cloudstream.desktop.app.domain.MainPageSection
import cloudstream.desktop.app.domain.MediaCard
import cloudstream.desktop.app.domain.MediaDetails
import cloudstream.desktop.app.domain.Provider
import cloudstream.desktop.app.domain.SourceLink
import cloudstream.desktop.app.domain.SubtitleItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeasonDownloadsTest {
    private fun link(source: String, q: Int, name: String = "$source ${q}p", url: String = "http://127.0.0.1:9/$source/$q") =
        SourceLink(source, name, url, "", q, emptyMap(), true, false)

    @Test fun `the same source is matched by name and quality, then quality, then best fit`() {
        val choice = SourceChoice("ServerA", "ServerA 1080p", 1080, setOf("English"))
        assertEquals("ServerA 1080p", choice.match(listOf(link("ServerB", 1080), link("ServerA", 720), link("ServerA", 1080)), 1080)!!.name)
        // Same source, same quality, another link name (episode-specific text).
        assertEquals(1080, choice.match(listOf(link("ServerA", 720), link("ServerA", 1080, name = "ServerA E5 1080p")), 1080)!!.quality)
        // Same source, only other qualities: the best at or below the chosen one.
        assertEquals(720, choice.match(listOf(link("ServerA", 480), link("ServerA", 720), link("ServerA", 2160)), 1080)!!.quality)
        assertNull(choice.match(listOf(link("ServerB", 1080)), 1080), "another source is not a match")
        assertNull(choice.match(listOf(link("ServerA", 1080).copy(isDash = true)), 1080), "a DASH link can't be downloaded")
        assertEquals("servera", choice.match(listOf(link("servera", 1080)), 1080)!!.source, "source names compare without case")
        val subs = listOf(SubtitleItem("Spanish", "s1", emptyMap()), SubtitleItem("english", "s2", emptyMap()), SubtitleItem("English", "s3", emptyMap()))
        assertEquals(listOf("s2"), choice.subtitles(subs).map { it.url }, "one subtitle per language picked")
    }

    @Test fun `eligible episodes are the aired ones not downloaded, at most 12`() {
        val now = 1_000_000L
        val eps = (1..20).map { EpisodeItem("e$it", null, 1, it, null, null, airDate = if (it == 19 || it == 20) now + 1 else now - 1) }
        fun dl(status: DownloadItem.Status) = DownloadItem("x", "P", "u", "T", null, "d", null, 1, 1, "l", null, false, "S", 1080, "u", "", emptyMap(), false, "f", null, null, status, addedAt = 0)
        val state = mapOf("e1" to DownloadItem.Status.Done, "e2" to DownloadItem.Status.Downloading, "e3" to DownloadItem.Status.Failed, "e4" to DownloadItem.Status.Paused)
        val got = SeasonDownloads.eligible(eps, now) { ep -> state[ep.data]?.let { dl(it) } }
        assertEquals(listOf(3) + (5..15).toList(), got.map { it.episode }, "failed counts as not downloaded; done/downloading/paused are skipped; 12 at most")
        assertEquals(listOf(16, 17, 18), SeasonDownloads.eligible(eps.drop(15), now) { null }.map { it.episode }, "upcoming episodes are left out")
    }

    private class FakeProvider(val linksFor: (String) -> List<SourceLink>) : Provider {
        override val name = "P"; override val lang = "en"; override val types = setOf("TvSeries"); override val hasMainPage = false
        override val mainPageSections = emptyList<MainPageSection>()
        override val searchTimeoutMs = 5_000L; override val loadTimeoutMs = 5_000L; override val linksTimeoutMs = 5_000L
        override suspend fun search(query: String, page: Int) = emptyList<MediaCard>()
        override suspend fun mainPage(section: MainPageSection, page: Int): HomePage? = null
        override suspend fun load(url: String): MediaDetails? = null
        override suspend fun loadLinks(data: String, onLink: (SourceLink) -> Unit, onSubtitle: (SubtitleItem) -> Unit): Boolean {
            linksFor(data).forEach(onLink)
            onSubtitle(SubtitleItem("English", "http://127.0.0.1:9/$data.vtt", emptyMap()))
            return true
        }
    }

    @Test fun `a season batch downloads every episode from the chosen source and asks for the one without it`() {
        val tmp = Files.createTempDirectory("season").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val downloads = Downloads(File(tmp, "downloads.json"), scope) { File(tmp, "out") }.apply { maxParallel = 0 } // queue only
            val provider = FakeProvider { data ->
                when (data) {
                    "e3" -> listOf(link("ServerB", 1080, url = "http://127.0.0.1:9/$data/b")) // no ServerA for E3
                    else -> listOf(link("ServerB", 1080, url = "http://127.0.0.1:9/$data/b"), link("ServerA", 1080, url = "http://127.0.0.1:9/$data/a"))
                }
            }
            val toasts = ArrayList<String>()
            val season = SeasonDownloads(scope, downloads, { provider }, { 1080 }, { it }, { toasts += it })
            val card = MediaCard("P", "Show", "https://show", null)
            val eps = (2..4).map { EpisodeItem("e$it", "Ep $it", 1, it, null, null) }
            val details = MediaDetails("P", "Show", "https://show", null, null, null, null, emptyList(), null, "TvSeries", null, eps, emptyList(), false)
            season.start(card, details, eps, SourceChoice("ServerA", "ServerA 1080p", 1080, setOf("English")))

            val end = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < end && (season.looking.value.isNotEmpty() || season.prompts.value.isEmpty())) Thread.sleep(30)
            assertTrue(season.looking.value.isEmpty(), "all lookups finished")
            val items = downloads.items.value.associateBy { it.episodeData }
            assertEquals(setOf("e2", "e4"), items.keys)
            assertTrue(items.values.all { it.url.endsWith("/a") && it.sourceName == "ServerA" }, "the chosen source: ${items.values.map { it.url }}")
            assertTrue(items.values.all { it.pendingSubs.orEmpty().map { s -> s.lang } == listOf("English") }, "the chosen subtitle language")
            val prompt = season.prompts.value.single()
            assertEquals("e3", prompt.episode.data)
            assertEquals("ServerA", prompt.wanted)
            assertEquals(listOf("ServerB"), prompt.links.map { it.source }, "the prompt offers the links that were found")
            season.dismiss(prompt)
            assertTrue(season.prompts.value.isEmpty())
            assertTrue(toasts.isEmpty(), "$toasts")
        } finally { scope.cancel(); tmp.deleteRecursively() }
    }
}
