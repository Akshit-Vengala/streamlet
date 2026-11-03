package cloudstream.desktop.app.sync

import cloudstream.desktop.app.sync.Http.orThrow
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipInputStream

data class SubQuery(
    val title: String, val lang: String, val imdbId: String?, val tmdbId: String?,
    val season: Int?, val episode: Int?, val year: Int?,
)

data class SubResult(
    val serviceId: String, val service: String, val name: String, val lang: String, val data: String,
    val hearingImpaired: Boolean, val headers: Map<String, String> = emptyMap(),
)

/** Online subtitle source. [loginKind] null = works without an account. */
abstract class SubtitleService(protected val store: ServiceStore) {
    abstract val id: String
    abstract val name: String
    open val loginKind: LoginKind? = null
    /** Login needed to search or download at all. */
    open val requiresLogin = false
    open val usernameLabel = "Username"
    open val signupUrl: String? = null
    val loggedInAs: String? get() = store["user"]?.takeIf { store["token"] != null }
    fun logout() = store.clear()
    open suspend fun loginPassword(user: String, password: String): Unit = error("not supported")

    abstract suspend fun search(q: SubQuery): List<SubResult>
    /** Downloads [r] into [dir]; returns the subtitle file (archives are unpacked). */
    abstract suspend fun download(r: SubResult, dir: File): File

    protected fun save(dir: File, nameHint: String, bytes: ByteArray): File {
        dir.mkdirs()
        if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) return unzip(dir, bytes)
        val ext = nameHint.substringAfterLast('.', "srt").lowercase().takeIf { it in SUB_EXT } ?: "srt"
        return File(dir, safe(nameHint.substringBeforeLast('.')) + ".$ext").also { it.writeBytes(bytes) }
    }

    /** First subtitle file in a zip (subdl / subsource ship zips). */
    private fun unzip(dir: File, bytes: ByteArray): File {
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val ext = e.name.substringAfterLast('.', "").lowercase()
                if (!e.isDirectory && ext in SUB_EXT) {
                    val out = File(dir, safe(e.name.substringAfterLast('/').substringBeforeLast('.')) + ".$ext")
                    out.writeBytes(z.readBytes()); return out
                }
            }
        }
        error("No subtitle file in the archive")
    }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80).ifBlank { "subtitle" }

    companion object {
        val SUB_EXT = setOf("srt", "ass", "ssa", "vtt", "sub")

        /** "English" / "en" / "pt-BR" -> ISO-639-1 ("en", "pt-br"). */
        fun langCode(s: String): String {
            val t = s.trim()
            if (t.length in 2..3 || (t.length == 5 && t[2] == '-')) return t.lowercase()
            return Locale.getISOLanguages().firstOrNull { Locale(it).getDisplayLanguage(Locale.ENGLISH).equals(t, true) } ?: "en"
        }
        fun langName(code: String): String = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }
    }
}

class OpenSubtitlesService(store: ServiceStore) : SubtitleService(store) {
    override val id = "opensubtitles"
    override val name = "OpenSubtitles"
    override val loginKind = LoginKind.Password
    override val signupUrl = "https://www.opensubtitles.com/en/users/sign_up"
    private val host = "https://api.opensubtitles.com/api/v1"
    // Public CloudStream API key from the upstream source (OpenSubtitlesApi.kt); downloads need a (free) user login.
    private val base = mapOf("User-Agent" to "Cloudstream3 v0.2", "Api-Key" to "uyBLgFD17MgrYmA0gSXoKllMJBelOYj2", "Accept" to "application/json")

    override suspend fun loginPassword(user: String, password: String) {
        val r = Http.postJson("$host/login", mapOf("username" to user, "password" to password), base)
        val t = r.obj().s("token") ?: error("OpenSubtitles: ${r.obj().s("message") ?: "invalid username or password"}")
        store["token"] = t; store["user"] = user; store["password"] = password // re-login when the 24 h token expires
        store["at"] = (System.currentTimeMillis() / 1000).toString()
    }

