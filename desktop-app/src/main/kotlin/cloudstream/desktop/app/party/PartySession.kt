package cloudstream.desktop.app.party

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/** The local player as the party sees it (adapter over DesktopPlayer + the source resolver). */
interface PartyPlayer {
    fun positionMs(): Long
    fun isPlaying(): Boolean
    fun isBuffering(): Boolean
    /** Video loaded ahead of the position (null = unknown). */
    fun aheadMs(): Long?
    fun play()
    fun pause()
    fun seek(ms: Long)
    fun setSpeed(speed: Double)
    fun currentMedia(): MediaRef?
    /** Resolve [media] with the local provider and open it paused at 0. false = not installed / no working source. */
    suspend fun open(media: MediaRef): Boolean
}

data class PartyConfig(
    val waitForEveryone: Boolean = true,
    /** Stalls shorter than this never pause the room; drift correction absorbs them. */
    val stallIgnoreMs: Long = 1500,
    /** "Ready" = not buffering and at least this much loaded ahead. */
    val readyAheadMs: Long = 5000,
    val waitCapMs: Long = 20_000,
    val tickMs: Long = 500,
    /**
     * Play is scheduled this far in the future (host time) so every member, host included, starts on the same frame:
     * network delay up to this much is absorbed instead of showing up as drift.
     */
    val startLeadMs: Long = 400,
    val heartbeatMs: Long = 5000,
    val memberTimeoutMs: Long = 15_000,
    val hostTimeoutMs: Long = 60_000,
    val pingEveryMs: Long = 30_000,
    val maxRequestsPerSecond: Int = 5,
)

data class MemberStatus(
    val buffering: Boolean = false, val aheadMs: Long? = null, val detached: Boolean = false, val noSource: Boolean = false,
    /** Local time it was received; [bufferingSince] local time the current stall began. */
    val at: Long = 0, val bufferingSince: Long? = null,
)

data class PartyUi(
    val selfId: String = "",
    val connected: Boolean = false,
    val members: List<Member> = emptyList(),
    val hostId: String = "",
    val mode: ControlMode = ControlMode.Everyone,
    val room: RoomState = RoomState(),
    val statuses: Map<String, MemberStatus> = emptyMap(),
    /** Out of sync on purpose ("pause just for me"); [rejoin] to come back. */
    val detached: Boolean = false,
    /** Kept falling behind; no longer chasing the room (see DriftController). */
    val slow: Boolean = false,
    /** How far behind the room this member is (negative = ahead). */
    val behindMs: Long = 0,
    val noSource: Boolean = false,
) {
    val isHost: Boolean get() = hostId == selfId && hostId.isNotEmpty()
    fun member(id: String) = members.firstOrNull { it.id == id }
    fun canControl(id: String) = id == hostId || mode == ControlMode.Everyone || (mode == ControlMode.CoHosts && member(id)?.coHost == true)
}

sealed interface PartyEvent {
    data class Toast(val text: String) : PartyEvent
    data class ChatLine(val name: String, val text: String, val self: Boolean) : PartyEvent
    data class Reaction(val name: String, val emoji: String) : PartyEvent
    /** Someone without control asked for [action] (shown to the host). */
    data class Nudge(val name: String, val action: Action) : PartyEvent
    /** This member cannot play what the room plays: offer install / source picker. */
    data class NoSource(val media: MediaRef) : PartyEvent
}

/**
 * One member of a watch party. Host-authoritative: the host's [RoomState] is the truth, others follow it and send
 * [Request]s. All work runs on [scope], which MUST be single-threaded (e.g. `Dispatchers.Default.limitedParallelism(1)`);
 * public methods only enqueue onto it.
 */
