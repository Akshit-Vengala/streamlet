package cloudstream.desktop.app

import cloudstream.desktop.app.party.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.*

class PartyKeyTest {
    @Test fun `code round trips and parsing is forgiving`() {
        val k = PartyKey.random()
        assertTrue(Regex("WP(-[0-9A-HJKMNP-TV-Z]{5}){4}").matches(k.code), k.code)
        assertEquals(k, PartyKey.parse(k.code))
        assertEquals(k, PartyKey.parse(k.code.lowercase().replace("-", " ")))
        assertEquals(k, PartyKey.parse(k.link))
        assertEquals(k, PartyKey.parse(k.code.removePrefix("WP-")))
        assertEquals(PartyKey.parse("WP-10000-00000-00000-00000"), PartyKey.parse("wp-lOooo-ooooo-ooooo-ooooo"))
        assertNull(PartyKey.parse("WP-12345"))
        assertNull(PartyKey.parse("WP-UUUUU-UUUUU-UUUUU-UUUUU")) // U is not in the alphabet
    }

    @Test fun `seal and open, wrong key and tampering rejected`() {
        val k = PartyKey.random()
        val frame = k.seal("hello".toByteArray())
        assertEquals("hello", String(k.open(frame)!!))
        assertNull(PartyKey.random().open(frame))
        assertNull(k.open(frame.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }))
        assertNotEquals(frame.toList(), k.seal("hello".toByteArray()).toList(), "fresh nonce per frame")
        assertFalse(k.toString().contains(k.code.substring(3, 8)), "the code never ends up in logs")
    }

    @Test fun `messages survive the codec, unknown ones are ignored`() {
        val m = StateMsg("a", RoomState(seq = 3, media = media("ep1"), positionMs = 5, atHostMs = 7, playing = true, speed = 1.5), "b", Action.Seek)
        assertEquals(m, PartyCodec.decode(PartyCodec.encode(m)))
        val w = Welcome("h", "g", listOf(Member("h", "Host", 1)), "h", ControlMode.HostOnly, RoomState())
        assertEquals(w, PartyCodec.decode(PartyCodec.encode(w)))
        assertNull(PartyCodec.decode("""{"t":"FutureThing","from":"x"}""".toByteArray()))
        assertNull(PartyCodec.decode("not json".toByteArray()))
    }
}

class PartyClockTest {
    @Test fun `offset from the least delayed sample`() {
        val c = ClockSync()
        // Host is 10 s ahead. One fast symmetric sample, one slow asymmetric one.
        c.add(t0 = 1000, hostT = 11_010, t2 = 1020)
        c.add(t0 = 2000, hostT = 12_400, t2 = 2500)
        assertEquals(10_000, c.offsetMs)
    }

    @Test fun `drift thresholds`() {
        val d = DriftController()
        assertEquals(DriftDecision(null, 1.0), d.update(0, 10_000, 10_080, 1.0))
        assertEquals(DriftDecision(null, 1.05), d.update(500, 10_000, 9_500, 1.0)) // 500 ms behind: faster
        assertEquals(DriftDecision(null, 1.05), d.update(1000, 10_000, 9_940, 1.0)) // still nudging (hysteresis)
        assertEquals(DriftDecision(null, 1.0), d.update(1500, 10_000, 9_980, 1.0)) // settled
        assertEquals(DriftDecision(null, 0.95), d.update(2000, 10_000, 10_600, 1.0)) // ahead: slower
        assertEquals(DriftDecision(10_000, 1.0), d.update(2500, 10_000, 5_000, 1.0)) // far: seek
    }

    @Test fun `catch-ups that do not hold make the guest slow`() {
        val d = DriftController()
        assertNotNull(d.update(0, 20_000, 10_000, 1.0).seekTo)
        assertNull(d.update(5_000, 30_000, 20_000, 1.0).seekTo, "no second seek within 15 s")
        assertNotNull(d.update(16_000, 40_000, 30_000, 1.0).seekTo) // failed once
        assertFalse(d.slow)
        assertNull(d.update(32_000, 60_000, 50_000, 1.0).seekTo) // failed twice: give up
        assertTrue(d.slow)
        d.reset(); assertFalse(d.slow)
    }
}

