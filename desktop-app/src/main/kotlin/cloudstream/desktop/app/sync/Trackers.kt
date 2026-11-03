package cloudstream.desktop.app.sync

import cloudstream.desktop.app.sync.Http.orThrow
import cloudstream.desktop.contracts.KeyValueStore
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.delay
import java.security.SecureRandom

enum class TrackStatus(val label: String) {
    Watching("Watching"), Completed("Completed"), OnHold("On hold"), Dropped("Dropped"), Planning("Plan to watch"), None("Not in list")
}

data class TrackEntry(
    val mediaId: String, val title: String?, val status: TrackStatus?, val progress: Int, val total: Int?,
    /** 0..10, null = no score. */
    val score: Int?, val url: String?,
)

/** What we know about a title, used to find it on a tracker. [ids] keys: anilist, mal, kitsu, imdb, tmdb, simkl. */
data class MatchQuery(val title: String, val year: Int?, val isAnime: Boolean, val isMovie: Boolean, val ids: Map<String, String>)

enum class LoginKind { Browser, DevicePin, Password }

data class PinChallenge(val userCode: String, val verificationUrl: String, val intervalSec: Int, val expiresSec: Int)

/** Per-profile key/value namespace for one service (tokens, client id, user name). Stored in the profile's settings file. */
/** A service's account data. Every value is encrypted at rest ([SecretBox]); old plain values are sealed on first read. */
class ServiceStore(private val kv: KeyValueStore, private val prefix: String) {
    operator fun get(k: String): String? {
        val raw = kv.get("$prefix/$k", String::class.java) ?: return null
        if (!SecretBox.isSealed(raw)) { runCatching { kv.set("$prefix/$k", SecretBox.seal(raw)) }; return raw }
        return SecretBox.open(raw)
    }
    operator fun set(k: String, v: String?) { if (v == null) kv.remove("$prefix/$k") else kv.set("$prefix/$k", SecretBox.seal(v)) }
    fun clear(keep: Set<String> = emptySet()) = kv.keys("$prefix/").forEach { if (it.removePrefix("$prefix/") !in keep) kv.remove(it) }
}

abstract class Tracker(protected val store: ServiceStore) {
    abstract val id: String
    abstract val name: String
    abstract val loginKind: LoginKind
    /** Animes only (AniList/MAL/Kitsu) or everything (Simkl). */
    abstract val animeOnly: Boolean
    open val needsClientId: Boolean = false
    open val clientIdHelp: String = ""
    abstract val signupUrl: String
    open val usernameLabel: String = "Email"

    var clientId: String?
        get() = store["clientId"]?.takeIf { it.isNotBlank() }
        set(v) { store["clientId"] = v?.trim() }

    val loggedInAs: String? get() = store["user"]?.takeIf { store["token"] != null }
    val loggedIn: Boolean get() = store["token"] != null

    fun logout() = store.clear(keep = setOf("clientId"))

    open suspend fun loginBrowser(open: (String) -> Unit): Unit = error("not supported")
    open suspend fun startPin(): PinChallenge = error("not supported")
    open suspend fun pollPin(c: PinChallenge): Boolean = error("not supported")
    open suspend fun loginPassword(user: String, password: String): Unit = error("not supported")

    abstract suspend fun find(q: MatchQuery): String?
    abstract suspend fun entry(mediaId: String): TrackEntry?
    /** Null arguments are left unchanged. [season] is only used by Simkl (TV episode history). */
    abstract suspend fun update(mediaId: String, status: TrackStatus?, progress: Int?, score: Int?, season: Int? = null)

    protected fun saveToken(access: String, refresh: String?, expiresInSec: Long?) {
        store["token"] = access; store["refresh"] = refresh
        store["expires"] = expiresInSec?.let { (System.currentTimeMillis() / 1000 + it - 60).toString() }
    }
    protected val token: String get() = store["token"] ?: error("$name: not logged in")
    protected fun expired() = store["expires"]?.toLongOrNull()?.let { System.currentTimeMillis() / 1000 > it } ?: false

