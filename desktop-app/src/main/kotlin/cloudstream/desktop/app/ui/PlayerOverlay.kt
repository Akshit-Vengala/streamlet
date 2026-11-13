package cloudstream.desktop.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloudstream.desktop.app.domain.SourceLink
import cloudstream.desktop.app.domain.SubtitleItem
import cloudstream.desktop.contracts.PlayerState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import java.awt.Component
import java.awt.Window
import javax.swing.SwingUtilities

/**
 * Everything the player overlay shows and can do. Plain state + callbacks, so the overlay (which lives in its own
 * window, see [VideoOverlayHost]) and the player screen share one model.
 */
class PlayerUi {
    var title by mutableStateOf("")
    var episodeLine by mutableStateOf("")
    var state by mutableStateOf(PlayerState(0, 0, false, false, null))
    var sources by mutableStateOf(emptyList<SourceLink>())
    var current by mutableStateOf<SourceLink?>(null)
    var failed by mutableStateOf(emptySet<String>())
    var stillSearching by mutableStateOf(false)
    var subtitles by mutableStateOf(emptyList<SubtitleItem>())
    /** The current stream's own subtitle tracks (polled from the player). */
    var embeddedSubs by mutableStateOf(emptyList<cloudstream.desktop.player.MediaTrack>())
    /** Selected subtitle: an external one's url, [EMBEDDED_SUB_KEY] + track id for the stream's own, null = off. */
    var selectedSub by mutableStateOf<String?>(null)
    var raw by mutableStateOf(false)
    var hasNext by mutableStateOf(false)
    var fullscreen by mutableStateOf(false)
    var speed by mutableStateOf(1.0)
    var volume by mutableStateOf(100)
    var resizeMode by mutableStateOf(0) // 0 fit, 1 fill (crop), 2 stretch
    /** Rewind / fast-forward amounts (Settings > Playback). */
    var seekBackMs by mutableStateOf(10_000L)
    var seekForwardMs by mutableStateOf(10_000L)
    /** Subtitle offset in seconds (mpv sub-delay). */
    var subDelay by mutableStateOf(0.0)
    var onSubDelay: (Double) -> Unit = {}
    /** The lines of the subtitle selected under [selectedSub] (blocking; null = not readable, e.g. the stream's own). */
    var subtitleCues: (String) -> List<SubCue>? = { null }
    /** Set while the subtitle sync screen is open: Esc = its Cancel. */
    var onSyncCancel: (() -> Unit)? = null
    /** Raw mpv command for keyboard shortcuts (mute, frame-step, track cycling, audio delay). */
    var onMpv: (List<String>) -> Unit = {}
    var muted by mutableStateOf(false)
    var audioDelay by mutableStateOf(0.0)
    /** Loading screen (no source chosen yet): lookup result message, and "Skip loading" = play the best found so far. */
    var loadMessage by mutableStateOf<String?>(null)
    /** The file's audio streams (polled off the UI thread); more than one adds the Audio button. */
    var audioList by mutableStateOf(emptyList<cloudstream.desktop.player.MediaTrack>())
    /** Quality variants of the current source (HLS/DASH); more than one adds the Video button. */
    var videoList by mutableStateOf(emptyList<cloudstream.desktop.player.MediaTrack>())
    var onVideoTrack: (Int) -> Unit = {}
    var onSkipLoading: () -> Unit = {}
    /** Source priority (Settings, per profile), edited from the Sources panel's gear. */
    var priority by mutableStateOf(emptyList<String>())
    /** Every source name seen so far (offered for ranking). */
    var knownSources by mutableStateOf(emptyList<String>())
    /** Moves a source by delta in the priority list (0 = add it at the end). */
    var onMoveSource: (String, Int) -> Unit = { _, _ -> }
    var onUnrankSource: (String) -> Unit = {}
    /** Hidden sources / servers (never listed or played); a name is a whole source or one server ("Source [Server 1]"). */
    var hiddenSources by mutableStateOf(emptyList<String>())
    var onHideSource: (String) -> Unit = {}
    var onUnhideSource: (String) -> Unit = {}

    var onBack: () -> Unit = {}
    var onNext: () -> Unit = {}
    /** The intro / recap / credits part playing now (its skip button shows), or null. */
    var skip by mutableStateOf<cloudstream.desktop.app.SkipSegment?>(null)
    var onSkip: () -> Unit = {}
    /** Seconds until the next episode starts by itself (end of an episode), or null. */
    var autoNextIn by mutableStateOf<Int?>(null)
    /** The credits are on (or the episode is about over): offer "Next episode" over the video. */
    var nextPrompt by mutableStateOf(false)
    var onCancelAutoNext: () -> Unit = {}
    var hasPrev by mutableStateOf(false)
    /** Repeat the current file (VLC's L). */
    var loop by mutableStateOf(false)
    var onPrev: () -> Unit = {}
    /** Temporary speed while a hold-for-2x lasts (not saved); null = back to [speed]. */
    var onHoldSpeed: (Double?) -> Unit = {}
    /** True while a hold-for-2x is active (shown as a badge). */
    var holding by mutableStateOf(false)
    var onQuit: () -> Unit = {}
    var onStop: () -> Unit = {}
    var onScreenshot: () -> Unit = {}
    /** Asks the player to flip pause. The UI calls [togglePlay], which also updates the icon at once. */
    var onPlayPause: () -> Unit = {}

    @Volatile private var expectPlaying: Boolean? = null
    @Volatile private var expectUntil = 0L

    /** Play/pause with an immediate icon change; the player's own report follows within a poll or two. */
    fun togglePlay() {
        val want = !state.playing
        expectPlaying = want; expectUntil = System.currentTimeMillis() + 1000
        state = state.copy(playing = want)
        onPlayPause()
    }

    /** Player report -> UI. Right after [togglePlay], a report still showing the old state does not flip the icon back. */
    fun applyPlayerState(raw: PlayerState) {
        val exp = expectPlaying
        state = if (exp != null && System.currentTimeMillis() < expectUntil && raw.playing != exp && !raw.buffering) raw.copy(playing = exp)
                else { expectPlaying = null; raw }
    }
    var onSeek: (Long) -> Unit = {}
    /** Relative seek (±10 s, Skip OP, arrow keys): done by the player, not computed from [state]. */
    var onSeekBy: (Long) -> Unit = {}
    /** Sources sheet "Apply": a new source (or null), and when [subChanged] the picked subtitle key (see [selectedSub]). */
    var onApply: (source: SourceLink?, subChanged: Boolean, subKey: String?) -> Unit = { _, _, _ -> }
    var onLoadSubtitleFile: () -> Unit = {}
    var onOnlineSubtitles: () -> Unit = {}
    var onFullscreen: () -> Unit = {}
    var onSpeed: (Double) -> Unit = {}
    var onVolume: (Int) -> Unit = {}
    var onResize: (Int) -> Unit = {}
    var audioTracks: () -> List<cloudstream.desktop.player.MediaTrack> = { emptyList() }
    var subtitleTracks: () -> List<cloudstream.desktop.player.MediaTrack> = { emptyList() }
    var onAudioTrack: (Int) -> Unit = {}
    var onSubtitleTrack: (Int?) -> Unit = {}
    /** Extra dialog content shown on top of the controls (online subtitle search). */
    var extraDialog: (@Composable () -> Unit)? by mutableStateOf(null)
}

