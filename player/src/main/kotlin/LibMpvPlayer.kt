package cloudstream.desktop.player

import cloudstream.desktop.contracts.*
import com.sun.jna.Native
import com.sun.jna.Pointer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.awt.Component
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.util.Locale
import javax.swing.SwingUtilities

/**
 * libmpv player. With [renderMode] the video is not put into a native window: mpv renders frames into memory
 * ([renderer]) and the app paints them, so its own controls can be drawn on top in the same window.
 */
class LibMpvPlayer(private val api: MpvApi = MpvApi.load(), val renderMode: Boolean = false) : DesktopPlayer {
    /** Frame source in [renderMode]; created on first use (after mpv_initialize). */
    @Volatile var renderer: MpvFrameRenderer? = null
        private set
    @Volatile private var pendingSize: Pair<Int, Int>? = null

    /** Pixel size of the video area ([renderMode] only). */
    fun setRenderSize(width: Int, height: Int) {
        val r = renderer
        if (r != null) r.setSize(width, height) else pendingSize = width to height
    }
    private val h: Pointer = api.mpv_create() ?: error("mpv_create failed")
    private val st = MutableStateFlow(PlayerState(0, 0, false, false, null))
    override val state: StateFlow<PlayerState> get() = st
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var released = false
    @Volatile private var lastError: String? = null
    /** Last few mpv error-level log lines, attached to playback failures. Cleared on every open. */
    private val recentLog = ArrayDeque<String>()
    @Volatile private var pendingSubs: List<SubtitleSource> = emptyList()
    /** False between open() and the new file's START_FILE: END_FILE events seen then belong to the previous file. */
    @Volatile private var currentStarted = false
    /** Subtitles mpv could not load (bad URL, unsupported format); not treated as playback failures. */
    val subtitleErrors = java.util.concurrent.CopyOnWriteArrayList<String>()
    @Volatile private var attached: Component? = null
    private var fullscreenWindow: Window? = null

    init {
        // wid must be set before initialize; attachTo() is therefore expected before first use,
        // so initialization is deferred until then (or until the first open without a surface).
    }

    private var initialized = false
    private var wid: String? = null

    @Synchronized
    private fun ensureInit() {
        if (initialized) return
        if (renderMode) {
            // Frames go to the app (render API), the app draws its own controls: no mpv window, OSC or key bindings.
            api.mpv_set_option_string(h, "vo", "libmpv")
            api.mpv_set_option_string(h, "hwdec", "auto-copy")
            api.mpv_set_option_string(h, "osc", "no")
            api.mpv_set_option_string(h, "input-default-bindings", "no")
        } else {
            wid?.let { api.mpv_set_option_string(h, "wid", it) }
            api.mpv_set_option_string(h, "input-default-bindings", "yes")
            api.mpv_set_option_string(h, "input-vo-keyboard", "yes")
            api.mpv_set_option_string(h, "osc", "yes")
        }
        // Automated tests run with -Dcloudstream.mpv.mute=true: they must never make noise on the user's machine.
        if (System.getProperty("cloudstream.mpv.mute") == "true") api.mpv_set_option_string(h, "mute", "yes")
        api.mpv_set_option_string(h, "keep-open", "yes")
        api.mpv_set_option_string(h, "idle", "yes")
        // Diagnostics: -Dcloudstream.mpv.log=v (or info/debug/trace) prints libmpv's own log to the console.
        System.getProperty("cloudstream.mpv.log")?.takeIf { it.isNotBlank() }?.let {
            api.mpv_set_option_string(h, "terminal", "yes")
            api.mpv_set_option_string(h, "msg-level", "all=$it")
        }
        check(api.mpv_initialize(h) >= 0) { "mpv_initialize failed" }
        // Keep mpv's own error lines (e.g. "[ffmpeg] https: HTTP error 403 Forbidden") to report the raw cause.
        runCatching { api.mpv_request_log_messages(h, "warn") } // network/demuxer causes are often logged as warnings
        if (renderMode) renderer = MpvFrameRenderer(api, h).also { r -> pendingSize?.let { (w, hh) -> r.setSize(w, hh) } }
        initialized = true
        scope.launch { while (isActive) { poll(); delay(150) } } // 150 ms: the seek bar / icons follow promptly
    }

    /** Saves the current frame as mpv renders it, subtitles included (not affected by windows covering the player). */
    fun screenshotTo(file: java.io.File): Boolean =
        initialized && api.mpv_command(h, arrayOf("screenshot-to-file", file.absolutePath, "subtitles", null)) >= 0

