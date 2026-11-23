package cloudstream.desktop.app

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import cloudstream.desktop.app.ui.AppRoot
import cloudstream.desktop.app.ui.PlayerDebug
import cloudstream.desktop.app.vm.DetailsLoad
import cloudstream.desktop.app.vm.Screen
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * `--party-shot <out dir> --role host|guest --code-file <f> [--data-dir d] [--provider P] [--query Q] [--secs N]`:
 * the REAL app offscreen in a watch party over the public relay. Run one host and one guest (separate processes,
 * separate data dirs). The host plays a real title, starts the party and writes the invite code to the code file,
 * then plays / pauses / seeks; the guest joins with the code and must follow by itself. Both log what their player
 * does (wall clock + position) so the two logs can be compared.
 */
object PartyShot {
    fun run(argv: Array<String>): Int {
        fun arg(k: String) = argv.indexOf(k).takeIf { it >= 0 }?.let { argv[it + 1] }
        val out = File(arg("--party-shot")!!).apply { mkdirs() }
        val role = arg("--role") ?: "host"
        val codeFile = File(arg("--code-file")!!)
        val secs = arg("--secs")?.toLong() ?: 240
        System.setProperty("cloudstream.mpv.mute", "true")
        val services = AppServices.real(AppServices.appRoot(arg("--data-dir")?.let(::File)))
        val c = AppController(services)
        runBlocking { c.extensions.start().join() }
        services.profiles.setAskOnStart(false)
        val scene = ImageComposeScene(1280, 720, Density(1f)) { AppRoot(c) }
        var t = 0L
        fun frames(ms: Long) { val end = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < end) { scene.render(t); t += 50_000_000L; Thread.sleep(50) } }
        fun shot(name: String) = File(out, "$role-$name.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        fun log(msg: String) = println("[$role %d] %s".format(System.currentTimeMillis(), msg))
        fun status() {
            val m = PlayerDebug.player
            val pos = m?.property("time-pos")?.toDoubleOrNull()?.let { (it * 1000).toLong() }
            val ui = c.party.session?.ui?.value
            log("screen=${c.nav.current::class.simpleName} pos=$pos paused=${m?.property("pause")} speed=${m?.property("speed")} " +
                "members=${ui?.members?.map { it.name }} host=${ui?.isHost} room=${ui?.room?.let { "seq${it.seq} playing=${it.playing} waiting=${it.waiting} ${it.media?.episodeLabel}" }} " +
                "behind=${ui?.behindMs} detached=${ui?.detached}")
        }
        fun waitFor(ms: Long, what: String, cond: () -> Boolean): Boolean {
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end) { if (cond()) { log("ok: $what"); return true }; frames(250) }
            log("TIMEOUT: $what"); status(); return false
        }
        try {
            if (role == "host") {
                codeFile.delete()
                val q = arg("--query") ?: "Inception"
                val candidates = arg("--provider")?.let { listOfNotNull(c.providerByName(it)) } ?: c.providers()
                log("providers: ${candidates.map { it.name }}")
                val (p, card) = candidates.firstNotNullOfOrNull { pr ->
                    runCatching { runBlocking { kotlinx.coroutines.withTimeoutOrNull(20_000) { pr.search(q, 1) } } }.getOrNull()?.firstOrNull()?.let { pr to it }
                } ?: run { log("no search result"); return 2 }
                log("title: ${card.name} (${p.name})")
                c.goTo(Screen.Home); c.openDetails(card)
                val vm = c.detailsVm(card)
                waitFor(60_000, "details loaded") { vm.state.value.load !is DetailsLoad.Loading }
                val ep = vm.playTarget() ?: run { log("nothing to play"); return 2 }
                c.playEpisode(vm, ep)
                if (!waitFor(150_000, "video playing") { (PlayerDebug.player?.property("time-pos")?.toDoubleOrNull() ?: 0.0) > 1.0 }) return 2
                c.party.start("")
                val code = c.party.active.value!!.code
                codeFile.writeText(code); log("party code written")
                if (!waitFor(120_000, "guest joined") { (c.party.session?.ui?.value?.members?.size ?: 0) >= 2 }) return 2
                // The guest resolves the title and its own source: the room waits for it (episode load).
                waitFor(150_000, "guest ready (room playing, guest status ready)") {
                    val u = c.party.session!!.ui.value
                    u.statuses.size >= 2 && u.statuses.values.all { !it.buffering } && u.room.playing
                }
                frames(15_000); status(); shot("1-playing")
                log("ACTION pause"); c.party.session!!.pause(); frames(5000); status()
                log("ACTION seek 600000"); c.party.seek(600_000); frames(8000); status()
                log("ACTION play"); c.party.session!!.play(); frames(20_000); status(); shot("2-after-seek")
                log("ACTION speed 1.5"); c.party.changeSpeed(1.5); frames(10_000); status()
                log("ACTION speed 1.0"); c.party.changeSpeed(1.0); frames(8000); status()
                c.party.session!!.chat("hello from host")
                frames(5000); status()
                log("ACTION leave (hand host on)")
                log("ACTION done"); frames(10_000)
            } else {
                if (!waitFor(400_000, "code file") { codeFile.isFile && codeFile.readText().isNotBlank() }) return 2
                val code = codeFile.readText().trim()
                check(c.party.join(code, "")) { "bad code" }
                val end = System.currentTimeMillis() + secs * 1000
                var n = 0
                while (System.currentTimeMillis() < end) {
                    frames(2000); status(); n++
                    if (n % 10 == 3) shot("t$n")
                }
                log("chat: " + c.party.chat.value.joinToString(" | ") { "${it.name}:${it.text}" })
            }
        } finally {
            shot("final")
            runBlocking { c.party.session?.leave() }
            scene.close(); services.close()
        }
        System.out.flush(); Runtime.getRuntime().halt(0); return 0
    }
}
