package cloudstream.desktop.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cloudstream.desktop.app.AppController
import cloudstream.desktop.app.PlayerHandle
import cloudstream.desktop.app.domain.SourceLink
import cloudstream.desktop.app.domain.SubtitleItem
import cloudstream.desktop.app.vm.DetailsLoad
import cloudstream.desktop.app.vm.DetailsViewModel
import cloudstream.desktop.app.vm.PlayRequest
import cloudstream.desktop.app.vm.buildPlaybackSource
import cloudstream.desktop.app.vm.episodeId
import cloudstream.desktop.app.vm.nextEpisode
import cloudstream.desktop.contracts.PlaybackSource
import cloudstream.desktop.contracts.PlayerState
import cloudstream.desktop.contracts.ResumePoint
import cloudstream.desktop.contracts.SubtitleSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Canvas
import java.awt.datatransfer.StringSelection
import java.awt.event.HierarchyEvent
import java.awt.Color as AwtColor

/**
 * One playback session. Deliberately NOT driven by recomposition: the main window is fully covered by the native
 * video surface and may not redraw, so anything waiting for a recomposition (state updates for the overlay, source
 * switches, fallback) would stall. Everything here runs on coroutines and acts on the player directly.
 */
private class PlayerSession(
    private val c: AppController,
    private val req: PlayRequest,
    private val dm: DetailsViewModel,
    val h: PlayerHandle,
    val ui: PlayerUi,
) {
    private val settings = c.services.settings
    /** null while the loading screen is up (no source chosen yet). */
    @Volatile var current: SourceLink? = req.link
    @Volatile var lastState = PlayerState(0, 0, false, false, null)
    /** Where the open source was asked to start, and whether it has played since (then its position is real). */
    @Volatile private var wantedMs = req.startMs
    @Volatile private var playedSinceOpen = false
    /**
     * Where another source should continue: the current one's position once it has played, else where it was asked
     * to start, so a resume point survives sources that fail before playing (they report position 0).
     */
    fun resumePoint(): Long = if (playedSinceOpen) lastState.positionMs.coerceAtLeast(0) else wantedMs
    private val failed = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    /** Reopen attempts of the current source (reset whenever another source is opened). */
    @Volatile private var reopens = 0
    /**
     * The user's subtitle choice, kept across source switches: null = automatic (each new source starts with its own
     * subtitle track, else the extension's file in the preferred language), "" = off, [EMBEDDED_CHOICE] + language =
     * the stream's own track in that language, else an external subtitle url.
     */
    @Volatile private var chosenSub: String? = null
    private val EMBEDDED_CHOICE = cloudstream.desktop.player.LibMpvPlayer.SUB_EMBEDDED_PREFIX
    /** Bumped on every open(); the failure watcher restarts its clock for the new file. */
    @Volatile var generation = 0
    private val mpv get() = h.player as? cloudstream.desktop.player.LibMpvPlayer
    private val dp get() = h.player as? cloudstream.desktop.player.DesktopPlayer

    private fun links() = dm.state.value.links.takeIf { it.episode?.data == req.episode.data }
    fun alternatives(): List<SourceLink> = links()?.links.orEmpty().let { l ->
        val first = req.link
        if (first == null || l.any { it.url == first.url }) l else listOf(first) + l
    }

    /** Loading screen over: play the best source by priority (lookup finished, or "Skip loading"). */
    fun startBest(): Boolean {
        if (current != null) return true
        val best = alternatives().firstOrNull { it.playable && it.url !in failed } ?: return false
        open(best, req.startMs)
        return true
    }
    /** [EARLY_START_COUNT] playable links at the preferred quality ("Auto" adaptive streams count); off for "Best available". */
    private fun enoughPreferred(links: List<SourceLink>): Boolean =
        cloudstream.desktop.app.countPreferred(links, settings.defaultQuality.value, failed.toSet()) >= cloudstream.desktop.app.EARLY_START_COUNT

    fun subtitles(): List<SubtitleItem> = links()?.subtitles?.takeIf { it.isNotEmpty() } ?: req.subtitles

    fun sourceFor(link: SourceLink, startMs: Long): PlaybackSource {
        val subs = subtitles()
        val choice = chosenSub
        val auto = choice == null || choice.startsWith(EMBEDDED_CHOICE)
        val ordered = when {
            choice == "" -> emptyList()
            auto -> subs
            else -> subs.sortedByDescending { it.url == choice }
        }
        return buildPlaybackSource(req.copy(link = link, startMs = startMs, subtitles = ordered), if (auto) settings.subtitleLang.value else "")
    }

    /** Tells libmpv which subtitle the next file starts with (see [chosenSub]). */
    private fun applySubtitlePick() {
        val m = mpv ?: return
        val choice = chosenSub
        m.preferredSubLang = settings.subtitleLang.value
        m.subtitlePick = when {
            choice == null -> cloudstream.desktop.player.LibMpvPlayer.SUB_AUTO
            choice == "" -> cloudstream.desktop.player.LibMpvPlayer.SUB_OFF
            choice.startsWith(EMBEDDED_CHOICE) -> choice
            else -> cloudstream.desktop.player.LibMpvPlayer.SUB_EXTERNAL
        }
    }

    /** Opens [link] in the player right now, at [startMs]. */
    fun open(link: SourceLink, startMs: Long) {
        cloudstream.desktop.app.AppLog.get("player").info("Playing \"${req.card.name}\" from ${link.source} / ${link.name} " +
            "(${link.qualityLabel}) ${cloudstream.desktop.app.redactUrl(link.url)} at ${startMs / 1000} s")
        wantedMs = startMs; playedSinceOpen = false
        current = link
        generation++
        ui.current = link
        val src = sourceFor(link, startMs)
        applySubtitlePick()
        // The new file's tracks: shown once the poll sees them (the old file's must not linger in the list).
        ui.embeddedSubs = emptyList()
        when (val choice = chosenSub) {
            "" -> ui.selectedSub = null
            null -> if (mpv == null) ui.selectedSub = src.subtitles.firstOrNull()?.url // libmpv: the poll reports what was picked
            else -> if (!choice.startsWith(EMBEDDED_CHOICE)) ui.selectedSub = choice
        }
        runCatching { h.player.open(src) }.onFailure { c.services.toast("Player error: ${it.message}") }
    }

    /** Fallback (or reopen) moving to [l]; continues where the previous source was. */
    fun switchTo(l: SourceLink) {
        val cur = current
        if (cur == null) { open(l, req.startMs); return }
        if (l.url == cur.url) return
        reopens = 0
        open(l, resumePoint())
    }

    /**
     * The user picked [l] (Source panel): that choice wins over whatever auto-start / fallback was doing. It gets a
     * fresh try even if it failed before; only if it fails does the fallback continue down the priority list.
     */
    fun userPick(l: SourceLink) {
        failed -= l.url
        ui.failed = failed.toSet()
        if (current?.url == l.url) { reopens = 0; open(l, resumePoint()); return }
        switchTo(l)
    }

    fun wireActions() {
        val next = nextEpisode(dm.visibleEpisodes(), req.episode)
        ui.title = req.titleName
        ui.episodeLine = req.episode.label.takeIf { it != "Play" && it != req.titleName && it != "Movie" }.orEmpty()
        ui.hasNext = next != null
        ui.onBack = { c.back() }
        ui.onNext = { if (next != null) { ui.autoNextIn = null; c.back(); c.playEpisode(dm, next) } }
        ui.onSkip = { ui.skip?.let { seg -> skipped += seg.kind; ui.skip = null; h.player.seek(seg.endMs) } }
        ui.onCancelAutoNext = { autoNextCancelled = true; ui.autoNextIn = null }
        val prev = dm.visibleEpisodes().let { l -> l.indexOfFirst { it.data == req.episode.data }.takeIf { it > 0 }?.let { l[it - 1] } }
        ui.hasPrev = prev != null
        ui.onPrev = { if (prev != null) { c.back(); c.playEpisode(dm, prev) } }
        ui.onFullscreen = { c.fullscreen.value = !c.fullscreen.value }
        // mpv's own pause flag ("playing" reads false while buffering, so it must not decide).
        // mpv's own pause flag ("playing" reads false while buffering, so it must not decide).
        ui.onPlayPause = { mpv?.togglePause() ?: run { if (h.player.state.value.playing) h.player.pause() else h.player.play() } }
        ui.onSeek = { h.player.seek(it.coerceAtLeast(0)) }
        ui.onSeekBy = { d -> mpv?.seekBy(d) ?: h.player.seek((h.player.state.value.positionMs + d).coerceAtLeast(0)) }
        // The speed the user sets is kept (next episode, restarts); hold-for-2x is temporary and never saved.
        ui.onSpeed = { ui.speed = it; dp?.setSpeed(it); settings.setPlaybackSpeed(it) }
        ui.onHoldSpeed = { s -> dp?.setSpeed(s ?: ui.speed) }
        ui.speed = settings.playbackSpeed.value
        if (ui.speed != 1.0) dp?.setSpeed(ui.speed)
        ui.onQuit = { c.services.close(); kotlin.system.exitProcess(0) }
        ui.onStop = { c.back() }
        ui.onScreenshot = {
            val dir = java.io.File(System.getProperty("user.home"), "Pictures/Streamlet").apply { mkdirs() }
            val f = java.io.File(dir, "streamlet-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".png")
            if (mpv?.screenshotTo(f) == true) c.services.toast("Snapshot saved: ${f.absolutePath}") else c.services.toast("Snapshot failed")
        }
        ui.onVolume = { ui.volume = it; dp?.setVolume(it) }
        ui.onResize = { mode ->
            ui.resizeMode = mode
            mpv?.setProperty("keepaspect", if (mode == 2) "no" else "yes")
            mpv?.setProperty("panscan", if (mode == 1) "1.0" else "0.0")
        }
        ui.onMpv = { args -> mpv?.command(*args.toTypedArray()) }
        ui.onSubDelay = { secs -> ui.subDelay = secs; mpv?.setProperty("sub-delay", "%.3f".format(java.util.Locale.ROOT, secs)) }
        // Sync screen lines: an external subtitle's file (url with its headers, or a local file); not the stream's own.
        ui.subtitleCues = { key ->
            if (key.startsWith(EMBEDDED_SUB_KEY)) null
            else SubtitleCueLoader.load(key, ui.subtitles.firstOrNull { it.url == key }?.headers.orEmpty())
        }
        ui.audioTracks = { dp?.audioTracks().orEmpty() }
        ui.subtitleTracks = { dp?.subtitleTracks().orEmpty() }
        ui.onAudioTrack = { dp?.setAudioTrack(it) }
        ui.onVideoTrack = { mpv?.setVideoTrack(it) }
        ui.onSubtitleTrack = { dp?.setSubtitleTrack(it) }
        ui.onApply = { src, subChanged, key ->
            // A subtitle picked here is the user's choice: it beats the automatic pick on every later source switch.
            if (subChanged) when {
                key == null -> { chosenSub = ""; ui.selectedSub = null; h.player.setSubtitle(null) }
                key.startsWith(EMBEDDED_SUB_KEY) -> {
                    val id = key.removePrefix(EMBEDDED_SUB_KEY).toIntOrNull()
                    val t = ui.embeddedSubs.firstOrNull { it.id == id }
                    if (id != null && t != null) {
                        chosenSub = EMBEDDED_CHOICE + (t.lang ?: t.title.orEmpty())
                        ui.selectedSub = key
                        dp?.setSubtitleTrack(id)
                    }
                }
                else -> ui.subtitles.firstOrNull { it.url == key }?.let { sub ->
                    chosenSub = sub.url; ui.selectedSub = sub.url
                    h.player.setSubtitle(SubtitleSource(sub.url, sub.lang, sub.headers))
                }
            }
            if (src != null) userPick(src)
        }
        ui.onMoveSource = { name, delta -> settings.moveSource(name, delta) }
        ui.onUnrankSource = { settings.unrankSource(it) }
        // Hiding re-sorts the list at once (see the priority/hidden collector); what is playing keeps playing.
        ui.onHideSource = { name -> settings.setSourceHidden(name, true); c.services.toast("Hidden: $name (unhide in Source → ⚙ Priority)") }
        ui.onUnhideSource = { settings.setSourceHidden(it, false) }
        ui.onSkipLoading = {
            if (!startBest()) c.services.toast("No playable source yet - still searching")
        }
        ui.onOnlineSubtitles = {
            ui.extraDialog = {
                OnlineSubtitlesDialog(
                    c, (dm.state.value.load as? DetailsLoad.Loaded)?.details, req.titleName, req.episode,
                    onLoaded = { f, lang -> h.player.setSubtitle(SubtitleSource(f.absolutePath, lang, emptyMap())) },
                    onDismiss = { ui.extraDialog = null },
                )
            }
        }
        ui.onLoadSubtitleFile = {
            val fd = java.awt.FileDialog(null as java.awt.Frame?, "Load subtitle", java.awt.FileDialog.LOAD)
            fd.setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in setOf("srt", "ass", "ssa", "vtt", "sub") }
            fd.isVisible = true
            val f = fd.file?.let { java.io.File(fd.directory, it) }
            if (f != null && f.isFile) { h.player.setSubtitle(SubtitleSource(f.absolutePath, f.nameWithoutExtension, emptyMap())); c.services.toast("Loaded ${f.name}") }
        }
    }

    /** Intro / recap / credits of the open file (see [SkipTimes]); recomputed for every file opened. */
    @Volatile private var segments: List<cloudstream.desktop.app.SkipSegment> = emptyList()
    /** Segment kinds already skipped (or dismissed) in this file: not offered again. */
    private val skipped = java.util.Collections.synchronizedSet(HashSet<cloudstream.desktop.app.SkipSegment.Kind>())
    @Volatile private var autoNextCancelled = false
    /** AniSkip answer for this episode (by duration, rounded to a minute: sources of one episode differ by seconds). */
    private val onlineSkips = HashMap<Long, List<cloudstream.desktop.app.SkipSegment>>()

    /** Waits for the open file's duration, then reads its chapters and (anime) AniSkip times. */
    private suspend fun loadSegments(gen: Int) {
        var dur = 0L
        while (generation == gen) { dur = lastState.durationMs; if (dur > 0) break; delay(500) }
        if (generation != gen || dur <= 0) return
        val chapters = withContext(Dispatchers.IO) { mpv?.chapters().orEmpty() }
        val fromChapters = cloudstream.desktop.app.SkipTimes.fromChapters(chapters, dur)
        val d = (dm.state.value.load as? DetailsLoad.Loaded)?.details
        val epNo = req.episode.episode
        val online = if (d != null && d.isAnime && epNo != null && fromChapters.size < 2)
            onlineSkips.getOrPut(dur / 60_000) { cloudstream.desktop.app.SkipTimes.aniSkip(d.syncIds, epNo, dur) } else emptyList()
        if (generation != gen) return
        segments = cloudstream.desktop.app.SkipTimes.merge(fromChapters, online)
        if (segments.isNotEmpty()) cloudstream.desktop.app.AppLog.get("skip").info("Skip times for ${req.episode.label}: " +
            segments.joinToString { "${it.kind} ${it.startMs / 1000}-${it.endMs / 1000}s" } + if (online.isNotEmpty()) " (AniSkip)" else " (chapters)")
    }

    /** Skip buttons, auto-skip, the credits "Next episode" prompt and the end-of-episode countdown. */
    private fun updatePrompts(st: PlayerState) {
        val hasNext = ui.hasNext
        val seg = segments.firstOrNull { st.positionMs in it && it.kind !in skipped }
        if (seg != null && seg.kind != cloudstream.desktop.app.SkipSegment.Kind.Credits && settings.autoSkipIntro.value && st.playing) {
            skipped += seg.kind; h.player.seek(seg.endMs); c.services.toast("Skipped ${seg.kind.name.lowercase()}")
            ui.skip = null
        } else {
            // In the credits with an episode after this one, "Next episode" replaces "Skip credits".
            ui.skip = seg?.takeIf { !(it.kind == cloudstream.desktop.app.SkipSegment.Kind.Credits && hasNext) }
        }
        val inCredits = seg?.kind == cloudstream.desktop.app.SkipSegment.Kind.Credits ||
            (segments.none { it.kind == cloudstream.desktop.app.SkipSegment.Kind.Credits } && cloudstream.desktop.contracts.isWatchedAt(st.positionMs, st.durationMs))
        ui.nextPrompt = hasNext && inCredits
        if (!st.ended) { if (ui.autoNextIn != null) ui.autoNextIn = null; autoNextCancelled = false }
    }

    /** At the end of the file: count down, then play the next episode (unless cancelled or turned off). */
    private suspend fun autoNext() {
        while (true) {
            delay(500)
            val st = lastState
            if (!st.ended || !ui.hasNext || !settings.autoNextEpisode.value || autoNextCancelled || ui.autoNextIn != null) continue
            for (s in 5 downTo 1) {
                ui.autoNextIn = s
                delay(1000)
                if (autoNextCancelled || !lastState.ended) { ui.autoNextIn = null; break }
            }
            if (ui.autoNextIn != null) { ui.onNext(); return }
        }
    }

    /** All long-running work of the session; cancelled when the player screen goes away. */
    suspend fun run() = coroutineScope {
        PlayerDebug.player = mpv
        // Player state -> overlay (no recomposition of the main window involved).
        launch {
            var synced = false
            h.player.state.collect { st ->
                lastState = st; ui.applyPlayerState(st)
                updatePrompts(st)
                if (st.playing && st.positionMs > 0) playedSinceOpen = true
                // Tracking: once ~90% watched, raise progress on signed-in trackers (once per play).
                if (!synced && st.durationMs > 60_000 && st.positionMs >= st.durationMs * 9 / 10) {
                    synced = true
                    (dm.state.value.load as? DetailsLoad.Loaded)?.details?.let { d ->
                        val sync = c.services.sync
                        c.services.scope.launch { sync.episodeFinished(d, req.episode).forEach { c.services.toast(it) } }
                    }
                }
            }
        }
        // Source list / subtitles (they keep arriving while the details screen is still looking).
        launch {
            dm.state.collect {
                val ls = links()
                ui.sources = alternatives(); ui.subtitles = subtitles(); ui.stillSearching = ls?.loading == true
                ui.loadMessage = ls?.message
                // Loading screen: when the lookup is over, start the best source by priority (CloudStream waits for
                // every extractor unless "Skip loading" is pressed).
                if (current == null && ls != null && !ls.loading && !startBest())
                    ui.loadMessage = ls.message ?: "No playable sources found"
                // Enough good links already: start without waiting for the slow extractors (the lookup keeps going
                // in the background, so later links still show up in the Source panel).
                if (current == null && ls != null && ls.loading && enoughPreferred(ls.links)) startBest()
            }
        }
        launch { c.fullscreen.collect { ui.fullscreen = it } }
        // Skip times for every file opened (a source switch is another file), and the end-of-episode countdown.
        launch { var gen = -1; while (true) { if (generation != gen) { gen = generation; segments = emptyList(); skipped.clear(); launch { loadSegments(gen) } }; delay(300) } }
        launch { autoNext() }
        // Audio streams of the current file (mpv property reads block: never on the UI thread).
        launch(Dispatchers.IO) {
            while (true) {
                delay(2000)
                if (current == null) continue
                val t = runCatching { dp?.audioTracks() }.getOrNull().orEmpty()
                if (t != ui.audioList) ui.audioList = t
                val v = runCatching { mpv?.videoTracks() }.getOrNull().orEmpty()
                if (v != ui.videoList) ui.videoList = v
                // Subtitles: the stream's own tracks for the list, and what is actually showing (auto pick included).
                val m = mpv ?: continue
                val subs = runCatching { m.subtitleTracks() }.getOrNull() ?: continue
                val own = subs.filter { !it.external }
                if (own != ui.embeddedSubs) ui.embeddedSubs = own
                val sel = subs.firstOrNull { it.selected }
                val key = when {
                    sel == null -> null
                    !sel.external -> EMBEDDED_SUB_KEY + sel.id
                    else -> sel.source
                }
                if (key != ui.selectedSub) ui.selectedSub = key
            }
        }
        launch { settings.showRawSources.collect { ui.raw = it } }
        // Priority (edited from the Sources panel's gear): the list re-sorts at once, and fallback follows it.
        launch { settings.knownSources.collect { ui.knownSources = it.toList() } }
        launch { settings.hiddenSources.collect { ui.hiddenSources = it.toList() } }
        launch {
            var first = true
            kotlinx.coroutines.flow.combine(settings.sourcePriority, settings.hiddenSources) { p, _ -> p }.collect { p ->
                ui.priority = p
                if (!first) dm.reorder()
                first = false
            }
        }
        launch { settings.seekBackSeconds.collect { ui.seekBackMs = it * 1000L } }
        launch { settings.seekForwardSeconds.collect { ui.seekForwardMs = it * 1000L } }
        // Autosave the resume point every 5 s.
        launch { while (true) { delay(5000); if (lastState.positionMs > 0) saveResume(c, req, lastState) } }
        // Failure watcher: mpv's "Playback failed" (with its raw error) or no video within the timeout.
        if (h.real && h.embeddable) launch { watchFailures() }
    }

    private suspend fun watchFailures() {
        var gen = -1
        var t0 = 0L; var started = false; var announced = false
        while (true) {
            delay(500)
            val current = current ?: continue // still on the loading screen
            if (gen != generation) { gen = generation; t0 = System.currentTimeMillis(); started = false; announced = false }
            val st = h.player.state.value
            if (st.positionMs > 0) { started = true }
            val age = System.currentTimeMillis() - t0
            val why = when {
                st.error?.startsWith("Playback failed") == true && age > 1000 -> st.error
                !started && age > settings.fallbackSeconds.value * 1000L -> "no video after ${settings.fallbackSeconds.value}s"
                else -> null
            } ?: continue
            val label = current.name.ifBlank { current.source }
            // A source that already played and then failed (often right after a seek on a server with poor range
            // support) is not dead: reopen it at the last good position before giving up on it.
            if (started && reopens < 2) {
                reopens++
                val at = lastState.positionMs.coerceAtLeast(0)
                c.services.toast("$label: $why\nReopening at ${fmtTime(at)}...")
                open(current, at)
                continue
            }
            reopens = 0
            failed += current.url
            ui.failed = failed.toSet()
            if (!settings.autoFallback.value) { c.services.toast("$label: $why"); gen = generation; t0 = Long.MAX_VALUE / 2; continue }
            val alts = alternatives()
            val next = alts.firstOrNull { it.playable && it.url !in failed && it.url != current.url }
            if (next != null) {
                val pos = alts.indexOfFirst { it.url == next.url } + 1
                c.services.toast("$label: $why\nTrying #$pos: ${next.name.ifBlank { next.source }} (${next.qualityLabel})")
                switchTo(next)
            } else if (links()?.loading != true) {
                c.services.toast("$label: $why\nAll ${alts.size} source(s) failed")
                t0 = Long.MAX_VALUE / 2 // stop retrying this one
            } else if (!announced) {
                c.services.toast("$label: $why\nWaiting for more sources..."); announced = true
            }
        }
    }
}

