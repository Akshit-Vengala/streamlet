package cloudstream.desktop.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import cloudstream.desktop.app.domain.MediaCard
import cloudstream.desktop.app.ui.AppRoot
import cloudstream.desktop.app.vm.Screen
import cloudstream.desktop.app.vm.titleId
import cloudstream.desktop.contracts.HistoryEntry
import cloudstream.desktop.contracts.WatchStatus
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * `--menu-shot <out dir> [cardX cardY itemY]`: the app offscreen (fake data) on My List with a Continue Watching
 * title; right-clicks the card, picks a status from the menu, then removes it from Continue Watching.
 */
object MenuShot {
    @OptIn(ExperimentalComposeUiApi::class)
    fun run(argv: Array<String>): Int {
        val i = argv.indexOf("--menu-shot")
        val out = File(argv[i + 1]).apply { mkdirs() }
        val nums = argv.drop(i + 2).mapNotNull { it.toFloatOrNull() }
        val services = AppServices.fakes(seedInstalled = true)
        val c = AppController(services)
        val card = MediaCard("Demo Anime", "Naruto Demo", "demo://Demo Anime/Naruto-1", null, "Anime")
        services.history.record(HistoryEntry(titleId(card.provider, card.url), card.name, card.provider, card.url, null, 3, 1, 100))
        c.goTo(Screen.Library)
        val scene = ImageComposeScene(1280, 800, Density(1f)) { AppRoot(c) }
        var t = 0L
        fun frames(ms: Long) { val end = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < end) { scene.render(t); t += 16_000_000L; Thread.sleep(16) } }
        fun shot(name: String) { File(out, "$name.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes) }
        fun press(x: Float, y: Float, right: Boolean) {
            val b = if (right) PointerButtons(isSecondaryPressed = true) else PointerButtons(isPrimaryPressed = true)
            scene.sendPointerEvent(PointerEventType.Move, Offset(x, y))
            scene.sendPointerEvent(PointerEventType.Press, Offset(x, y), buttons = b); frames(50)
            scene.sendPointerEvent(PointerEventType.Release, Offset(x, y), buttons = PointerButtons()); frames(400)
        }
        var rc = 0
        fun check(ok: Boolean, what: String) { println((if (ok) "PASS " else "FAIL ") + what); if (!ok) rc = 1 }
        try {
            frames(1000); shot("0-library")
            val cx = nums.getOrElse(0) { 200f }; val cy = nums.getOrElse(1) { 200f }
            press(cx, cy, right = true); shot("1-menu-open")
            check(c.nav.current is Screen.Library, "right-click does not open the title")
            // Menu rows: "Remove from Continue Watching", Watching, On-Hold, Plan to Watch, ... (item 3 = Plan to Watch)
            val itemY = nums.getOrElse(2) { cy + 178f }
            press(cx + 60f, itemY, right = false); shot("2-after-pick")
            val st = c.library.state.value.statusOf(card)
            println("  status now: $st")
            check(st == WatchStatus.PlanToWatch, "picking \"Plan to Watch\" puts it in the library ($st)")
            check(c.library.state.value.bookmarks.size == 1, "listed under My List (${c.library.state.value.bookmarks.size})")
            press(cx, cy, right = true); shot("3-menu-again")
            val firstY = nums.getOrElse(3) { cy + 33f }
            press(cx + 60f, firstY, right = false); shot("4-after-remove-cw")
            check(c.library.state.value.history.isEmpty(), "\"Remove from Continue Watching\" empties the row (${c.library.state.value.history.size})")
            check(c.library.state.value.statusOf(card) == WatchStatus.PlanToWatch, "the library entry stays")
        } catch (e: Throwable) { e.printStackTrace(); rc = 2 } finally { scene.close() }
        println(if (rc == 0) "RESULT: MENU OK" else "RESULT: FAILED")
        System.out.flush(); Runtime.getRuntime().halt(rc); return rc
    }
}