    companion object {
        fun pickByYear(cands: List<Pair<String, Int?>>, year: Int?): String? =
            (if (year != null) cands.firstOrNull { it.second == year } else null)?.first ?: cands.firstOrNull()?.first
    }
}

// ------------------------------------------------------------------------------------------------ AniList

class AniListTracker(store: ServiceStore) : Tracker(store) {
    override val id = "anilist"
    override val name = "AniList"
    override val loginKind = LoginKind.Browser
    override val animeOnly = true
    override val needsClientId = true
    override val signupUrl = "https://anilist.co/signup"
    override val clientIdHelp = "Create a client at anilist.co/settings/developer with redirect URL ${OAuthLoopback.REDIRECT_URI} and paste its Client ID."

    private suspend fun gql(query: String, vars: Map<String, Any?> = emptyMap(), auth: Boolean = true): JsonObject {
        val h = mutableMapOf("Accept" to "application/json")
        if (auth && loggedIn) h["Authorization"] = "Bearer $token"
        val r = Http.postJson("https://graphql.anilist.co", mapOf("query" to query, "variables" to vars), h)
        val o = r.obj()
        o.a("errors")?.firstOrNull()?.let { e -> if (o.o("data") == null || !r.ok) error("AniList: ${e.s("message")}") }
        return o.o("data") ?: JsonObject()
    }

    override suspend fun loginBrowser(open: (String) -> Unit) {
        val cid = clientId ?: error("Enter your AniList Client ID first")
        val p = OAuthLoopback.await { open("https://anilist.co/api/v2/oauth/authorize?client_id=${Http.enc(cid)}&response_type=token") }
        val t = p["access_token"] ?: error("AniList login failed: ${p["error_description"] ?: p["error"] ?: "no token"}")
        saveToken(t, null, p["expires_in"]?.toLongOrNull())
        store["user"] = gql("query { Viewer { id name } }").o("Viewer").s("name")
    }

    override suspend fun find(q: MatchQuery): String? {
        q.ids["anilist"]?.let { return it }
        q.ids["mal"]?.toIntOrNull()?.let { mal ->
            gql("query(\$m:Int){ Media(idMal:\$m, type:ANIME){ id } }", mapOf("m" to mal), auth = false).o("Media").i("id")?.let { return it.toString() }
        }
        val list = gql("query(\$s:String){ Page(perPage:6){ media(search:\$s, type:ANIME){ id startDate{year} } } }", mapOf("s" to q.title), auth = false)
            .o("Page").a("media")?.map { it.i("id").toString() to it.o("startDate").i("year") }.orEmpty()
        return pickByYear(list, q.year)
    }

    override suspend fun entry(mediaId: String): TrackEntry? {
        val m = gql("query(\$id:Int){ Media(id:\$id){ id episodes siteUrl title{romaji english} mediaListEntry{ status progress score(format:POINT_10) } } }",
            mapOf("id" to mediaId.toInt())).o("Media") ?: return null
        val e = m.o("mediaListEntry")
        return TrackEntry(mediaId, m.o("title").s("english") ?: m.o("title").s("romaji"), e?.s("status")?.let(::fromApi) ?: TrackStatus.None,
            e.i("progress") ?: 0, m.i("episodes"), e.i("score")?.takeIf { it > 0 }, m.s("siteUrl"))
    }

    override suspend fun update(mediaId: String, status: TrackStatus?, progress: Int?, score: Int?, season: Int?) {
        if (status == TrackStatus.None) {
            val eid = gql("query(\$id:Int){ Media(id:\$id){ mediaListEntry{ id } } }", mapOf("id" to mediaId.toInt())).o("Media").o("mediaListEntry").i("id") ?: return
            gql("mutation(\$id:Int){ DeleteMediaListEntry(id:\$id){ deleted } }", mapOf("id" to eid)); return
        }
        gql("mutation(\$id:Int,\$st:MediaListStatus,\$p:Int,\$sc:Float){ SaveMediaListEntry(mediaId:\$id,status:\$st,progress:\$p,score:\$sc){ id } }",
            mapOf("id" to mediaId.toInt(), "st" to status?.let(::toApi), "p" to progress, "sc" to score?.toDouble()).filterValues { it != null })
    }

