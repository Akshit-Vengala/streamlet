package cloudstream.desktop.app.party

import cloudstream.desktop.app.AppController
import cloudstream.desktop.app.AppLog
import cloudstream.desktop.app.domain.EpisodeItem
import cloudstream.desktop.app.domain.MediaCard
import cloudstream.desktop.app.vm.DetailsLoad
import cloudstream.desktop.app.vm.DetailsViewModel
import cloudstream.desktop.app.vm.Screen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.swing.SwingUtilities
import kotlin.coroutines.resume

/**
 * What an open player screen offers the party (implemented by the player screen; libmpv only). Every call may block
 * briefly (mpv property reads): the party calls it from its own thread, never the UI thread.
 */
interface PartyTarget {
    /** What this screen plays (known before the file is loaded). */
    val intended: MediaRef
    /** The file is open and has a duration: positions mean something now. */
    fun loaded(): Boolean
    fun positionMs(): Long
    /** Not paused (true while buffering too). */
    fun isPlaying(): Boolean
    fun isBuffering(): Boolean
    fun aheadMs(): Long?
    fun play()
    fun pause()
    fun seek(ms: Long)
    fun setSpeed(speed: Double)
}

/** A chat panel line. */
data class PartyChatLine(val name: String, val text: String, val self: Boolean, val system: Boolean = false)

/** The active party as the UI sees it. */
class PartyInfo(val code: String, val link: String, val session: PartySession, internal val scope: CoroutineScope)

