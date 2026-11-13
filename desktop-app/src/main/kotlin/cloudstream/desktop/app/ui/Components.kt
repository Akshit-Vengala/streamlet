package cloudstream.desktop.app.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.animateScrollBy
import kotlinx.coroutines.launch
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cloudstream.desktop.app.domain.MediaCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Artwork cache. Images are decoded small (they are shown as tiles), kept in memory within a byte budget, and their
 * small versions are saved on disk (JPEG, a few tens of KB each) so the next start shows them without downloading.
 * Failures are cached as null in memory only.
 */
object ImageCache {
    /** Decoded pixels kept in memory (4 bytes per pixel). */
    private const val MEMORY_BUDGET = 64L shl 20
    /** Small JPEGs kept on disk; the least recently used go first. */
    private const val DISK_BUDGET = 100L shl 20
    val diskDir: java.io.File get() = java.io.File(cloudstream.desktop.contracts.AppDirs.root, "cache/images")
    private class Entry(val bmp: ImageBitmap?) { val bytes = bmp?.let { it.width.toLong() * it.height * 4 } ?: 0L }
    private var used = 0L
    private val map = LinkedHashMap<String, Entry>(64, 0.75f, true)
    private val client by lazy {
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(8)).build()
    }

    @Synchronized private fun cached(key: String): Pair<Boolean, ImageBitmap?> = map[key]?.let { true to it.bmp } ?: (false to null)
    @Synchronized private fun put(key: String, v: ImageBitmap?) {
        val e = Entry(v)
        map.put(key, e)?.let { used -= it.bytes }
        used += e.bytes
        // Evicted bitmaps are left to the GC: one may still be on screen, so it is never closed here.
        val it = map.entries.iterator()
        while (used > MEMORY_BUDGET && it.hasNext()) { val old = it.next(); if (old.value === e) continue; used -= old.value.bytes; it.remove() }
    }

    /**
     * Fetches (or reads the small copy from disk), decodes and downscales to at most [maxPx] on the longest side, all
     * on IO. (makeFromEncoded alone is a LAZY image: the full-size decode then happened on the render thread when it
     * first scrolled into view, and the 2000x3000 original was resampled every frame - the scrolling stutter.)
     */
    suspend fun load(url: String, maxPx: Int = TILE_PX): ImageBitmap? {
        val key = "$maxPx|$url"
        val (hit, v) = cached(key)
        if (hit) return v
        val bmp = withContext(Dispatchers.IO) {
            runCatching {
                val file = diskFile(key)
                val small = file.takeIf { it.isFile }?.let { f ->
                    runCatching { f.readBytes() }.getOrNull()?.also { f.setLastModified(System.currentTimeMillis()) }
                }
                if (small != null) toBitmap(org.jetbrains.skia.Image.makeFromEncoded(small).let { lazy -> decodeScaled(lazy, maxPx) })
                else {
                    val req = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(15)).header("User-Agent", "Mozilla/5.0").build()
                    val bytes = client.send(req, HttpResponse.BodyHandlers.ofByteArray()).body()
                    val img = decodeScaled(org.jetbrains.skia.Image.makeFromEncoded(bytes), maxPx)
                    runCatching { saveSmall(file, img) }
                    toBitmap(img)
                }
            }.getOrNull()
        }
        put(key, bmp)
        return bmp
    }

    private fun toBitmap(img: org.jetbrains.skia.Image): ImageBitmap = try { img.toComposeImageBitmap() } finally { img.close() }

    private fun diskFile(key: String): java.io.File {
        val h = java.security.MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return java.io.File(diskDir, "$h.jpg")
    }

    @Volatile private var writesSinceTrim = 0
    private fun saveSmall(file: java.io.File, img: org.jetbrains.skia.Image) {
        val data = img.encodeToData(org.jetbrains.skia.EncodedImageFormat.JPEG, 85) ?: return
        file.parentFile.mkdirs()
        val tmp = java.io.File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(data.bytes)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        if (++writesSinceTrim >= 50) { writesSinceTrim = 0; trimDisk() }
    }

    /** Keeps the disk cache under its budget (oldest use first). Also run at start-up (DiskCleanup). */
    fun trimDisk() {
        val files = diskDir.listFiles { f -> f.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) { if (total <= DISK_BUDGET) break; total -= f.length(); f.delete() }
    }

    private fun decodeScaled(src: org.jetbrains.skia.Image, maxPx: Int): org.jetbrains.skia.Image {
        try {
            val scale = minOf(1f, maxPx.toFloat() / maxOf(src.width, src.height))
            val w = maxOf(1, (src.width * scale).toInt()); val h = maxOf(1, (src.height * scale).toInt())
            val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(w, h)
            surface.canvas.drawImageRect(
                src, org.jetbrains.skia.Rect.makeWH(src.width.toFloat(), src.height.toFloat()), org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()),
                org.jetbrains.skia.SamplingMode.MITCHELL, null, true,
            )
            return surface.makeImageSnapshot().also { surface.close() }
        } finally { src.close() }
    }
}