    /** Sets an mpv property (resize modes etc.): e.g. "panscan" "1.0", "keepaspect" "no". */
    fun setProperty(name: String, value: String) { if (initialized) setProp(name, value) }

    /** Raw mpv property (diagnostics / tests), e.g. "sub-text", "sid", "video-codec". */
    fun property(name: String): String? = if (initialized) prop(name) else null

    /** Initialises mpv now (render mode: so the frame renderer exists before the first file is opened). */
    fun start() = ensureInit()

    private fun prop(name: String): String? {
        val p = api.mpv_get_property_string(h, name) ?: return null
        return try { p.getString(0, "UTF-8") } finally { api.mpv_free(p) }
    }

    // Every command / property change is QUEUED (mpv_command_async): a synchronous call blocks the caller - often the
    // UI thread - for as long as mpv is busy, e.g. while it downloads a subtitle file, which froze the whole app.
    // Async commands are executed in order, so open()'s option sequence still applies before its loadfile.
    private val replyIds = java.util.concurrent.atomic.AtomicLong(1)
    /** reply id -> subtitle language, for sub-add failures reported in MPV_EVENT_COMMAND_REPLY. */
    private val subAddReplies = java.util.concurrent.ConcurrentHashMap<Long, String>()

    private fun setProp(name: String, value: String) { cmdId("set", name, value) }

    private fun cmd(vararg a: String) { cmdId(*a) }

    private fun cmdId(vararg a: String): Long {
        ensureInit()
        val id = replyIds.incrementAndGet()
        val rc = api.mpv_command_async(h, id, arrayOf(*a, null))
        if (rc < 0) lastError = "${a.first()} failed: ${api.mpv_error_string(rc)}"
        return id
    }

    private fun drainEvents() {
        while (true) {
            val ev = api.mpv_wait_event(h, 0.0)
            val id = ev.getInt(0)
            if (id == 0) break // MPV_EVENT_NONE
            if (id == 2) { // MPV_EVENT_LOG_MESSAGE: {prefix, level, text, log_level}
                val m = ev.getPointer(16) ?: continue
                val line = "[${m.getPointer(0)?.getString(0) ?: "mpv"}] ${m.getPointer(16)?.getString(0)?.trim() ?: ""}"
                synchronized(recentLog) { recentLog.addLast(line); while (recentLog.size > 6) recentLog.removeFirst() }
            }
            if (id == 8) { // MPV_EVENT_FILE_LOADED: pick the subtitle and attach the link's subtitle files (sub-add needs a loaded file)
                val subs = pendingSubs; pendingSubs = emptyList()
                val pick = subtitlePick
                val own = chooseOwnSubtitle(pick)
                when {
                    own != null -> setProp("sid", own.id.toString())
                    pick == SUB_OFF -> setProp("sid", "no")
                }
                val selectExternal = own == null && pick != SUB_OFF
                subs.forEachIndexed { i, s ->
                    // The first external (preferred language first, see buildPlaybackSource) is selected only when the
                    // stream has no subtitle of its own to show; the rest are listed only.
                    // Async: each sub-add downloads its file; a slow subtitle host must never block anything.
                    val reply = cmdId("sub-add", s.url, if (i == 0 && selectExternal) "select" else "auto", s.lang, s.lang)
                    subAddReplies[reply] = s.lang
                }
            }
            if (id == 5) { // MPV_EVENT_COMMAND_REPLY {event_id, error, reply_userdata, ...}
                val err = ev.getInt(4); val reply = ev.getLong(8)
                val lang = subAddReplies.remove(reply)
                // A failed subtitle is not a playback error (that would trigger source fallback): just note it.
                if (lang != null && err < 0) subtitleErrors += "$lang: ${api.mpv_error_string(err)}"
            }
            if (id == 6) currentStarted = true // MPV_EVENT_START_FILE of the file we opened last
            if (id == 7) { // MPV_EVENT_END_FILE
                // The previous file's END_FILE arrives AFTER a new loadfile: ignore it, it is not about the new file.
                if (!currentStarted) continue
                val data = ev.getPointer(16) ?: continue
                if (data.getInt(0) == 4) {
                    val raw = synchronized(recentLog) { recentLog.distinct().joinToString(" | ") }
                    lastError = "Playback failed: ${api.mpv_error_string(data.getInt(4))}" + if (raw.isNotEmpty()) " -- $raw" else ""
                }
            }
        }
    }