/**
 * The video is a native (heavyweight) surface: nothing drawn by Compose in the main window can cover it. The controls
 * therefore live in a transparent, undecorated window owned by the main window and kept exactly over the video.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun VideoOverlayHost(anchor: Component?, toasts: Flow<String>, ui: PlayerUi) {
    DisposableEffect(anchor) {
        if (anchor == null) return@DisposableEffect onDispose { }
        val owner: Window? = SwingUtilities.getWindowAncestor(anchor)
        val dialog = ComposeDialog(owner, java.awt.Dialog.ModalityType.MODELESS).apply {
            isUndecorated = true
            isTransparent = true
            focusableWindowState = true
            title = "CloudStream player controls"
            setContent { CloudTheme { PlayerControls(ui, toasts) } }
        }
        // Keep the overlay glued to the video surface (moves, resizes, fullscreen, minimise).
        var last = java.awt.Rectangle()
        val timer = javax.swing.Timer(80) {
            val showing = anchor.isShowing && owner?.isShowing == true &&
                ((owner as? java.awt.Frame)?.extendedState?.and(java.awt.Frame.ICONIFIED) ?: 0) == 0
            if (showing) {
                val r = java.awt.Rectangle(anchor.locationOnScreen, anchor.size)
                if (r != last) { last = r; dialog.bounds = r }
                if (!dialog.isVisible) dialog.isVisible = true
            } else if (dialog.isVisible) dialog.isVisible = false
        }.apply { start() }
        onDispose { timer.stop(); dialog.isVisible = false; dialog.dispose() }
    }
}

private val Red = Color(0xFFE50914)

/** [PlayerUi.selectedSub] prefix for the stream's own subtitle tracks. */
const val EMBEDDED_SUB_KEY = "#track:"

/** Case-insensitive "natural" order ("English 2" before "English 10"). */
val naturalOrder: Comparator<String> = Comparator { a, b ->
    val re = Regex("\\d+|\\D+")
    val x = re.findAll(a.lowercase()).map { it.value }.toList(); val y = re.findAll(b.lowercase()).map { it.value }.toList()
    for (i in 0 until minOf(x.size, y.size)) {
        val p = x[i]; val q = y[i]
        val c = if (p[0].isDigit() && q[0].isDigit()) p.trimStart('0').length.compareTo(q.trimStart('0').length).takeIf { it != 0 } ?: p.trimStart('0').compareTo(q.trimStart('0'))
                else p.compareTo(q)
        if (c != 0) return@Comparator c
    }
    x.size.compareTo(y.size)
}

/** The subtitle list as shown: sorted by language (natural order), url as tie-breaker for a stable order. */
fun sortedSubtitles(list: List<SubtitleItem>): List<SubtitleItem> =
    list.sortedWith(compareBy(naturalOrder) { it: SubtitleItem -> it.lang }.thenBy { it.url })

/**
 * One row of the subtitle list: entries with exactly the same name (trimmed, case-insensitive) share a row and are
 * told apart by number (1, 2, 3...); a differently named entry - even a near-identical one - keeps its own row.
 * [keys] are the selection keys in display order.
 */
data class SubtitleGroup(val name: String, val keys: List<String>)

fun groupSameNamed(entries: List<Pair<String, String>>): List<SubtitleGroup> {
    val byName = LinkedHashMap<String, Pair<String, MutableList<String>>>()
    for ((name, key) in entries) byName.getOrPut(name.trim().lowercase()) { name.trim() to ArrayList() }.second += key
    return byName.values.map { (name, keys) -> SubtitleGroup(name, keys) }
}

/** The visible player's key handler; the main window offers every key to it first. */
object PlayerKeys {
    /** (key, shift, alt, ctrl) of a key press -> handled. */
    @Volatile var handler: ((Key, Boolean, Boolean, Boolean) -> Boolean)? = null
    /** A key release (Space: tap = pause, hold = 2x) -> handled. */
    @Volatile var keyUp: ((Key) -> Boolean)? = null
}

private const val HOLD_SPEED = 2.0
private const val HOLD_MS = 400L

