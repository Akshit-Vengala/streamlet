package cloudstream.desktop.app.party

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/** Opaque frames to/from the other members of one room (the relay forwards; it cannot read them). */
interface PartyTransport {
    val incoming: Flow<ByteArray>
    suspend fun send(frame: ByteArray)
    suspend fun close()
}

/**
 * A relay in memory: tests, and later LAN mode. Frames go to every other connected client, after [latencyMs]
 * (simulated network delay; needs [scope]).
 */
class InMemoryRelay(private val latencyMs: Long = 0, private val scope: kotlinx.coroutines.CoroutineScope? = null) {
    private val clients = java.util.concurrent.CopyOnWriteArrayList<Client>()

    fun connect(): PartyTransport = Client().also { clients += it }

    private inner class Client : PartyTransport {
        val flow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 4096, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        override val incoming: Flow<ByteArray> = flow
        override suspend fun send(frame: ByteArray) {
            val to = clients.filter { it !== this }
            if (latencyMs > 0 && scope != null) scope.launch { kotlinx.coroutines.delay(latencyMs); to.forEach { it.flow.emit(frame) } }
            else to.forEach { it.flow.emit(frame) }
        }
        override suspend fun close() { clients.remove(this) }
    }
}