    private fun poll() {
        if (released) return
        drainEvents()
        val pos = prop("time-pos")?.toDoubleOrNull() ?: 0.0
        val dur = prop("duration")?.toDoubleOrNull() ?: 0.0
        val paused = prop("pause") == "yes"
        val idle = prop("idle-active") == "yes"
        val cache = prop("paused-for-cache") == "yes"
        val coreIdle = prop("core-idle") == "yes"
        val seeking = prop("seeking") == "yes"
        // Waiting for data: cache underrun, a seek in progress, or not paused yet not playing (opening / stalled).
        val ended = prop("eof-reached") == "yes"
        val buffering = !idle && !paused && !ended && (cache || seeking || coreIdle)
        st.value = PlayerState(
            (pos * 1000).toLong(), (dur * 1000).toLong(),
            playing = !paused && !idle && !coreIdle,
            buffering = buffering,
            error = lastError,
            bufferPercent = if (buffering) prop("cache-buffering-state")?.toIntOrNull()?.coerceIn(0, 100) else null,
        )
    }

    override fun open(source: PlaybackSource) {
        currentStarted = false
        lastError = null
        synchronized(recentLog) { recentLog.clear() }
        val cfg = MpvOptions.map(source)
        for ((k, v) in cfg.options) setProp(k, v)
        // External subtitles are added on MPV_EVENT_FILE_LOADED (setting sub-files up front did not attach them).
        setProp("sub-files", "")
        pendingSubs = cfg.subtitles
        // Built-in tracks in the preferred language are picked automatically (the external one, if any, wins: added + selected later).
        cfg.subtitles.firstOrNull()?.lang?.let { l -> setProp("slang", MpvOptions.langCodes(l).joinToString(",")) }
        setProp("pause", "no")
        cmd("loadfile", cfg.url, "replace")
    }

    /**
     * Which subtitle the next opened file starts with: [SUB_AUTO] = the stream's own track (preferred language first),
     * else the first external one; [SUB_EXTERNAL] = the first external one (the user's pick); [SUB_OFF]; or
     * [SUB_EMBEDDED_PREFIX] + language = the stream's own track in that language (the user's pick of a built-in track).
     */
    @Volatile var subtitlePick: String = SUB_AUTO
    /** Preferred subtitle language (Settings), e.g. "English". */
    @Volatile var preferredSubLang: String = ""

    /** The stream's own subtitle track to show, or null (none, or [pick] wants an external one / none). */
    private fun chooseOwnSubtitle(pick: String): MediaTrack? {
        if (pick == SUB_OFF || pick == SUB_EXTERNAL) return null
        val own = tracks("sub").filter { !it.external }
        if (own.isEmpty()) return null
        val usable = own.filter { !it.forced }.ifEmpty { own } // "forced" = signs/songs only
        fun matching(lang: String): MediaTrack? {
            if (lang.isBlank()) return null
            val codes = MpvOptions.langCodes(lang)
            return usable.firstOrNull { t -> codes.any { c -> c.equals(t.lang, true) } || t.title?.contains(lang, true) == true }
        }
        val wanted = if (pick.startsWith(SUB_EMBEDDED_PREFIX)) pick.removePrefix(SUB_EMBEDDED_PREFIX) else ""
        return matching(wanted) ?: matching(preferredSubLang) ?: usable.first()
    }

    companion object {
        const val SUB_AUTO = "auto"
        const val SUB_EXTERNAL = "external"
        const val SUB_OFF = "off"
        const val SUB_EMBEDDED_PREFIX = "embedded:"
    }

    /** Any mpv command, queued (keyboard shortcuts: "cycle mute", "frame-step", "cycle sid", "add sub-delay 0.05"...). */
    fun command(vararg args: String) = cmd(*args)

    /** Flips mpv's own pause flag (the UI's "playing" can read false while buffering, so it must not decide). */
    fun togglePause() = cmd("cycle", "pause")

    override fun play() = setProp("pause", "no")
    override fun pause() = setProp("pause", "yes")
    override fun seek(ms: Long) = cmd("seek", "%.3f".format(Locale.ROOT, ms / 1000.0), "absolute+exact")
    /** Relative seek done by mpv itself (no dependency on the UI's idea of the current position). */
    fun seekBy(deltaMs: Long) = cmd("seek", "%.3f".format(Locale.ROOT, deltaMs / 1000.0), "relative+exact")
    override fun stop() { cmd("stop"); st.value = PlayerState(0, 0, false, false, null) }