    private fun fromApi(s: String) = when (s) {
        "CURRENT", "REPEATING" -> TrackStatus.Watching; "COMPLETED" -> TrackStatus.Completed; "PAUSED" -> TrackStatus.OnHold
        "DROPPED" -> TrackStatus.Dropped; "PLANNING" -> TrackStatus.Planning; else -> TrackStatus.None
    }
    private fun toApi(s: TrackStatus) = when (s) {
        TrackStatus.Watching -> "CURRENT"; TrackStatus.Completed -> "COMPLETED"; TrackStatus.OnHold -> "PAUSED"
        TrackStatus.Dropped -> "DROPPED"; TrackStatus.Planning -> "PLANNING"; TrackStatus.None -> null
    }

    companion object {
        /** AniList id -> MAL id through the public API (no login), used by MAL/Kitsu matching. */
        suspend fun malIdFor(anilistId: String): String? = runCatching {
            Http.postJson("https://graphql.anilist.co", mapOf("query" to "query(\$id:Int){ Media(id:\$id){ idMal } }", "variables" to mapOf("id" to anilistId.toInt())))
                .obj().o("data").o("Media").i("idMal")?.toString()
        }.getOrNull()
    }
}

// ------------------------------------------------------------------------------------------------ MyAnimeList

class MalTracker(store: ServiceStore) : Tracker(store) {
    override val id = "mal"
    override val name = "MyAnimeList"
    override val loginKind = LoginKind.Browser
    override val animeOnly = true
    override val needsClientId = true
    override val signupUrl = "https://myanimelist.net/register.php"
    override val clientIdHelp = "Create an app (type \"other\") at myanimelist.net/apiconfig with App Redirect URL ${OAuthLoopback.REDIRECT_URI} and paste its Client ID."
    private val api = "https://api.myanimelist.net/v2"

    override suspend fun loginBrowser(open: (String) -> Unit) {
        val cid = clientId ?: error("Enter your MyAnimeList Client ID first")
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val rnd = SecureRandom()
        val verifier = (1..96).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
        val state = (1..16).map { chars[rnd.nextInt(62)] }.joinToString("")
        val p = OAuthLoopback.await {
            open("https://myanimelist.net/v1/oauth2/authorize?response_type=code&client_id=${Http.enc(cid)}&code_challenge=$verifier" +
                "&code_challenge_method=plain&state=$state&redirect_uri=${Http.enc(OAuthLoopback.REDIRECT_URI)}")
        }
        if (p["state"] != state) error("MyAnimeList login failed: state mismatch")
        val code = p["code"] ?: error("MyAnimeList login failed: ${p["error"] ?: "no code"}")
        tokenRequest(Http.form("client_id" to cid, "grant_type" to "authorization_code", "code" to code,
            "code_verifier" to verifier, "redirect_uri" to OAuthLoopback.REDIRECT_URI))
        store["user"] = Http.get("$api/users/@me", auth()).orThrow().obj().s("name")
    }

    private suspend fun tokenRequest(form: String) {
        val o = Http.postForm("https://myanimelist.net/v1/oauth2/token", form).orThrow().obj()
        saveToken(o.s("access_token") ?: error("MyAnimeList: no access token"), o.s("refresh_token"), o.d("expires_in")?.toLong())
    }

    private suspend fun auth(): Map<String, String> {
        if (expired()) store["refresh"]?.let { r -> tokenRequest(Http.form("client_id" to clientId, "grant_type" to "refresh_token", "refresh_token" to r)) }
        return mapOf("Authorization" to "Bearer $token")
    }

    override suspend fun find(q: MatchQuery): String? {
        q.ids["mal"]?.let { return it }
        q.ids["anilist"]?.let { a -> AniListTracker.malIdFor(a)?.let { return it } }
        val data = Http.get("$api/anime?q=${Http.enc(q.title.take(64))}&limit=6&fields=start_date", auth()).obj().a("data")
        return pickByYear(data?.map { it.o("node").i("id").toString() to it.o("node").s("start_date")?.take(4)?.toIntOrNull() }.orEmpty(), q.year)
    }

