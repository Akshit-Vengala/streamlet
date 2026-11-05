package cloudstream.desktop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import cloudstream.desktop.app.domain.MediaCard
import cloudstream.desktop.app.domain.SourceLink
import cloudstream.desktop.app.ui.AppRoot
import cloudstream.desktop.app.ui.CloudTheme
import cloudstream.desktop.app.ui.DownloadDialog
import cloudstream.desktop.app.ui.PlayerControls
import cloudstream.desktop.app.ui.PlayerUi
import cloudstream.desktop.app.vm.DetailsLoad
import cloudstream.desktop.app.vm.Screen
import cloudstream.desktop.app.vm.episodeId
import cloudstream.desktop.contracts.PlayerState
import cloudstream.desktop.contracts.ResumePoint
import kotlinx.coroutines.flow.emptyFlow
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Screenshots of the episode features (`--screenshots`): title page with progress / downloads / countdowns, the
 * episode menu, the download dialog, the Downloads section and the player's skip / next-episode prompts.
 */
object NewScreens {
    private val card = MediaCard("Demo Anime", "Naruto Demo", "demo://Demo Anime/Naruto-1", null, "Anime")

    fun run(dir: File): Int {
        var failed = 0
        val tmp = File(System.getProperty("java.io.tmpdir"), "streamlet-shots").apply { deleteRecursively(); mkdirs() }
        fun shot(name: String, w: Int = 1280, h: Int = 800, setup: (AppController) -> Unit = {}, after: (ImageComposeScene, Long) -> Long = { _, t -> t },
                 content: (@Composable (AppController) -> Unit)? = null) {
            val services = AppServices.fakes(seedInstalled = true)
            val c = AppController(services)
            runCatching {
                services.profiles.setAskOnStart(false)
                seed(c, tmp)
                setup(c)
                val scene = ImageComposeScene(w, h, Density(1f)) { content?.invoke(c) ?: AppRoot(c) }
                var t = 0L
                repeat(50) { scene.render(t); t += 50_000_000L; Thread.sleep(40) }
                t = after(scene, t)
                File(dir, "$name.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                scene.close()
                println("screenshot $name -> ${File(dir, "$name.png").absolutePath}")
            }.onFailure { failed++; println("screenshot $name failed: $it"); it.printStackTrace() }
            services.downloads.showOnly(emptyList())
            services.close()
        }

        fun openTitle(c: AppController) { c.goTo(Screen.Search); c.openDetails(card); c.detailsVm(card); Thread.sleep(700) }

        shot("details-episodes", h = 1560, setup = ::openTitle)
        shot("episode-menu", h = 1560, setup = ::openTitle, after = { scene, t0 ->
            var t = t0
            val at = Offset(700f, 1010f) // the 2nd episode row
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isSecondaryPressed = true))
            repeat(12) { scene.render(t); t += 50_000_000L; Thread.sleep(30) }
            scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons())
            repeat(6) { scene.render(t); t += 50_000_000L; Thread.sleep(30) }
            t
        })
        shot("download-dialog", setup = ::openTitle) { c ->
            AppRoot(c)
            val vm = c.detailsVm(card)
            val d = (vm.state.value.load as? DetailsLoad.Loaded)?.details
            if (d != null) CloudTheme { DownloadDialog(c, vm, d, vm.visibleEpisodes()[2]) {} }
        }
        shot("downloads", setup = { c -> c.goTo(Screen.Downloads) })
        shot("downloads-expanded", setup = { c -> c.goTo(Screen.Downloads) }, after = { scene, t0 ->
            var t = t0
            val at = Offset(600f, 170f) // the show card
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons())
            repeat(12) { scene.render(t); t += 50_000_000L; Thread.sleep(30) }
            t
        })
        shot("player-skip-intro", content = { _ ->
            val ui = PlayerUi().apply {
                title = "Naruto Demo"; episodeLine = "S1 E3 - Episode 3"; hasNext = true
                current = SourceLink("Alpha", "alpha server", "https://example.invalid/a.m3u8", "", 1080, emptyMap(), true, false)
                state = PlayerState(97_000, 1_420_000, true, false, null)
                skip = SkipSegment(SkipSegment.Kind.Intro, 90_000, 180_000)
            }
            CloudTheme { Box(Modifier.fillMaxSize().background(Color(0xFF101418))) { PlayerControls(ui, emptyFlow()) } }
        })
        shot("player-next-episode", content = { _ ->
            val ui = PlayerUi().apply {
                title = "Naruto Demo"; episodeLine = "S1 E3 - Episode 3"; hasNext = true
                current = SourceLink("Alpha", "alpha server", "https://example.invalid/a.m3u8", "", 1080, emptyMap(), true, false)
                state = PlayerState(1_420_000, 1_420_000, false, false, null, ended = true)
                autoNextIn = 4
            }
            CloudTheme { Box(Modifier.fillMaxSize().background(Color(0xFF101418))) { PlayerControls(ui, emptyFlow()) } }
        })
        tmp.deleteRecursively()
        return failed
    }

    /** E1 watched (left in the credits), E2 half way, downloads for E1-E4 and a film. */
    private fun seed(c: AppController, tmp: File) {
        val lib = c.services.library
        val now = System.currentTimeMillis()
        lib.setResume(ResumePoint(episodeId(card.provider, "${card.url}#e1"), 1_390_000, 1_420_000, now))
        lib.setResume(ResumePoint(episodeId(card.provider, "${card.url}#e2"), 610_000, 1_420_000, now))
        fun file(name: String, size: Long) = File(tmp, name).apply { java.io.RandomAccessFile(this, "rw").use { it.setLength(size) } }.absolutePath
        fun item(ep: Int, status: DownloadItem.Status, f: String, seg: Int = 0, err: String? = null) = DownloadItem(
            episodeId(card.provider, "${card.url}#e$ep"), card.provider, card.url, card.name, null,
            "${card.url}#e$ep", "Episode $ep", 1, ep, "S1 E$ep - Episode $ep", null, false, "Alpha", 1080,
            "https://example.invalid/alpha/1080.m3u8", "", emptyMap(), true, f, emptyList(),
            if (status == DownloadItem.Status.Done) listOf(DownloadedSub("English", file("e$ep.English.vtt", 40_000))) else emptyList(),
            status, bytes = if (seg > 0) 318_000_000 else 0, segmentsDone = seg, segmentsTotal = if (seg > 0) 356 else 0, error = err, addedAt = now - ep,
        )
        val movie = MediaCard("Demo Movies", "Interstellar Demo", "demo://Demo Movies/Interstellar", null, "Movie")
        c.services.downloads.showOnly(listOf(
            item(1, DownloadItem.Status.Done, file("e1.ts", 612_000_000)),
            item(2, DownloadItem.Status.Downloading, File(tmp, "e2.ts").path, seg = 167),
            item(3, DownloadItem.Status.Paused, File(tmp, "e3.ts").path, seg = 81),
            item(4, DownloadItem.Status.Failed, File(tmp, "e4.ts").path, err = "Segment 12 of 356 could not be downloaded"),
            DownloadItem(episodeId(movie.provider, "${movie.url}#movie"), movie.provider, movie.url, movie.name, null, "${movie.url}#movie", "Movie",
                null, null, "Movie", null, true, "Beta", 2160, "https://example.invalid/b.mkv", "", emptyMap(), false,
                file("movie.mkv", 2_400_000_000), emptyList(), listOf(DownloadedSub("English", file("movie.English.srt", 90_000))),
                DownloadItem.Status.Done, addedAt = now - 100),
        ))
    }
}
