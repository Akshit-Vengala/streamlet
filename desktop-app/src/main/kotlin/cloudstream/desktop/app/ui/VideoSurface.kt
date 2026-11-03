package cloudstream.desktop.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import cloudstream.desktop.player.LibMpvPlayer
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/**
 * The video, painted from mpv's software-rendered frames (see MpvFrameRenderer). Ordinary Compose content: anything
 * placed after it in a Box (the player controls) draws on top, in the same window.
 */
@Composable
fun VideoSurface(player: LibMpvPlayer, modifier: Modifier = Modifier) {
    var image by remember(player) { mutableStateOf<ImageBitmap?>(null) }
    // player.renderer is not Compose state: it appears when mpv finishes initialising (off the UI thread). Reading it
    // once during composition raced with that and left the picture black (audio only) for the whole session.
    val renderer by androidx.compose.runtime.produceState(player.renderer, player) {
        while (value == null) { kotlinx.coroutines.delay(20); value = player.renderer }
    }
    renderer?.let { renderer ->
        LaunchedEffect(renderer) {
            renderer.frameSeq.collect {
                // Copy the newest frame into a Skia image (the renderer reuses its buffers).
                renderer.withLatest { f ->
                    org.jetbrains.skia.Image.makeRaster(
                        ImageInfo(f.width, f.height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), f.bytes, f.width * 4,
                    ).toComposeImageBitmap()
                }?.let { image = it }
            }
        }
    }
    Box(modifier.background(Color.Black).onSizeChanged { player.setRenderSize(it.width, it.height) }) {
        // mpv already letterboxes into the requested size, so the frame fills the area as-is.
        image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
    }
}
