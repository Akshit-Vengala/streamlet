package cloudstream.desktop.app.party

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal MQTT 3.1.1 client over WebSocket (subprotocol "mqtt"): CONNECT, SUBSCRIBE, PUBLISH QoS 0, PING. Just what a
 * watch party needs to use a public broker as a dumb, untrusted relay for encrypted frames. Java's built-in
 * WebSocket, no dependency; behaves the same on Linux and Windows.
 *
 * Lifecycle: every way a connection can end (local [close], the broker closing, a socket error, a send that fails or
 * times out, no reply to our pings for [deadAfterMs]) goes through one shutdown, which calls [onClosed] exactly once.
 * Sends are queued (one WebSocket send outstanding at a time, in order) and never block the caller.
 */
class MqttWs(
    val url: URI,
    private val onMessage: (topic: String, payload: ByteArray) -> Unit,
    private val onClosed: (reason: String) -> Unit,
    private val keepAliveSec: Int = 30,
    /** PINGREQ this often; the broker answers each one, so silence for [deadAfterMs] means a dead link (sleep, Wi-Fi change). */
    private val pingEveryMs: Long = 10_000,
    private val deadAfterMs: Long = 25_000,
    private val sendTimeoutMs: Long = 5_000,
    /** More queued sends than this = the broker is not keeping up: drop the connection (the others carry on). */
    private val maxQueuedSends: Int = 256,
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build()
    @Volatile private var ws: WebSocket? = null
    private val connack = CompletableFuture<Int>()
    private val subacks = HashMap<Int, CompletableFuture<Int>>()
    private val inBuf = ByteArrayOutputStream()
    private val closed = AtomicBoolean(false)
    private var nextId = 1
    private val sendLock = Any()
    private var tail: CompletableFuture<*> = CompletableFuture.completedFuture(null)
    private val queued = AtomicInteger()
    @Volatile private var lastRxNanos = System.nanoTime()
    @Volatile private var pinger: Thread? = null

    /** Connects and waits for CONNACK. Throws on failure or refusal (the connection is then closed). */
    fun connect(clientId: String, timeoutMs: Long = 10_000) {
        val listener = object : WebSocket.Listener {
            override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
                lastRxNanos = System.nanoTime()
                val b = ByteArray(data.remaining()).also { data.get(it) }
                synchronized(inBuf) { inBuf.write(b); drain() }
                webSocket.request(1)
                return null
            }
            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? { webSocket.request(1); return null }
            override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? { shutdown("closed $statusCode $reason"); return null }
            override fun onError(webSocket: WebSocket, error: Throwable) { shutdown("error ${error.javaClass.simpleName}: ${error.message}") }
        }
        try {
            val socket = http.newWebSocketBuilder().subprotocols("mqtt").connectTimeout(Duration.ofSeconds(8))
                .buildAsync(url, listener).get(timeoutMs, TimeUnit.MILLISECONDS)
            ws = socket
            check(!closed.get()) { "closed while connecting" }
            val body = ByteArrayOutputStream().apply {
                str("MQTT"); write(4); write(0x02) // level 3.1.1, clean session
                write(keepAliveSec shr 8); write(keepAliveSec and 0xFF); str(clientId)
            }.toByteArray()
            packet(0x10, body)
            val rc = connack.get(timeoutMs, TimeUnit.MILLISECONDS)
            check(rc == 0) { "broker refused the connection (code $rc)" }
        } catch (e: Exception) {
            shutdown("connect failed: ${e.javaClass.simpleName}: ${e.message}")
            runCatching { ws?.abort() } // closed while connecting: shutdown ran before the socket existed
            throw e
        }
        lastRxNanos = System.nanoTime()
        pinger = Thread({
            try {
                while (!closed.get()) {
                    Thread.sleep(pingEveryMs)
                    if (closed.get()) break
                    if ((System.nanoTime() - lastRxNanos) / 1_000_000 > deadAfterMs) {
                        shutdown("no reply from the broker for ${deadAfterMs / 1000.0} s"); break
                    }
                    packet(0xC0, ByteArray(0))
                }
            } catch (_: InterruptedException) {}
        }, "mqtt-ping").apply { isDaemon = true; start() }
    }

    /** Subscribes and waits for SUBACK. Throws on failure (the connection is then closed). */
    fun subscribe(topic: String, timeoutMs: Long = 10_000) {
        val id: Int
        val ack = CompletableFuture<Int>()
        synchronized(subacks) { id = nextId++; subacks[id] = ack }
        if (closed.get()) ack.completeExceptionally(IllegalStateException("closed"))
        try {
            val body = ByteArrayOutputStream().apply { write(id shr 8); write(id and 0xFF); str(topic); write(0) }.toByteArray()
            packet(0x82, body)
            val rc = ack.get(timeoutMs, TimeUnit.MILLISECONDS)
            check(rc != 0x80) { "subscription to $topic refused" }
        } catch (e: Exception) {
            shutdown("subscribe failed: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    /** Queues a QoS 0 publish; returns at once. A failed send closes the connection (see [onClosed]). */
    fun publish(topic: String, payload: ByteArray) {
        val body = ByteArrayOutputStream().apply { str(topic); write(payload) }.toByteArray()
        packet(0x30, body)
    }

    fun close() = shutdown("closed locally", graceful = true)

    val isOpen: Boolean get() = !closed.get() && ws?.isOutputClosed == false

    /** The one way out. Idempotent; [onClosed] runs exactly once. */
    private fun shutdown(reason: String, graceful: Boolean = false) {
        if (closed.getAndSet(true)) return
        val socket = ws
        if (graceful && socket != null) {
            runCatching { packet(0xE0, ByteArray(0), whileClosing = true).get(1, TimeUnit.SECONDS) } // DISCONNECT
            runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "").get(1, TimeUnit.SECONDS) }
        }
        runCatching { socket?.abort() }
        val err = IllegalStateException(reason)
        connack.completeExceptionally(err)
        synchronized(subacks) { subacks.values.forEach { it.completeExceptionally(err) }; subacks.clear() }
        pinger?.takeIf { it !== Thread.currentThread() }?.interrupt()
        runCatching { onClosed(reason) }
    }

    private fun packet(type: Int, body: ByteArray, whileClosing: Boolean = false): CompletableFuture<*> {
        val socket = ws
        if (socket == null || (closed.get() && !whileClosing)) return CompletableFuture.failedFuture<Unit>(IllegalStateException("not connected"))
        val out = ByteArrayOutputStream(body.size + 5)
        out.write(type)
        var len = body.size
        do { var d = len % 128; len /= 128; if (len > 0) d = d or 0x80; out.write(d) } while (len > 0)
        out.write(body)
        val bytes = out.toByteArray()
        if (queued.incrementAndGet() > maxQueuedSends) {
            queued.decrementAndGet()
            if (!whileClosing) shutdown("broker is not keeping up ($maxQueuedSends sends queued)")
            return CompletableFuture.failedFuture<Unit>(IllegalStateException("send queue full"))
        }
        // WebSocket allows one outstanding send: chain them, in order, without blocking the caller.
        synchronized(sendLock) {
            val next = tail.handle { _, _ -> null }
                .thenCompose { socket.sendBinary(ByteBuffer.wrap(bytes), true) }
                .orTimeout(sendTimeoutMs, TimeUnit.MILLISECONDS)
            next.whenComplete { _, e ->
                queued.decrementAndGet()
                if (e != null && !whileClosing) shutdown("send failed: ${(e.cause ?: e).let { "${it.javaClass.simpleName}: ${it.message}" }}")
            }
            tail = next
            return next
        }
    }

    /** Parses every complete MQTT packet in [inBuf] (packets can span or share WebSocket frames). */
    private fun drain() {
        val all = inBuf.toByteArray()
        var p = 0
        while (true) {
            if (all.size - p < 2) break
            var mul = 1; var len = 0; var i = p + 1; var ok = false
            while (i < all.size && i < p + 5) {
                val d = all[i].toInt() and 0xFF
                len += (d and 0x7F) * mul; mul *= 128; i++
                if (d and 0x80 == 0) { ok = true; break }
            }
            if (!ok || all.size - i < len) break
            handle(all[p].toInt() and 0xFF, all.copyOfRange(i, i + len))
            p = i + len
        }
        inBuf.reset(); inBuf.write(all, p, all.size - p)
    }

    private fun handle(header: Int, b: ByteArray) {
        when (header shr 4) {
            2 -> connack.complete(b.getOrElse(1) { -1 }.toInt() and 0xFF)
            9 -> synchronized(subacks) { subacks.remove(((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)) }
                ?.complete(b.getOrElse(2) { 0 }.toInt() and 0xFF)
            3 -> {
                val tl = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
                val topic = String(b, 2, tl)
                var off = 2 + tl
                if ((header shr 1) and 3 > 0) off += 2 // QoS > 0 carries a packet id (we never ask for it)
                runCatching { onMessage(topic, b.copyOfRange(off, b.size)) }
            }
            13 -> {} // PINGRESP: arriving at all is the point (lastRxNanos)
        }
    }

    private fun ByteArrayOutputStream.str(s: String) { val u = s.toByteArray(); write(u.size shr 8); write(u.size and 0xFF); write(u) }
}
