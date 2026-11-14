package cloudstream.desktop.app.party

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * `--relay-probe [broker urls...]`: measures public MQTT brokers as watch party relays, then runs a real two-member
 * party over them. Sends only random test bytes / encrypted frames; nothing about the user.
 */
object RelayProbe {
    fun run(argv: Array<String>): Int {
        val urls = argv.dropWhile { it != "--relay-probe" }.drop(1).filter { it.startsWith("ws") }.map(::URI)
            .ifEmpty { PublicRelayTransport.DEFAULT_BROKERS }
        println("== per broker")
        val usable = urls.filter { probeBroker(it) }
        if (usable.isEmpty()) { println("no usable broker"); return 1 }
        println("\n== live party over ${usable.size} broker(s): ${usable.joinToString { it.host }}")
        return runBlocking { party(usable) }
    }

    private fun pct(xs: List<Long>, p: Double) = xs.sorted().let { if (it.isEmpty()) -1 else it[((it.size - 1) * p).toInt()] }

    private fun probeBroker(u: URI): Boolean {
        val topic = "wp1/probe-" + PartyKey.newMemberId().lowercase()
        val rtts = CopyOnWriteArrayList<Long>()
        val got = ConcurrentHashMap<Int, Int>() // payload size -> count
        val c = MqttWs(u, onMessage = { _, p ->
            got.merge(p.size, 1, Int::plus)
            if (p.size == 300) rtts += (System.nanoTime() - java.nio.ByteBuffer.wrap(p, 0, 8).long) / 1_000_000
        }, onClosed = { println("   ${u.host}: closed: $it") })
        val t0 = System.nanoTime()
        try {
            c.connect("wpprobe" + PartyKey.newMemberId().lowercase())
            val connectMs = (System.nanoTime() - t0) / 1_000_000
            c.subscribe(topic)
            fun msg(size: Int) = ByteArray(size).also { java.nio.ByteBuffer.wrap(it).putLong(System.nanoTime()) }
            repeat(20) { c.publish(topic, msg(300)); Thread.sleep(150) }
            Thread.sleep(1500)
            val echoed = got[300] ?: 0
            repeat(100) { c.publish(topic, msg(301)) }
            Thread.sleep(3000)
            val burst = got[301] ?: 0
            c.publish(topic, msg(16 * 1024)); c.publish(topic, msg(64 * 1024))
            Thread.sleep(3000)
            println("   %-26s connect %4d ms | echo %2d/20 p50 %4d ms p95 %4d ms | burst %3d/100 | 16 KB %s 64 KB %s".format(
                u.host, connectMs, echoed, pct(rtts, 0.5), pct(rtts, 0.95), burst,
                if (got[16 * 1024] != null) "ok" else "LOST", if (got[64 * 1024] != null) "ok" else "LOST"))
            return echoed >= 18
        } catch (e: Exception) {
            println("   %-26s FAILED: %s".format(u.host, "${e.javaClass.simpleName}: ${e.message?.take(140)}"))
            return false
        } finally { c.close() }
    }

