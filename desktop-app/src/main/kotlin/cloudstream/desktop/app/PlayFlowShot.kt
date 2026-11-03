package cloudstream.desktop.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import cloudstream.desktop.app.domain.DemoCatalog
import cloudstream.desktop.app.domain.DemoProvider
import cloudstream.desktop.app.domain.MediaCard
import cloudstream.desktop.app.ui.AppRoot
import cloudstream.desktop.app.vm.Screen
import cloudstream.desktop.contracts.PlaybackSource
import cloudstream.desktop.contracts.Player
import cloudstream.desktop.contracts.fakes.FakePlayer
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `--play-flow-shot <out dir>`: the whole app offscreen (fake services) with a provider whose links arrive one every
 * 1.5 s. Episode play -> player loading screen -> links counted live -> best by priority starts at the end of the
 * lookup; second run: "Skip loading" clicked while still searching.
 */
object PlayFlowShot {
    @OptIn(ExperimentalComposeUiApi::class)
    fun run(out: File): Int {
        out.mkdirs()
        val opened = CopyOnWriteArrayList<String>()
        val services = AppServices.fakes(
            catalog = DemoCatalog(listOf(DemoProvider("Demo Anime", types = setOf("Anime", "TvSeries"), linkDelayMs = 1500))),
            players = PlayerSource {
                val fp = FakePlayer()
                PlayerHandle(object : Player by fp { override fun open(source: PlaybackSource) { opened += source.url; fp.open(source) } }, "test", real = false, embeddable = false)
            },
        )
        val c = AppController(services)
        val card = MediaCard("Demo Anime", "Naruto Demo", "demo://Demo Anime/Naruto-1", null, "Anime")
        c.goTo(Screen.Home); c.openDetails(card)
        val vm = c.detailsVm(card)
        val w = 1280; val h = 800
        val scene = ImageComposeScene(w, h, Density(1f)) { AppRoot(c) }
        var t = 0L
        fun frames(ms: Long) { val end = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < end) { scene.render(t); t += 16_000_000L; Thread.sleep(16) } }
        fun shot(name: String) { File(out, "$name.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes) }
        var rc = 0
        fun check(ok: Boolean, what: String) { println((if (ok) "PASS " else "FAIL ") + what); if (!ok) rc = 1 }
        try {
            frames(1200)
            val eps = vm.visibleEpisodes()
            check(eps.size >= 2, "details loaded (${eps.size} episodes)")

            // 1) No skip: the best source starts when the lookup ends.
            c.playEpisode(vm, eps[0])
            frames(2300); shot("1-loading-1-link")
            val ls1 = vm.state.value.links
            check(c.nav.current is Screen.Player, "episode click opens the player at once")
            check(ls1.episode != null && ls1.loading, "the source lookup is still running inside the player (not cancelled)")
            check(ls1.links.size == 1 && opened.isEmpty(), "1 source gathered, nothing played yet (links=${ls1.links.size}, opened=$opened)")
            frames(1500); shot("2-loading-2-links")
            check(vm.state.value.links.links.size == 2 && opened.isEmpty(), "count grows to 2, still waiting")
            frames(2500)
            check(!vm.state.value.links.loading, "lookup finished")
            check(opened.size == 1 && opened[0].contains("alpha/1080"), "best source by priority (1080p alpha) started at the end ($opened)")

            // 2) Skip loading while still searching.
            c.back(); frames(300); opened.clear()
            c.playEpisode(vm, eps[1])
            frames(1900); shot("3-skip-1-link")
            check(opened.isEmpty() && vm.state.value.links.loading, "second episode: loading screen, nothing played")
            val skipX = System.getProperty("skipX", "585").toFloat(); val skipY = System.getProperty("skipY", "453").toFloat()
            scene.sendPointerEvent(PointerEventType.Move, Offset(skipX, skipY))
            scene.sendPointerEvent(PointerEventType.Press, Offset(skipX, skipY)); frames(60)
            scene.sendPointerEvent(PointerEventType.Release, Offset(skipX, skipY)); frames(500)
            check(opened.size == 1 && opened[0].contains("alpha/1080"), "Skip loading plays the best source found so far ($opened)")
            check(vm.state.value.links.loading, "the lookup keeps going after skip (late sources still listed)")
            frames(3500)
            check(opened.size == 1, "end of lookup does not restart playback after skip ($opened)")
            c.back(); frames(300)
            check(vm.state.value.links.episode == null, "leaving the player stops the lookup")
        } catch (e: Throwable) { e.printStackTrace(); rc = 2 }
        finally { scene.close(); services.close() }
        println(if (rc == 0) "RESULT: PLAY FLOW OK" else "RESULT: FAILED")
        System.out.flush(); Runtime.getRuntime().halt(rc); return rc
    }
}
