package cloudstream.desktop.app

import cloudstream.desktop.app.party.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.*

/**
 * A tiny MQTT 3.1.1 broker over WebSocket on localhost, just enough for [MqttWs]: CONNECT, SUBSCRIBE, PUBLISH (QoS 0,
 * echoed to every subscriber like real brokers do), PINGREQ, DISCONNECT. Switches simulate broken brokers.
 */
class FakeBroker : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val url: URI get() = URI("ws://127.0.0.1:${server.localPort}/mqtt")
    /** false: PINGREQ gets no answer (with [forward] false too: a dead, half-open link). */
    @Volatile var answerPings = true
    /** false: publishes are accepted and silently dropped. */
    @Volatile var forward = true
    val connects = AtomicInteger()
    private val conns = CopyOnWriteArrayList<Conn>()

    private inner class Conn(val socket: Socket) {
        val out: OutputStream = socket.getOutputStream()
        val topics = CopyOnWriteArrayList<String>()
        @Synchronized fun frame(op: Int, data: ByteArray) {
            val h = ByteArrayOutputStream()
            h.write(0x80 or op)
            when {
                data.size < 126 -> h.write(data.size)
                data.size < 65536 -> { h.write(126); h.write(data.size shr 8); h.write(data.size and 0xFF) }
                else -> { h.write(127); for (i in 7 downTo 0) h.write(((data.size.toLong() shr (8 * i)) and 0xFF).toInt()) }
            }
            out.write(h.toByteArray()); out.write(data); out.flush()
        }
    }

    init {
        thread(isDaemon = true, name = "fake-broker") {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { serve(s) }
            }
        }
    }

    /** The broker drops every connection (remote close). */
    fun dropAll() = conns.forEach { runCatching { it.socket.close() } }
    val clientCount get() = conns.size

    override fun close() { runCatching { server.close() }; dropAll() }

    private fun serve(s: Socket) {
        val c = Conn(s)
        try {
            val inp = DataInputStream(s.getInputStream().buffered())
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) head.append(inp.readUnsignedByte().toChar())
            val key = head.lines().first { it.lowercase().startsWith("sec-websocket-key:") }.substringAfter(':').trim()
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
            c.out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\nSec-WebSocket-Protocol: mqtt\r\n\r\n").toByteArray()); c.out.flush()
            conns += c
            val buf = ByteArrayOutputStream()
            while (true) {
                val b0 = inp.readUnsignedByte(); val b1 = inp.readUnsignedByte()
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) len = inp.readUnsignedShort().toLong() else if (len == 127L) len = inp.readLong()
                val mask = if (b1 and 0x80 != 0) ByteArray(4).also { inp.readFully(it) } else null
                val data = ByteArray(len.toInt()).also { inp.readFully(it) }
                if (mask != null) for (i in data.indices) data[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
                when (b0 and 0x0F) {
                    8 -> break
                    9 -> c.frame(0xA, data)
                    0, 2 -> { buf.write(data); mqtt(c, buf) }
                }
            }
        } catch (_: Exception) {
        } finally { conns -= c; runCatching { s.close() } }
    }

    private fun mqtt(c: Conn, buf: ByteArrayOutputStream) {
        val all = buf.toByteArray(); var p = 0
        while (all.size - p >= 2) {
            var mul = 1; var len = 0; var i = p + 1; var ok = false
            while (i < all.size && i < p + 5) { val d = all[i].toInt() and 0xFF; len += (d and 0x7F) * mul; mul *= 128; i++; if (d and 0x80 == 0) { ok = true; break } }
            if (!ok || all.size - i < len) break
            val raw = all.copyOfRange(p, i + len); val body = all.copyOfRange(i, i + len)
            when ((all[p].toInt() and 0xFF) shr 4) {
                1 -> { connects.incrementAndGet(); c.frame(2, byteArrayOf(0x20, 2, 0, 0)) }
                8 -> {
                    val tl = ((body[2].toInt() and 0xFF) shl 8) or (body[3].toInt() and 0xFF)
                    c.topics += String(body, 4, tl)
                    c.frame(2, byteArrayOf(0x90.toByte(), 3, body[0], body[1], 0))
                }
                3 -> if (forward) {
                    val tl = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
                    val topic = String(body, 2, tl)
                    conns.filter { topic in it.topics }.forEach { runCatching { it.frame(2, raw) } }
                }
                12 -> if (answerPings) c.frame(2, byteArrayOf(0xD0.toByte(), 0))
                14 -> runCatching { c.socket.close() }
            }
            p = i + len
        }
        buf.reset(); buf.write(all, p, all.size - p)
    }
}

