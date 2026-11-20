package cloudstream.desktop.app.party

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
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
    /** For matching the episode when its data string differs between machines; artwork for the title page. */
    val season: Int? = null,
    val episode: Int? = null,
    val posterUrl: String? = null,
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

/**
 * [joinedAt] is the join ORDER (1, 2, 3 ... assigned by the host as max + 1), not a time: it is compared across
 * machines (host election, two-host tie-break), where clocks do not agree.
 */
data class Member(val id: String, val name: String, val joinedAt: Long, val coHost: Boolean = false)

/** Wire messages. [to] = null means everyone in the room. */
sealed class PartyMsg {
    abstract val from: String
    open val to: String? get() = null
}

data class Hello(override val from: String, val name: String, val app: String? = null) : PartyMsg()
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

/** A decoded frame: the message and its sender's frame counter [n] (replay protection, see PartySession). */
data class PartyFrame(val msg: PartyMsg, val n: Long)

/**
 * JSON with a type tag `t` and the sender's frame counter `n`. Unknown, malformed or incomplete frames decode to null
 * (newer clients may add message types). Gson ignores Kotlin nullability, so required fields, enum names and value
 * ranges are checked on the JSON first: a frame from a different app version must never reach the session with
 * nulls in non-null fields.
 */
object PartyCodec {
    private val gson = Gson()
    private val types = listOf(
        Hello::class, Welcome::class, Roster::class, StateMsg::class, Ping::class, Pong::class,
        Request::class, Status::class, Chat::class, React::class, Bye::class,
    ).associate { it.simpleName!! to it.java }

    fun encode(m: PartyMsg, n: Long = 0): ByteArray {
        val o = gson.toJsonTree(m).asJsonObject
        o.addProperty("t", m::class.simpleName)
        o.addProperty("n", n)
        return o.toString().toByteArray()
    }

    fun decode(bytes: ByteArray): PartyMsg? = decodeFrame(bytes)?.msg

    fun decodeFrame(bytes: ByteArray): PartyFrame? = try {
        val o = JsonParser.parseString(String(bytes)).asJsonObject
        val t = o.get("t")?.takeIf { it.isString() }?.asString
        val cls = types[t]
        if (cls == null || !valid(t!!, o)) null
        else PartyFrame(gson.fromJson(o, cls), if (o.isNum("n")) o.get("n").asLong else 0)
    } catch (_: Exception) { null }

    private val modes = ControlMode.entries.map { it.name }.toSet()
    private val actions = Action.entries.map { it.name }.toSet()

    private fun valid(t: String, o: JsonObject): Boolean = o.isStr("from") && when (t) {
        "Hello" -> o.isStr("name") && o.optStr("app")
        "Welcome" -> o.isStr("to") && o.members("members") && o.isStr("hostId") && o.isEnum("mode", modes) && o.room("room")
        "Roster" -> o.members("members") && o.isStr("hostId") && o.isEnum("mode", modes)
        "StateMsg" -> o.room("room") && o.optStr("by") && (o.absent("action") || o.isEnum("action", actions))
        "Ping" -> o.isStr("to") && o.isNum("id") && o.isNum("t0")
        "Pong" -> o.isStr("to") && o.isNum("id") && o.isNum("t0") && o.isNum("hostT")
        "Request" -> o.isEnum("action", actions) && o.isNum("baseSeq") && (o.absent("positionMs") || o.isNum("positionMs")) &&
            (o.absent("media") || media(o.get("media")))
        "Status" -> o.isBool("buffering") && o.isBool("detached") && o.isBool("noSource") && (o.absent("aheadMs") || o.isNum("aheadMs"))
        "Chat" -> o.isStr("text")
        "React" -> o.isStr("emoji")
        "Bye" -> true
        else -> false
    }

    private fun JsonElement.isString() = isJsonPrimitive && asJsonPrimitive.isString
    private fun JsonObject.absent(k: String) = get(k).let { it == null || it.isJsonNull }
    private fun JsonObject.isStr(k: String) = get(k)?.isString() == true
    private fun JsonObject.optStr(k: String) = absent(k) || isStr(k)
    private fun JsonObject.isNum(k: String) = get(k)?.let { it.isJsonPrimitive && it.asJsonPrimitive.isNumber } == true
    private fun JsonObject.isBool(k: String) = get(k)?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean } == true
    private fun JsonObject.isEnum(k: String, names: Set<String>) = isStr(k) && get(k).asString in names
    private fun JsonObject.members(k: String) = get(k)?.takeIf { it.isJsonArray }?.asJsonArray?.all { e ->
        e.isJsonObject && e.asJsonObject.let { it.isStr("id") && it.isStr("name") && it.isNum("joinedAt") && (it.absent("coHost") || it.isBool("coHost")) }
    } == true
    /** RoomState has a default for every field (Gson then uses them), but what IS present must make sense. */
    private fun JsonObject.room(k: String): Boolean {
        val r = get(k)?.takeIf { it.isJsonObject }?.asJsonObject ?: return false
        val numsOk = listOf("seq", "positionMs", "atHostMs").all { r.absent(it) || r.isNum(it) }
        val boolsOk = listOf("playing", "waiting").all { r.absent(it) || r.isBool(it) }
        val speedOk = r.absent("speed") || (r.isNum("speed") && r.get("speed").asDouble.let { it > 0.05 && it <= 16 })
        return numsOk && boolsOk && speedOk && (r.absent("media") || media(r.get("media")))
    }
    private fun media(e: JsonElement): Boolean {
        val m = e.takeIf { it.isJsonObject }?.asJsonObject ?: return false
        return listOf("packageName", "apiName", "titleUrl", "titleName", "episodeKey", "episodeLabel").all { m.isStr(it) } &&
            m.isNum("packageVersion") && m.optStr("repoUrl") && m.optStr("sourceName") && m.optStr("posterUrl") &&
            listOf("quality", "season", "episode").all { m.absent(it) || m.isNum(it) }
    }
}