    private suspend fun auth(): Map<String, String> {
        val at = store["at"]?.toLongOrNull() ?: 0
        if (System.currentTimeMillis() / 1000 - at > 23 * 3600) {
            val u = store["user"]; val p = store["password"]
            if (u != null && p != null) loginPassword(u, p)
        }
        return base + (store["token"]?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap())
    }

    override suspend fun search(q: SubQuery): List<SubResult> {
        val p = buildList {
            val imdb = q.imdbId?.removePrefix("tt")?.toIntOrNull()
            if (imdb != null) add("imdb_id=$imdb") else if (q.tmdbId != null) add("tmdb_id=${q.tmdbId}") else add("query=${Http.enc(q.title)}")
            add("languages=${q.lang}")
            q.season?.takeIf { it > 0 }?.let { add("season_number=$it") }
            q.episode?.takeIf { it > 0 }?.let { add("episode_number=$it") }
            if (imdb == null) q.year?.let { add("year=$it") }
        }.joinToString("&")
        val r = Http.get("$host/subtitles?$p", base)
        if (r.code == 429) error("OpenSubtitles: too many requests, wait a bit")
        return r.orThrow().obj().a("data")?.flatMap { item ->
            val at = item.o("attributes")
            at.a("files")?.mapNotNull { f ->
                SubResult(id, name, f.s("file_name") ?: at.s("release") ?: q.title, at.s("language") ?: q.lang,
                    f.i("file_id")?.toString() ?: return@mapNotNull null, at?.get("hearing_impaired")?.asBoolean == true)
            }.orEmpty()
        }.orEmpty()
    }

    override suspend fun download(r: SubResult, dir: File): File {
        if (store["token"] == null) error("OpenSubtitles downloads need a free account: sign in under Settings > Accounts")
        val res = Http.postJson("$host/download", mapOf("file_id" to r.data.toInt()), auth())
        val o = res.obj()
        val link = o.s("link") ?: error("OpenSubtitles: ${o.s("message") ?: "download failed (${res.code})"}")
        return save(dir, o.s("file_name") ?: r.name, Http.get(link).orThrow().body)
    }
}

class SubDlService(store: ServiceStore) : SubtitleService(store) {
    override val id = "subdl"
    override val name = "SubDL"
    override val loginKind = LoginKind.Password
    override val requiresLogin = true
    override val usernameLabel = "Email"
    override val signupUrl = "https://subdl.com/panel/register"

    override suspend fun loginPassword(user: String, password: String) {
        val t = Http.postJson("https://api.subdl.com/login", mapOf("email" to user, "password" to password)).obj().s("token")
            ?: error("SubDL: invalid email or password")
        val key = Http.get("https://api.subdl.com/user/userApi", mapOf("Authorization" to "Bearer $t")).obj().s("api_key")
            ?: error("SubDL: could not get an API key")
        store["token"] = key; store["user"] = user
    }

    override suspend fun search(q: SubQuery): List<SubResult> {
        val key = store["token"] ?: error("SubDL needs an account: sign in under Settings > Accounts")
        val idq = q.imdbId?.let { "&imdb_id=$it" } ?: q.tmdbId?.let { "&tmdb_id=$it" } ?: "&film_name=${Http.enc(q.title)}"
        val url = "https://api.subdl.com/api/v1/subtitles?api_key=$key$idq&languages=${q.lang.uppercase()}" +
            (q.season?.takeIf { it > 0 }?.let { "&season_number=$it" } ?: "") + (q.episode?.takeIf { it > 0 }?.let { "&episode_number=$it" } ?: "")
        return Http.get(url, mapOf("Accept" to "application/json")).obj().a("subtitles")?.mapNotNull { s ->
            SubResult(id, name, s.s("release_name") ?: s.s("name") ?: q.title, s.s("lang") ?: q.lang,
                "https://dl.subdl.com" + (s.s("url") ?: return@mapNotNull null), s.s("hi") == "true")
        }.orEmpty()
    }

