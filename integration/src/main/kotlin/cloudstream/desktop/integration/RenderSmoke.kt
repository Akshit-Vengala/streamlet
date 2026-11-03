package cloudstream.desktop.integration

import cloudstream.desktop.contracts.PlaybackSource
import cloudstream.desktop.player.LibMpvPlayer
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Verifies libmpv's software render path (no window): frames arrive, change over time, and pause / relative seek /
 * absolute seek behave. args: <video file or URL> <out dir>
 */
fun main(args: Array<String>) {
    val src = args[0]; val out = File(args[1]).apply { mkdirs() }
    System.setProperty("cloudstream.mpv.mute", "true") // a test: no sound on the user's machine
    val p = LibMpvPlayer(renderMode = true)
    var rc = 0
    fun check(ok: Boolean, what: String) { println((if (ok) "PASS " else "FAIL ") + what); if (!ok) rc = 1 }
    fun frameHash(): Int? = p.renderer?.withLatest { f -> f.bytes.contentHashCode() }
    fun waitFor(ms: Long, cond: () -> Boolean): Boolean { val end = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(100) }; return cond() }
    fun pos() = p.state.value.positionMs
    // A subtitle host that never answers (a plugin had one): must not freeze anything.
    val hang = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/hang.vtt") { ex -> runCatching { Thread.sleep(120_000) }; ex.close() }
        executor = java.util.concurrent.Executors.newCachedThreadPool(); start()
    }
    val hangUrl = "http://127.0.0.1:${hang.address.port}/hang.vtt"
    try {
        p.start()
        p.setRenderSize(960, 540)
        p.open(PlaybackSource(src, emptyMap(), null, false, false,
            listOf(cloudstream.desktop.contracts.SubtitleSource(hangUrl, "English", emptyMap())), 0))
        check(waitFor(15_000) { (p.renderer?.frameSeq?.value ?: 0) > 5 && pos() > 500 }, "frames arrive and position advances (frames=${p.renderer?.frameSeq?.value}, pos=${pos()})")
        check(p.property("mute") == "yes", "test playback is muted")
        val h1 = frameHash(); Thread.sleep(700); val h2 = frameHash()
        check(h1 != null && h1 != h2, "picture changes while playing")
        p.renderer?.withLatest { f -> saveFrame(f.width, f.height, f.bytes, File(out, "frame-playing.png")) }

        val tCall = System.nanoTime(); p.togglePause(); val callMs = (System.nanoTime() - tCall) / 1_000_000
        check(callMs < 100, "pause call returns at once while a subtitle download hangs (${callMs} ms)")
        Thread.sleep(800)
        val pausedAt = pos(); Thread.sleep(1500)
        check(p.property("pause") == "yes" && !p.state.value.playing && Math.abs(pos() - pausedAt) < 300, "pause holds position ($pausedAt -> ${pos()})")
        p.togglePause()
        check(waitFor(3000) { p.state.value.playing }, "resume plays again")

        val before = pos(); p.seekBy(10_000)
        check(waitFor(4000) { pos() >= before + 9_000 }, "+10 s is relative ($before -> ${pos()})")
        val before2 = pos(); p.seekBy(-10_000)
        check(waitFor(4000) { pos() <= before2 - 8_000 }, "-10 s is relative ($before2 -> ${pos()})")
        p.seek(15_000)
        check(waitFor(4000) { pos() in 14_500..17_500 }, "absolute seek (seek bar) to 15 s (${pos()})")
        check(waitFor(4000) { p.state.value.playing && p.state.value.error == null }, "still playing after seeks, no error (${p.state.value})")
        p.renderer?.withLatest { f -> saveFrame(f.width, f.height, f.bytes, File(out, "frame-after-seek.png")) }

        // Video streams (quality variants): one -> no Video button; add a second -> listed with labels, switchable.
        check(waitFor(3000) { p.videoTracks().size == 1 }, "one video stream listed (${p.videoTracks()})")
        p.command("video-add", src, "auto", "second")
        check(waitFor(5000) { p.videoTracks().size == 2 }, "second video stream listed (${p.videoTracks()})")
        check(p.videoTracks().all { it.title?.endsWith("p") == true || it.title?.contains("p ·") == true }, "streams labelled by height (${p.videoTracks().map { it.title }})")
        val other = p.videoTracks().first { !it.selected }
        p.setVideoTrack(other.id)
        check(waitFor(4000) { p.property("vid") == other.id.toString() && p.videoTracks().first { it.selected }.id == other.id }, "switching to stream ${other.id} (vid=${p.property("vid")})")
        check(waitFor(4000) { p.state.value.playing && p.state.value.error == null }, "still playing after the switch")
        println("duration=${p.state.value.durationMs} frames=${p.renderer?.frameSeq?.value}")

        // Subtitle pick per source: a stream with its own subtitle track (an EDL joining the video and an .srt into
        // ONE file) plus an external file. Optional 3rd arg: an .srt file.
        args.getOrNull(2)?.let(::File)?.takeIf { it.isFile }?.let { srt ->
            fun lenItem(s: String) = "%${s.toByteArray().size}%$s"
            val edl = File(out, "with-subs.edl").apply {
                writeText("# mpv EDL v0\n!new_stream\n${lenItem(File(src).absolutePath)}\n!new_stream\n!track_meta,lang=eng,title=Own\n${lenItem(srt.absolutePath)}\n")
            }
            val ext = File(out, "external.srt").apply { writeBytes(srt.readBytes()) }
            val withExt = PlaybackSource(edl.absolutePath, emptyMap(), null, false, false,
                listOf(cloudstream.desktop.contracts.SubtitleSource(ext.absolutePath, "English", emptyMap())), 0)
            fun selectedSub() = p.subtitleTracks().firstOrNull { it.selected }
            fun openWith(pick: String): cloudstream.desktop.player.MediaTrack? {
                p.subtitlePick = pick; p.preferredSubLang = "English"; p.open(withExt)
                waitFor(8000) { p.state.value.positionMs > 300 && p.subtitleTracks().size >= 2 }
                Thread.sleep(500)
                return selectedSub()
            }
            val auto = openWith(LibMpvPlayer.SUB_AUTO)
            check(p.subtitleTracks().count { !it.external } == 1 && p.subtitleTracks().count { it.external } == 1, "stream's own + external subtitle listed (${p.subtitleTracks()})")
            check(auto != null && !auto.external, "auto: the source's own subtitle is picked over the external one ($auto)")
            val user = openWith(LibMpvPlayer.SUB_EXTERNAL)
            check(user != null && user.external && user.source?.endsWith("external.srt") == true, "user's external pick survives a source switch ($user)")
            val off = openWith(LibMpvPlayer.SUB_OFF)
            check(off == null, "off stays off on a new source ($off)")
            val own = openWith(LibMpvPlayer.SUB_EMBEDDED_PREFIX + "eng")
            check(own != null && !own.external && own.lang == "eng", "user's built-in pick (by language) on a new source ($own)")
            // No own track: auto falls back to the external file.
            p.subtitlePick = LibMpvPlayer.SUB_AUTO
            p.open(PlaybackSource(src, emptyMap(), null, false, false, listOf(cloudstream.desktop.contracts.SubtitleSource(ext.absolutePath, "English", emptyMap())), 0))
            waitFor(8000) { p.state.value.positionMs > 300 && p.subtitleTracks().isNotEmpty() }; Thread.sleep(500)
            check(selectedSub()?.external == true, "auto without an own track: the external subtitle (${p.subtitleTracks()})")
        }
    } catch (t: Throwable) {
        t.printStackTrace(); rc = 2
    } finally { p.release(); hang.stop(0) }
    println(if (rc == 0) "RESULT: RENDER PLAYER OK" else "RESULT: FAILED")
    Runtime.getRuntime().halt(rc)
}

private fun saveFrame(w: Int, h: Int, bgrx: ByteArray, file: File) {
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until h) for (x in 0 until w) {
        val i = (y * w + x) * 4
        img.setRGB(x, y, ((bgrx[i + 2].toInt() and 0xFF) shl 16) or ((bgrx[i + 1].toInt() and 0xFF) shl 8) or (bgrx[i].toInt() and 0xFF))
    }
    ImageIO.write(img, "png", file)
}