/** The open player's libmpv instance, for diagnostics (RealPlayShot). */
object PlayerDebug { @Volatile var player: cloudstream.desktop.player.LibMpvPlayer? = null }

@Composable
fun PlayerScreen(c: AppController, req: PlayRequest) {
    var handle by remember(req) { mutableStateOf<PlayerHandle?>(null) }
    var failure by remember(req) { mutableStateOf<String?>(null) }
    val settings = c.services.settings
    val ui = remember(req) { PlayerUi() }
    val dm = remember(req) { c.detailsVm(req.card) }

    // Create the player off the UI thread (loads native libs).
    LaunchedEffect(req) {
        val log = cloudstream.desktop.app.AppLog.get("player")
        try {
            handle = withContext(Dispatchers.IO) { c.services.players.create(settings.playerChoice.value) }
            handle?.let { log.info("Player for \"${req.card.name}\": ${it.description} (real=${it.real}, embedded=${it.embeddable})") }
        } catch (t: Throwable) {
            log.log(java.util.logging.Level.WARNING, "Could not start the player", t)
            failure = "${t::class.simpleName}: ${t.message}"
        }
    }
    val h = handle
    val session = remember(h) { h?.let { PlayerSession(c, req, dm, it, ui).also { s -> s.wireActions() } } }
    LaunchedEffect(session) { session?.run() }

    // Persist the resume point when leaving; leave fullscreen too.
    DisposableEffect(session) {
        onDispose {
            session?.let { if (it.lastState.positionMs > 0) saveResume(c, req, it.lastState) }
            if (PlayerDebug.player === h?.player) PlayerDebug.player = null
            h?.release()
            c.fullscreen.value = false
        }
    }
    // Leaving the player (not the null -> session switch above): stop looking for this episode's sources.
    DisposableEffect(req) { onDispose { dm.cancelSources(req.episode) } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            failure != null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                ErrorText("Could not start player: $failure")
                OutlinedButton(onClick = { c.back() }) { Text("Back") }
            }
            session == null -> CenteredProgress("Starting player...")
            else -> PlayerBody(c, req, session)
        }
    }
}