/**
 * App-wide watch party: at most one at a time; it survives screen changes (episode changes go back through the title
 * page). Owns the [PartySession], the public relay transport and the [PartyPlayer] adapter over whichever player
 * screen is open ([register] / [unregister]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PartyManager(private val c: AppController) {
    private val log = AppLog.get("party")
    private val _active = MutableStateFlow<PartyInfo?>(null)
    val active: StateFlow<PartyInfo?> = _active.asStateFlow()
    private val _chat = MutableStateFlow<List<PartyChatLine>>(emptyList())
    val chat: StateFlow<List<PartyChatLine>> = _chat.asStateFlow()
    /** Top bar "Join" dialog. */
    val joinDialog = MutableStateFlow(false)

    private val scope: CoroutineScope? get() = _active.value?.scope
    private val lock = Any()
    @Volatile private var target: PartyTarget? = null
    /** Room media a target was opened FOR (it may describe the same episode differently, e.g. matched by number). */
    @Volatile private var targetRoomMedia: MediaRef? = null
    @Volatile private var pending: Pending? = null
    @Volatile private var navigating = false
    @Volatile private var lastSpeed = 1.0
    private var watchJob: Job? = null

    private data class Pending(val provider: String, val titleUrl: String, val episodeData: String, val media: MediaRef)

    val session: PartySession? get() = _active.value?.session
    val isActive: Boolean get() = _active.value != null
    val isGuest: Boolean get() = session?.ui?.value?.isHost == false

    init {
        // Closing the app says goodbye, so the others do not wait for a timeout.
        Runtime.getRuntime().addShutdownHook(Thread({
            val info = _active.value ?: return@Thread
            _active.value = null
            runBlocking { withTimeoutOrNull(1500) { shutdown(info) } }
        }, "party-leave"))
    }

    // ---- start / join / leave ------------------------------------------------------------------------------------

    /** The name to offer in the start / join forms: the last one used, else the profile's, else the OS user's. */
    fun defaultName(): String = c.services.globalSettings.partyName.value.ifBlank {
        c.services.profiles.current.value.name.takeIf { it.isNotBlank() && !it.equals("Default", ignoreCase = true) }
            ?: System.getProperty("user.name").orEmpty()
    }

    /** Guests watch what the host plays: only the host controls play / pause / seek / speed (see [PartySession.setMode]). */
    fun start(name: String) {
        if (isActive) return
        val key = PartyKey.random()
        val s = open(key, name) ?: return
        s.host(ControlMode.HostOnly)
        _chat.value = listOf(PartyChatLine("", "Watch party started. Share the code: ${key.code}", self = false, system = true))
        copyToClipboard(key.code)
        c.services.toast("Watch party started - invite code copied:\n${key.code}")
    }

    /** false = not a valid code. */
    fun join(code: String, name: String): Boolean {
        val key = PartyKey.parse(code) ?: return false
        leave()
        val s = open(key, name) ?: return false
        s.join()
        _chat.value = listOf(PartyChatLine("", "Joining...", self = false, system = true))
        c.services.toast("Joining the watch party...")
        return true
    }

    /** Leave the party (a host hands it to someone else first); [endForAll] (host only) closes it for everyone. */
    fun leave(endForAll: Boolean = false) {
        val info = _active.value ?: return
        _active.value = null
        watchJob?.cancel()
        targetRoomMedia = null; pending = null
        c.services.scope.launch { shutdown(info, endForAll) }
    }

    private suspend fun shutdown(info: PartyInfo, endForAll: Boolean = false) {
        runCatching { info.session.leave(endForAll) }
        info.scope.cancel()
        log.info("Left the watch party")
    }

    private fun open(key: PartyKey, chosenName: String): PartySession? {
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
        val transport = PublicRelayTransport(key.roomId, scope = c.services.scope, log = { log.info(it) })
        transport.start()
        val name = chosenName.trim().take(40).ifBlank { defaultName() }.ifBlank { "Guest" }
        if (chosenName.isNotBlank()) c.services.globalSettings.setPartyName(name)
        val s = PartySession(key, transport, Adapter(), sc, name, appVersion = cloudstream.desktop.app.update.BuildInfo.version,
            log = { log.info(it) })
        _active.value = PartyInfo(key.code, key.link, s, sc)
        sc.launch { s.events.collect { onEvent(it) } }
        var wasConnected = false
        sc.launch { s.ui.collect { u -> if (u.connected && !wasConnected) { wasConnected = true; system(if (u.isHost) "You are the host" else "Joined the watch party") } } }
        watchJob = c.services.scope.launch { watchLeavingThePlayer() }
        log.info("Watch party $key opened")
        return s
    }

    private fun onEvent(e: PartyEvent) {
        when (e) {
            is PartyEvent.Toast -> { toast(e.text); system(e.text) }
            is PartyEvent.ChatLine -> { add(PartyChatLine(e.name, e.text, e.self)); if (!e.self) toast("${e.name}: ${e.text}") }
            is PartyEvent.Reaction -> { add(PartyChatLine(e.name, e.emoji, e.self)); if (!e.self) toast("${e.name} ${e.emoji}") }
            PartyEvent.Ended -> { c.services.toast("The host ended the watch party"); leave() }
            is PartyEvent.Nudge -> toast("${e.name} wants to ${e.action.name.lowercase()}")
            is PartyEvent.NoSource -> {
                val msg = "Could not open \"${e.media.titleName}\" ${e.media.episodeLabel}: " +
                    if (c.providerByName(e.media.apiName) == null) "install the extension that provides ${e.media.apiName}" else "no working source"
                c.services.toast(msg); system(msg)
            }
        }
    }
    private var lastToast: Pair<String, Long>? = null
    /** The same popup again within a couple of seconds (spam, repeats) is shown once; it still goes to the chat log. */
    private fun toast(text: String) {
        val t = System.currentTimeMillis()
        lastToast?.let { (txt, at) -> if (txt == text && t - at < 2500) return }
        lastToast = text to t
        c.services.toast(text)
    }
    private fun system(text: String) = add(PartyChatLine("", text, self = false, system = true))
    private fun add(l: PartyChatLine) { _chat.value = (_chat.value + l).takeLast(200) }

    // ---- controls from the player screen ---------------------------------------------------------------------

    /** Play/pause from the local controls: decided by the player's real pause flag (the UI flag flips first). */
    fun togglePlay() {
        val s = session ?: return
        scope?.launch { if (synchronized(lock) { target?.isPlaying() } == true) s.pause() else s.play() }
    }
    fun seek(ms: Long) { session?.seek(ms.coerceAtLeast(0)) }
    /** Host only: the room's speed (the session waits for the value to settle before sending it). */
    fun changeSpeed(x: Double) { session?.changeSpeed(x) }
    fun seekBy(deltaMs: Long) {
        val s = session ?: return
        scope?.launch { synchronized(lock) { target?.positionMs() }?.let { s.seek((it + deltaMs).coerceAtLeast(0)) } }
    }

    /** A guest's Next / Previous / episode pick: asks the room (the host's answer navigates everyone, this one too). */
    fun requestEpisode(vm: DetailsViewModel, ep: EpisodeItem) {
        val d = (vm.state.value.load as? DetailsLoad.Loaded)?.details ?: return
        session?.changeEpisode(mediaRef(vm.card, d.name, ep, null))
    }

    /** Source the room plays, for a guest's source pick (null = own priority). */
    fun preferredSource(provider: String, titleUrl: String, episodeData: String): String? =
        targetRoomMedia?.takeIf { pending?.let { p -> p.provider == provider && p.titleUrl == titleUrl && p.episodeData == episodeData } == true }?.sourceName

    // ---- player screens --------------------------------------------------------------------------------------

    /** A player screen opened. In a party: tie it to the room (or make it the room's new episode). */
    fun register(t: PartyTarget) {
        val p = pending
        val forRoom = p != null && p.provider == t.intended.apiName && p.titleUrl == t.intended.titleUrl && p.episodeData == t.intended.episodeKey
        synchronized(lock) { target = t; targetRoomMedia = if (forRoom) p!!.media else null }
        val s = session ?: return
        scope?.launch {
            t.setSpeed(lastSpeed) // speed is room state; the screen applied the user's own speed
            val room = s.ui.value.room.media
            val mine = targetRoomMedia ?: t.intended
            when {
                room != null && mine.sameAs(room) -> if (s.ui.value.detached) s.rejoin()
                forRoom -> {}
                // Opened by hand: that is the room's next episode (a guest's becomes a request to the host).
                else -> { if (s.ui.value.detached) s.rejoin(); s.changeEpisode(t.intended) }
            }
        }
    }

    /** The player screen is going away: after this returns the party never touches that player again. */
    fun unregister(t: PartyTarget) { synchronized(lock) { if (target === t) { target = null; targetRoomMedia = null } } }

    /** Leaving the player (not for another episode) = "pause just for me" until Rejoin. */
    private suspend fun watchLeavingThePlayer() {
        c.nav.stack.collectLatest { st ->
            if (st.last() is Screen.Player) return@collectLatest
            delay(2000)
            val s = session ?: return@collectLatest
            if (navigating || c.nav.current is Screen.Player || s.ui.value.detached || !s.ui.value.connected) return@collectLatest
            s.detach()
            c.services.toast("Still in the watch party - Rejoin from the top bar")
        }
    }

    fun rejoin() { session?.rejoin() }

    // ---- the PartyPlayer adapter -----------------------------------------------------------------------------

    private inner class Adapter : PartyPlayer {
        private inline fun <T> on(default: T, f: (PartyTarget) -> T): T = synchronized(lock) {
            val t = target ?: return default
            try { f(t) } catch (e: Exception) { default }
        }
        override fun positionMs() = on(0L) { it.positionMs() }
        override fun isPlaying() = on(false) { it.loaded() && it.isPlaying() }
        override fun isBuffering() = on(false) { it.isBuffering() }
        override fun aheadMs() = on(null) { it.aheadMs() }
        override fun play() = on(Unit) { it.play() }
        override fun pause() = on(Unit) { it.pause() }
        override fun seek(ms: Long) = on(Unit) { it.seek(ms) }
        override fun setSpeed(speed: Double) { lastSpeed = speed; on(Unit) { it.setSpeed(speed) } }
        override fun speed() = c.services.settings.playbackSpeed.value
        override fun currentMedia(): MediaRef? = synchronized(lock) { target?.takeIf { it.loaded() }?.let { targetRoomMedia ?: it.intended } }
        override suspend fun open(media: MediaRef): Boolean = openMedia(media)
    }

    /** Brings [media] up in a player screen (title page, episode, source lookup) and waits until its file is open. */
    private suspend fun openMedia(media: MediaRef): Boolean {
        val open = synchronized(lock) { target }
        if (open != null && (targetRoomMedia ?: open.intended).sameAs(media)) return awaitLoaded(media, sameScreen = open)
        if (c.providerByName(media.apiName) == null) return false
        navigating = true
        try {
            val card = MediaCard(media.apiName, media.titleName, media.titleUrl, media.posterUrl)
            val vm = onUi { c.detailsVm(card) }
            val st = withTimeoutOrNull(60_000) { vm.state.first { it.load !is DetailsLoad.Loading } } ?: return false
            val d = (st.load as? DetailsLoad.Loaded)?.details ?: return false
            val ep = d.episodes.firstOrNull { it.data == media.episodeKey }
                ?: d.episodes.firstOrNull { media.episode != null && it.season == media.season && it.episode == media.episode }
                ?: d.episodes.takeIf { d.isMovie || it.size == 1 }?.firstOrNull()
                ?: return false
            pending = Pending(card.provider, card.url, ep.data, media)
            onUi {
                if (c.nav.current is Screen.Player) c.back()
                val onTitle = (c.nav.current as? Screen.Details)?.card?.let { it.url == card.url && it.provider == card.provider } == true
                if (!onTitle) c.openDetails(card)
                c.playEpisode(vm, ep)
            }
            return awaitLoaded(media, sameScreen = null)
        } finally { navigating = false }
    }

    private suspend fun awaitLoaded(media: MediaRef, sameScreen: PartyTarget?): Boolean = withTimeoutOrNull(150_000) {
        while (true) {
            val ok = synchronized(lock) {
                val t = target
                t != null && (sameScreen == null || t === sameScreen) && (targetRoomMedia ?: t.intended).sameAs(media) && t.loaded()
            }
            if (ok) return@withTimeoutOrNull true
            delay(250)
        }
        @Suppress("UNREACHABLE_CODE") false
    } ?: false

    private suspend fun <T> onUi(block: () -> T): T = suspendCancellableCoroutine { cont ->
        SwingUtilities.invokeLater { cont.resume(block()) }
    }

    companion object {
        fun mediaRef(card: MediaCard, titleName: String, ep: EpisodeItem, sourceName: String?) = MediaRef(
            packageName = card.provider, packageVersion = 0, repoUrl = null, apiName = card.provider,
            titleUrl = card.url, titleName = titleName, episodeKey = ep.data,
            episodeLabel = ep.label.takeIf { it != "Play" && it != titleName } ?: "",
            sourceName = sourceName, season = ep.season, episode = ep.episode, posterUrl = card.posterUrl,
        )

        fun copyToClipboard(text: String) = runCatching {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
        }
    }
}