/** CloudStream-style player controls: top bar, centre transport, bottom seek bar + action row; auto-hide. */
@OptIn(ExperimentalComposeUiApi::class)
/** CloudStream-style player controls; drawn on top of [VideoSurface] in the same window. */
@Composable
fun PlayerControls(ui: PlayerUi, toasts: Flow<String>) {
    var visible by remember { mutableStateOf(true) }
    var lastActivity by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var dialog by remember { mutableStateOf<String?>(null) } // "sources", "speed", "sync"
    var flash by remember { mutableStateOf<String?>(null) }   // short centre message (resize mode, volume, seek)
    val snack = remember { SnackbarHostState() }
    val focus = remember { FocusRequester() }
    fun poke() { visible = true; lastActivity = System.currentTimeMillis() }
    val back = ui.seekBackMs; val fwd = ui.seekForwardMs

    // Keys come from the WINDOW (Main.kt), not from this box: focus moves to buttons/panels after clicks, and keys
    // then never reached a focus-based handler (arrows, Space "did nothing").
    // Hold for 2x (YouTube / CloudStream): Space or the mouse held on the video plays at 2x until released.
    // A quick Space tap still pauses, on release (so a hold is not also a pause).
    var spaceDownAt by remember { mutableLongStateOf(0L) }
    var mouseHoldEnded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun startHold() { if (!ui.holding && ui.current != null) { ui.holding = true; ui.onHoldSpeed(HOLD_SPEED) } }
    fun endHold() { if (ui.holding) { ui.holding = false; ui.onHoldSpeed(null) } }
    LaunchedEffect(spaceDownAt) { if (spaceDownAt != 0L) { delay(HOLD_MS); if (spaceDownAt != 0L) startHold() } }
    DisposableEffect(ui) { onDispose { endHold() } }
    // Alt+Tab while holding: the key release never arrives, so the hold ends when the window loses focus.
    val windowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused) { if (!windowFocused) { spaceDownAt = 0L; endHold() } }

    DisposableEffect(ui) {
        val handler: (Key, Boolean, Boolean, Boolean) -> Boolean = handler@{ key, shift, alt, ctrl ->
            if (ui.extraDialog != null) return@handler false // a text field (online subtitle search) has the keys
            // Subtitle sync: its ms field takes digits and "-"; Esc cancels (handled by the dialog itself).
            if (dialog == "sync") return@handler if (key == Key.Escape) { ui.onSyncCancel?.invoke() ?: run { dialog = null }; true } else false
            if (dialog == "goto") return@handler if (key == Key.Escape) { dialog = null; true } else false // its time field types
            poke()
            if (key == Key.Spacebar && ui.current != null) {
                if (spaceDownAt == 0L) spaceDownAt = System.currentTimeMillis() // auto-repeat while held: ignored
                return@handler true
            }
            handlePlayerKey(key, shift, alt, ctrl, ui, ui.seekBackMs, ui.seekForwardMs, onFlash = { flash = it }, onDialog = { dialog = it }, dialogOpen = dialog != null)
        }
        val up: (Key) -> Boolean = up@{ key ->
            if (key != Key.Spacebar || spaceDownAt == 0L) return@up false
            spaceDownAt = 0L
            if (ui.holding) endHold() else ui.togglePlay()
            true
        }
        PlayerKeys.handler = handler
        PlayerKeys.keyUp = up
        onDispose {
            if (PlayerKeys.handler === handler) PlayerKeys.handler = null
            if (PlayerKeys.keyUp === up) PlayerKeys.keyUp = null
        }
    }
    LaunchedEffect(Unit) { toasts.collect { snack.showSnackbar(it) } }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // Fade out after 3 s without mouse movement while playing and no sheet open; the pointer hides with them.
    val sheetOpen = dialog != null || ui.extraDialog != null
    LaunchedEffect(visible, lastActivity, sheetOpen, ui.state.playing) {
        if (visible && !sheetOpen && ui.state.playing) { delay(1500); visible = false }
    }
    LaunchedEffect(flash) { if (flash != null) { delay(1200); flash = null } }
    val alpha by animateFloatAsState(if (visible || sheetOpen) 1f else 0f, tween(300))
    val hideCursor = !visible && !sheetOpen

    Box(
        Modifier.fillMaxSize()
            // Fully transparent pixels would let mouse events fall through to the video window: keep a hint of alpha.
            .background(Color.Black.copy(alpha = 0.012f))
            .pointerHoverIcon(if (hideCursor) BlankPointer else PointerIcon.Default)
            .focusRequester(focus)
            .focusable()
            .onPointerEvent(PointerEventType.Move) { poke() }
            .onPointerEvent(PointerEventType.Scroll) { ev ->
                // Scrolling a list in a panel (sources, subtitles) also reaches this box: that lowered the volume.
                if (dialog != null || ui.extraDialog != null || ui.current == null || ev.changes.any { it.isConsumed }) return@onPointerEvent
                val dy = ev.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                if (dy == 0f) return@onPointerEvent
                ui.onVolume((ui.volume - (dy * 5).toInt()).coerceIn(0, 130)); flash = "Volume ${ui.volume}%"; poke()
            }
            // Drag up / down on the right half: volume (CloudStream's swipe gesture); a full-height drag = 150 %.
            .pointerInput(Unit) {
                var active = false; var startVol = 0; var acc = 0f
                detectVerticalDragGestures(
                    onDragStart = { at -> active = at.x > size.width / 2f && dialog == null && ui.extraDialog == null && ui.current != null; startVol = ui.volume; acc = 0f },
                    onVerticalDrag = { change, dy ->
                        if (active) {
                            change.consume(); acc += dy
                            val v = (startVol - (acc / size.height * 150f).toInt()).coerceIn(0, 130)
                            if (v != ui.volume) ui.onVolume(v)
                            flash = "Volume ${ui.volume}%"; poke()
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        if (dialog != null || ui.extraDialog != null || ui.current == null) return@detectTapGestures
                        val hold = scope.launch { delay(HOLD_MS); startHold() }
                        tryAwaitRelease()
                        hold.cancel()
                        if (ui.holding) { endHold(); mouseHoldEnded = true } // a hold is not also a tap
                    },
                    onTap = {
                        if (mouseHoldEnded) { mouseHoldEnded = false; return@detectTapGestures }
                        visible = !visible; lastActivity = System.currentTimeMillis()
                    },
                    onDoubleTap = { ui.onFullscreen() },
                )
            },
    ) {
        val st = ui.state
        val loading = ui.current == null
        // Buffering over the video (controls hidden; with them shown the centre button turns into the spinner).
        if (st.buffering && alpha < 0.5f && !loading) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(Modifier.size(56.dp), color = Red)
            Text(st.bufferPercent?.let { "Buffering $it%" } ?: "Buffering...", color = Color.White, fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(0.5f)).padding(horizontal = 8.dp, vertical = 3.dp))
        }

        if (loading) LoadingSources(ui) { dialog = "sources" }
        else if (alpha > 0.01f) Box(Modifier.fillMaxSize().alpha(alpha)) {
            // Scrims
            Box(Modifier.fillMaxWidth().height(140.dp).align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(Color.Black.copy(0.75f), Color.Transparent))))
            Box(Modifier.fillMaxWidth().height(190.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.85f)))))

            // Top bar
            Row(Modifier.fillMaxWidth().align(Alignment.TopStart).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayerIconButton("←") { ui.onBack() }
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(ui.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (ui.episodeLine.isNotBlank()) Text(ui.episodeLine, color = Color(0xFFD6D6DC), fontSize = 13.sp, maxLines = 1)
                    ui.current?.let { cur ->
                        Text("${cur.name.ifBlank { cur.source }}  ·  ${cur.qualityLabel}", color = Color(0xFFA0A0AA), fontSize = 12.sp, maxLines = 1)
                    }
                }
                PlayerIconButton(if (ui.fullscreen) "⤡  Exit full screen" else "⤢  Full screen") { ui.onFullscreen() }
            }

            // Centre transport (seek amounts from Settings)
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundButton("↺\n${back / 1000}", 64) { ui.onSeekBy(-back); poke() }
                if (st.buffering) Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(64.dp), color = Red)
                    st.bufferPercent?.let { Text("$it%", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                }
                else RoundButton(if (st.playing) "❚❚" else "▶", 84, glyph = if (st.playing) Glyph.Pause else Glyph.Play) { ui.togglePlay(); poke() }
                RoundButton("↻\n${fwd / 1000}", 64) { ui.onSeekBy(fwd); poke() }
            }

            // Bottom: seek bar + centred action row (grows outward from the middle)
            Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 12.dp)) {
                var drag by remember { mutableStateOf<Float?>(null) }
                val dur = st.durationMs.coerceAtLeast(1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(fmtTime(drag?.let { (it * dur).toLong() } ?: st.positionMs), color = Color.White, fontSize = 13.sp)
                    Slider(
                        value = drag ?: (st.positionMs.toFloat() / dur).coerceIn(0f, 1f),
                        onValueChange = { drag = it; poke() },
                        onValueChangeFinished = { drag?.let { ui.onSeek((it * dur).toLong()) }; drag = null },
                        enabled = st.durationMs > 0,
                        colors = SliderDefaults.colors(thumbColor = Red, activeTrackColor = Red, inactiveTrackColor = Color.White.copy(0.3f)),
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    )
                    Text("-" + fmtTime((st.durationMs - st.positionMs).coerceAtLeast(0)), color = Color.White, fontSize = 13.sp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                    val modes = listOf("Fit", "Fill", "Stretch")
                    PlayerIconButton("⬚  Resize: ${modes[ui.resizeMode]}") {
                        val next = (ui.resizeMode + 1) % 3; ui.onResize(next); flash = modes[next]; poke()
                    }
                    PlayerIconButton("⏲  Speed ${"%.2f".format(ui.speed).trimEnd('0').trimEnd('.')}x") { dialog = "speed" }
                    if (ui.selectedSub != null) PlayerIconButton("⇄  Sync subs") { dialog = "sync" }
                    PlayerIconButton("☰  Source") { dialog = "sources" }
                    if (ui.videoList.size > 1) PlayerIconButton("▣  Video: ${ui.videoList.firstOrNull { it.selected }?.title?.substringBefore(" ·") ?: "Auto"}") { dialog = "video" }
                    if (ui.audioList.size > 1) PlayerIconButton("♪  Audio") { dialog = "audio" }
                    ui.skip?.let { seg -> PlayerIconButton("⏩  ${seg.kind.label}") { ui.onSkip() } }
                    if (ui.hasNext) PlayerIconButton("⏭  Next episode") { ui.onNext() }
                }
                st.error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp, maxLines = 2, modifier = Modifier.align(Alignment.CenterHorizontally)) }
            }
        }

        flash?.let {
            Text(it, Modifier.align(Alignment.Center).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(0.7f)).padding(horizontal = 18.dp, vertical = 10.dp),
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        // Skip intro / credits and Next episode: bottom-right over the video, shown even with the controls hidden.
        if (!loading) Row(
            Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = if (alpha > 0.5f) 150.dp else 48.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            ui.skip?.let { seg ->
                // Fades away 5 s after it appears (restarts for each new segment); the bottom-bar button stays until the segment ends.
                var faded by remember(seg) { mutableStateOf(false) }
                LaunchedEffect(seg) { delay(5_000); faded = true }
                val a by animateFloatAsState(if (faded) 0f else 1f, tween(600))
                if (a > 0f) Box(Modifier.alpha(a)) { PromptButton(seg.kind.label + "  ›", primary = false) { ui.onSkip() } }
            }
            val next = ui.autoNextIn
            if (ui.hasNext && next != null) {
                PromptButton("Cancel", primary = false) { ui.onCancelAutoNext() }
                PromptButton("Next episode in $next  ›", primary = true) { ui.onNext() }
            } else if (ui.hasNext && ui.nextPrompt) PromptButton("Next episode  ›", primary = true) { ui.onNext() }
        }
        if (ui.holding) {
            Text("2x  ▶▶", Modifier.align(Alignment.TopCenter).padding(top = 24.dp).clip(RoundedCornerShape(20.dp))
                .background(Color.Black.copy(0.65f)).padding(horizontal = 16.dp, vertical = 6.dp),
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }

        when (dialog) {
            "sources" -> SourcesDialog(ui) { dialog = null }
            "speed" -> SpeedDialog(ui) { dialog = null }
            "goto" -> GotoTimeDialog(ui) { dialog = null }
            "sync" -> SyncSubsDialog(ui) { dialog = null }
            "keys" -> KeysDialog(ui) { dialog = null }
            "audio" -> AudioDialog(ui) { dialog = null }
            "video" -> VideoDialog(ui) { dialog = null }
        }
        ui.extraDialog?.invoke()
        // Toasts sit just above the seek bar (bottom bar = action row + seek bar, ~110 dp).
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 150.dp)) { SnackbarHost(snack) }
    }
}