    override suspend fun entry(mediaId: String): TrackEntry? {
        val o = Http.get("$api/anime/$mediaId?fields=num_episodes,my_list_status,title,alternative_titles", auth()).orThrow().obj()
        val st = o.o("my_list_status")
        return TrackEntry(mediaId, o.o("alternative_titles").s("en")?.takeIf { it.isNotBlank() } ?: o.s("title"),
            st?.s("status")?.let(::fromApi) ?: TrackStatus.None, st.i("num_episodes_watched") ?: 0, o.i("num_episodes")?.takeIf { it > 0 },
            st.i("score")?.takeIf { it > 0 }, "https://myanimelist.net/anime/$mediaId")
    }

    override suspend fun update(mediaId: String, status: TrackStatus?, progress: Int?, score: Int?, season: Int?) {
        if (status == TrackStatus.None) { Http.request("DELETE", "$api/anime/$mediaId/my_list_status", auth()); return }
        Http.postForm("$api/anime/$mediaId/my_list_status",
            Http.form("status" to status?.let(::toApi), "num_watched_episodes" to progress, "score" to score), auth(), method = "PATCH").orThrow()
    }

    private fun fromApi(s: String) = when (s) {
        "watching" -> TrackStatus.Watching; "completed" -> TrackStatus.Completed; "on_hold" -> TrackStatus.OnHold
        "dropped" -> TrackStatus.Dropped; "plan_to_watch" -> TrackStatus.Planning; else -> TrackStatus.None
    }
    private fun toApi(s: TrackStatus) = when (s) {
        TrackStatus.Watching -> "watching"; TrackStatus.Completed -> "completed"; TrackStatus.OnHold -> "on_hold"
        TrackStatus.Dropped -> "dropped"; TrackStatus.Planning -> "plan_to_watch"; TrackStatus.None -> null
    }
}

// ------------------------------------------------------------------------------------------------ Simkl

class SimklTracker(store: ServiceStore) : Tracker(store) {
    override val id = "simkl"
    override val name = "Simkl"
    override val loginKind = LoginKind.DevicePin
    override val animeOnly = false
    override val needsClientId = true
    override val signupUrl = "https://simkl.com/"
    override val clientIdHelp = "Create an app at simkl.com/settings/developer (any redirect URI) and paste its Client ID. You then sign in with a PIN."
    private val api = "https://api.simkl.com"

    private fun h(): Map<String, String> = mapOf("Authorization" to "Bearer $token", "simkl-api-key" to (clientId ?: ""))

    override suspend fun startPin(): PinChallenge {
        val cid = clientId ?: error("Enter your Simkl Client ID first")
        val o = Http.get("$api/oauth/pin?client_id=${Http.enc(cid)}").orThrow().obj()
        return PinChallenge(o.s("user_code") ?: error("Simkl: no PIN"), o.s("verification_url") ?: "https://simkl.com/pin",
            o.i("interval") ?: 5, o.i("expires_in") ?: 900)
    }