    /** A player on the wall clock (no video): enough to see the sync working end to end. */
    private class ClockPlayer(var media: MediaRef?) : PartyPlayer {
        private var pos = 0.0; private var playing = false; private var speed = 1.0
        private var last = System.currentTimeMillis()
        @Synchronized private fun adv() { val t = System.currentTimeMillis(); if (playing) pos += (t - last) * speed; last = t }
        @Synchronized override fun positionMs(): Long { adv(); return pos.toLong() }
        override fun isPlaying() = playing
        override fun isBuffering() = false
        override fun aheadMs(): Long? = 30_000
        @Synchronized override fun play() { adv(); playing = true }
        @Synchronized override fun pause() { adv(); playing = false }
        @Synchronized override fun seek(ms: Long) { adv(); pos = ms.toDouble() }
        @Synchronized override fun setSpeed(speed: Double) { adv(); this.speed = speed }
        override fun currentMedia() = media
        override suspend fun open(media: MediaRef): Boolean { this.media = media; seek(0); return true }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun party(brokers: List<URI>): Int {
        val key = PartyKey.random()
        val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val tHost = PublicRelayTransport(key.roomId, brokers, io) { println("   [host] $it") }
        val tGuest = PublicRelayTransport(key.roomId, brokers, io) { println("   [guest] $it") }
        val m = MediaRef("Pkg", 1, null, "Api", "https://example.invalid/t", "Probe title", "e1", "Episode 1")
        val hp = ClockPlayer(m); val gp = ClockPlayer(null)
        val hs = PartySession(key, tHost, hp, CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1)), "host", "host")
        val gs = PartySession(key, tGuest, gp, CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1)), "guest", "guest")
        var failures = 0
        fun check(ok: Boolean, what: String) { println((if (ok) "   [ok]   " else "   [FAIL] ") + what); if (!ok) failures++ }
        suspend fun until(timeoutMs: Long, cond: () -> Boolean): Long? {
            val t0 = System.currentTimeMillis()
            return withTimeoutOrNull(timeoutMs) { while (!cond()) delay(10); System.currentTimeMillis() - t0 }
        }
        try {
            tHost.start(); tGuest.start()
            check(until(15_000) { tHost.connected.value.size == brokers.size && tGuest.connected.value.size == brokers.size } != null,
                "both connected to all brokers")
            hs.host()
            delay(300)
            val joinMs = run { val t0 = System.currentTimeMillis(); gs.join(); until(15_000) { gs.ui.value.connected }?.let { System.currentTimeMillis() - t0 } }
            check(joinMs != null, "guest joined in $joinMs ms")
            check(until(5_000) { m.sameAs(gp.currentMedia()) } != null, "guest opened the room's title itself")
            delay(4000) // a few pings for the clock offset

            hs.play()
            val playMs = until(5_000) { gp.isPlaying() }
            check(playMs != null, "play reached the guest in $playMs ms")
            delay(8000)
            val drifts = (1..10).map { delay(500); abs(hp.positionMs() - gp.positionMs()) }
            check(drifts.max() < 60, "in sync while playing: max drift ${drifts.max()} ms (samples $drifts)")

            hs.pause()
            val pauseMs = until(5_000) { !gp.isPlaying() }
            check(pauseMs != null, "pause reached the guest in $pauseMs ms")
            delay(500)
            check(abs(hp.positionMs() - gp.positionMs()) < 100, "paused on the same frame (${abs(hp.positionMs() - gp.positionMs())} ms apart)")

            hs.seek(1_800_000)
            val seekMs = until(5_000) { abs(gp.positionMs() - 1_800_000) < 100 }
            check(seekMs != null, "seek reached the guest in $seekMs ms")

            gs.play()
            val reqMs = until(5_000) { hp.isPlaying() }
            check(reqMs != null, "guest's play request reached the host in $reqMs ms")

            // Redundancy: the guest loses one broker; the room must not notice.
            val lost = brokers.first()
            tGuest.dropForTest(lost)
            delay(200)
            hs.pause()
            val afterDropMs = until(5_000) { !gp.isPlaying() }
            check(afterDropMs != null, "with ${lost.host} gone, pause still arrived in $afterDropMs ms (via the others)")
            check(until(40_000) { lost in tGuest.connected.value } != null, "${lost.host} reconnected by itself")

            val soak = System.getenv("RELAY_SOAK")?.toLongOrNull() ?: 0
            if (soak > 0) {
                println("   soak: idle for $soak s ...")
                delay(soak * 1000)
                check(tHost.connected.value.isNotEmpty() && tGuest.connected.value.isNotEmpty(),
                    "after $soak s idle: host on ${tHost.connected.value.size}, guest on ${tGuest.connected.value.size} broker(s)")
                hs.play()
                val soakMs = until(5_000) { gp.isPlaying() }
                check(soakMs != null, "after $soak s idle, play reached the guest in $soakMs ms")
                delay(6000)
                val d = abs(hp.positionMs() - gp.positionMs())
                check(d < 100, "in sync after the idle period: $d ms")
            }

            val hostDeliveries = hs.ui.value.statuses["guest"]
            check(hostDeliveries != null, "host sees the guest's status (buffering=${hostDeliveries?.buffering})")
        } finally {
            gs.leave(); hs.leave()
            io.cancel()
        }
        println("== result: ${if (failures == 0) "OK" else "$failures failure(s)"}")
        return if (failures == 0) 0 else 1
    }
}