fun media(ep: String) = MediaRef("Pkg", 1, "https://x/repo.json", "Api", "https://site/title", "Title", ep, "Episode $ep", "Server A", 1080)

/** A player on virtual time: plays at [rate] x speed, stalls for [seekStallMs] after each seek / open. */
class FakePlayer(private val clock: () -> Long, var media: MediaRef? = null) : PartyPlayer {
    var pos = 0.0
    var playing = false
    var curSpeed = 1.0
    var rate = 1.0
    var stallUntil = 0L
    var seekStallMs = 0L
    var openDelayMs = 0L
    var canOpen = true
    var seeks = 0
    private var last = clock()

    private fun advance() {
        val t = clock()
        val from = maxOf(last, stallUntil)
        if (playing && t > from) pos += (t - from) * curSpeed * rate
        last = t
    }

    fun stall(ms: Long) { advance(); stallUntil = clock() + ms }
    override fun positionMs(): Long { advance(); return pos.toLong() }
    override fun isPlaying() = playing
    override fun isBuffering() = clock() < stallUntil
    override fun aheadMs(): Long? = if (isBuffering()) 0 else 30_000
    override fun play() { advance(); playing = true }
    override fun pause() { advance(); playing = false }
    override fun seek(ms: Long) { advance(); pos = ms.toDouble(); seeks++; if (seekStallMs > 0) stallUntil = clock() + seekStallMs }
    override fun setSpeed(speed: Double) { advance(); curSpeed = speed }
    override fun currentMedia() = media
    override suspend fun open(media: MediaRef): Boolean {
        delay(openDelayMs)
        if (!canOpen) return false
        advance(); this.media = media; pos = 0.0; playing = false
        return true
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PartySessionTest {
    private class Peer(val session: PartySession, val player: FakePlayer) {
        val ui get() = session.ui.value
        val events = ArrayList<PartyEvent>()
    }

    private val key = PartyKey.random()
    private var relay = InMemoryRelay()

    private fun TestScope.peer(name: String, skewMs: Long = 0, config: PartyConfig = PartyConfig(), media: MediaRef? = null): Peer {
        val clock = { testScheduler.currentTime + skewMs }
        val p = FakePlayer(clock, media)
        val scope: CoroutineScope = backgroundScope
        val s = PartySession(key, relay.connect(), p, scope, name, selfId = name, now = clock, config = config)
        return Peer(s, p).also { peer -> scope.launch { s.events.collect { peer.events += it } } }
    }

    private fun TestScope.wait(ms: Long) { advanceTimeBy(ms); runCurrent() }

    private fun assertNear(expected: Long, actual: Long, tol: Long, what: String) =
        assertTrue(abs(expected - actual) <= tol, "$what: expected ~$expected, was $actual")

    @Test fun `guest follows play, pause and seek`() = runTest {
        val host = peer("host", media = media("1"))
        val guest = peer("guest")
        host.session.host(); runCurrent()
        guest.session.join(); wait(3000)
        assertEquals("host", guest.ui.hostId)
        assertTrue(media("1").sameAs(guest.player.media), "guest opened the room's episode itself")

        host.session.play(); wait(10_000)
        assertTrue(guest.player.playing)
        assertNear(host.player.positionMs(), guest.player.positionMs(), 300, "in sync while playing")

        host.session.pause(); wait(1000)
        assertFalse(guest.player.playing)
        assertNear(host.player.positionMs(), guest.player.positionMs(), 50, "paused at the same frame")

        host.session.seek(600_000); wait(1000)
        assertNear(600_000, guest.player.positionMs(), 50, "followed the seek")
        assertTrue(guest.events.any { it is PartyEvent.Toast && "jumped to 10:00" in it.text })
    }

    @Test fun `any member can control by default, host only turns it into a nudge`() = runTest {
        val host = peer("host", media = media("1"))
        val guest = peer("guest")
        host.session.host(); runCurrent(); guest.session.join(); wait(3000)
        host.session.play(); wait(5000)

        guest.session.pause(); wait(1000)
        assertFalse(host.player.playing, "guest's pause reached the host")
        assertTrue(host.events.any { it is PartyEvent.Toast && it.text.startsWith("guest paused at") })

        host.session.setMode(ControlMode.HostOnly); wait(1000)
        guest.session.play(); wait(1000)
        assertFalse(host.player.playing, "no control: nothing happens")
        assertFalse(guest.player.playing)
        assertTrue(host.events.any { it is PartyEvent.Nudge && it.action == Action.Play })
    }

    @Test fun `a seek made against an older timeline is dropped`() = runTest {
        val host = peer("host", media = media("1"))
        val g1 = peer("g1"); val g2 = peer("g2")
        host.session.host(); runCurrent(); g1.session.join(); g2.session.join(); wait(3000)
        // g2's seek is sent before it has seen g1's: simulate by raising the host's seq in between without delivering.
        g1.session.seek(100_000); g2.session.seek(5_000); wait(1000)
        assertNear(100_000, host.player.positionMs(), 50, "g2's seek was relative to the old timeline")
        assertNear(100_000, g2.player.positionMs(), 50, "and g2 was brought to the room's position")
    }

    @Test fun `room waits for a buffering guest, ignores short stalls, caps the wait`() = runTest {
        val host = peer("host", media = media("1"))
        val guest = peer("guest")
        host.session.host(); runCurrent(); guest.session.join(); wait(3000)
        host.session.play(); wait(5000)

        guest.player.stall(800); wait(3000)
        assertTrue(host.player.playing, "a short stall does not pause everyone")

        guest.player.stall(4000); wait(2500)
        assertFalse(host.player.playing, "paused for the buffering guest")
        assertTrue(host.ui.room.waiting)
        wait(3000)
        assertTrue(host.player.playing, "resumed once the guest was ready")
        wait(5000)
        assertNear(host.player.positionMs(), guest.player.positionMs(), 300, "back in sync")

        guest.player.stall(120_000); wait(25_000)
        assertTrue(host.player.playing, "waited at most 20 s")
        assertTrue(host.events.any { it is PartyEvent.Toast && it.text.startsWith("Continuing without guest") })
        wait(10_000)
        assertTrue(host.player.playing, "and does not pause for the same guest again until it recovers")
    }

    @Test fun `episode change waits until everyone has opened it`() = runTest {
        val host = peer("host", media = media("1"))
        val guest = peer("guest")
        host.session.host(); runCurrent(); guest.session.join(); wait(3000)
        host.session.play(); wait(3000)
        guest.player.openDelayMs = 4000
        host.player.media = media("2"); host.player.pos = 0.0
        host.session.changeEpisode(media("2")); wait(2000)
        assertFalse(host.player.playing, "host waits while the guest resolves the episode")
        wait(4000)
        assertTrue(host.player.playing && guest.player.playing)
        assertTrue(media("2").sameAs(guest.player.media))
        wait(5000)
        assertNear(host.player.positionMs(), guest.player.positionMs(), 300, "started together")
    }

    @Test fun `skewed clock and a slightly slow player still converge`() = runTest {
        val host = peer("host", media = media("1"))
        val guest = peer("guest", skewMs = 7_000) // guest's clock is 7 s ahead
        guest.player.rate = 0.99
        host.session.host(); runCurrent(); guest.session.join(); wait(5000)
        host.session.play(); wait(120_000)
        assertNear(host.player.positionMs(), guest.player.positionMs(), 300, "clock offset and speed nudges")
        assertTrue(guest.player.seeks <= 2, "corrected by speed, not by repeated seeks (${guest.player.seeks})")
    }

    @Test fun `a guest that buffers after every catch-up is marked slow instead of seeking forever`() = runTest {
        val cfg = PartyConfig(waitForEveryone = false)
        val host = peer("host", config = cfg, media = media("1"))
        val guest = peer("guest", config = cfg)
        host.session.host(); runCurrent(); guest.session.join(); wait(3000)
        guest.player.seekStallMs = 3000
        host.session.play(); wait(3000)
        guest.player.stall(5000) // falls behind once; every catch-up seek then stalls 3 s again
        wait(90_000)
        assertTrue(guest.ui.slow)
        assertTrue(guest.player.seeks <= 4, "seeks: ${guest.player.seeks}")
        assertTrue(host.player.playing, "the room never waited")
        guest.session.catchUp(); runCurrent()
        assertFalse(guest.ui.slow)
    }

    @Test fun `when the host leaves the longest-connected guest takes over`() = runTest {
        val host = peer("host", media = media("1"))
        val g1 = peer("g1"); val g2 = peer("g2")
        host.session.host(); runCurrent()
        g1.session.join(); wait(1000); g2.session.join(); wait(3000)
        host.session.play(); wait(5000)
        host.session.leave(); wait(2000)
        assertEquals("g1", g1.ui.hostId); assertEquals("g1", g2.ui.hostId)
        assertTrue(g1.ui.isHost)
        assertTrue(g2.player.playing, "playback carried on")
        g1.session.pause(); wait(1000)
        assertFalse(g2.player.playing, "g2 follows the new host")
        assertNear(g1.player.positionMs(), g2.player.positionMs(), 50, "same frame")
    }

    @Test fun `a silent member is dropped and the room stops waiting for it`() = runTest {
        val host = peer("host", media = media("1"))
        val guest = peer("guest")
        host.session.host(); runCurrent(); guest.session.join(); wait(3000)
        assertEquals(2, host.ui.members.size)
        guest.session.leave(); wait(1000)
        assertEquals(1, host.ui.members.size)
        assertTrue(host.events.any { it is PartyEvent.Toast && it.text == "guest left" })
    }

    @Test fun `a detached guest is not waited for and rejoins at the room position`() = runTest {
        val host = peer("host", media = media("1"))
        val guest = peer("guest")
        host.session.host(); runCurrent(); guest.session.join(); wait(3000)
        host.session.play(); wait(3000)
        guest.session.detach(); wait(10_000)
        assertFalse(guest.player.playing)
        assertTrue(host.player.playing, "the room did not wait")
        guest.session.rejoin(); wait(3000)
        assertTrue(guest.player.playing)
        assertNear(host.player.positionMs(), guest.player.positionMs(), 300, "back at the room position")
    }

    @Test fun `a guest that cannot open the episode is told and not waited for`() = runTest {
        val host = peer("host", media = media("1"))
        val guest = peer("guest")
        guest.player.canOpen = false
        host.session.host(); runCurrent(); guest.session.join(); wait(3000)
        assertTrue(guest.events.any { it is PartyEvent.NoSource })
        host.session.play(); wait(3000)
        host.session.seek(50_000); wait(2000)
        assertTrue(host.player.playing, "seek barrier skipped the member without a source")
    }

    @Test fun `with real network delay, play still starts on the same frame`() = runTest {
        relay = InMemoryRelay(latencyMs = 150, scope = backgroundScope)
        val host = peer("host", media = media("1"))
        val guest = peer("guest", skewMs = -3_000)
        host.session.host(); runCurrent(); guest.session.join(); wait(6000)
        host.session.play(); wait(300)
        assertFalse(host.player.playing, "host waits for the scheduled start too")
        wait(5000)
        assertNear(host.player.positionMs(), guest.player.positionMs(), 15, "scheduled start absorbed 150 ms of delay")
        host.session.pause(); wait(1000)
        assertNear(host.player.positionMs(), guest.player.positionMs(), 40, "a late-arriving pause lands on the host's frame")
        guest.session.pause(); wait(1000); guest.session.play(); wait(5000)
        assertNear(host.player.positionMs(), guest.player.positionMs(), 15, "also when a guest presses play")
    }

    @Test fun `chat and reactions reach everyone`() = runTest {
        val host = peer("host", media = media("1"))
        val guest = peer("guest")
        host.session.host(); runCurrent(); guest.session.join(); wait(3000)
        guest.session.chat("  hi all  "); guest.session.react("🔥"); wait(500)
        assertTrue(host.events.contains(PartyEvent.ChatLine("guest", "hi all", self = false)))
        assertTrue(host.events.contains(PartyEvent.Reaction("guest", "🔥")))
    }

    @Test fun `frames from another room are ignored`() = runTest {
        val host = peer("host", media = media("1"))
        host.session.host(); runCurrent()
        val other = PartyKey.random()
        relay.connect().send(other.seal(PartyCodec.encode(Hello("intruder", "Mallory", ""))))
        wait(1000)
        assertEquals(listOf("host"), host.ui.members.map { it.id })
    }
}