/** Artwork sizes (longest side, pixels): tiles are ~150 dp wide, so 360 px stays sharp at 150 % display scaling. */
const val TILE_PX = 360
const val BLUR_PX = 160
const val BACKDROP_PX = 1280

@Composable
fun RemoteImage(url: String?, modifier: Modifier = Modifier, placeholderText: String = "", contentScale: ContentScale = ContentScale.Crop, maxPx: Int = TILE_PX) {
    var bmp by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    val fetchable = !url.isNullOrBlank() && url.startsWith("http")
    var loading by remember(url) { mutableStateOf(fetchable) }
    LaunchedEffect(url) { if (fetchable) { bmp = ImageCache.load(url!!, maxPx); loading = false } }
    val b = bmp
    if (b != null) {
        Image(b, null, modifier, contentScale = contentScale)
    } else if (loading) {
        // Artwork on its way: shimmer like CloudStream's poster placeholders.
        Box(modifier.background(Color(0xFF1A1A20)).shimmer()) { ShimmerBlock(Modifier.fillMaxSize(), alpha = 0.12f, corner = 0.dp) }
    } else {
        Box(
            modifier.background(Brush.linearGradient(listOf(Color(0xFF2B2B33), Color(0xFF17171C), Color(0xFF231417)))),
            contentAlignment = Alignment.TopStart,
        ) {
            // Faint monogram as "artwork"; the card's own scrim shows the readable title.
            Text(placeholderText.take(1).uppercase(), Modifier.padding(10.dp), style = MaterialTheme.typography.displaySmall,
                color = Color.White.copy(alpha = 0.12f), fontWeight = FontWeight.Black)
        }
    }
}

/**
 * Artwork tile: zooms and lifts on hover, title on a bottom scrim (artwork often has no text), quality tag top-right.
 * [progress] (0..1) draws a red watch-progress bar along the bottom (Continue watching).
 */