/**
 * CloudStream's player loading screen: the player is open while the extension looks for links; "Skip loading" (with
 * the live count) plays the best link by priority right away, otherwise the best one starts when the lookup ends.
 */
@Composable
private fun LoadingSources(ui: PlayerUi, onSources: () -> Unit) {
    val found = ui.sources.size
    val playable = ui.sources.count { it.playable }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().align(Alignment.TopStart).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            PlayerIconButton("←") { ui.onBack() }
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(ui.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (ui.episodeLine.isNotBlank()) Text(ui.episodeLine, color = Color(0xFFD6D6DC), fontSize = 13.sp, maxLines = 1)
            }
        }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (ui.stillSearching) CircularProgressIndicator(Modifier.size(56.dp), color = Red)
            Text(
                if (ui.stillSearching) "Loading sources..." else ui.loadMessage ?: "Loading sources...",
                color = Color.White, fontSize = 16.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Always there while nothing plays; the number is every source gathered so far (live).
                Box(
                    Modifier.clip(RoundedCornerShape(6.dp)).background(if (playable > 0) Red else Color(0xFF3A3A40))
                        .clickable(enabled = playable > 0) { ui.onSkipLoading() }.padding(horizontal = 22.dp, vertical = 12.dp),
                ) { Text("Skip loading ($found)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
                if (found > 0) PlayerIconButton("☰  Sources") { onSources() }
            }
        }
    }
}

/**
 * VLC's default player hotkeys (plus the user's own rewind/fast-forward step on the plain arrows).
 * Returns true when the key was handled.
 */