@Composable
private fun PlayerBody(c: AppController, req: PlayRequest, s: PlayerSession) {
    val h = s.h
    var anchor by remember(h) { mutableStateOf<Canvas?>(null) }
    var opened by remember(h) { mutableStateOf(false) }

    val rendered = (h.player as? cloudstream.desktop.player.LibMpvPlayer)?.takeIf { it.renderMode }
    // Render mode / external / fake players open straight away; a windowed libmpv needs its Canvas displayable first.
    LaunchedEffect(h) {
        if ((rendered != null || !h.embeddable) && !opened) {
            rendered?.let { runCatching { withContext(Dispatchers.IO) { it.start() } } }
            opened = true // after start(): the renderer exists before the surface is shown
            req.link?.let { s.open(it, req.startMs) } // else the loading screen picks one (end of lookup / Skip)
        }
    }

    if (rendered != null) {
        // Video and controls in ONE window: mpv renders frames into memory, Compose paints them, controls on top.
        Box(Modifier.fillMaxSize()) {
            if (rendered.renderer != null || opened) VideoSurface(rendered, Modifier.fillMaxSize())
            PlayerControls(s.ui, c.services.toasts)
        }
    } else if (h.embeddable) {
        SwingPanel(
            factory = {
                Canvas().apply {
                    background = AwtColor.BLACK
                    addHierarchyListener { e ->
                        if (e.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() != 0L && isDisplayable && !opened) {
                            opened = true
                            runCatching { h.player.attachTo(this) }.onFailure { t -> c.services.toast("Player error: ${t.message}") }
                            req.link?.let { s.open(it, req.startMs) }
                            anchor = this
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        // Controls float in their own transparent window above the native video surface.
        VideoOverlayHost(anchor, c.services.toasts, s.ui)
    } else {
        val cur = s.ui.current
        if (cur == null) { PlayerControls(s.ui, c.services.toasts); return }
        val url = s.sourceFor(cur, 0).url
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            if (!h.real) {
                Text("No video backend available", style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text(h.description, style = MaterialTheme.typography.bodySmall, color = Color(0xFFCCCCCC))
                Text(cloudstream.desktop.player.MpvLocator.installHint, style = MaterialTheme.typography.bodySmall, color = Color(0xFFCCCCCC))
            } else {
                Text("Playing in an external ${h.description} window", style = MaterialTheme.typography.titleMedium, color = Color.White)
            }
            Text(url, style = MaterialTheme.typography.labelSmall, color = Color(0xFF999999), maxLines = 3)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                OutlinedButton(onClick = { c.back() }) { Text("Back") }
                OutlinedButton(onClick = {
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(url), null)
                    c.services.toast("Stream URL copied")
                }) { Text("Copy stream URL") }
                if (!h.real) OutlinedButton(onClick = { cloudstream.desktop.app.openInBrowser(url) }) { Text("Open in default app") }
            }
        }
    }
}

private fun saveResume(c: AppController, req: PlayRequest, st: PlayerState) {
    // Kept as is: a point in the credits reads as watched (ResumePoint.watched), so the title page offers the next
    // episode and playing this one again starts at the beginning (ResumePoint.startMs).
    val id = episodeId(req.card.provider, req.episode.data)
    c.services.library.setResume(ResumePoint(id, st.positionMs, st.durationMs, System.currentTimeMillis()))
}
