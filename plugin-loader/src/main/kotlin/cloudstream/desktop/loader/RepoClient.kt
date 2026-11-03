package cloudstream.desktop.loader

import cloudstream.desktop.contracts.PluginInfo
import cloudstream.desktop.contracts.RepoInfo
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.security.MessageDigest

internal val json = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

/** Mirrors upstream `SitePlugin` (plugins.json entry). */
data class SitePlugin(
    val url: String,
    val status: Int = 1,
    val version: Int = 0,
    val apiVersion: Int = 1,
    val name: String,
    val internalName: String,
    val authors: List<String> = emptyList(),
    val description: String? = null,
    val repositoryUrl: String? = null,
    val tvTypes: List<String>? = null,
    val language: String? = null,
    val iconUrl: String? = null,
    val fileSize: Long? = null,
    val fileHash: String? = null,
)

/** Mirrors upstream `Repository` (repo.json). */
data class RepositoryManifest(
    val name: String = "",
    val iconUrl: String? = null,
    val description: String? = null,
    val manifestVersion: Int = 1,
    val pluginLists: List<String> = emptyList(),
)

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun sha256Hex(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { s ->
        val buf = ByteArray(65536)
        while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/** Fetches `repo.json` -> plugin lists -> plugins; mirrors upstream RepositoryManager URL handling. */
class RepoClient(
    private val http: HttpFetcher,
    /** Mirrors the app's "jsdelivr proxy" setting. */
    private val useJsDelivr: Boolean = false,
) {
    private val ghRegex = Regex("^https://raw.githubusercontent.com/([A-Za-z0-9-]+)/([A-Za-z0-9_.-]+)/(.*)$")
    private val schemeRegex = Regex("^https?://")
    private val customScheme = Regex("^(cloudstreamrepo://)|(https://cs\\.repo/\\??)")
    private val shortcode = Regex("^[a-zA-Z0-9!_-]+$")

    fun convertRawGitUrl(url: String): String {
        if (!useJsDelivr) return url
        val m = ghRegex.find(url) ?: return url
        val (user, repo, rest) = m.destructured
        return "https://cdn.jsdelivr.net/gh/$user/$repo@$rest"
    }

    /** Accepts full URLs, `cloudstreamrepo://` / `https://cs.repo/` links and cutt.ly / `!`-py.md shortcodes. */
    suspend fun parseRepoUrl(input: String): String? {
        val url = input.trim()
        return when {
            customScheme.containsMatchIn(url) -> {
                val stripped = url.replace(customScheme, "")
                if (schemeRegex.containsMatchIn(stripped)) url else "https://$stripped"
            }
            schemeRegex.containsMatchIn(url) -> url
            shortcode.matches(url) -> runCatching {
                if (url.startsWith("!")) {
                    val loc = http.redirectLocation("https://py.md/${url.removePrefix("!")}") ?: return null
                    if (loc.startsWith("https://py.md/404") || loc.removeSuffix("/") == "https://py.md") null else loc
                } else {
                    val loc = http.redirectLocation("https://cutt.ly/$url") ?: return null
                    if (loc.startsWith("https://cutt.ly/404") || loc.removeSuffix("/") == "https://cutt.ly") null else loc
                }
            }.getOrNull()
            else -> null
        }
    }

    suspend fun fetchManifest(repoUrl: String): RepositoryManifest {
        val node: JsonNode = json.readTree(http.getBytes(convertRawGitUrl(repoUrl)))
        // Be lenient: a bare plugins.json array is treated as a one-list repo.
        if (node.isArray) return RepositoryManifest(name = repoUrl, pluginLists = listOf(repoUrl))
        return json.treeToValue(node, RepositoryManifest::class.java)
            .also { require(it.pluginLists.isNotEmpty()) { "Repository $repoUrl has no pluginLists" } }
    }

    suspend fun addRepo(input: String): RepoInfo {
        val url = parseRepoUrl(input) ?: throw IllegalArgumentException("Invalid repository url or shortcode: $input")
        val m = fetchManifest(url)
        return RepoInfo(url, m.name.ifBlank { url }, m.pluginLists)
    }

    suspend fun fetchPlugins(listUrl: String): List<SitePlugin> =
        json.readValue(http.getBytes(convertRawGitUrl(listUrl)), Array<SitePlugin>::class.java).toList()

    suspend fun listAvailable(repo: RepoInfo): List<PluginInfo> =
        repo.pluginListUrls.flatMap { list ->
            runCatching { fetchPlugins(list) }.getOrDefault(emptyList())
        }.map { it.toInfo(repo.url) }

    suspend fun downloadBytes(url: String): ByteArray = http.getBytes(convertRawGitUrl(url))
}

fun SitePlugin.toInfo(repoUrl: String) = PluginInfo(
    internalName = internalName, name = name, version = version, url = url, repoUrl = repoUrl,
    language = language, tvTypes = tvTypes ?: emptyList(), iconUrl = iconUrl, status = status, fileHash = fileHash,
)