    override suspend fun pollPin(c: PinChallenge): Boolean {
        val deadline = System.currentTimeMillis() + c.expiresSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(c.intervalSec * 1000L)
            val o = Http.get("$api/oauth/pin/${c.userCode}?client_id=${Http.enc(clientId ?: "")}").obj()
            o.s("access_token")?.let { t ->
                saveToken(t, null, null)
                store["user"] = Http.postJson("$api/users/settings", JsonObject(), h()).obj().o("user").s("name") ?: "Simkl user"
                return true
            }
        }
        return false
    }

    /** Media ids are "<kind>:<simklId>" with kind movie / show / anime. */
    override suspend fun find(q: MatchQuery): String? {
        val cid = Http.enc(clientId ?: "")
        for ((k, param) in listOf("imdb" to "imdb", "tmdb" to "tmdb", "mal" to "mal", "anilist" to "anilist", "kitsu" to "kitsu")) {
            val v = q.ids[k] ?: continue
            val extra = if (k == "tmdb") "&type=${if (q.isMovie) "movie" else "show"}" else ""
            val arr = Http.get("$api/search/id?client_id=$cid&$param=${Http.enc(v)}$extra").json()
            val first = (arr as? JsonArray)?.firstOrNull() ?: continue
            val sid = first.o("ids").i("simkl") ?: continue
            return "${first.s("type") ?: kind(q)}:$sid"
        }
        val arr = Http.get("$api/search/${if (q.isMovie) "movie" else if (q.isAnime) "anime" else "tv"}?client_id=$cid&q=${Http.enc(q.title)}").json() as? JsonArray ?: return null
        val c = arr.mapNotNull { e -> e.o("ids").i("simkl_id")?.let { it.toString() to e.i("year") } }
        return pickByYear(c, q.year)?.let { "${kind(q)}:$it" }
    }

    private fun kind(q: MatchQuery) = if (q.isMovie) "movie" else if (q.isAnime) "anime" else "show"
    private fun bucket(kind: String) = if (kind == "movie") "movies" else "shows"

    override suspend fun entry(mediaId: String): TrackEntry? {
        val (kind, sid) = mediaId.split(':', limit = 2).let { it[0] to it[1] }
        val arr = Http.postJson("$api/sync/watched?extended=counters", listOf(mapOf("simkl" to sid.toInt())), h()).json() as? JsonArray
        val e = arr?.firstOrNull()
        val st = when (e.s("list")) {
            "watching" -> TrackStatus.Watching; "completed" -> TrackStatus.Completed; "hold" -> TrackStatus.OnHold
            "dropped" -> TrackStatus.Dropped; "plantowatch" -> TrackStatus.Planning; null -> if (e.s("result") == "false") TrackStatus.None else null
            else -> TrackStatus.None
        }
        return TrackEntry(mediaId, null, st, e.i("episodes_watched") ?: 0, e.i("total_episodes"), null, "https://simkl.com/$kind/$sid")
    }

    override suspend fun update(mediaId: String, status: TrackStatus?, progress: Int?, score: Int?, season: Int?) {
        val (kind, sid) = mediaId.split(':', limit = 2).let { it[0] to it[1].toInt() }
        val ids = mapOf("simkl" to sid)
        if (status != null) {
            if (status == TrackStatus.None) Http.postJson("$api/sync/history/remove", mapOf(bucket(kind) to listOf(mapOf("ids" to ids))), h()).orThrow()
            else Http.postJson("$api/sync/add-to-list", mapOf(bucket(kind) to listOf(mapOf("to" to when (status) {
                TrackStatus.Watching -> "watching"; TrackStatus.Completed -> "completed"; TrackStatus.OnHold -> "hold"
                TrackStatus.Dropped -> "dropped"; else -> "plantowatch"
            }, "ids" to ids))), h()).orThrow()
        }
        if (progress != null && progress > 0) {
            val item: Map<String, Any> = when {
                kind == "movie" -> mapOf("ids" to ids)
                kind == "anime" || season == null -> mapOf("ids" to ids, "episodes" to listOf(mapOf("number" to progress)))
                else -> mapOf("ids" to ids, "seasons" to listOf(mapOf("number" to season, "episodes" to listOf(mapOf("number" to progress)))))
            }
            Http.postJson("$api/sync/history", mapOf(bucket(kind) to listOf(item)), h()).orThrow()
        }
        if (score != null && score > 0) Http.postJson("$api/sync/ratings", mapOf(bucket(kind) to listOf(mapOf("ids" to ids, "rating" to score))), h())
    }
}

// ------------------------------------------------------------------------------------------------ Kitsu

class KitsuTracker(store: ServiceStore) : Tracker(store) {
    override val id = "kitsu"
    override val name = "Kitsu"
    override val loginKind = LoginKind.Password
    override val animeOnly = true
    override val signupUrl = "https://kitsu.app/"
    private val api = "https://kitsu.app/api/edge"
    private val jsonApi = "application/vnd.api+json"

    private suspend fun tokenRequest(form: String) {
        val r = Http.postForm("https://kitsu.app/api/oauth/token", form)
        if (!r.ok) error("Kitsu: ${r.obj().s("error_description") ?: "login failed (${r.code})"}")
        val o = r.obj()
        saveToken(o.s("access_token")!!, o.s("refresh_token"), o.d("expires_in")?.toLong())
    }