private fun handlePlayerKey(
    key: Key, shift: Boolean, alt: Boolean, ctrl: Boolean, ui: PlayerUi, back: Long, fwd: Long,
    onFlash: (String) -> Unit, onDialog: (String?) -> Unit, dialogOpen: Boolean,
): Boolean {
    // Loading screen: nothing to control yet (Space would leave the coming video paused).
    if (ui.current == null && key != Key.Escape && key != Key.F && key != Key.F1) return false
    fun seek(ms: Long) { ui.onSeekBy(ms); onFlash((if (ms > 0) "+" else "-") + fmtSpan(Math.abs(ms))) }
    fun jump(dir: Int): Long = when {
        ctrl && alt -> 300_000L   // VLC long jump: 5 min
        ctrl -> 60_000L           // medium jump: 1 min
        alt -> 10_000L            // short jump: 10 s
        shift -> 3_000L           // very short jump: 3 s
        else -> if (dir < 0) back else fwd // plain arrows: the user's own steps (Settings)
    }
    val speeds = listOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 3.0, 4.0)
    fun speedStep(d: Int) {
        val i = speeds.indexOfFirst { it >= ui.speed - 1e-6 }.let { if (it < 0) speeds.lastIndex else it }
        val s = speeds[(i + d).coerceIn(0, speeds.lastIndex)]; ui.onSpeed(s); onFlash("Speed ${s}x")
    }
    fun fineSpeed(d: Double) {
        val s = (Math.round((ui.speed + d) * 100) / 100.0).coerceIn(0.1, 4.0); ui.onSpeed(s); onFlash("Speed ${fmtSpeed(s)}x")
    }
    fun volume(d: Int) { ui.onVolume((ui.volume + d).coerceIn(0, 130)); onFlash("Volume ${ui.volume}%") }
    when (key) {
        Key.Spacebar, Key.MediaPlayPause -> ui.togglePlay()
        Key.MediaPlay -> if (!ui.state.playing) ui.togglePlay()
        Key.MediaPause -> if (ui.state.playing) ui.togglePlay()
        Key.DirectionLeft -> seek(-jump(-1))
        Key.DirectionRight -> seek(jump(1))
        Key.DirectionUp -> volume(5)
        Key.DirectionDown -> volume(-5)
        Key.M -> { ui.muted = !ui.muted; ui.onMpv(listOf("set", "mute", if (ui.muted) "yes" else "no")); onFlash(if (ui.muted) "Muted" else "Sound on") }
        Key.F, Key.F11 -> ui.onFullscreen()
        Key.Escape -> if (dialogOpen) onDialog(null) else if (ui.fullscreen) ui.onFullscreen() else ui.onBack()
        // VLC: - / + step through the preset speeds, [ / ] change it finely (0.1x), = back to normal.
        Key.Minus, Key.NumPadSubtract -> speedStep(-1)
        Key.Plus, Key.NumPadAdd -> speedStep(1)
        Key.LeftBracket -> fineSpeed(-0.1)
        Key.RightBracket -> fineSpeed(0.1)
        Key.Equals -> if (shift) speedStep(1) /* "+" on the main row */ else { ui.onSpeed(1.0); onFlash("Speed 1x") }
        Key.E -> { ui.onMpv(listOf("frame-step")); onFlash("Next frame") }
        Key.V -> { ui.onMpv(listOf("cycle", "sid", if (shift || alt) "down" else "up")); onFlash("Subtitle track") }
        Key.B -> { ui.onMpv(listOf("cycle", "aid", if (shift || alt) "down" else "up")); onFlash("Audio track") }
        Key.S -> when {
            shift -> ui.onScreenshot()                 // VLC: Shift+S snapshot
            ctrl || alt -> return false
            else -> ui.onStop()                        // VLC: S stop
        }
        Key.MediaStop -> ui.onStop()
        Key.P, Key.MediaPrevious -> if (ui.hasPrev) ui.onPrev() else onFlash("No previous episode")
        Key.MediaNext -> if (ui.hasNext) ui.onNext()
        Key.L -> { ui.loop = !ui.loop; ui.onMpv(listOf("set", "loop-file", if (ui.loop) "inf" else "no")); onFlash(if (ui.loop) "Repeat: on" else "Repeat: off") }
        Key.PageUp -> { ui.onMpv(listOf("add", "chapter", "1")); onFlash("Next chapter") }
        Key.PageDown -> { ui.onMpv(listOf("add", "chapter", "-1")); onFlash("Previous chapter") }
        Key.Q -> if (ctrl) ui.onQuit() else return false
        Key.W -> if (ctrl) ui.onBack() else return false
        Key.MoveEnd -> if (ui.state.durationMs > 0) ui.onSeek((ui.state.durationMs - 5_000).coerceAtLeast(0))
        Key.C, Key.Z -> { val m = if (key == Key.C) (if (ui.resizeMode == 1) 0 else 1) else (ui.resizeMode + 1) % 3; ui.onResize(m); onFlash(listOf("Fit", "Fill", "Stretch")[m]) }
        Key.G -> { ui.onSubDelay(ui.subDelay - 0.05); onFlash("Subtitle delay %+.0f ms".format(ui.subDelay * 1000)) }
        Key.H -> { ui.onSubDelay(ui.subDelay + 0.05); onFlash("Subtitle delay %+.0f ms".format(ui.subDelay * 1000)) }
        Key.J -> { ui.audioDelay -= 0.05; ui.onMpv(listOf("set", "audio-delay", "%.3f".format(java.util.Locale.ROOT, ui.audioDelay))); onFlash("Audio delay %+.0f ms".format(ui.audioDelay * 1000)) }
        Key.K -> { ui.audioDelay += 0.05; ui.onMpv(listOf("set", "audio-delay", "%.3f".format(java.util.Locale.ROOT, ui.audioDelay))); onFlash("Audio delay %+.0f ms".format(ui.audioDelay * 1000)) }
        Key.A -> { val m = (ui.resizeMode + 1) % 3; ui.onResize(m); onFlash(listOf("Fit", "Fill", "Stretch")[m]) }
        Key.T -> if (ctrl) onDialog("goto") /* VLC: Ctrl+T jump to time */ else onFlash("${fmtTime(ui.state.positionMs)} / ${fmtTime(ui.state.durationMs)}")
        Key.N -> if (ui.hasNext) ui.onNext()
        Key.MoveHome -> ui.onSeek(0)
        Key.F1 -> onDialog("keys")
        else -> {
            if (key == Key.Slash && shift) { onDialog("keys"); return true } // "?"
            // 0-9: jump to 0 %, 10 % ... 90 % (as on YouTube; VLC leaves them unused).
            val digit = digitKeys.indexOf(key).takeIf { it >= 0 }?.rem(10)
            if (digit != null && !ctrl && !alt && ui.state.durationMs > 0) {
                ui.onSeek(ui.state.durationMs * digit / 10); onFlash("${digit * 10} %"); return true
            }
            return false
        }
    }
    return true
}

private val digitKeys = listOf(
    Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine,
    Key.NumPad0, Key.NumPad1, Key.NumPad2, Key.NumPad3, Key.NumPad4, Key.NumPad5, Key.NumPad6, Key.NumPad7, Key.NumPad8, Key.NumPad9,
)

private fun fmtSpeed(s: Double): String = if (s == Math.floor(s)) s.toInt().toString() else "%.2f".format(java.util.Locale.ROOT, s).trimEnd('0')

private fun fmtSpan(ms: Long): String = if (ms >= 60_000) "${ms / 60_000} min" else "${ms / 1000} s"

/** "Ctrl+T" (VLC's Jump to time): type 1:23:45, 23:45 or 45 (seconds) and press Enter. */
@Composable
private fun GotoTimeDialog(ui: PlayerUi, onClose: () -> Unit) {
    var text by remember { mutableStateOf(fmtTime(ui.state.positionMs)) }
    val focus = remember { FocusRequester() }
    fun parse(s: String): Long? {
        val parts = s.trim().split(':').map { it.trim().toLongOrNull() ?: return null }
        if (parts.isEmpty() || parts.size > 3) return null
        return parts.fold(0L) { acc, p -> acc * 60 + p } * 1000
    }
    fun go() { parse(text)?.let { ms -> ui.onSeek(ms.coerceIn(0, ui.state.durationMs.takeIf { it > 0 } ?: ms)); onClose() } }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    OverlayPanel(onClose, 360, heightFraction = 0.32f) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text("Jump to time", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("of ${fmtTime(ui.state.durationMs)}", color = Color(0xFF9A9AA2), fontSize = 13.sp)
            Spacer(Modifier.height(14.dp))
            androidx.compose.foundation.text.BasicTextField(
                value = text,
                onValueChange = { v -> text = v.filter { it.isDigit() || it == ':' }.take(9) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 24.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { go() }),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                modifier = Modifier.width(200.dp).focusRequester(focus).padding(vertical = 6.dp)
                    .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) { go(); true } else false },
            )
            Box(Modifier.width(200.dp).height(1.dp).background(Color(0xFFAAAAAA)))
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SyncAction("Go", primary = true) { go() }
                SyncAction("Cancel") { onClose() }
            }
        }
    }
}

