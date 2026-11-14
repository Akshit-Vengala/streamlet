package cloudstream.desktop.app.party

import com.google.gson.Gson
import com.google.gson.JsonParser

/**
 * What is playing, in a form every member can resolve with their OWN installed provider: video links are often tied
 * to the IP/session that resolved them, so links themselves are never shared.
 */
data class MediaRef(
    /** Internal name and version of the provider package that serves the title, and the repo it installs from. */
    val packageName: String,
    val packageVersion: Int,
    val repoUrl: String?,
    val apiName: String,
    val titleUrl: String,
    val titleName: String,
    /** Stable id of the episode within the title (data url of the episode, or "movie"). */
    val episodeKey: String,
    val episodeLabel: String,
    /** The host's chosen source: guests pick the same name, else the same quality, else the first that works. */
    val sourceName: String? = null,
    val quality: Int? = null,
) {
    fun sameAs(o: MediaRef?) = o != null && o.titleUrl == titleUrl && o.episodeKey == episodeKey
}

/** The room's playback, in HOST clock time. */
data class RoomState(
    val seq: Long = 0,
    val media: MediaRef? = null,
    val positionMs: Long = 0,
    val atHostMs: Long = 0,
    val playing: Boolean = false,
    val speed: Double = 1.0,
    /** Paused until everyone is ready (after a seek / episode change, or while someone buffers); resumes by itself. */
    val waiting: Boolean = false,
) {
    /** Playback starts at [atHostMs], which can be slightly in the future (scheduled start): before it, [positionMs]. */
    fun positionAt(hostNowMs: Long): Long =
        if (playing) positionMs + ((hostNowMs - atHostMs).coerceAtLeast(0) * speed).toLong() else positionMs
    fun startsLaterThan(hostNowMs: Long) = playing && hostNowMs < atHostMs
}

enum class ControlMode { Everyone, HostOnly, CoHosts }
enum class Action { Play, Pause, Seek, Episode }

data class Member(val id: String, val name: String, val joinedAt: Long, val coHost: Boolean = false)

/** Wire messages. [to] = null means everyone in the room. */
sealed class PartyMsg {
    abstract val from: String
    open val to: String? get() = null
}

data class Hello(override val from: String, val name: String, val app: String) : PartyMsg()
data class Welcome(override val from: String, override val to: String?, val members: List<Member>, val hostId: String, val mode: ControlMode, val room: RoomState) : PartyMsg()
data class Roster(override val from: String, val members: List<Member>, val hostId: String, val mode: ControlMode) : PartyMsg()
/** [by]/[action]: who changed the room and how (null for heartbeats and automatic waits). */
data class StateMsg(override val from: String, val room: RoomState, val by: String? = null, val action: Action? = null) : PartyMsg()
data class Ping(override val from: String, override val to: String?, val id: Int, val t0: Long) : PartyMsg()
data class Pong(override val from: String, override val to: String?, val id: Int, val t0: Long, val hostT: Long) : PartyMsg()
data class Request(override val from: String, val action: Action, val positionMs: Long? = null, val media: MediaRef? = null, val baseSeq: Long = 0) : PartyMsg()
data class Status(override val from: String, val buffering: Boolean, val aheadMs: Long?, val detached: Boolean, val noSource: Boolean) : PartyMsg()
data class Chat(override val from: String, val text: String) : PartyMsg()
data class React(override val from: String, val emoji: String) : PartyMsg()
data class Bye(override val from: String) : PartyMsg()

/** JSON with a type tag `t`; unknown or malformed frames decode to null (newer clients may add message types). */
object PartyCodec {
    private val gson = Gson()
    private val types = listOf(
        Hello::class, Welcome::class, Roster::class, StateMsg::class, Ping::class, Pong::class,
        Request::class, Status::class, Chat::class, React::class, Bye::class,
    ).associate { it.simpleName!! to it.java }

    fun encode(m: PartyMsg): ByteArray {
        val o = gson.toJsonTree(m).asJsonObject
        o.addProperty("t", m::class.simpleName)
        return o.toString().toByteArray()
    }

    fun decode(bytes: ByteArray): PartyMsg? = runCatching {
        val o = JsonParser.parseString(String(bytes)).asJsonObject
        val cls = types[o.get("t")?.asString] ?: return null
        gson.fromJson(o, cls)
    }.getOrNull()
}
