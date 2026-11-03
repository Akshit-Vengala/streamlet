package cloudstream.desktop.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import cloudstream.desktop.app.ui.CloudTheme
import cloudstream.desktop.app.ui.PlayerControls
import cloudstream.desktop.app.ui.PlayerUi
import cloudstream.desktop.app.ui.VideoSurface
import cloudstream.desktop.contracts.PlaybackSource
import cloudstream.desktop.player.LibMpvPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * `--player-shot <video> <out dir>`: the REAL player UI (VideoSurface + PlayerControls) rendered offscreen with a real
 * libmpv, driven by real pointer clicks; prints what changed and saves screenshots. Verifies the controls are wired.
 */
private fun PlayerUi.hasAudioButtonShown() = audioList.size > 1

object PlayerShot {
    @OptIn(ExperimentalComposeUiApi::class)
    fun run(argv: Array<String>): Int {
        val i = argv.indexOf("--player-shot")
        val video = argv[i + 1]; val out = File(argv[i + 2]).apply { mkdirs() }
        System.setProperty("cloudstream.mpv.mute", "true") // a test: no sound on the user's machine
        val p = LibMpvPlayer(renderMode = true)
        val ui = PlayerUi()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        ui.title = "Test video"; ui.episodeLine = "S1 E1 - Pilot"; ui.hasNext = true
        ui.onPlayPause = { p.togglePause() }
        ui.onSeek = { p.seek(it) }
        ui.onSeekBy = { p.seekBy(it) }
        ui.onSpeed = { ui.speed = it; p.setSpeed(it) }
        ui.onVolume = { ui.volume = it; p.setVolume(it) }
        scope.launch { p.state.collect { ui.applyPlayerState(it) } }
        // Loading screen first: links "arriving", then Skip loading starts the best one.
        fun link(n: Int, q: Int) = cloudstream.desktop.app.domain.SourceLink("Src$n", "Server $n", "http://test.invalid/$n", "", q, emptyMap(), false, false)
        ui.stillSearching = true
        ui.sources = listOf(link(1, 1080), link(2, 720))
        ui.onSkipLoading = { ui.current = ui.sources.first(); p.open(PlaybackSource(video, emptyMap(), null, false, false, emptyList(), 0)) }

        val w = 1280; val h = 720
        val scene = ImageComposeScene(w, h, Density(1f)) {
            CloudTheme { Box(Modifier.fillMaxSize()) { VideoSurface(p, Modifier.fillMaxSize()); PlayerControls(ui, MutableSharedFlow()) } }
        }
        var t = 0L
        fun frames(ms: Long) { val end = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < end) { scene.render(t); t += 16_000_000L; Thread.sleep(16) } }
        fun shot(name: String) { File(out, "$name.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes); println("  shot $name.png  state=${p.state.value.let { "pos=${it.positionMs / 1000.0}s playing=${it.playing}" }} mpvPause=${p.property("pause")}") }
        fun click(x: Float, y: Float) {
            scene.sendPointerEvent(PointerEventType.Move, Offset(x, y))
            scene.sendPointerEvent(PointerEventType.Press, Offset(x, y)); frames(60)
            scene.sendPointerEvent(PointerEventType.Release, Offset(x, y)); frames(400)
        }
        val skipX = System.getProperty("skipX", "575").toFloat(); val skipY = System.getProperty("skipY", "413").toFloat()
        var rc = 0
        fun check(ok: Boolean, what: String) { println((if (ok) "PASS " else "FAIL ") + what); if (!ok) rc = 1 }
        try {
            // The surface is composed BEFORE mpv (and its frame renderer) exists: the race that left only audio.
            frames(200)
            p.start()
            frames(400); shot("0-loading-2")
            ui.sources = ui.sources + link(3, 480); frames(400); shot("0-loading-3")
            check(ui.current == null && p.state.value.positionMs == 0L, "loading screen: nothing plays before a source is chosen")
            click(skipX, skipY); frames(300)
            check(ui.current != null, "clicking Skip loading starts playback")
            ui.stillSearching = false
            frames(2500); shot("1-playing")
            check(p.state.value.playing && p.state.value.positionMs > 500, "video plays and the UI receives its state")
            val px = org.jetbrains.skia.Bitmap.makeFromImage(scene.render(t)).getColor(100, 300)
            check((px and 0xFFFFFF) != 0, "the picture is painted, not just audio (pixel #%06X)".format(px and 0xFFFFFF))
            check(p.property("mute") == "yes", "test playback is muted")
            val cx = w / 2f; val cy = h / 2f
            scene.sendPointerEvent(PointerEventType.Move, Offset(cx, cy))
            scene.sendPointerEvent(PointerEventType.Press, Offset(cx, cy)); scene.sendPointerEvent(PointerEventType.Release, Offset(cx, cy))
            scene.render(t); t += 16_000_000L
            check(!ui.state.playing, "pause icon changes on the very next frame")
            frames(500); shot("2-after-click-pause")
            check(p.property("pause") == "yes", "clicking the centre button pauses")
            val paused = p.state.value.positionMs; frames(1200)
            check(Math.abs(p.state.value.positionMs - paused) < 300, "position holds while paused")
            click(cx, cy); frames(800); shot("3-after-click-play")
            check(p.property("pause") == "no", "clicking it again resumes")
            val before = p.state.value.positionMs
            click(cx + 42 + 48 + 32, cy); frames(1500) // forward button: half play (42) + gap (48) + half button (32)
            shot("4-after-forward")
            check(p.state.value.positionMs >= before + 9_000, "forward button skips +10 s from the current position ($before -> ${p.state.value.positionMs})")
            // Arrow keys through the window-level hook (works whatever has focus), using the configured steps.
            fun key(k: androidx.compose.ui.input.key.Key): Boolean = cloudstream.desktop.app.ui.PlayerKeys.handler?.invoke(k, false, false, false) == true
            ui.seekForwardMs = 15_000; ui.seekBackMs = 5_000; frames(200)
            val k0 = p.state.value.positionMs
            check(key(androidx.compose.ui.input.key.Key.DirectionRight), "Right arrow is handled")
            frames(1500)
            check(p.state.value.positionMs in (k0 + 13_000)..(k0 + 18_000), "Right arrow = +15 s as set ($k0 -> ${p.state.value.positionMs})")
            val k1 = p.state.value.positionMs
            key(androidx.compose.ui.input.key.Key.DirectionLeft); frames(1500)
            check(p.state.value.positionMs in (k1 - 6_000)..(k1 - 2_000), "Left arrow = -5 s as set ($k1 -> ${p.state.value.positionMs})")
            p.seek(3000); frames(800)
            frames(4000); shot("5-controls-faded")

            fun drag(x: Float, y0: Float, y1: Float) {
                scene.sendPointerEvent(PointerEventType.Move, Offset(x, y0))
                scene.sendPointerEvent(PointerEventType.Press, Offset(x, y0)); frames(30)
                for (k in 1..10) { scene.sendPointerEvent(PointerEventType.Move, Offset(x, y0 + (y1 - y0) * k / 10)); frames(20) }
                scene.sendPointerEvent(PointerEventType.Release, Offset(x, y1)); frames(300)
            }
            val v0 = ui.volume
            drag(w * 0.8f, 560f, 200f); shot("6-swipe-up-right")
            check(ui.volume > v0 && p.property("volume")?.toDoubleOrNull()?.toInt() == ui.volume, "swipe up on the right raises the volume ($v0 -> ${ui.volume}, mpv ${p.property("volume")})")
            val v1 = ui.volume
            drag(w * 0.8f, 200f, 560f)
            check(ui.volume < v1, "swipe down on the right lowers it ($v1 -> ${ui.volume})")
            val v2 = ui.volume
            drag(w * 0.2f, 560f, 200f)
            check(ui.volume == v2, "swipe on the left half leaves the volume alone")

            check(!ui.hasAudioButtonShown(), "one audio stream: no Audio button")
            ui.audioList = listOf(cloudstream.desktop.player.MediaTrack(1, "eng", null, true), cloudstream.desktop.player.MediaTrack(2, "hin", "Hindi", false))
            scene.sendPointerEvent(PointerEventType.Move, Offset(w / 2f, h / 2f)); frames(500); shot("7-audio-button")

            // Mouse wheel: volume over the video, but NOT while scrolling a panel's list (that lowered the volume).
            fun wheel(n: Int) = repeat(n) { scene.sendPointerEvent(PointerEventType.Scroll, Offset(cx, cy), scrollDelta = Offset(0f, 1f)); frames(40) }
            val v3 = ui.volume; wheel(1)
            check(ui.volume < v3, "mouse wheel over the video lowers the volume ($v3 -> ${ui.volume})")
            key(androidx.compose.ui.input.key.Key.F1); frames(300); shot("8-panel-open")
            val v4 = ui.volume; wheel(6)
            check(ui.volume == v4, "scrolling inside an open panel leaves the volume alone ($v4 -> ${ui.volume})")
            key(androidx.compose.ui.input.key.Key.Escape); frames(300)

            // Source panel: same-named subtitles share a numbered row, different names keep their own rows.
            fun sub(lang: String, n: Int) = cloudstream.desktop.app.domain.SubtitleItem(lang, "http://subs.invalid/$n.vtt", emptyMap())
            ui.subtitles = listOf(sub("English", 1), sub("English", 2), sub("English", 3), sub("English (SDH)", 4), sub("Spanish", 5), sub("Server 1 English", 6))
            scene.sendPointerEvent(PointerEventType.Move, Offset(w / 2f, h / 2f)); frames(400)
            click(System.getProperty("sourceX", "745").toFloat(), System.getProperty("sourceY", "689").toFloat()); frames(500)
            shot("9-sources-subtitles")
            key(androidx.compose.ui.input.key.Key.Escape); frames(300)

            // Subtitle sync screen: the button only with a subtitle on; lines listed, the one on now with its bar.
            ui.selectedSub = null; scene.sendPointerEvent(PointerEventType.Move, Offset(w / 2f, h / 2f)); frames(400); shot("10-no-sub-no-sync")
            val pos = p.state.value.positionMs
            val srt = File(out, "sync-test.srt").apply {
                writeText((0 until 40).joinToString("\n\n") { k ->
                    val s = pos - 6000 + k * 1500L; fun f(ms: Long) = "%02d:%02d:%02d,%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
                    "${k + 1}\n${f(s)} --> ${f(s + 1400)}\nLine number ${k + 1}"
                })
            }
            ui.subtitleCues = { cloudstream.desktop.app.ui.SubtitleCueLoader.load(it) }
            ui.onSubDelay = { ui.subDelay = it; p.setProperty("sub-delay", "%.3f".format(java.util.Locale.ROOT, it)) }
            ui.selectedSub = srt.absolutePath
            scene.sendPointerEvent(PointerEventType.Move, Offset(w / 2f, h / 2f)); frames(400); shot("11-sub-with-sync")
            click(System.getProperty("syncX", "560").toFloat(), System.getProperty("syncY", "689").toFloat()); frames(1200)
            shot("12-sync-screen")
            check(!key(androidx.compose.ui.input.key.Key.Minus), "sync screen: '-' goes to the ms field, not the speed")
            ui.onSubDelay(0.7); frames(800); shot("13-sync-delayed")
            check(key(androidx.compose.ui.input.key.Key.Escape), "Esc on the sync screen is handled (Cancel)")
            frames(300)
            check(ui.subDelay == 0.0, "Cancel restores the delay from before (${ui.subDelay})")
            frames(300); shot("13b-after-sync")
            ui.selectedSub = null; scene.sendPointerEvent(PointerEventType.Move, Offset(w / 2f, h / 2f)); frames(400)
            click(System.getProperty("speedX", "530").toFloat(), System.getProperty("speedY", "689").toFloat()); frames(500)
            shot("14-speed")
        } catch (t: Throwable) { t.printStackTrace(); rc = 2 }
        finally { scene.close(); p.release() }
        println(if (rc == 0) "RESULT: PLAYER UI OK" else "RESULT: FAILED")
        System.out.flush(); Runtime.getRuntime().halt(rc); return rc
    }
}