class PartyRelayTest {
    /** Errors from the real-thread coroutines: reported HERE, not by whichever coroutine test happens to run next. */
    private val leaked = CopyOnWriteArrayList<Throwable>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> leaked += e })
    private val brokers = ArrayList<FakeBroker>()
    private val transports = ArrayList<PublicRelayTransport>()

    @AfterTest fun tearDown() {
        runBlocking { transports.forEach { it.close() } }
        brokers.forEach { it.close() }
        scope.cancel()
        leaked.firstOrNull()?.let { throw AssertionError("a background coroutine failed", it) }
    }

    private fun broker() = FakeBroker().also { brokers += it }
    private fun transport(room: String, vararg b: FakeBroker) =
        PublicRelayTransport(room, b.map { it.url }, scope, pingEveryMs = 500, deadAfterMs = 2000, minBackoffMs = 100)
            .also { transports += it; it.start() }

    private suspend fun until(timeoutMs: Long, cond: () -> Boolean) =
        withTimeoutOrNull(timeoutMs) { while (!cond()) delay(20); true } ?: false

    private fun collect(t: PublicRelayTransport): MutableList<String> {
        val got = CopyOnWriteArrayList<String>()
        scope.launch { t.incoming.collect { got += String(it.copyOfRange(12, it.size)) } }
        return got
    }

    /** A frame shaped like a sealed one: 12 random "nonce" bytes, then the text. */
    private fun frame(text: String) = ByteArray(12).also(java.util.Random()::nextBytes) + text.toByteArray()

    @Test fun `a frame sent through two brokers arrives once, and the sender gets no echo`() = runBlocking {
        val a = broker(); val b = broker()
        val tx = transport("r1", a, b); val rx = transport("r1", a, b)
        val gotRx = collect(rx); val gotTx = collect(tx)
        assertTrue(until(15_000) { tx.connected.value.size == 2 && rx.connected.value.size == 2 })
        tx.send(frame("hello"))
        assertTrue(until(10_000) { gotRx.isNotEmpty() })
        delay(500)
        assertEquals(listOf("hello"), gotRx)
        assertEquals(emptyList(), gotTx)
    }

    @Test fun `a locally dropped connection leaves the connected set and comes back`() = runBlocking {
        val a = broker()
        val t = transport("r2", a)
        assertTrue(until(15_000) { a.url in t.connected.value })
        t.dropForTest(a.url)
        assertTrue(until(5000) { a.url !in t.connected.value }, "dropped connection still listed")
        assertTrue(until(15_000) { a.url in t.connected.value }, "did not reconnect")
        assertEquals(2, a.connects.get())
    }

    @Test fun `the broker closing the connection is followed by a reconnect`() = runBlocking {
        val a = broker()
        val t = transport("r3", a); val rx = transport("r3", a)
        val got = collect(rx)
        assertTrue(until(15_000) { a.url in t.connected.value && a.url in rx.connected.value })
        a.dropAll()
        assertTrue(until(15_000) { a.connects.get() >= 4 && a.url in t.connected.value && a.url in rx.connected.value }, "did not reconnect")
        t.send(frame("after"))
        assertTrue(until(10_000) { "after" in got })
    }

    @Test fun `a silent broker (half-open link) is detected and reconnected`() = runBlocking {
        val a = broker()
        val t = transport("r4", a)
        assertTrue(until(15_000) { a.url in t.connected.value })
        a.answerPings = false; a.forward = false
        assertTrue(until(10_000) { a.url !in t.connected.value }, "dead link not noticed")
        a.answerPings = true; a.forward = true
        assertTrue(until(15_000) { a.url in t.connected.value }, "did not come back")
        delay(5000) // > 2x the dead-link timeout
        assertTrue(a.url in t.connected.value, "stays connected while the broker answers")
    }

    @Test fun `a dead broker neither delays sending nor delivery through the others`() = runBlocking {
        val dead = broker(); val good = broker()
        val tx = transport("r5", dead, good); val rx = transport("r5", dead, good)
        val got = collect(rx)
        assertTrue(until(15_000) { tx.connected.value.size == 2 && rx.connected.value.size == 2 })
        dead.forward = false; dead.answerPings = false
        val t0 = System.nanoTime()
        repeat(20) { tx.send(frame("m$it")) }
        val sendMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue(sendMs < 1000, "send took $sendMs ms")
        assertTrue(until(10_000) { got.size == 20 }, "got ${got.size}/20")
    }

    @Test fun `a full party works over the brokers`() = runBlocking {
        val a = broker(); val b = broker()
        val key = PartyKey.random()
        val tHost = transport(key.roomId, a, b); val tGuest = transport(key.roomId, a, b)
        val clock = { System.nanoTime() / 1_000_000 }
        val hp = FakePlayer(clock, media("1")); val gp = FakePlayer(clock)
        val one = Dispatchers.Default.limitedParallelism(1)
        val hs = PartySession(key, tHost, hp, CoroutineScope(scope.coroutineContext + one), "host", "host")
        val gs = PartySession(key, tGuest, gp, CoroutineScope(scope.coroutineContext + Dispatchers.Default.limitedParallelism(1)), "guest", "guest")
        hs.host(); gs.join() // join waits for a broker itself
        assertTrue(until(15_000) { gs.ui.value.connected && media("1").sameAs(gp.media) }, "joined")
        delay(1500)
        hs.play()
        assertTrue(until(10_000) { gp.playing }, "play arrived")
        a.dropAll()
        hs.pause()
        assertTrue(until(10_000) { !gp.playing }, "pause arrived with one broker gone")
        gs.leave(); hs.leave()
    }
}
