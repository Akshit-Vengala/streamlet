package cloudstream.desktop.integration

import cloudstream.desktop.androidshim.ShimRuntime
import cloudstream.desktop.contracts.PlaybackSource
import cloudstream.desktop.contracts.SubtitleSource
import cloudstream.desktop.loader.ContextProvider
import cloudstream.desktop.loader.DefaultEntrypoint
import cloudstream.desktop.loader.PluginConverter
import cloudstream.desktop.loader.PluginLoader
import cloudstream.desktop.player.LibMpvPlayer
import cloudstream.desktop.storage.StorageManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.awt.Canvas
import java.awt.Color
import java.awt.Frame
import java.awt.Rectangle
import java.awt.Robot
import java.io.File
import javax.imageio.ImageIO

/**
 * Plays a REAL stream link through libmpv embedded in an AWT window, with the plugin's subtitles, and screenshots it.
 * Runs plugin code, uses the network and opens a window: run by hand.
 *   args: <cs3 file> <work dir> <query> [--link-index N] [--sub-lang English] [--seek-sec 600]
 * Tries links in order (like the app's fallback) until one plays.
 */
fun main(args: Array<String>) {
    val cs3 = File(args[0]); val work = File(args[1]).apply { mkdirs() }; val query = args[2]
    fun opt(n: String) = args.indexOf(n).takeIf { it >= 0 }?.let { args[it + 1] }
    val linkIndex = opt("--link-index")?.toInt() ?: 0
    val subLang = opt("--sub-lang") ?: "English"
    val seekSec = opt("--seek-sec")?.toLong() ?: 600

    cloudstream.desktop.webview.AndroidWebViewSupport.install()
    com.lagradost.cloudstream3.CommonActivity.activity = androidx.appcompat.app.AppCompatActivity()

    val storage = StorageManager(File(work, "data"))
    ShimRuntime.setKeyValueStore(storage.asKeyValueStore("shared_prefs")); ShimRuntime.setDataDir(File(work, "files"))
    val conv = PluginConverter(File(work, "jars"))
    val jar = conv.convert(cs3)
    conv.warnings.filter { "split" in it.lowercase() }.forEach { println("CONVERT $it") }
    val loader = PluginLoader(entrypoint = DefaultEntrypoint(ContextProvider { ShimRuntime.context() }))
    println("load: " + loader.load(cs3.nameWithoutExtension, cs3, jar).toString().take(120))
    val api = APIHolder.allProviders.first()

    val links = java.util.concurrent.CopyOnWriteArrayList<ExtractorLink>()
    val subs = java.util.concurrent.CopyOnWriteArrayList<SubtitleFile>()
    var title = query
    runBlocking {
        val hit = withTimeout(45_000) { api.search(query, 1) }!!.items.first()
        println("search hit: ${hit.name} ${hit.url.take(100)}")
        val loaded = withTimeout(60_000) { api.load(hit.url) }!!
        title = loaded.name
        val data = when (loaded) {
            is AnimeLoadResponse -> loaded.episodes.values.first().first().data
            is TvSeriesLoadResponse -> loaded.episodes.first().data
            is MovieLoadResponse -> loaded.dataUrl
            else -> error("unsupported ${loaded::class}")
        }
        runCatching {
            withTimeout(75_000) { api.loadLinks(data, false, { subs += it }, { links += it; println("  link: ${it.name} q=${it.quality} ${it.type} ${it.url.take(90)}") }) }
        }.onFailure { println("loadLinks ended: $it") }
    }
    println("links=${links.size} subtitles=${subs.size}: ${subs.map { it.lang }.distinct().take(15)}")
    check(links.isNotEmpty()) { "no links" }
    val sub = subs.firstOrNull { it.lang.contains(subLang, true) || it.lang.equals(subLang.take(2), true) } ?: subs.firstOrNull()
    println("subtitle: ${sub?.lang} ${sub?.url?.take(100)}")

    val frame = Frame("PlaySmoke - $title").apply { setSize(1280, 720); setLocation(80, 60); background = Color.BLACK }
    val canvas = Canvas().apply { background = Color.BLACK }
    frame.add(canvas); frame.isVisible = true
    Thread.sleep(800)
    val player = LibMpvPlayer()
    var rc = 1
    try {
        player.attachTo(canvas)
        val ordered = (listOfNotNull(links.getOrNull(linkIndex)) + links).distinctBy { it.url }.filter { it.url.startsWith("http") }
        var playing: ExtractorLink? = null
        for ((i, l) in ordered.withIndex().take(30)) {
            val src = PlaybackSource(
                l.url, l.headers.filterKeys { !it.equals("referer", true) }, l.referer.takeIf { it.isNotBlank() },
                l.type == ExtractorLinkType.M3U8, l.type == ExtractorLinkType.DASH,
                listOfNotNull(sub?.let { SubtitleSource(it.url, it.lang, it.headers.orEmpty()) }), 0,
            )
            println("TRY #${i + 1}: ${l.name} (${l.quality}) ${l.url.take(90)}")
            if (i == 0) println("   full url=${l.url}\n   referer=${l.referer} headers=${l.headers}")
            player.open(src)
            val t0 = System.currentTimeMillis(); var ok = false
            while (System.currentTimeMillis() - t0 < 25_000) {
                val st = player.state.value
                if (st.error != null) { println("   failed: ${st.error}"); break }
                if (st.positionMs >= 2000) { ok = true; break }
                Thread.sleep(500)
            }
            if (ok) { playing = l; break } else if (player.state.value.error == null) println("   no video after 25 s")
        }
        checkNotNull(playing) { "no link played" }
        println("PLAYING: ${playing.name} (${playing.quality})")
        if ("--controls-test" in args) {
            fun snap(what: String) = println("CTRL $what: pos=${player.state.value.positionMs / 1000}s pause=${player.property("pause")} playing=${player.state.value.playing} buffering=${player.state.value.buffering} err=${player.state.value.error}")
            snap("start")
            player.togglePause(); Thread.sleep(1500); snap("after togglePause #1 (expect paused)")
            Thread.sleep(2000); snap("2 s later (position should not move)")
            player.togglePause(); Thread.sleep(1500); snap("after togglePause #2 (expect playing)")
            val p0 = player.state.value.positionMs
            player.seek(p0 + 10_000); Thread.sleep(4000); snap("after +10 s seek")
            player.seek((player.state.value.positionMs - 10_000).coerceAtLeast(0)); Thread.sleep(4000); snap("after -10 s seek")
            player.seek(p0 + 300_000); Thread.sleep(8000); snap("after +5 min seek")
        }
        // Jump into the film so there is dialogue on screen, then wait for a subtitle line.
        val dur = player.state.value.durationMs
        if (dur > 0) player.seek(minOf(seekSec * 1000, dur / 2))
        var subText: String? = null
        val t1 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t1 < 45_000) {
            subText = player.property("sub-text")?.takeIf { it.isNotBlank() }
            if (subText != null && player.state.value.positionMs > 0 && !player.state.value.buffering) { Thread.sleep(400); break }
            Thread.sleep(300)
        }
        if (player.subtitleErrors.isNotEmpty()) println("subtitle errors: ${player.subtitleErrors}")
        println("sid=${player.property("sid")} sub tracks=${player.subtitleTracks().map { "${it.id}:${it.lang ?: it.title}" }} on-screen subtitle=\"${subText?.replace('\n', ' ')}\"")
        println("video=${player.property("video-codec")} ${player.property("width")}x${player.property("height")} pos=${player.state.value.positionMs / 1000}s of ${dur / 1000}s")
        // 1) mpv's own render of the frame + subtitle (immune to other windows); 2) the on-screen window.
        val frameShot = File(work, "playback-subs-frame.png")
        println("mpv frame screenshot: ${player.screenshotTo(frameShot)} -> ${frameShot.absolutePath}")
        frame.isAlwaysOnTop = true; frame.toFront(); Thread.sleep(700)
        val img = Robot().createScreenCapture(Rectangle(canvas.locationOnScreen, canvas.size))
        val shot = File(work, "playback-subs.png"); ImageIO.write(img, "png", shot)
        println("screenshot ${shot.absolutePath}")
        rc = if (subText != null) 0 else 2
        println(if (rc == 0) "RESULT: PLAYBACK + SUBTITLES OK" else "RESULT: PLAYING, but no subtitle line seen")
    } finally { player.release(); frame.dispose(); storage.close() }
    System.out.flush(); Runtime.getRuntime().halt(rc)
}