/** The shortcut list (F1 or ?). */
@Composable
private fun KeysDialog(ui: PlayerUi, onClose: () -> Unit) {
    val rows = listOf(
        "Space" to "Play / pause (tap)", "Hold Space, hold mouse on video" to "2x while held",
        "F, F11, double-click" to "Full screen", "Esc" to "Close panel / leave full screen / back",
        "Left / Right" to "Rewind / forward ${ui.seekBackMs / 1000} s / ${ui.seekForwardMs / 1000} s (Settings)",
        "Shift + Left / Right" to "3 s", "Alt + Left / Right" to "10 s", "Ctrl + Left / Right" to "1 min",
        "Ctrl + Alt + Left / Right" to "5 min", "Ctrl + T" to "Jump to time", "0 - 9" to "Jump to 0 % - 90 %",
        "Home / End" to "Start / end", "Page Up / Down" to "Next / previous chapter",
        "Up / Down, mouse wheel" to "Volume", "M" to "Mute",
        "- / +" to "Slower / faster (steps)", "[ / ]" to "Slower / faster (0.1x)", "=" to "Normal speed",
        "E" to "Next frame", "V / Shift+V" to "Next / previous subtitle track", "B / Shift+B" to "Next / previous audio track",
        "G / H" to "Subtitles 50 ms earlier / later", "J / K" to "Audio 50 ms earlier / later",
        "A, Z" to "Resize: fit / fill / stretch", "C" to "Crop (fill) on / off", "T" to "Show time",
        "N / P" to "Next / previous episode", "L" to "Repeat on / off", "S" to "Stop (back to the title)",
        "Shift + S" to "Snapshot (Pictures/Streamlet)", "Ctrl + W" to "Close the player", "Ctrl + Q" to "Quit",
        "Media keys" to "Play/pause, stop, next, previous", "F1 or ?" to "This list",
    )
    OverlayPanel(onClose, 620) {
        Column {
            Text("Keyboard shortcuts", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(10.dp))
            LazyColumn {
                items(rows) { (k, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Text(k, Modifier.width(230.dp), color = Color(0xFFE6E6EA), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(v, color = Color(0xFFB0B0B8), fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

/** An invisible mouse pointer (controls hidden). */
private val BlankPointer: PointerIcon by lazy {
    PointerIcon(java.awt.Toolkit.getDefaultToolkit().createCustomCursor(
        java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB), java.awt.Point(0, 0), "blank"))
}

/**
 * CloudStream's subtitle delay screen: the subtitle's lines on the left (the one on screen now in white with a bar
 * showing how far into it playback is; clicking a line makes it show now), on the right the delay in ms with
 * ‹ / › = 1 s and − / + = 100 ms, and Apply / Reset / Cancel. Changes apply live; Cancel restores the old delay.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SyncSubsDialog(ui: PlayerUi, onClose: () -> Unit) {
    val original = remember { Math.round(ui.subDelay * 1000) }
    var ms by remember { mutableLongStateOf(original) }
    var text by remember { mutableStateOf(original.toString()) }
    fun set(v: Long, fromField: Boolean = false) {
        ms = v; if (!fromField) text = v.toString()
        ui.onSubDelay(v / 1000.0)
    }
    fun cancel() { ui.onSubDelay(original / 1000.0); onClose() }
    DisposableEffect(Unit) { ui.onSyncCancel = { cancel() }; onDispose { ui.onSyncCancel = null } }

    val key = ui.selectedSub
    val cues by androidx.compose.runtime.produceState<List<SubCue>?>(null, key) {
        value = key?.let { k -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { ui.subtitleCues(k) }.getOrNull() } } ?: emptyList()
    }
    // Playback position, smoothed between player reports (they come a few times a second).
    var now by remember { mutableLongStateOf(ui.state.positionMs) }
    LaunchedEffect(Unit) {
        var basePos = ui.state.positionMs; var baseAt = System.nanoTime()
        while (true) {
            androidx.compose.runtime.withFrameNanos { frame ->
                val st = ui.state
                if (st.positionMs != basePos) { basePos = st.positionMs; baseAt = frame }
                now = if (st.playing && !st.buffering) basePos + ((frame - baseAt) / 1_000_000 * ui.speed).toLong().coerceIn(0, 2000) else basePos
            }
        }
    }
    val fileTime = now - ms // sub-delay > 0 shows a line later than its own time
    val list = cues.orEmpty()
    val active = activeCueIndex(list, fileTime)
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var userScrollAt by remember { mutableLongStateOf(0L) }
    val follow = nearestCueIndex(list, fileTime)
    LaunchedEffect(follow, list) {
        if (list.isNotEmpty() && System.currentTimeMillis() - userScrollAt > 4000 && !listState.isScrollInProgress)
            listState.animateScrollToItem(follow)
    }
    val dim = Color(0xFF9A9A9A)

    Box(Modifier.fillMaxSize().background(Color.Black).clickable(MutableInteractionSource(), null) {}
        .padding(horizontal = 28.dp, vertical = 20.dp)) {
        Row(Modifier.fillMaxSize()) {
            // Lines
            Box(Modifier.weight(0.6f).fillMaxHeight().onPointerEvent(PointerEventType.Scroll) { userScrollAt = System.currentTimeMillis() }) {
                when {
                    cues == null -> Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                        Text("  Loading subtitle lines...", color = dim, fontSize = 15.sp)
                    }
                    list.isEmpty() -> Text(
                        "The lines of this subtitle can't be listed (the stream's own track, or the file couldn't be read).\nThe delay still applies.",
                        Modifier.align(Alignment.Center), color = dim, fontSize = 15.sp,
                    )
                    else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
                        itemsIndexed(list) { i, cue ->
                            val on = i == active
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                                // Click: make this line show now.
                                .clickable { set(now - cue.startMs) }
                                .padding(vertical = 10.dp, horizontal = 8.dp)) {
                                Text(cue.text, color = if (on) Color.White else dim, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, fontSize = 19.sp)
                                if (on) {
                                    val span = (cue.endMs - cue.startMs).coerceAtLeast(1)
                                    val f = ((fileTime - cue.startMs).toFloat() / span).coerceIn(0f, 1f)
                                    Spacer(Modifier.height(8.dp))
                                    Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0xFF555555))) {
                                        Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(Color.White))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.width(24.dp))
            // Delay controls
            Box(Modifier.weight(0.4f).fillMaxHeight()) {
                Column(Modifier.align(Alignment.Center).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Subtitle delay", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text(
                        when {
                            ms > 0 -> "Use this if the subtitles are shown ${ms}ms too early"
                            ms < 0 -> "Use this if the subtitles are shown ${-ms}ms too late"
                            else -> "No subtitle delay"
                        },
                        color = dim, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        SyncStep("‹") { set(ms - 1000) }
                        SyncStep("−") { set(ms - 100) }
                        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                            androidx.compose.foundation.text.BasicTextField(
                                value = text,
                                onValueChange = { v ->
                                    val clean = v.filterIndexed { i, ch -> ch.isDigit() || (ch == '-' && i == 0) }.take(8)
                                    text = clean
                                    clean.toLongOrNull()?.let { set(it, fromField = true) } ?: run { if (clean.isEmpty()) set(0, fromField = true) }
                                },
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 20.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            )
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFAAAAAA)))
                        }
                        SyncStep("+") { set(ms + 100) }
                        SyncStep("›") { set(ms + 1000) }
                    }
                    Text("‹ ›  1 s     − +  100 ms     click a line to show it now", color = Color(0xFF6E6E76), fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
                }
                Row(Modifier.align(Alignment.BottomEnd), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SyncAction("Apply", primary = true) { onClose() }
                    SyncAction("Reset") { set(0) }
                    SyncAction("Cancel") { cancel() }
                }
            }
        }
    }
}

/**
 * CloudStream's speed panel: the speed on top, − / slider / + (0.05x steps), and preset buttons underneath.
 * Clicking outside closes it.
 */
@Composable
private fun SpeedDialog(ui: PlayerUi, onClose: () -> Unit) {
    val min = 0.1; val max = 2.0; val step = 0.05
    fun label(s: Double) = "%.2f".format(java.util.Locale.ROOT, s).trimEnd('0').let { if (it.endsWith('.')) it + "0" else it } + "x"
    fun snap(v: Double) = (Math.round(v / step) * step).coerceIn(min, max).let { Math.round(it * 100) / 100.0 }
    fun set(v: Double) { val s = snap(v); if (s != ui.speed) ui.onSpeed(s) }
    val presets = listOf(0.25, 1.0, 1.25, 1.5, 2.0)
    Box(Modifier.fillMaxSize().clickable(MutableInteractionSource(), null, onClick = onClose), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(640.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(0.88f))
                .clickable(MutableInteractionSource(), null) {}.padding(horizontal = 18.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label(ui.speed), color = Color.White, fontSize = 22.sp)
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SpeedSquare("−") { set(ui.speed - step) }
                Slider(
                    value = ui.speed.toFloat().coerceIn(min.toFloat(), max.toFloat()),
                    onValueChange = { set(it.toDouble()) },
                    valueRange = min.toFloat()..max.toFloat(),
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White,
                        inactiveTrackColor = Color(0xFF2A2A2E)),
                    modifier = Modifier.weight(1f).padding(horizontal = 18.dp),
                )
                SpeedSquare("+") { set(ui.speed + step) }
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { s ->
                    val sel = Math.abs(s - ui.speed) < 1e-6
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(4.dp)).background(if (sel) Color(0xFF3A3A40) else Color(0xFF1C1C1E))
                        .clickable { ui.onSpeed(s) }.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        Text(label(s), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, letterSpacing = 1.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedSquare(label: String, onClick: () -> Unit) {
    Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1C1C1E)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = Color.White, fontSize = 24.sp)
    }
}

@Composable
private fun SyncStep(label: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SyncAction(label: String, primary: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(4.dp)).background(if (primary) Color(0xFFE8E8EC) else Color(0xFF1C1C1E))
        .clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 11.dp)) {
        Text(label, color = if (primary) Color.Black else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

@Composable
private fun PlayerIconButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label,
        modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
    )
}

@Composable
private fun RoundButton(label: String, size: Int, glyph: Glyph? = null, onClick: () -> Unit) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(Color.Black.copy(0.35f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { if (glyph != null) GlyphIcon(glyph, (size * 0.42f).dp) else Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size / 3.2).sp, lineHeight = (size / 3.2).sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
}

/** Dim backdrop + centred panel (dialogs stay inside the overlay window, above the video). */
@Composable
private fun OverlayPanel(onDismiss: () -> Unit, width: Int, heightFraction: Float = 0.82f, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(0.55f)).clickable(MutableInteractionSource(), null, onClick = onDismiss), contentAlignment = Alignment.Center) {
        Box(Modifier.width(width.dp).fillMaxHeight(heightFraction).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1B1B21))
            .clickable(MutableInteractionSource(), null) {}.padding(18.dp)) { content() }
    }
}

/**
 * CloudStream's "Sources" sheet: sources on the left, subtitles on the right, Apply / Cancel. Raw mode shows the
 * full URL / referer / headers of each link.
 */
@Composable
private fun SourcesDialog(ui: PlayerUi, onClose: () -> Unit) {
    var pickSource by remember { mutableStateOf(ui.current) }
    var pickSub by remember { mutableStateOf(ui.selectedSub) }
    var editPriority by remember { mutableStateOf(false) }
    OverlayPanel(onClose, 920) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(Modifier.weight(1.3f)) {
                  if (editPriority) PriorityEditor(ui) { editPriority = false }
                  else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Sources" + if (ui.stillSearching) "  (still searching...)" else "  (${ui.sources.size})",
                            Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        // The priority list lives here, next to the sources it orders.
                        PlayerIconButton("⚙  Priority") { editPriority = true }
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(if (ui.raw) ui.sources else ui.sources.filter { it.playable }, key = { it.url }) { l ->
                            val sel = l.url == pickSource?.url
                            val failed = l.url in ui.failed
                            // Hide menu: this server (link name) or the whole source; right-click or the ⊘ button.
                            var hideAt by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
                            Box {
                            Column(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (sel) Color.White.copy(0.12f) else Color.Transparent)
                                    .onSecondaryPress { hideAt = it }
                                    .clickable(enabled = l.playable) { pickSource = l }.padding(horizontal = 10.dp, vertical = 8.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (sel) "◉ " else "○ ", color = if (sel) Red else Color(0xFF8A8A94), fontSize = 16.sp)
                                    Text(l.name.ifBlank { l.source }, Modifier.weight(1f), color = if (failed) Color(0xFF8A8A94) else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(l.qualityLabel, color = Color(0xFFB0B0B8), fontSize = 12.sp)
                                    if (l.url == ui.current?.url) Text("  playing", color = Red, fontSize = 12.sp)
                                    if (failed) Text("  failed", color = Color(0xFFFF8A80), fontSize = 12.sp)
                                    Text("  ⊘", Modifier.clip(RoundedCornerShape(4.dp)).clickable { hideAt = androidx.compose.ui.geometry.Offset(560f, 30f) }
                                        .padding(horizontal = 4.dp), color = Color(0xFF8A8A94), fontSize = 14.sp)
                                }
                                if (l.source.isNotBlank() && l.source != l.name) Text(l.source, color = Color(0xFF8A8A94), fontSize = 11.sp, modifier = Modifier.padding(start = 22.dp))
                                if (ui.raw) Text(
                                    "${l.url}${if (l.referer.isNotBlank()) "\nreferer=${l.referer}" else ""}${if (l.headers.isNotEmpty()) "\nheaders=${l.headers}" else ""}",
                                    color = Color(0xFF8A8F99), fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(start = 22.dp),
                                )
                            }
                            hideAt?.let { at ->
                                CardMenu(true, at, buildList {
                                    val server = cloudstream.desktop.app.serverKey(l.name)
                                    if (server.isNotEmpty() && !server.equals(l.source, true))
                                        add(CardMenuItem("Hide this server: $server") { ui.onHideSource(server) })
                                    if (l.source.isNotBlank()) add(CardMenuItem("Hide everything from ${l.source}") { ui.onHideSource(l.source) })
                                }) { hideAt = null }
                            }
                            }
                        }
                    }
                  }
                }
                Column(Modifier.weight(1f)) {
                    val own = ui.embeddedSubs.sortedWith(compareBy(naturalOrder) { t: cloudstream.desktop.player.MediaTrack -> trackName(t) })
                    val external = sortedSubtitles(ui.subtitles)
                    Text("Subtitles (${own.size + external.size})", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PlayerIconButton("Search online") { onClose(); ui.onOnlineSubtitles() }
                        PlayerIconButton("Load from file") { onClose(); ui.onLoadSubtitleFile() }
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            SubRow("No subtitles", pickSub == null) { pickSub = null }
                        }
                        // The stream's own tracks first ("in stream"), then the extension's subtitle files. Same-named
                        // entries share one row with numbered choices; different names keep their own rows.
                        val ownGroups = groupSameNamed(own.map { "${trackName(it)}  ·  in stream" to (EMBEDDED_SUB_KEY + it.id) })
                        val extGroups = groupSameNamed(external.map { it.lang to it.url })
                        items(ownGroups + extGroups, key = { "g:" + it.keys.first() }) { g ->
                            SubGroupRow(g, pickSub) { pickSub = it }
                        }
                    }
                }
            }
            HorizontalDivider(color = Color.White.copy(0.1f))
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClose) { Text("Cancel", color = Color(0xFFD0D0D6)) }
                TextButton(onClick = {
                    val src = pickSource?.takeIf { it.url != ui.current?.url }
                    val subChanged = pickSub != ui.selectedSub
                    ui.onApply(src, subChanged, pickSub)
                    onClose()
                }) { Text("Apply", color = Red, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/**
 * Source priority editor (the Sources column's gear): ranked sources are tried first, in order; the rest follow by
 * quality. Sources of the current title are offered first for ranking, then every other source seen so far.
 */
@Composable
private fun PriorityEditor(ui: PlayerUi, onDone: () -> Unit) {
    val here = ui.sources.map { it.source.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
    fun ranked(n: String) = ui.priority.any { it.equals(n, true) } || ui.hiddenSources.any { it.equals(n, true) }
    val unrankedHere = here.filterNot(::ranked).sortedWith(naturalOrder)
    val unrankedOther = ui.knownSources.filterNot { k -> ranked(k) || here.any { it.equals(k, true) } }.sortedWith(naturalOrder)
    @Composable fun Small(label: String, enabled: Boolean = true, onClick: () -> Unit) = Text(
        label,
        Modifier.clip(RoundedCornerShape(5.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 9.dp, vertical = 5.dp),
        color = if (enabled) Color.White else Color(0xFF55555E), fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
    )
    @Composable fun UnrankedRow(n: String) = Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(n, Modifier.weight(1f), color = Color(0xFFD0D0D6), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Small("+ Rank") { ui.onMoveSource(n, 0) }
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayerIconButton("←") { onDone() }
            Text("Source priority", Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Text("Ranked sources are tried first, in this order (auto-start and fallback); the rest follow by quality. Applies to every title.",
            color = Color(0xFF9A9AA4), fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            if (ui.priority.isEmpty()) item { Text("Nothing ranked yet: use \"+ Rank\" below.", color = Color(0xFF8A8A94), fontSize = 13.sp, modifier = Modifier.padding(6.dp)) }
            itemsIndexed(ui.priority, key = { _, n -> "r:$n" }) { i, n ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color.White.copy(0.05f)).padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("#${i + 1}", Modifier.width(34.dp), color = Red, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(n, Modifier.weight(1f), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Small("↑", enabled = i > 0) { ui.onMoveSource(n, -1) }
                    Small("↓", enabled = i < ui.priority.lastIndex) { ui.onMoveSource(n, 1) }
                    Small("✕") { ui.onUnrankSource(n) }
                }
                Spacer(Modifier.height(4.dp))
            }
            if (unrankedHere.isNotEmpty()) {
                item { Text("In this title, not ranked", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)) }
                items(unrankedHere, key = { "h:$it" }) { UnrankedRow(it) }
            }
            if (unrankedOther.isNotEmpty()) {
                item { Text("Seen elsewhere", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)) }
                items(unrankedOther, key = { "o:$it" }) { UnrankedRow(it) }
            }
            if (ui.hiddenSources.isNotEmpty()) {
                item { Text("Hidden (never listed or played)", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)) }
                items(ui.hiddenSources.sortedWith(naturalOrder), key = { "x:$it" }) { n ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(n, Modifier.weight(1f), color = Color(0xFF8A8A94), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Small("Unhide") { ui.onUnhideSource(n) }
                    }
                }
            }
        }
    }
}

/** A subtitle row; several same-named entries show as numbered choices (1, 2, 3...) on the one row. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SubGroupRow(g: SubtitleGroup, picked: String?, onPick: (String) -> Unit) {
    if (g.keys.size == 1) { SubRow(g.name, picked == g.keys[0]) { onPick(g.keys[0]) }; return }
    val sel = g.keys.indexOf(picked)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (sel >= 0) Color.White.copy(0.12f) else Color.Transparent)
            .clickable { if (sel < 0) onPick(g.keys[0]) }.padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (sel >= 0) "◉ " else "○ ", color = if (sel >= 0) Red else Color(0xFF8A8A94), fontSize = 16.sp)
        Text(g.name, Modifier.weight(1f), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        androidx.compose.foundation.layout.FlowRow(
            Modifier.padding(start = 8.dp).widthIn(max = 200.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            g.keys.forEachIndexed { i, k ->
                val on = i == sel
                Box(
                    Modifier.size(28.dp).clip(RoundedCornerShape(5.dp))
                        .background(if (on) Red else Color.White.copy(alpha = 0.10f)).clickable { onPick(k) },
                    contentAlignment = Alignment.Center,
                ) { Text("${i + 1}", color = Color.White, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp) }
            }
        }
    }
}

@Composable
private fun SubRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (selected) Color.White.copy(0.12f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (selected) "◉ " else "○ ", color = if (selected) Red else Color(0xFF8A8A94), fontSize = 16.sp)
        Text(label, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun <T> ChoiceDialog(title: String, options: List<T>, label: (T) -> String, selected: (T) -> Boolean, onPick: (T) -> Unit, onClose: () -> Unit) {
    OverlayPanel(onClose, 360) {
        Column {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            LazyColumn { items(options) { o -> SubRow(label(o), selected(o)) { onPick(o); onClose() } } }
        }
    }
}

/** Audio tracks and the file's built-in subtitle tracks (as mpv reports them). */
@Composable
private fun TracksDialog(ui: PlayerUi, onClose: () -> Unit) {
    val audio = remember { ui.audioTracks() }
    val subs = remember { ui.subtitleTracks() }
    OverlayPanel(onClose, 720) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Audio", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                LazyColumn {
                    if (audio.isEmpty()) item { Text("No audio tracks reported", color = Color(0xFF8A8A94)) }
                    items(audio) { t -> SubRow(trackName(t), t.selected) { ui.onAudioTrack(t.id); onClose() } }
                }
            }
            Column(Modifier.weight(1f)) {
                Text("Subtitle tracks", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                LazyColumn {
                    item { SubRow("Off", subs.none { it.selected }) { ui.onSubtitleTrack(null); onClose() } }
                    items(subs) { t -> SubRow(trackName(t), t.selected) { ui.onSubtitleTrack(t.id); onClose() } }
                }
            }
        }
    }
}