    override fun setSubtitle(track: SubtitleSource?) {
        if (track == null) setProp("sid", "no") else cmd("sub-add", track.url, "select", track.lang, track.lang)
    }

    override fun setVolume(percent: Int) = setProp("volume", percent.coerceIn(0, 130).toString())
    override fun setSpeed(speed: Double) = setProp("speed", "%.2f".format(Locale.ROOT, speed.coerceIn(0.25, 4.0)))
    override fun setAudioTrack(id: Int) = setProp("aid", id.toString())
    override fun setSubtitleTrack(id: Int?) = setProp("sid", id?.toString() ?: "no")
    override fun audioTracks() = tracks("audio")
    override fun subtitleTracks() = tracks("sub")

    /**
     * Video streams; for an HLS/DASH master playlist these are its quality variants. Title = "1080p · 4.2 Mbps",
     * highest first. Cover art is not a video stream.
     */
    fun videoTracks(): List<MediaTrack> {
        if (!initialized) return emptyList()
        val n = prop("track-list/count")?.toIntOrNull() ?: return emptyList()
        return (0 until n).mapNotNull { i ->
            if (prop("track-list/$i/type") != "video" || prop("track-list/$i/albumart") == "yes") return@mapNotNull null
            val id = prop("track-list/$i/id")?.toIntOrNull() ?: return@mapNotNull null
            val height = prop("track-list/$i/demux-h")?.toIntOrNull()
            val bitrate = prop("track-list/$i/hls-bitrate")?.toLongOrNull()?.takeIf { it > 0 }
            val label = listOfNotNull(height?.let { "${it}p" }, bitrate?.let { "%.1f Mbps".format(Locale.ROOT, it / 1e6) }).joinToString(" · ")
            Triple(height ?: 0, bitrate ?: 0, MediaTrack(id, null, label.ifBlank { prop("track-list/$i/title") ?: "Stream $id" }, prop("track-list/$i/selected") == "yes"))
        }.sortedWith(compareByDescending<Triple<Int, Long, MediaTrack>> { it.first }.thenByDescending { it.second }).map { it.third }
    }

    fun setVideoTrack(id: Int) = setProp("vid", id.toString())

    private fun tracks(type: String): List<MediaTrack> {
        if (!initialized) return emptyList()
        val n = prop("track-list/count")?.toIntOrNull() ?: return emptyList()
        return (0 until n).mapNotNull { i ->
            if (prop("track-list/$i/type") != type) return@mapNotNull null
            MediaTrack(
                prop("track-list/$i/id")?.toIntOrNull() ?: return@mapNotNull null,
                prop("track-list/$i/lang"), prop("track-list/$i/title"),
                prop("track-list/$i/selected") == "yes",
                external = prop("track-list/$i/external") == "yes",
                forced = prop("track-list/$i/forced") == "yes",
                source = prop("track-list/$i/external-filename"),
            )
        }
    }

    /** The component must already be displayable (added to a visible window). */
    override fun attachTo(component: Component) {
        attached = component
        wid = Native.getComponentID(component).toString()
        if (initialized) api.mpv_set_property_string(h, "wid", wid!!)
        component.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_SPACE -> if (st.value.playing) pause() else play()
                    KeyEvent.VK_LEFT -> cmd("seek", "-10", "relative")
                    KeyEvent.VK_RIGHT -> cmd("seek", "10", "relative")
                    KeyEvent.VK_UP -> cmd("add", "volume", "5")
                    KeyEvent.VK_DOWN -> cmd("add", "volume", "-5")
                    KeyEvent.VK_F -> toggleFullscreen()
                    KeyEvent.VK_ESCAPE -> if (fullscreenWindow != null) toggleFullscreen()
                }
            }
        })
    }

    override fun toggleFullscreen() {
        SwingUtilities.invokeLater {
            val w = attached?.let { SwingUtilities.getWindowAncestor(it) } ?: return@invokeLater
            val dev = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
            if (!dev.isFullScreenSupported) return@invokeLater
            if (fullscreenWindow == null) { dev.fullScreenWindow = w; fullscreenWindow = w }
            else { dev.fullScreenWindow = null; fullscreenWindow = null }
        }
    }

    override fun release() {
        if (released) return
        released = true
        scope.cancel()
        runCatching { renderer?.release() } // the render context must be freed before the core is destroyed
        renderer = null
        api.mpv_terminate_destroy(h)
    }
}
