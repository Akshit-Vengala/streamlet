package cloudstream.desktop.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import cloudstream.desktop.player.LibMpvPlayer
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode

/**
 * The video, painted from mpv's software-rendered frames (see MpvFrameRenderer). Ordinary Compose content: anything
 * placed after it in a Box (the player controls) draws on top, in the same window.
 *
 * Each frame is a native Skia image (~8 MB at 1080p) that is CLOSED as soon as it can no longer be drawn. Leaving them
 * to the garbage collector let native memory pile up to gigabytes between collections (the Java side of an image is
 * tiny, so collections are rare), and each collection then froze playback for 2-3 s while it freed them.
 */
@Composable
fun VideoSurface(player: LibMpvPlayer, modifier: Modifier = Modifier) {
    var frame by remember(player) { mutableStateOf<Image?>(null) }
    // Replaced frames still alive: the newest of them may be referenced by a draw already recorded this frame.
    val retired = remember(player) { ArrayDeque<Image>() }
    // player.renderer is not Compose state: it appears when mpv finishes initialising (off the UI thread). Reading it
    // once during composition raced with that and left the picture black (audio only) for the whole session.
    val renderer by androidx.compose.runtime.produceState(player.renderer, player) {
        while (value == null) { kotlinx.coroutines.delay(20); value = player.renderer }
    }
    renderer?.let { renderer ->
        // Runs on the UI thread, like drawing: no frame is closed while it is being painted.
        LaunchedEffect(renderer) {
            renderer.frameSeq.collect {
                // Copy the newest frame into a Skia image (the renderer reuses its buffers).
                val next = renderer.withLatest { f ->
                    // RGB_888X: Skia ignores the padding byte, so the frame is opaque on every backend (a BGRA image
                    // read mpv's 0 padding as alpha: white garbage on Direct3D).
                    Image.makeRaster(ImageInfo(f.width, f.height, ColorType.RGB_888X, ColorAlphaType.OPAQUE), f.bytes, f.width * 4)
                } ?: return@collect
                frame?.let { retired.addLast(it) }
                frame = next
                while (retired.size > 2) retired.removeFirst().close()
            }
        }
    }
    DisposableEffect(player) {
        onDispose {
            retired.forEach { runCatching { it.close() } }; retired.clear()
            frame?.let { runCatching { it.close() } }; frame = null
        }
    }
    Box(
        modifier.background(Color.Black).onSizeChanged { player.setRenderSize(it.width, it.height) }.drawBehind {
            // mpv already letterboxes into the requested size, so the frame fills the area as-is.
            val img = frame ?: return@drawBehind
            drawIntoCanvas { c ->
                c.nativeCanvas.drawImageRect(
                    img, Rect.makeWH(img.width.toFloat(), img.height.toFloat()), Rect.makeWH(size.width, size.height),
                    SamplingMode.LINEAR, null, true,
                )
            }
        },
    )
}