/** Audio stream picker (shown only when the file has more than one). */
@Composable
private fun AudioDialog(ui: PlayerUi, onClose: () -> Unit) {
    OverlayPanel(onClose, 480, heightFraction = 0.6f) {
        Column {
            Text("Audio", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            LazyColumn {
                items(ui.audioList) { t ->
                    SubRow(trackName(t), t.selected) {
                        ui.onAudioTrack(t.id)
                        ui.audioList = ui.audioList.map { it.copy(selected = it.id == t.id) } // until the next poll
                        onClose()
                    }
                }
            }
        }
    }
}

/** Quality picker for the current source (shown only when it offers more than one). */
@Composable
private fun VideoDialog(ui: PlayerUi, onClose: () -> Unit) {
    OverlayPanel(onClose, 480, heightFraction = 0.6f) {
        Column {
            Text("Video quality", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            LazyColumn {
                items(ui.videoList) { t ->
                    SubRow(t.title ?: "Stream ${t.id}", t.selected) {
                        ui.onVideoTrack(t.id)
                        ui.videoList = ui.videoList.map { it.copy(selected = it.id == t.id) } // until the next poll
                        onClose()
                    }
                }
            }
        }
    }
}

private fun trackName(t: cloudstream.desktop.player.MediaTrack) = listOfNotNull(t.lang, t.title).joinToString(" - ").ifBlank { "Track ${t.id}" }

/** A large pill over the video (Skip intro, Next episode). */
@Composable
private fun PromptButton(label: String, primary: Boolean, onClick: () -> Unit) {
    Text(
        label,
        Modifier.clip(RoundedCornerShape(6.dp))
            .background(if (primary) Color.White else Color.Black.copy(0.6f))
            .border(1.dp, Color.White.copy(if (primary) 0f else 0.7f), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp),
        color = if (primary) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp,
    )
}