class PartySession(
    val key: PartyKey,
    private val transport: PartyTransport,
    private val player: PartyPlayer,
    private val scope: CoroutineScope,
    private val selfName: String,
    val selfId: String = PartyKey.newMemberId(),
    private val now: () -> Long = System::currentTimeMillis,
    private val config: PartyConfig = PartyConfig(),
    private val appVersion: String = "",
) {
    private val _ui = MutableStateFlow(PartyUi(selfId = selfId))
    val ui: StateFlow<PartyUi> = _ui.asStateFlow()
    private val _events = MutableSharedFlow<PartyEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<PartyEvent> = _events.asSharedFlow()

    private val st get() = _ui.value
    private val clock = ClockSync()
    private val drift = DriftController()
    private val lastSeen = HashMap<String, Long>()
    private val requestTimes = HashMap<String, ArrayDeque<Long>>()
    private val jobs = ArrayList<Job>()

    private var welcomed = false
    private var lastHelloAt = Long.MIN_VALUE / 2
    private var lastPingAt = Long.MIN_VALUE / 2
    private var pingsSent = 0
    private var lastHeartbeatAt = Long.MIN_VALUE / 2
    private var lastStatusSent: List<Boolean>? = null
    private var lastStatusAt = Long.MIN_VALUE / 2
    private var forceStatus = false
    private var waitStartedAt = 0L
    /** Members the room stopped waiting for (wait cap hit): not waited for again until they are ready. */
    private val gaveUpOn = HashSet<String>()
    private var lastPositionChange: Pair<Long, String?> = 0L to null // seq, by
    private var opening: MediaRef? = null
    private var speedSet: Double? = null
    private var startJob: Job? = null
    private fun startAt(t: Long) = if (st.members.size > 1) t + config.startLeadMs else t

    private fun hostNow() = if (st.isHost) now() else now() + clock.offsetMs
    private fun nameOf(id: String?) = st.member(id ?: "")?.name ?: "Someone"

    // ---- lifecycle -------------------------------------------------------------------------------------------

    /** Start a room with this member as host, playing what the local player has open (if anything). */
    fun host(mode: ControlMode = ControlMode.Everyone) = start {
        val t = now()
        welcomed = true
        _ui.update {
            it.copy(
                connected = true, hostId = selfId, mode = mode, members = listOf(Member(selfId, selfName, t)),
                room = RoomState(seq = 1, media = player.currentMedia(), positionMs = player.positionMs(), atHostMs = t, playing = player.isPlaying()),
            )
        }
        broadcastState()
    }

    /** Join the room of [key]: says hello until the host answers. */
    fun join() = start { sendHello() }

    private fun start(init: suspend () -> Unit) {
        jobs += scope.launch { transport.incoming.collect { f -> key.open(f)?.let(PartyCodec::decode)?.let { handle(it) } } }
        jobs += scope.launch {
            init()
            while (isActive) { delay(config.tickMs); tick() }
        }
    }

    suspend fun leave() {
        runCatching { send(Bye(selfId)) }
        jobs.forEach { it.cancel() }
        runCatching { transport.close() }
        player.setSpeed(st.room.speed)
        _ui.update { it.copy(connected = false) }
    }

    // ---- local actions ---------------------------------------------------------------------------------------

    fun play() = act(Action.Play)
    fun pause() = act(Action.Pause)
    fun seek(ms: Long) = act(Action.Seek, ms.coerceAtLeast(0))
    fun changeEpisode(media: MediaRef) = act(Action.Episode, 0, media)

    private fun act(a: Action, pos: Long? = null, media: MediaRef? = null) = scope.launch {
        when {
            st.isHost -> applyAction(a, pos, media, selfId)
            st.canControl(selfId) -> {
                // Feels instant; the host's next state confirms (or corrects) it.
                if (!st.detached) when (a) {
                    Action.Play -> player.play()
                    Action.Pause -> player.pause()
                    Action.Seek -> { player.seek(pos ?: 0); drift.reset() }
                    Action.Episode -> {}
                }
                send(Request(selfId, a, pos, media, st.room.seq))
            }
            else -> {
                send(Request(selfId, a, pos, media, st.room.seq))
                _events.emit(PartyEvent.Toast("Asked the host to ${a.name.lowercase()}"))
            }
        }
    }

    /** Pause just for me (leave the shared timeline until [rejoin]); the room does not wait for detached members. */
    fun detach() = scope.launch {
        _ui.update { it.copy(detached = true) }
        player.pause(); forceStatus = true
    }

    fun rejoin() = scope.launch {
        _ui.update { it.copy(detached = false, slow = false) }
        drift.reset(); forceStatus = true
        applyRoom(st.room, explicit = true)
    }

    /** Give up on being "slow": jump to the room's position and chase it again. */
    fun catchUp() = scope.launch {
        drift.reset(); _ui.update { it.copy(slow = false) }
        applyRoom(st.room, explicit = true)
    }

    fun chat(text: String) = scope.launch {
        val t = text.trim().take(500).ifEmpty { return@launch }
        send(Chat(selfId, t)); _events.emit(PartyEvent.ChatLine(selfName, t, self = true))
    }

    fun react(emoji: String) = scope.launch {
        send(React(selfId, emoji.take(8))); _events.emit(PartyEvent.Reaction(selfName, emoji.take(8)))
    }

    fun setMode(mode: ControlMode) = hostOnly { _ui.update { it.copy(mode = mode) } }
    fun setCoHost(id: String, coHost: Boolean) = hostOnly {
        _ui.update { s -> s.copy(members = s.members.map { if (it.id == id) it.copy(coHost = coHost) else it }) }
    }
    /** Hand the host role to [id]; it rebases the room onto its own clock when it sees the roster. */
    fun makeHost(id: String) = hostOnly {
        if (st.member(id) == null || id == selfId) return@hostOnly
        _ui.update { it.copy(hostId = id) }
        clock.reset(); pingsSent = 0; lastSeen[id] = now()
    }

    private fun hostOnly(block: () -> Unit) = scope.launch {
        if (!st.isHost) return@launch
        block()
        send(Roster(selfId, st.members, st.hostId, st.mode))
    }

    // ---- incoming --------------------------------------------------------------------------------------------

    private suspend fun handle(m: PartyMsg) {
        if (m.from == selfId || (m.to != null && m.to != selfId)) return
        lastSeen[m.from] = now()
        when (m) {
            is Hello -> if (st.isHost) onHello(m)
            is Welcome -> onWelcome(m)
            is Roster -> onRoster(m)
            is StateMsg -> if (m.from == st.hostId && !st.isHost) onState(m)
            is Ping -> if (st.isHost) send(Pong(selfId, m.from, m.id, m.t0, now()))
            is Pong -> if (m.from == st.hostId) clock.add(m.t0, m.hostT, now())
            is Request -> if (st.isHost) onRequest(m)
            is Status -> recordStatus(m.from, m.buffering, m.aheadMs, m.detached, m.noSource)
            is Chat -> _events.emit(PartyEvent.ChatLine(nameOf(m.from), m.text.take(500), self = false))
            is React -> _events.emit(PartyEvent.Reaction(nameOf(m.from), m.emoji.take(8)))
            is Bye -> onBye(m.from)
        }
    }

    private suspend fun onHello(m: Hello) {
        if (st.member(m.from) == null) {
            _ui.update { it.copy(members = it.members + Member(m.from, m.name.take(40), now())) }
            _events.emit(PartyEvent.Toast("${m.name.take(40)} joined"))
        }
        send(Welcome(selfId, m.from, st.members, st.hostId, st.mode, st.room))
        send(Roster(selfId, st.members, st.hostId, st.mode))
    }

    private suspend fun onWelcome(m: Welcome) {
        val first = !welcomed
        welcomed = true
        clock.reset(); pingsSent = 0; lastPingAt = Long.MIN_VALUE / 2
        _ui.update { it.copy(connected = true, members = m.members, hostId = m.hostId, mode = m.mode, room = m.room) }
        lastSeen[m.hostId] = now()
        if (first) _events.emit(PartyEvent.Toast("Joined ${nameOf(m.hostId)}'s watch party"))
        ping()
        applyRoom(m.room, explicit = true)
    }

    private suspend fun onRoster(m: Roster) {
        if (st.isHost && m.hostId != selfId) {
            // Two hosts (both promoted after a drop-out): the one who joined earlier keeps the role.
            val mine = st.member(selfId)?.joinedAt ?: Long.MAX_VALUE
            val theirs = m.members.firstOrNull { it.id == m.from }?.joinedAt ?: Long.MAX_VALUE
            if (m.from == m.hostId && theirs > mine) { send(Roster(selfId, st.members, st.hostId, st.mode)); return }
        }
        val hostChanged = m.hostId != st.hostId
        _ui.update { it.copy(members = m.members, hostId = m.hostId, mode = m.mode) }
        if (hostChanged) {
            lastSeen[m.hostId] = now()
            if (m.hostId == selfId) becomeHost(m.members)
            else { clock.reset(); pingsSent = 0; lastPingAt = Long.MIN_VALUE / 2; ping(); _events.emit(PartyEvent.Toast("${nameOf(m.hostId)} is now the host")) }
        }
        if (!st.isHost && m.members.none { it.id == selfId }) { welcomed = false; sendHello() } // dropped by the host: rejoin
    }

    private suspend fun onState(m: StateMsg) {
        val s = m.room
        if (s.seq < st.room.seq) return
        val explicit = s.seq > st.room.seq
        _ui.update { it.copy(room = s) }
        if (s.waiting) forceStatus = true
        if (explicit && m.by != null && m.by != selfId && m.action != null) _events.emit(PartyEvent.Toast(describe(m.by, m.action, s)))
        applyRoom(s, explicit)
    }

    private suspend fun onRequest(m: Request) {
        val q = requestTimes.getOrPut(m.from) { ArrayDeque() }
        val t = now()
        while (q.isNotEmpty() && t - q.first() > 1000) q.removeFirst()
        if (q.size >= config.maxRequestsPerSecond) return
        q.addLast(t)
        if (!st.canControl(m.from)) { _events.emit(PartyEvent.Nudge(nameOf(m.from), m.action)); return }
        // A seek made against a timeline someone else has since moved is stale: drop it rather than jump back.
        val (changedSeq, changedBy) = lastPositionChange
        if (m.action == Action.Seek && changedSeq > m.baseSeq && changedBy != m.from) return
        applyAction(m.action, m.positionMs, m.media, m.from)
    }

    private suspend fun onBye(id: String) {
        if (id == st.hostId && !st.isHost) { elect(); return }
        if (st.isHost && st.member(id) != null) removeMembers(listOf(id), left = true)
    }

    // ---- host logic ------------------------------------------------------------------------------------------

    private suspend fun applyAction(a: Action, pos: Long?, media: MediaRef?, by: String) {
        val t = now()
        val r = st.room
        val cur = r.positionAt(t)
        var next = when (a) {
            Action.Play -> r.copy(playing = true, positionMs = cur, atHostMs = startAt(t), waiting = false)
            Action.Pause -> r.copy(playing = false, positionMs = cur, atHostMs = t, waiting = false)
            Action.Seek -> r.copy(positionMs = pos ?: cur, atHostMs = t)
            Action.Episode -> r.copy(media = media ?: return, positionMs = pos ?: 0, atHostMs = t)
        }
        val intendsToPlay = next.playing || (next.waiting && a != Action.Pause)
        if (config.waitForEveryone && intendsToPlay && (a == Action.Seek || a == Action.Episode) && st.members.size > 1) {
            next = next.copy(playing = false, waiting = true); waitStartedAt = t
        }
        setRoom(next, by, a)
    }

    private suspend fun setRoom(next: RoomState, by: String? = null, a: Action? = null) {
        val s = next.copy(seq = st.room.seq + 1)
        if (a == Action.Seek || a == Action.Episode) lastPositionChange = s.seq to by
        _ui.update { it.copy(room = s) }
        send(StateMsg(selfId, s, by, a))
        if (by != null && by != selfId && a != null) _events.emit(PartyEvent.Toast(describe(by, a, s)))
        applyRoom(s, explicit = true)
    }

    private suspend fun broadcastState() = send(StateMsg(selfId, st.room))

    private suspend fun hostTick(t: Long) {
        if (t - lastHeartbeatAt >= config.heartbeatMs) {
            lastHeartbeatAt = t
            broadcastState(); send(Roster(selfId, st.members, st.hostId, st.mode))
        }
        val gone = st.members.filter { it.id != selfId && t - (lastSeen[it.id] ?: t) > config.memberTimeoutMs }.map { it.id }
        if (gone.isNotEmpty()) removeMembers(gone, left = false)
        if (config.waitForEveryone) barrier(t)
    }

    /** Pause the room while someone (not detached, able to play) buffers; resume when all are ready or after the cap. */
    private suspend fun barrier(t: Long) {
        val active = st.members.filter { it.id !in gaveUpOn }
            .mapNotNull { m -> st.statuses[m.id]?.takeIf { !it.detached && !it.noSource }?.let { m to it } }
        val r = st.room
        if (r.playing && !r.waiting) {
            val stalled = active.filter { (_, s) -> s.buffering && s.bufferingSince != null && t - s.bufferingSince >= config.stallIgnoreMs }
            if (stalled.isNotEmpty()) {
                waitStartedAt = t
                setRoom(r.copy(playing = false, waiting = true, positionMs = r.positionAt(t), atHostMs = t))
                _events.emit(PartyEvent.Toast("Waiting for ${stalled.joinToString { it.first.name }}"))
            }
        } else if (r.waiting) {
            val notReady = active.filter { (_, s) -> !(s.at >= waitStartedAt && isReady(s.buffering, s.aheadMs)) }
            val timedOut = t - waitStartedAt >= config.waitCapMs
            if (notReady.isEmpty() || timedOut) {
                setRoom(r.copy(playing = true, waiting = false, atHostMs = startAt(t)))
                if (notReady.isNotEmpty()) {
                    gaveUpOn += notReady.map { it.first.id } // or the room would pause for them again right away
                    _events.emit(PartyEvent.Toast("Continuing without ${notReady.joinToString { it.first.name }}"))
                }
            }
        }
    }

    private suspend fun removeMembers(ids: List<String>, left: Boolean) {
        val names = ids.map { nameOf(it) }
        _ui.update { s -> s.copy(members = s.members.filter { it.id !in ids }, statuses = s.statuses - ids.toSet()) }
        gaveUpOn -= ids.toSet()
        ids.forEach { lastSeen.remove(it) }
        send(Roster(selfId, st.members, st.hostId, st.mode))
        _events.emit(PartyEvent.Toast(names.joinToString() + if (left) " left" else " lost connection"))
    }

    /** The host is gone: the longest-connected member takes over (everyone computes the same answer). */
    private suspend fun elect() {
        val remaining = st.members.filter { it.id != st.hostId }.sortedBy { it.joinedAt }
        val next = remaining.firstOrNull() ?: return
        if (next.id == selfId) becomeHost(remaining)
        else {
            _ui.update { it.copy(hostId = next.id, members = remaining) }
            lastSeen[next.id] = now(); clock.reset(); pingsSent = 0; lastPingAt = Long.MIN_VALUE / 2
            _events.emit(PartyEvent.Toast("${next.name} is now the host"))
        }
    }

    /** Take over: the room's timeline moves onto this member's clock. */
    private suspend fun becomeHost(members: List<Member>) {
        val t = now()
        val r = st.room
        val pos = r.positionAt(t + clock.offsetMs)
        _ui.update { it.copy(hostId = selfId, members = members, room = r.copy(seq = r.seq + 1, positionMs = pos, atHostMs = t), slow = false, behindMs = 0) }
        members.forEach { lastSeen[it.id] = t }
        clock.reset(); drift.reset()
        if (st.room.waiting) waitStartedAt = t
        send(Roster(selfId, st.members, st.hostId, st.mode))
        broadcastState()
        _events.emit(PartyEvent.Toast("You are now the host"))
    }

    // ---- following -------------------------------------------------------------------------------------------

    /** Bring the local player to [s]: media, play/pause, position on explicit changes. */
    private fun applyRoom(s: RoomState, explicit: Boolean) {
        if (st.detached) return
        val media = s.media
        if (media != null && !media.sameAs(player.currentMedia())) {
            if (!media.sameAs(opening)) {
                opening = media
                scope.launch {
                    val ok = runCatching { player.open(media) }.getOrDefault(false)
                    if (media.sameAs(opening)) opening = null
                    _ui.update { it.copy(noSource = !ok) }
                    forceStatus = true
                    if (ok) applyRoom(st.room, explicit = true) else _events.emit(PartyEvent.NoSource(media))
                }
            }
            return
        }
        if (s.startsLaterThan(hostNow())) {
            // Scheduled start: wait paused on the start frame, then start exactly at atHostMs.
            if (player.isPlaying()) player.pause()
            if (abs(player.positionMs() - s.positionMs) > 20) player.seek(s.positionMs)
            setSpeed(s.speed)
            startJob?.cancel()
            startJob = scope.launch {
                delay((s.atHostMs - hostNow()).coerceAtLeast(0))
                if (st.room.seq == s.seq) applyRoom(st.room, explicit = false)
            }
            return
        }
        if (s.playing) { if (!player.isPlaying()) player.play() } else if (player.isPlaying()) player.pause()
        val target = s.positionAt(hostNow())
        val off = abs(target - player.positionMs())
        // Paused: land on the room's exact frame (a pause arrives one network delay late; a paused seek is cheap).
        if ((explicit && off > 1000) || (!s.playing && off > 40)) { player.seek(target); drift.reset() }
        setSpeed(s.speed)
    }

    private fun setSpeed(x: Double) { if (speedSet != x) { speedSet = x; player.setSpeed(x) } }

    private suspend fun guestTick(t: Long) {
        if (!welcomed) { if (t - lastHelloAt >= 2000) sendHello(); return }
        if ((pingsSent < 4 && t - lastPingAt >= 1000) || t - lastPingAt >= config.pingEveryMs) ping()
        if (t - (lastSeen[st.hostId] ?: t) > config.hostTimeoutMs) { elect(); return }
        val r = st.room
        if (st.detached || opening != null || r.media?.sameAs(player.currentMedia()) == false) return
        if (!r.playing) { setSpeed(r.speed); return }
        if (r.startsLaterThan(t + clock.offsetMs)) return // scheduled start pending
        if (!player.isPlaying()) player.play()
        if (player.isBuffering()) return
        val expected = r.positionAt(t + clock.offsetMs)
        val local = player.positionMs()
        val d = drift.update(t, expected, local, r.speed)
        d.seekTo?.let { player.seek(it) }
        setSpeed(d.speed)
        _ui.update { it.copy(slow = drift.slow, behindMs = expected - local) }
    }

    private suspend fun tick() {
        val t = now()
        if (st.isHost) hostTick(t) else guestTick(t)
        reportStatus(t)
    }

    private fun isReady(buffering: Boolean, aheadMs: Long?) = !buffering && (aheadMs == null || aheadMs >= config.readyAheadMs)

    private suspend fun reportStatus(t: Long) {
        val buffering = player.isBuffering() || opening != null
        val ahead = if (opening != null) 0 else player.aheadMs()
        val s = st
        recordStatus(selfId, buffering, ahead, s.detached, s.noSource)
        if (s.isHost || !welcomed) return
        val key = listOf(buffering, isReady(buffering, ahead), s.detached, s.noSource)
        if (forceStatus || key != lastStatusSent || t - lastStatusAt >= config.heartbeatMs) {
            forceStatus = false; lastStatusSent = key; lastStatusAt = t
            send(Status(selfId, buffering, ahead, s.detached, s.noSource))
        }
    }

    private fun recordStatus(id: String, buffering: Boolean, aheadMs: Long?, detached: Boolean, noSource: Boolean) {
        val t = now()
        if (isReady(buffering, aheadMs)) gaveUpOn -= id
        _ui.update { s ->
            val prev = s.statuses[id]
            val since = if (buffering) prev?.bufferingSince ?: t else null
            s.copy(statuses = s.statuses + (id to MemberStatus(buffering, aheadMs, detached, noSource, t, since)))
        }
    }

    // ---- sending ---------------------------------------------------------------------------------------------

    private suspend fun sendHello() { lastHelloAt = now(); send(Hello(selfId, selfName, appVersion)) }

    private suspend fun ping() {
        if (st.isHost || st.hostId.isEmpty()) return
        lastPingAt = now(); pingsSent++
        send(Ping(selfId, st.hostId, pingsSent, now()))
    }

    private suspend fun send(m: PartyMsg) = transport.send(key.seal(PartyCodec.encode(m)))

    private fun describe(by: String, a: Action, s: RoomState): String = nameOf(by) + when (a) {
        Action.Play -> " pressed play"
        Action.Pause -> " paused at ${fmt(s.positionMs)}"
        Action.Seek -> " jumped to ${fmt(s.positionMs)}"
        Action.Episode -> " started ${s.media?.episodeLabel ?: "the next episode"}"
    }

    companion object {
        fun fmt(ms: Long): String {
            val sec = ms / 1000
            val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
    }
}
