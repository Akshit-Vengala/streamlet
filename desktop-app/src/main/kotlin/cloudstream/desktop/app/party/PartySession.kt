package cloudstream.desktop.app.party

import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.withContext
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext
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
    /** The current playback speed (restored when leaving: speed is room state while in a party). */
    fun speed(): Double
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
    /** Host: after it moves its own player (play, seek, open, scheduled start), how long before its drift is judged. */
    val settleMs: Long = 2000,
    /** Host: its player this far off the room timeline (a short stall) moves the timeline to the player... */
    val reanchorAboveMs: Long = 300,
    /** ...at most this often. */
    val reanchorGapMs: Long = 5000,
    /** Guest: after an own play/pause/seek, how long to wait for the host's answer before snapping back to the room. */
    val optimisticHoldMs: Long = 2000,
    /** Host: a speed change is sent once the speed has stayed put this long. */
    val speedDebounceMs: Long = 300,
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
    data class Reaction(val name: String, val emoji: String, val self: Boolean = false) : PartyEvent
    /** Someone without control asked for [action] (shown to the host). */
    data class Nudge(val name: String, val action: Action) : PartyEvent
    /** The host ended the party: the app leaves it. */
    data object Ended : PartyEvent
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
    /** A MONOTONIC local clock (ms): the wall clock jumps when the OS or NTP adjusts it. Only differences matter. */
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val config: PartyConfig = PartyConfig(),
    private val appVersion: String = "",
    private val log: (String) -> Unit = {},
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
    private var openJob: Job? = null
    private var left = false
    private var speedBefore = 1.0
    /** Host: do not judge its own drift before this (player still settling after a jump). */
    private var settleUntil = 0L
    private var lastReanchorAt = Long.MIN_VALUE / 2
    /** Guest: own optimistic action waiting for the host's answer (a state with seq > [holdSeq]) until [holdUntil]. */
    private var holdSeq = 0L
    private var holdUntil = Long.MIN_VALUE / 2
    private fun holding() = now() < holdUntil
    private var holdPending = false
    /** Host: a speed change waits a moment so a dragged slider sends one change, not dozens. */
    private var speedJob: Job? = null
    /** Guest without control: last "only the host" toast; host: last nudge shown per member (both throttled). */
    private var lastAskToastAt = Long.MIN_VALUE / 2
    private val lastNudgeAt = HashMap<String, Long>()
    /** Frame counters: own (starts high so a restarted session never reuses old numbers), and a window per sender. */
    private var sendCounter = System.currentTimeMillis() * 1000
    private val windows = HashMap<String, ReplayWindow>()
    private fun startAt(t: Long) = if (st.members.size > 1) t + config.startLeadMs else t

    private fun hostNow() = if (st.isHost) now() else now() + clock.offsetMs
    /** A guest knows where the room is only once its clock is synced to the host's (local clocks are unrelated). */
    private fun synced() = st.isHost || clock.synced
    private fun nameOf(id: String?) = st.member(id ?: "")?.name ?: "Someone"

    // ---- lifecycle -------------------------------------------------------------------------------------------

    /** Start a room with this member as host, playing what the local player has open (if anything). */
    fun host(mode: ControlMode = ControlMode.Everyone) = start {
        val t = now()
        welcomed = true
        _ui.update {
            it.copy(
                connected = true, hostId = selfId, mode = mode, members = listOf(Member(selfId, selfName, 1)),
                room = RoomState(seq = 1, media = player.currentMedia(), positionMs = player.positionMs(), atHostMs = t, playing = player.isPlaying()),
            )
        }
        setSpeed(st.room.speed)
        broadcastState()
    }

    /** Join the room of [key]: says hello until the host answers. */
    fun join() = start { transport.awaitReady(); sendHello() }

    private fun start(init: suspend () -> Unit) {
        jobs += scope.launch { transport.incoming.collect { f -> guarded("message") { receive(f) } } }
        jobs += scope.launch {
            speedBefore = player.speed()
            guarded("start") { init() }
            while (isActive) { delay(config.tickMs); guarded("tick") { tick() } }
        }
    }

    /** Everything the session launches goes through [guarded]: a player that throws must not end the session. */
    private fun launchSafe(block: suspend () -> Unit) = scope.launch { guarded("task") { block() } }

    /** One bad message or player hiccup must never stop the session. */
    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { log("party: $what failed: $e") }
    }

    /**
     * Leave the room. A host hands the role to the longest-connected member first (the same one a guest election would
     * pick, so a lost hand-off message changes nothing), unless [endForAll]: then the room closes for everyone.
     */
    suspend fun leave(endForAll: Boolean = false) = withContext(scope.coroutineContext[ContinuationInterceptor] ?: EmptyCoroutineContext) {
        if (left) return@withContext
        left = true
        startJob?.cancel(); openJob?.cancel(); speedJob?.cancel()
        jobs.forEach { it.cancel() }
        try {
            if (st.isHost && endForAll) repeat(2) { send(End(selfId)) }
            else if (st.isHost) {
                val rest = st.members.filter { it.id != selfId }.sortedBy { it.joinedAt }
                rest.firstOrNull()?.let { next ->
                    _ui.update { it.copy(hostId = next.id, members = rest) }
                    send(Roster(selfId, rest, next.id, st.mode))
                }
            }
            send(Bye(selfId))
        } catch (e: CancellationException) { throw e } catch (_: Exception) {}
        try { transport.close() } catch (e: CancellationException) { throw e } catch (_: Exception) {}
        player.setSpeed(speedBefore)
        _ui.update { it.copy(connected = false) }
    }

    // ---- local actions ---------------------------------------------------------------------------------------

    fun play() = act(Action.Play)
    fun pause() = act(Action.Pause)
    fun seek(ms: Long) = act(Action.Seek, ms.coerceAtLeast(0))
    fun changeEpisode(media: MediaRef) = act(Action.Episode, 0, media)

    private fun act(a: Action, pos: Long? = null, media: MediaRef? = null) = launchSafe {
        if (left) return@launchSafe
        when {
            st.isHost -> applyAction(a, pos, media, selfId)
            st.canControl(selfId) -> {
                // Feels instant; the host's next state confirms (or corrects) it.
                if (!st.detached && a != Action.Episode) {
                    when (a) {
                        Action.Play -> player.play()
                        Action.Pause -> player.pause()
                        else -> { player.seek(pos ?: 0); drift.reset() }
                    }
                    // Until the host answers, heartbeats and drift correction must not undo it.
                    holdSeq = st.room.seq; holdUntil = now() + config.optimisticHoldMs; holdPending = true
                }
                send(Request(selfId, a, pos, media, st.room.seq))
            }
            else -> {
                send(Request(selfId, a, pos, media, st.room.seq))
                val t = now()
                if (t - lastAskToastAt >= ASK_TOAST_GAP_MS) {
                    lastAskToastAt = t
                    _events.emit(PartyEvent.Toast("Only the host controls playback - asked them to ${a.name.lowercase()}"))
                }
            }
        }
    }

    /** Pause just for me (leave the shared timeline until [rejoin]); the room does not wait for detached members. */
    fun detach() = launchSafe {
        _ui.update { it.copy(detached = true) }
        player.pause(); forceStatus = true
    }

    fun rejoin() = launchSafe {
        _ui.update { it.copy(detached = false, slow = false) }
        drift.reset(); forceStatus = true
        applyRoom(st.room, explicit = true)
    }

    /** Give up on being "slow": jump to the room's position and chase it again. */
    fun catchUp() = launchSafe {
        drift.reset(); _ui.update { it.copy(slow = false) }
        applyRoom(st.room, explicit = true)
    }

    fun chat(text: String) = launchSafe {
        val t = text.trim().take(500).ifEmpty { return@launchSafe }
        send(Chat(selfId, t)); _events.emit(PartyEvent.ChatLine(selfName, t, self = true))
    }

    fun react(emoji: String) = launchSafe {
        send(React(selfId, emoji.take(8))); _events.emit(PartyEvent.Reaction(selfName, emoji.take(8), self = true))
    }

    /**
     * Host: play everyone at [speed]. Applied after a short pause, so dragging the speed slider sends only where it
     * stops. Speed travels in [RoomState] (no new [Action]: older clients would drop a state with an unknown action).
     */
    fun changeSpeed(speed: Double) = launchSafe {
        if (left || !st.isHost) return@launchSafe
        val x = (Math.round(speed.coerceIn(MIN_SPEED, MAX_SPEED) * 100) / 100.0)
        speedJob?.cancel()
        speedJob = launchSafe {
            delay(config.speedDebounceMs)
            val r = st.room
            if (!st.isHost || r.speed == x) return@launchSafe
            val t = now()
            // Rebase the timeline at the change: positionAt multiplies elapsed time by speed.
            setRoom(r.copy(speed = x, positionMs = r.positionAt(t), atHostMs = if (r.startsLaterThan(t)) r.atHostMs else t))
        }
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

    private fun hostOnly(block: () -> Unit) = launchSafe {
        if (!st.isHost) return@launchSafe
        block()
        send(Roster(selfId, st.members, st.hostId, st.mode))
    }

    // ---- incoming --------------------------------------------------------------------------------------------

    private suspend fun receive(f: ByteArray) {
        val frame = key.open(f)?.let(PartyCodec::decodeFrame) ?: return
        val m = frame.msg
        if (m.from == selfId) return
        // Replay protection: brokers are untrusted and anyone can re-send captured ciphertext. Each sender numbers
        // its frames; a number seen before, or too old for the window, is dropped. No exceptions (not even Hello: a
        // replayed Hello must not reset a member's window). A restarted session has a new random id, hence a new
        // window, and its counter starts from the wall clock, so even a reused id would continue upwards.
        if (!windows.getOrPut(m.from) { ReplayWindow() }.accept(frame.n)) return
        handle(m)
    }

    private suspend fun handle(m: PartyMsg) {
        if (m.to != null && m.to != selfId) return
        val member = st.member(m.from) != null
        // Only members talk in the room (a non-member can only say Hello, or be the host welcoming us).
        if (!member && (m is Request || m is Status || m is Chat || m is React) && (st.isHost || welcomed)) return
        lastSeen[m.from] = now()
        when (m) {
            is Hello -> if (st.isHost) onHello(m)
            is Welcome -> onWelcome(m)
            is Roster -> if (welcomed) onRoster(m) // before the Welcome, a heartbeat roster says nothing about us
            is StateMsg -> if (m.from == st.hostId && !st.isHost) onState(m)
            is Ping -> if (st.isHost) send(Pong(selfId, m.from, m.id, m.t0, now()))
            is Pong -> if (m.from == st.hostId && !st.isHost) onPong(m)
            is Request -> if (st.isHost) onRequest(m)
            is Status -> recordStatus(m.from, m.buffering, m.aheadMs, m.detached, m.noSource)
            is Chat -> _events.emit(PartyEvent.ChatLine(nameOf(m.from), m.text.take(500), self = false))
            is React -> _events.emit(PartyEvent.Reaction(nameOf(m.from), m.emoji.take(8), self = false))
            is Bye -> onBye(m.from)
            is End -> if (m.from == st.hostId && !st.isHost) _events.emit(PartyEvent.Ended)
        }
    }

    private fun onPong(m: Pong) {
        val first = !clock.synced
        clock.add(m.t0, m.hostT, now())
        // Until now the guest could not tell where the room is (unrelated clocks): apply it on the first sample.
        if (first && clock.synced && welcomed) applyRoom(st.room, explicit = true)
    }

    private suspend fun onHello(m: Hello) {
        if (st.member(m.from) == null) {
            val order = (st.members.maxOfOrNull { it.joinedAt } ?: 0) + 1
            val name = uniqueName(m.name.trim().take(40).ifEmpty { "Guest" })
            _ui.update { it.copy(members = it.members + Member(m.from, name, order)) }
            _events.emit(PartyEvent.Toast("$name joined"))
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
        if (s.seq > holdSeq) { holdUntil = Long.MIN_VALUE / 2; holdPending = false } // the host has answered (or moved on)
        val speedChanged = explicit && s.speed != st.room.speed && st.room.seq > 0
        _ui.update { it.copy(room = s) }
        if (s.waiting) forceStatus = true
        if (speedChanged) _events.emit(PartyEvent.Toast("${nameOf(st.hostId)} set the speed to ${fmtSpeed(s.speed)}x"))
        if (explicit && m.by != null && m.by != selfId && m.action != null) _events.emit(PartyEvent.Toast(describe(m.by, m.action, s)))
        applyRoom(s, explicit)
    }

    private suspend fun onRequest(m: Request) {
        val q = requestTimes.getOrPut(m.from) { ArrayDeque() }
        val t = now()
        while (q.isNotEmpty() && t - q.first() > 1000) q.removeFirst()
        if (q.size >= config.maxRequestsPerSecond) return
        q.addLast(t)
        if (!st.canControl(m.from)) {
            // One nudge per member every few seconds: a guest holding a key must not flood the host with popups.
            if (t - (lastNudgeAt[m.from] ?: Long.MIN_VALUE / 2) >= ASK_TOAST_GAP_MS) { lastNudgeAt[m.from] = t; _events.emit(PartyEvent.Nudge(nameOf(m.from), m.action)) }
            return
        }
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
        reanchor(t)
    }

    /**
     * The room timeline is the host's clock, but the host's VIDEO can fall behind it (a stall too short to pause the
     * room, or any stall with waiting off). Guests follow the timeline, so move the timeline to the host's player.
     */
    private suspend fun reanchor(t: Long) {
        val r = st.room
        if (!r.playing || r.waiting || r.startsLaterThan(t) || st.detached || opening != null) return
        if (r.media?.sameAs(player.currentMedia()) == false) return
        if (!player.isPlaying() || player.isBuffering() || t < settleUntil || t - lastReanchorAt < config.reanchorGapMs) return
        val local = player.positionMs()
        if (abs(r.positionAt(t) - local) < config.reanchorAboveMs) return
        lastReanchorAt = t
        setRoom(r.copy(positionMs = local, atHostMs = t))
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

    /** Two members never share a name in the room ("Sam", "Sam 2"): the roster, chat and logs stay readable. */
    private fun uniqueName(name: String): String {
        val taken = st.members.map { it.name.lowercase() }.toSet()
        if (name.lowercase() !in taken) return name
        return (2..999).asSequence().map { "$name $it" }.first { it.lowercase() !in taken }
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
        // Never synced to the old host: its timestamps mean nothing here, the own player is the best estimate.
        val pos = if (clock.synced) r.positionAt(t + clock.offsetMs)
            else if (r.media?.sameAs(player.currentMedia()) != false) player.positionMs() else r.positionMs
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
        if (st.detached || left) return
        val media = s.media
        if (media != null && !media.sameAs(player.currentMedia())) {
            if (!media.sameAs(opening)) {
                opening = media
                openJob = launchSafe {
                    val ok = try { player.open(media) } catch (e: CancellationException) { throw e } catch (e: Exception) { log("party: open failed: $e"); false }
                    if (media.sameAs(opening)) opening = null
                    _ui.update { it.copy(noSource = !ok) }
                    forceStatus = true
                    settleUntil = now() + config.settleMs
                    if (ok) applyRoom(st.room, explicit = true) else _events.emit(PartyEvent.NoSource(media))
                }
            }
            return
        }
        if (!synced()) return // where the room is becomes known with the first pong (onPong applies it then)
        if (!explicit && holding()) return // own optimistic action not answered yet: a heartbeat must not undo it
        if (s.startsLaterThan(hostNow())) {
            // Scheduled start: wait paused on the start frame, then start exactly at atHostMs.
            if (player.isPlaying()) player.pause()
            if (abs(player.positionMs() - s.positionMs) > 20) player.seek(s.positionMs)
            setSpeed(s.speed)
            startJob?.cancel()
            startJob = launchSafe {
                delay((s.atHostMs - hostNow()).coerceAtLeast(0))
                if (st.room.seq == s.seq) applyRoom(st.room, explicit = false)
            }
            return
        }
        if (s.playing) { if (!player.isPlaying()) { player.play(); settleUntil = now() + config.settleMs } } else if (player.isPlaying()) player.pause()
        val target = s.positionAt(hostNow())
        val off = abs(target - player.positionMs())
        // Paused: land on the room's exact frame (a pause arrives one network delay late; a paused seek is cheap).
        if ((explicit && off > 1000) || (!s.playing && off > 40)) { player.seek(target); drift.reset(); settleUntil = now() + config.settleMs }
        setSpeed(s.speed)
    }

    private fun setSpeed(x: Double) { if (speedSet != x) { speedSet = x; player.setSpeed(x) } }

    private suspend fun guestTick(t: Long) {
        if (!welcomed) { if (t - lastHelloAt >= 2000) sendHello(); return }
        if ((pingsSent < 4 && t - lastPingAt >= 1000) || t - lastPingAt >= config.pingEveryMs) ping()
        if (t - (lastSeen[st.hostId] ?: t) > config.hostTimeoutMs) { elect(); return }
        val r = st.room
        if (!clock.synced || holding()) return
        if (holdPending) { holdPending = false; drift.reset(); applyRoom(r, explicit = true); return } // never answered: back to the room
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

    private suspend fun send(m: PartyMsg) = transport.send(key.seal(PartyCodec.encode(m, ++sendCounter)))

    private fun describe(by: String, a: Action, s: RoomState): String = nameOf(by) + when (a) {
        Action.Play -> " pressed play"
        Action.Pause -> " paused at ${fmt(s.positionMs)}"
        Action.Seek -> " jumped to ${fmt(s.positionMs)}"
        Action.Episode -> " started ${s.media?.episodeLabel ?: "the next episode"}"
    }

    /** Accepts each frame number once, and none older than [size] behind the newest (frames reorder across brokers). */
    private class ReplayWindow(private val size: Int = 512) {
        private var max = Long.MIN_VALUE
        private val seen = HashSet<Long>()
        fun accept(n: Long): Boolean {
            if (max != Long.MIN_VALUE && n <= max - size) return false
            if (!seen.add(n)) return false
            if (n > max) { max = n; seen.removeAll { it <= max - size } }
            return true
        }
    }

    companion object {
        const val MIN_SPEED = 0.1
        const val MAX_SPEED = 4.0
        private const val ASK_TOAST_GAP_MS = 3000L
        fun fmtSpeed(x: Double): String = "%.2f".format(java.util.Locale.ROOT, x).trimEnd('0').trimEnd('.')
        fun fmt(ms: Long): String {
            val sec = ms / 1000
            val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
    }
}
