package cloudstream.desktop.app.party

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

/**
 * Fallback transport over free public MQTT brokers: every frame is published to ALL connected brokers and the first
 * copy to arrive wins, so one broker going down or dropping messages does not matter. Frames are already end-to-end
 * encrypted ([PartyKey]); a broker sees a topic derived from the room id and ciphertext, nothing else.
 *
 * Dedup and self-echo: a frame starts with its random 12-byte nonce, used as its id.
 */
class PublicRelayTransport(
    roomId: String,
    private val brokers: List<URI> = DEFAULT_BROKERS,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) : PartyTransport {
    val topic = "wp1/$roomId"
    private val clientId = "wp" + PartyKey.newMemberId().lowercase()
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    override val incoming: Flow<ByteArray> = inbox.receiveAsFlow()

    private val conns = java.util.concurrent.ConcurrentHashMap<URI, MqttWs>()
    private val _connected = MutableStateFlow<Set<URI>>(emptySet())
    /** Brokers currently connected and subscribed. */
    val connected: StateFlow<Set<URI>> = _connected
    private val seen = object : LinkedHashMap<String, Unit>(512, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > 4096
    }
    private val jobs = ArrayList<Job>()
    @Volatile private var closing = false

    /** Connects to every broker in the background, reconnecting with backoff. Returns at once. */
    fun start() { brokers.forEach { u -> jobs += scope.launch(Dispatchers.IO) { keepConnected(u) } } }

    private suspend fun keepConnected(u: URI) {
        var backoff = 1000L
        while (scope.isActive && !closing) {
            val done = kotlinx.coroutines.CompletableDeferred<String>()
            val c = MqttWs(u, onMessage = { _, p -> onFrame(p) }, onClosed = { done.complete(it) })
            val t0 = System.nanoTime()
            try {
                c.connect(clientId + "-" + brokers.indexOf(u))
                c.subscribe(topic)
                conns[u] = c; _connected.value = conns.keys.toSet()
                log("relay ${u.host}: connected in ${(System.nanoTime() - t0) / 1_000_000} ms")
                backoff = 1000L
                log("relay ${u.host}: closed (${done.await()})")
            } catch (e: kotlinx.coroutines.CancellationException) {
                c.close(); throw e
            } catch (e: Exception) {
                log("relay ${u.host}: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
                c.close()
            }
            conns.remove(u); _connected.value = conns.keys.toSet()
            if (closing) return
            delay(backoff); backoff = (backoff * 2).coerceAtMost(30_000)
        }
    }

    private fun idOf(frame: ByteArray) = frame.copyOfRange(0, minOf(12, frame.size)).joinToString("") { "%02x".format(it) }

    private fun onFrame(p: ByteArray) {
        val id = idOf(p)
        val fresh = synchronized(seen) { seen.put(id, Unit) == null }
        if (fresh) inbox.trySend(p)
    }

    override suspend fun send(frame: ByteArray) {
        synchronized(seen) { seen[idOf(frame)] = Unit } // our own echo comes back from every broker: drop it
        val targets = conns.values.filter { it.isOpen }
        if (targets.isEmpty()) return // the session's heartbeats re-send state once a broker is back
        withContext(Dispatchers.IO) {
            targets.forEach { c -> runCatching { c.publish(topic, frame) }.onFailure { log("relay ${c.url.host}: publish failed: ${it.message}") } }
        }
    }

    override suspend fun close() {
        closing = true
        jobs.forEach { it.cancel() }
        withContext(Dispatchers.IO) { conns.values.forEach { it.close() } }
        conns.clear(); _connected.value = emptySet()
        inbox.close()
    }

    /** Test hook: drop one broker's connection as if the network to it failed (it reconnects). */
    internal fun dropForTest(u: URI) { conns[u]?.close() }

    companion object {
        /** Public brokers with secure WebSocket on standard-ish ports. Editable in settings later. */
        val DEFAULT_BROKERS = listOf(
            URI("wss://broker.emqx.io:8084/mqtt"),
            URI("wss://broker.hivemq.com:8884/mqtt"),
            URI("wss://test.mosquitto.org:8081/mqtt"),
        )
    }
}
