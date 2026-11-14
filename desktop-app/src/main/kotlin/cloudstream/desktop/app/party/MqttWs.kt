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

/**
 * Minimal MQTT 3.1.1 client over WebSocket (subprotocol "mqtt"): CONNECT, SUBSCRIBE, PUBLISH QoS 0, PING. Just what a
 * watch party needs to use a public broker as a dumb, untrusted relay for encrypted frames. Java's built-in
 * WebSocket, no dependency; behaves the same on Linux and Windows.
 */
class MqttWs(
    val url: URI,
    private val onMessage: (topic: String, payload: ByteArray) -> Unit,
    private val onClosed: (reason: String) -> Unit,
    private val keepAliveSec: Int = 30,
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build()
    private var ws: WebSocket? = null
    private val connack = CompletableFuture<Int>()
    private val subacks = HashMap<Int, CompletableFuture<Int>>()
    private val inBuf = ByteArrayOutputStream()
    private val closed = AtomicBoolean(false)
    private var nextId = 1
    private val sendLock = Any()
    private var pinger: Thread? = null

    /** Connects and waits for CONNACK. Throws on failure or refusal. */
    fun connect(clientId: String, timeoutMs: Long = 10_000) {
        val listener = object : WebSocket.Listener {
            override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
                val b = ByteArray(data.remaining()).also { data.get(it) }
                synchronized(inBuf) { inBuf.write(b); drain() }
                webSocket.request(1)
                return null
            }
            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? { webSocket.request(1); return null }
            override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? { closedWith("closed $statusCode $reason"); return null }
            override fun onError(webSocket: WebSocket, error: Throwable) { closedWith("error ${error.javaClass.simpleName}: ${error.message}") }
        }
        ws = http.newWebSocketBuilder().subprotocols("mqtt").connectTimeout(Duration.ofSeconds(8))
            .buildAsync(url, listener).get(timeoutMs, TimeUnit.MILLISECONDS)
        val body = ByteArrayOutputStream().apply {
            str("MQTT"); write(4); write(0x02) // level 3.1.1, clean session
            write(keepAliveSec shr 8); write(keepAliveSec and 0xFF); str(clientId)
        }.toByteArray()
        packet(0x10, body)
        val rc = connack.get(timeoutMs, TimeUnit.MILLISECONDS)
        check(rc == 0) { "broker refused the connection (code $rc)" }
        pinger = Thread({
            try { while (!closed.get()) { Thread.sleep(keepAliveSec * 1000L / 2); packet(0xC0, ByteArray(0)) } } catch (_: Exception) {}
        }, "mqtt-ping").apply { isDaemon = true; start() }
    }

    fun subscribe(topic: String, timeoutMs: Long = 10_000) {
        val id = synchronized(subacks) { nextId++.also { subacks[it] = CompletableFuture() } }
        val body = ByteArrayOutputStream().apply { write(id shr 8); write(id and 0xFF); str(topic); write(0) }.toByteArray()
        packet(0x82, body)
        val rc = subacks[id]!!.get(timeoutMs, TimeUnit.MILLISECONDS)
        check(rc != 0x80) { "subscription to $topic refused" }
    }

    fun publish(topic: String, payload: ByteArray) {
        val body = ByteArrayOutputStream().apply { str(topic); write(payload) }.toByteArray()
        packet(0x30, body)
    }

    fun close() {
        if (closed.getAndSet(true)) return
        runCatching { packet(0xE0, ByteArray(0)) }
        runCatching { ws?.sendClose(WebSocket.NORMAL_CLOSURE, "")?.get(2, TimeUnit.SECONDS) }
        runCatching { ws?.abort() }
    }

    val isOpen: Boolean get() = !closed.get() && ws?.isOutputClosed == false

    private fun closedWith(reason: String) {
        if (closed.getAndSet(true)) return
        connack.completeExceptionally(IllegalStateException(reason))
        onClosed(reason)
    }

    private fun packet(type: Int, body: ByteArray) {
        val out = ByteArrayOutputStream(body.size + 5)
        out.write(type)
        var len = body.size
        do { var d = len % 128; len /= 128; if (len > 0) d = d or 0x80; out.write(d) } while (len > 0)
        out.write(body)
        // WebSocket allows one outstanding send: serialise.
        synchronized(sendLock) { ws!!.sendBinary(ByteBuffer.wrap(out.toByteArray()), true).get(10, TimeUnit.SECONDS) }
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
        }
    }

    private fun ByteArrayOutputStream.str(s: String) { val u = s.toByteArray(); write(u.size shr 8); write(u.size and 0xFF); write(u) }
}
