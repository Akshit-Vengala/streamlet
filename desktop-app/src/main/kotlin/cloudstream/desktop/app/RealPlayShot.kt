package cloudstream.desktop.app

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import cloudstream.desktop.app.ui.AppRoot
import cloudstream.desktop.app.vm.DetailsLoad
import cloudstream.desktop.app.vm.Screen
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * `--real-play-shot <out dir> [--provider <name>] [--query Inception] [--secs 150]`: the REAL app offscreen: search,
 * open the first hit, press Play, then log the source lookup and what the player does every 5 s (with screenshots).
 */
object RealPlayShot {
    fun run(argv: Array<String>): Int {
        fun arg(k: String) = argv.indexOf(k).takeIf { it >= 0 }?.let { argv[it + 1] }
        val out = File(arg("--real-play-shot")!!).apply { mkdirs() }
        System.setProperty("cloudstream.mpv.mute", "true") // a test: no sound on the user's machine
        val providerName = arg("--provider")
        val query = arg("--query") ?: "Inception"
        val secs = arg("--secs")?.toLong() ?: 150
        val services = AppServices.real(AppServices.appRoot(null))
        val c = AppController(services)
        runBlocking { c.extensions.start().join() }
        c.extensions.state.value.installed.forEach { r ->
            println("extension ${r.info.name}: " + when (val l = r.load) {
                is cloudstream.desktop.contracts.LoadResult.Ok -> "loaded ${l.apis}"
                is cloudstream.desktop.contracts.LoadResult.Failed -> "FAILED ${l.error.take(200)}"
                null -> "not loaded"
            } + "  safety=" + r.safety?.items?.joinToString { it.id + if (it.dangerous && !it.allowed) "(BLOCKED)" else "" })
        }
        services.profiles.setAskOnStart(false)
        val p = (if (providerName != null) c.providerByName(providerName) else c.providers().firstOrNull()) ?: run { println("provider $providerName not loaded: ${c.providers().map { it.name }}"); Runtime.getRuntime().halt(2); return 2 }
        val card = runBlocking { p.search(query, 1) }.firstOrNull() ?: run { println("no search result"); Runtime.getRuntime().halt(2); return 2 }
        println("title: ${card.name} ${card.url}")
        c.goTo(Screen.Home); c.openDetails(card)
        val vm = c.detailsVm(card)
        // `--window`: a real on-screen window (GPU drawing, like the installed app), captured from the screen.
        val window = if ("--window" in argv) {
            var w: androidx.compose.ui.awt.ComposeWindow? = null
            javax.swing.SwingUtilities.invokeAndWait {
                w = androidx.compose.ui.awt.ComposeWindow().apply {
                    setContent { AppRoot(c) }; setSize(1280, 760); setLocation(40, 40); isVisible = true
                }
            }
            w
        } else null
        val scene = ImageComposeScene(1280, 720, Density(1f)) { if (window == null) AppRoot(c) }
        var t = 0L
        fun frames(ms: Long) {
            if (window != null) { Thread.sleep(ms); return }
            val end = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < end) { scene.render(t); t += 50_000_000L; Thread.sleep(50) }
        }
        fun capture(f: File) {
            if (window != null) {
                val b = window.contentPane.let { java.awt.Rectangle(it.locationOnScreen, it.size) }
                javax.imageio.ImageIO.write(java.awt.Robot().createScreenCapture(b), "png", f)
            } else f.writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
        try {
            val loadEnd = System.currentTimeMillis() + 60_000
            while (vm.state.value.load is DetailsLoad.Loading && System.currentTimeMillis() < loadEnd) frames(200)
            println("details: ${vm.state.value.load::class.simpleName}")
            val wantEp = arg("--episode")?.toInt(); val wantSeason = arg("--season")?.toInt()
            val all = (vm.state.value.load as? DetailsLoad.Loaded)?.details?.episodes.orEmpty()
            val ep = (if (wantEp != null) all.firstOrNull { it.episode == wantEp && (wantSeason == null || it.season == wantSeason) } else vm.playTarget())
                ?: run { println("nothing to play (episodes: ${all.map { it.label }})"); return 2 }
            println("episode: ${ep.label}")
            val t0 = System.currentTimeMillis()
            c.playEpisode(vm, ep)
            var n = 0
            while (System.currentTimeMillis() - t0 < secs * 1000) {
                frames(5000); n++
                val ls = vm.state.value.links
                val pl = (c.nav.current as? Screen.Player)
                println("t=%3ds screen=%s lookup=%s links=%d playable=%d msg=%s".format(
                    (System.currentTimeMillis() - t0) / 1000, c.nav.current::class.simpleName, if (ls.loading) "running" else "done",
                    ls.links.size, ls.links.count { it.playable }, ls.message))
                cloudstream.desktop.app.ui.PlayerDebug.player?.let { m ->
                    println("   subs offered=${ls.subtitles.size} tracks=" + m.subtitleTracks().joinToString { "${it.lang ?: it.title}${if (it.external) "(ext)" else ""}${if (it.selected) "*" else ""}" } +
                        " sub-text=" + (m.property("sub-text")?.replace('\n', ' ')?.take(80) ?: "-") + " errors=${m.subtitleErrors.take(3)}")
                }
                if (n % 3 == 1) capture(File(out, "t%03d.png".format((System.currentTimeMillis() - t0) / 1000)))
                if (pl == null) break
            }
            fun shot(name: String) = capture(File(out, "$name.png"))
            shot("final")
            // `--tour`: real clicks through the player's Source panel (and its ⚙ Priority), then the Settings screen.
            // Click positions come from -DsourceX/-DsourceY and -DgearX/-DgearY (read them off tour-1-controls.png).
            if ("--tour" in argv && c.nav.current is Screen.Player) {
                fun move(x: Float, y: Float) = scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, androidx.compose.ui.geometry.Offset(x, y))
                fun click(x: Float, y: Float) {
                    move(x, y); frames(100)
                    scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, androidx.compose.ui.geometry.Offset(x, y)); frames(100)
                    scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, androidx.compose.ui.geometry.Offset(x, y)); frames(800)
                }
                // Still on the loading screen (the lookup runs on): press Skip loading, give the stream time to start.
                System.getProperty("skipX")?.let { click(it.toFloat(), System.getProperty("skipY", "414").toFloat()); frames(15_000) }
                move(640f, 300f); frames(200); move(650f, 310f); frames(600); shot("tour-1-controls")
                System.getProperty("sourceX")?.let { sx ->
                    click(sx.toFloat(), System.getProperty("sourceY", "689").toFloat()); frames(1500); shot("tour-2-sources")
                    System.getProperty("gearX")?.let { gx -> click(gx.toFloat(), System.getProperty("gearY")!!.toFloat()); frames(800); shot("tour-3-priority") }
                    cloudstream.desktop.app.ui.PlayerKeys.handler?.invoke(androidx.compose.ui.input.key.Key.Escape, false, false, false); frames(500)
                }
                c.goTo(Screen.Settings); frames(2500); shot("tour-4-settings")
                c.goTo(Screen.Extensions); frames(2500); shot("tour-5-extensions")
                repeat(System.getProperty("extScroll", "5").toInt()) {
                    scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Scroll, androidx.compose.ui.geometry.Offset(640f, 400f), scrollDelta = androidx.compose.ui.geometry.Offset(0f, 3f)); frames(100)
                }
                frames(800); shot("tour-6-extensions-scrolled")
            }
        } finally { scene.close(); services.close() }
        System.out.flush(); Runtime.getRuntime().halt(0); return 0
    }
}