    private suspend fun h(): Map<String, String> {
        if (expired()) store["refresh"]?.let { tokenRequest(Http.form("grant_type" to "refresh_token", "refresh_token" to it)) }
        return mapOf("Authorization" to "Bearer $token", "Accept" to jsonApi)
    }

    override suspend fun loginPassword(user: String, password: String) {
        tokenRequest(Http.form("grant_type" to "password", "username" to user, "password" to password))
        val u = Http.get("$api/users?filter[self]=true", h()).orThrow().obj().a("data")?.firstOrNull() ?: error("Kitsu: no user")
        store["userId"] = u.s("id"); store["user"] = u.o("attributes").s("name")
    }

    override suspend fun find(q: MatchQuery): String? {
        q.ids["kitsu"]?.let { return it }
        val mal = q.ids["mal"] ?: q.ids["anilist"]?.let { AniListTracker.malIdFor(it) }
        if (mal != null) {
            val o = Http.get("$api/mappings?filter[externalSite]=myanimelist/anime&filter[externalId]=$mal&include=item", h()).obj()
            o.a("included")?.firstOrNull()?.s("id")?.let { return it }
        }
        val data = Http.get("$api/anime?filter[text]=${Http.enc(q.title)}&page[limit]=6&fields[anime]=startDate", h()).obj().a("data")
        return pickByYear(data?.map { it.s("id")!! to it.o("attributes").s("startDate")?.take(4)?.toIntOrNull() }.orEmpty(), q.year)
    }

    private suspend fun libraryEntry(mediaId: String) =
        Http.get("$api/library-entries?filter[userId]=${store["userId"]}&filter[animeId]=$mediaId", h()).obj().a("data")?.firstOrNull()

    override suspend fun entry(mediaId: String): TrackEntry? {
        val anime = Http.get("$api/anime/$mediaId?fields[anime]=episodeCount,canonicalTitle,slug", h()).orThrow().obj().o("data").o("attributes")
        val e = libraryEntry(mediaId)?.o("attributes")
        val st = when (e.s("status")) {
            "current" -> TrackStatus.Watching; "completed" -> TrackStatus.Completed; "on_hold" -> TrackStatus.OnHold
            "dropped" -> TrackStatus.Dropped; "planned" -> TrackStatus.Planning; else -> TrackStatus.None
        }
        return TrackEntry(mediaId, anime.s("canonicalTitle"), st, e.i("progress") ?: 0, anime.i("episodeCount"),
            e.i("ratingTwenty")?.let { (it + 1) / 2 }, anime.s("slug")?.let { "https://kitsu.app/anime/$it" })
    }

    override suspend fun update(mediaId: String, status: TrackStatus?, progress: Int?, score: Int?, season: Int?) {
        val existing = libraryEntry(mediaId)
        if (status == TrackStatus.None) { existing?.s("id")?.let { Http.request("DELETE", "$api/library-entries/$it", h()) }; return }
        val attrs = mutableMapOf<String, Any>()
        status?.let { attrs["status"] = when (it) {
            TrackStatus.Watching -> "current"; TrackStatus.Completed -> "completed"; TrackStatus.OnHold -> "on_hold"
            TrackStatus.Dropped -> "dropped"; else -> "planned"
        } }
        progress?.let { attrs["progress"] = it }
        score?.let { attrs["ratingTwenty"] = (it * 2).coerceIn(2, 20) }
        val data: Map<String, Any> = if (existing != null) mapOf("data" to mapOf("id" to existing.s("id"), "type" to "libraryEntries", "attributes" to attrs))
        else mapOf("data" to mapOf("type" to "libraryEntries", "attributes" to (mapOf("status" to "current") + attrs), "relationships" to mapOf(
            "user" to mapOf("data" to mapOf("type" to "users", "id" to store["userId"])),
            "anime" to mapOf("data" to mapOf("type" to "anime", "id" to mediaId)),
        )))
        Http.request(if (existing != null) "PATCH" else "POST", "$api/library-entries" + (existing?.s("id")?.let { "/$it" } ?: ""),
            h(), Http.gson.toJson(data), jsonApi).orThrow()
    }
}