    override suspend fun download(r: SubResult, dir: File) = save(dir, r.name, Http.get(r.data).orThrow().body)
}

class SubSourceService(store: ServiceStore) : SubtitleService(store) {
    override val id = "subsource"
    override val name = "SubSource"
    private val api = "https://api.subsource.net/v1"

    override suspend fun search(q: SubQuery): List<SubResult> {
        val imdb = q.imdbId ?: return emptyList() // IMDb id search only (as upstream)
        val res = Http.postJson("$api/movie/search", mapOf("includeSeasons" to false, "limit" to 15, "query" to imdb, "signal" to "{}")).obj()
        val link = res.a("results")?.firstOrNull()?.s("link") ?: return emptyList()
        val langName = SubtitleService.langName(q.lang)
        val subs = Http.get(api + link.replace("series", "subtitles")).obj().a("subtitles") ?: return emptyList()
        // The API has no episode filter: match S01E01 / 1x01 in the release name (season AND episode).
        val epRx = if ((q.season ?: 0) > 0 && q.episode != null)
            Regex("(?i)(s0*${q.season}\\s*e0*${q.episode}(?!\\d))|(\\b0*${q.season}x0*${q.episode}(?!\\d))") else null
        return subs.filter { it.s("release_type") != "trailer" && it.s("language").equals(langName, true) && (epRx == null || epRx.containsMatchIn(it.s("release_info").orEmpty())) }
            .mapNotNull { s -> SubResult(id, name, s.s("release_info") ?: q.title, q.lang, s.s("link") ?: return@mapNotNull null, s.i("hearing_impaired") == 1) }
    }

    override suspend fun download(r: SubResult, dir: File): File {
        val tok = Http.get("$api/subtitle/${r.data}").obj().o("subtitle").s("download_token") ?: error("SubSource: no download token")
        return save(dir, r.name, Http.get("$api/subtitle/download/$tok").orThrow().body)
    }
}

class Addic7edService(store: ServiceStore) : SubtitleService(store) {
    override val id = "addic7ed"
    override val name = "Addic7ed"
    private val host = "https://www.addic7ed.com"

    override suspend fun search(q: SubQuery): List<SubResult> {
        val season = q.season ?: 0; val ep = q.episode ?: 0
        val search = if (season > 0) "${q.title} $season $ep" else q.title
        val res = Http.get("$host/search.php?search=${Http.enc(search)}&Submit=Search")
        val langName = SubtitleService.langName(q.lang)
        val page = when {
            "/movie/" in res.finalUrl || "/serie/" in res.finalUrl -> res.finalUrl
            else -> org.jsoup.Jsoup.parse(res.text, host).select("table.tabel a").firstOrNull { a ->
                val h = a.attr("href")
                if (season > 0) Regex("serie/.+/$season/$ep/").containsMatchIn(h) else "movie/" in h
            }?.absUrl("href") ?: return emptyList()
        }
        val doc = org.jsoup.Jsoup.parse(Http.get(page).text, host)
        val title = doc.selectFirst("span.titulo")?.text()?.substringBefore(" Subtitle") ?: q.title
        return doc.select(".tabel95 .tabel95 tr:has(.language)").filter { it.select(".language").text().contains(langName, true) }.mapNotNull { row ->
            val a = row.selectFirst("a[href~=updated/|original/]") ?: return@mapNotNull null
            val version = row.parent()?.select(".NewsTitle")?.text()?.substringAfter("Version ")?.substringBefore(", Duration").orEmpty()
            SubResult(id, name, "$title $version".trim(), q.lang, a.absUrl("href"), false, mapOf("Referer" to "$host/"))
        }
    }

    override suspend fun download(r: SubResult, dir: File) = save(dir, r.name + ".srt", Http.get(r.data, r.headers).orThrow().body)
}