@Composable
fun PosterCard(
    card: MediaCard, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = 150.dp, horizontal: Boolean = false,
    progress: Float? = null, caption: String? = null,
    /** Right-click menu entries (built when it opens, so they show the current state). */
    menu: (() -> List<CardMenuItem>)? = null,
) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val scale by animateFloatAsState(if (hovered) 1.07f else 1f, tween(160))
    val w = if (horizontal) width * 1.6f else width
    val h = if (horizontal) width * 0.9f else width * 1.5f
    var menuAt by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    var menuItems by remember { mutableStateOf(emptyList<CardMenuItem>()) }
    Box(modifier.width(w)) {
    Column(Modifier.width(w)) {
        Box(
            Modifier.width(w).height(h)
                .then(if (menu == null) Modifier else Modifier.onSecondaryPress { at -> menuItems = menu(); menuAt = at })
                .zIndex(if (hovered) 1f else 0f)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .shadow(if (hovered) 18.dp else 0.dp, RoundedCornerShape(6.dp))
                .clip(RoundedCornerShape(6.dp))
                .border(if (hovered) 2.dp else 0.dp, if (hovered) Color.White.copy(alpha = 0.85f) else Color.Transparent, RoundedCornerShape(6.dp))
                .hoverable(src).clickable(onClick = onClick),
        ) {
            RemoteImage(card.posterUrl, Modifier.fillMaxSize(), placeholderText = card.name)
            Box(Modifier.fillMaxWidth().height(h * 0.45f).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f)))))
            Text(
                card.name, Modifier.align(Alignment.BottomStart).padding(start = 8.dp, end = 8.dp, bottom = if (progress != null) 10.dp else 8.dp),
                color = Color.White, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            // As CloudStream's cards: quality top-left, the score top-right ("★ 7.7").
            card.quality?.let { q ->
                Text(q, Modifier.align(Alignment.TopStart).padding(6.dp).background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                    color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            card.score?.let { sc -> ScoreBadge(sc, Modifier.align(Alignment.TopEnd).padding(6.dp)) }
            if (progress != null) {
                Box(Modifier.fillMaxWidth().height(4.dp).align(Alignment.BottomCenter).background(Color.White.copy(alpha = 0.25f)))
                Box(Modifier.fillMaxWidth(progress.coerceIn(0.02f, 1f)).height(4.dp).align(Alignment.BottomStart).background(Accent))
            }
        }
        if (caption != null) Text(caption, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
    menuAt?.let { at -> CardMenu(true, at, menuItems) { menuAt = null } }
    }
}

/** A title's score out of 10, IMDb-style yellow tag (CloudStream's rating badge on cards). */
@Composable
fun ScoreBadge(score: Double, modifier: Modifier = Modifier) {
    Text(
        "★ " + String.format(java.util.Locale.ROOT, "%.1f", score),
        modifier.background(Color(0xFFF5C518), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
        color = Color(0xFF1A1A1A), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black,
    )
}

/** Right mouse button pressed on this element: [onPress] gets the pointer position (px, local). */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Modifier.onSecondaryPress(onPress: (androidx.compose.ui.geometry.Offset) -> Unit): Modifier =
    this.onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press) { e ->
        if (e.buttons.isSecondaryPressed) {
            e.changes.firstOrNull()?.let { ch -> ch.consume(); onPress(ch.position) }
        }
    }

/** CloudStream's shimmer colours (res/values: grayShimmer #DCDCDC, base alpha 0.2, highlight alpha 0.3, 2000 ms). */
private val ShimmerGray = Color(0xFFDCDCDC)

/**
 * Facebook-style shimmer like CloudStream's ShimmerFrameLayout: one light band sweeps diagonally across everything
 * drawn inside (the skeleton shapes act as the mask), so a whole page shimmers as one surface.
 */
fun Modifier.shimmer(durationMs: Int = 2000): Modifier = composed {
    val t = androidx.compose.animation.core.rememberInfiniteTransition()
    val p by t.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(tween(durationMs, easing = androidx.compose.animation.core.LinearEasing)))
    graphicsLayer(compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen).drawWithContent {
        drawContent()
        val band = size.width * 0.3f
        val x = -band + (size.width + 2 * band) * p
        drawRect(
            Brush.linearGradient(
                listOf(Color.Transparent, ShimmerGray.copy(alpha = 0.3f), Color.Transparent),
                start = androidx.compose.ui.geometry.Offset(x - band, 0f), end = androidx.compose.ui.geometry.Offset(x + band, band * 0.5f),
            ),
            blendMode = androidx.compose.ui.graphics.BlendMode.SrcAtop,
        )
    }
}

/** One skeleton shape (base colour); put a [shimmer] on a parent to animate a whole group. */
@Composable
fun ShimmerBlock(modifier: Modifier, alpha: Float = 0.2f, corner: Dp = 6.dp) =
    Box(modifier.clip(RoundedCornerShape(corner)).background(ShimmerGray.copy(alpha = alpha)))

/** Grey placeholder tile while a row loads (instead of a spinner). */
@Composable
fun SkeletonCard(width: Dp = 150.dp, horizontal: Boolean = false) =
    ShimmerBlock(Modifier.width(if (horizontal) width * 1.6f else width).height(if (horizontal) width * 0.9f else width * 1.5f))

@Composable
fun SkeletonRow(count: Int = 8, width: Dp = 150.dp, horizontal: Boolean = false) {
    Row(Modifier.shimmer(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { repeat(count) { SkeletonCard(width, horizontal) } }
}

/**
 * CloudStream's home loading screen (fragment_home.xml `home_loading_shimmer`): a 500 dp featured block, then three
 * "loading_list" rows (short title line + a row of posters), all under one shimmer.
 */
@Composable
fun HomeLoadingShimmer(sidePad: Dp = 40.dp) {
    Column(Modifier.fillMaxSize().shimmer()) {
        ShimmerBlock(Modifier.fillMaxWidth().height(500.dp), alpha = 0.08f, corner = 0.dp) // upstream: View alpha 0.25 x base alpha 0.2
        Column(Modifier.padding(start = sidePad, end = sidePad, top = 16.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            repeat(3) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ShimmerBlock(Modifier.width(170.dp).height(22.dp), corner = 4.dp) // loading_line_short
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { repeat(10) { ShimmerBlock(Modifier.width(150.dp).height(225.dp)) } }
                }
            }
        }
    }
}

/** Row heading in the Netflix style: bold, white, optional muted trailing text. */
@Composable
fun RowTitle(text: String, trailing: String? = null) {
    Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text, style = MaterialTheme.typography.titleLarge, color = Color.White)
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
    }
}

/** White "Play"-style button. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true, leading: String? = "▶") {
    androidx.compose.material3.Button(
        onClick, enabled = enabled, shape = RoundedCornerShape(4.dp),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 22.dp, vertical = 10.dp),
    ) {
        // The default play arrow is drawn (a text "▶" sits low and off-centre next to the label).
        if (leading == "▶") { GlyphIcon(Glyph.Play, 16.dp, Color.Black); Box(Modifier.width(10.dp)) }
        Text((leading?.takeIf { it != "▶" }?.let { "$it  " } ?: "") + text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

/** Translucent grey secondary button ("More info", "My List"). */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    androidx.compose.material3.Button(
        onClick, enabled = enabled, shape = RoundedCornerShape(4.dp),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0x99606068), contentColor = Color.White),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 10.dp),
    ) { Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
}

/** Standard content padding for screens under the floating top bar. */
fun Modifier.screenPadding() = this.padding(start = 40.dp, end = 40.dp, top = TopBarHeight + 12.dp, bottom = 16.dp)

@Composable fun Spacer4() = Box(Modifier.height(4.dp))
@Composable fun Spacer8() = Box(Modifier.height(8.dp))
@Composable fun Spacer16() = Box(Modifier.height(16.dp))

@Composable
fun CenteredProgress(text: String? = null, modifier: Modifier = Modifier.fillMaxSize()) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            if (text != null) { Spacer8(); Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
fun ErrorText(message: String, modifier: Modifier = Modifier) {
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = modifier)
}

@Composable
fun Badge(text: String, color: Color = MaterialTheme.colorScheme.secondaryContainer, textColor: Color = MaterialTheme.colorScheme.onSecondaryContainer) {
    Surface(color = color, shape = RoundedCornerShape(6.dp)) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = textColor)
    }
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) { Column(Modifier.padding(18.dp)) { content() } }
}

@Composable
fun ScreenTitle(text: String, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text, style = MaterialTheme.typography.headlineMedium, color = Color.White)
        trailing()
    }
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) = Box(Modifier.size(size).clip(RoundedCornerShape(50)).background(color))

/**
 * A horizontally scrolling row with mouse-friendly scrolling: the wheel scrolls it sideways while Shift is held (and
 * trackpads / tilt wheels already do), and hovering shows ‹ › arrows at the edges that page the row.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ScrollRow(
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Box(
        modifier.hoverable(src)
        // One-handed: press on the row and drag sideways (a plain click still opens the card).
        .dragToScroll(state, scope)
        .onPointerEvent(
            androidx.compose.ui.input.pointer.PointerEventType.Scroll, androidx.compose.ui.input.pointer.PointerEventPass.Initial,
        ) { e ->
            if (e.keyboardModifiers.isShiftPressed) {
                val d = e.changes.firstOrNull()?.scrollDelta ?: return@onPointerEvent
                val amount = (if (d.x != 0f) d.x else d.y) * 90f
                e.changes.forEach { it.consume() }
                scope.launch { state.scrollBy(amount) }
            }
        },
    ) {
        androidx.compose.foundation.lazy.LazyRow(state = state, contentPadding = contentPadding, horizontalArrangement = horizontalArrangement, content = content)
        @Composable fun Arrow(glyph: Glyph, align: Alignment, enabled: Boolean, dir: Int) {
            if (hovered && enabled) Box(
                Modifier.align(align).padding(horizontal = 10.dp).size(40.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Color.Black.copy(0.72f)).border(1.dp, Color.White.copy(0.25f), androidx.compose.foundation.shape.CircleShape)
                    .clickable { scope.launch { state.animateScrollBy(dir * state.layoutInfo.viewportSize.width * 0.8f) } },
                contentAlignment = Alignment.Center,
            ) { GlyphIcon(glyph, 22.dp) }
        }
        Arrow(Glyph.ChevronLeft, Alignment.CenterStart, state.canScrollBackward, -1)
        Arrow(Glyph.ChevronRight, Alignment.CenterEnd, state.canScrollForward, 1)
    }
}

private fun Modifier.dragToScroll(state: androidx.compose.foundation.lazy.LazyListState, scope: kotlinx.coroutines.CoroutineScope): Modifier = composed {
    val drag = androidx.compose.foundation.gestures.rememberDraggableState { delta -> scope.launch { state.scrollBy(-delta) } }
    this.draggable(drag, androidx.compose.foundation.gestures.Orientation.Horizontal)
}
