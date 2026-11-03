import cloudstream.desktop.contracts.LoadResult
import cloudstream.desktop.loader.*
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.*

private class FakeHttp(val map: Map<String, ByteArray>, val redirects: Map<String, String> = emptyMap()) : HttpFetcher {
    override suspend fun getBytes(url: String) = map[url] ?: error("404 $url")
    override suspend fun redirectLocation(url: String) = redirects[url]
}

class RepoClientTest {
    private val repoJson = """{"name":"Test Repo","manifestVersion":1,"pluginLists":["https://x/plugins.json"]}"""
    private val pluginsJson = """[{"url":"https://x/A.cs3","status":1,"version":3,"apiVersion":1,"name":"A","internalName":"A",
        "authors":["me"],"description":null,"repositoryUrl":null,"tvTypes":["Movie"],"language":"en","iconUrl":null,
        "fileSize":10,"fileHash":"sha256-abc","unknownField":1}]"""
    private val http = FakeHttp(
        mapOf("https://x/repo.json" to repoJson.toByteArray(), "https://x/plugins.json" to pluginsJson.toByteArray()),
        mapOf("https://cutt.ly/abc" to "https://x/repo.json", "https://py.md/abc" to "https://x/repo.json",
            "https://cutt.ly/bad" to "https://cutt.ly/404"),
    )
    private val client = RepoClient(http)

    @Test fun parseUrls() = runBlocking {
        assertEquals("https://x/repo.json", client.parseRepoUrl(" https://x/repo.json "))
        assertEquals("https://x/repo.json", client.parseRepoUrl("cloudstreamrepo://x/repo.json"))
        assertEquals("https://x/repo.json", client.parseRepoUrl("https://cs.repo/?x/repo.json"))
        assertEquals("https://x/repo.json", client.parseRepoUrl("abc"))
        assertEquals("https://x/repo.json", client.parseRepoUrl("!abc"))
        assertNull(client.parseRepoUrl("bad"))
        assertNull(client.parseRepoUrl("not a url!!"))
    }

    @Test fun addRepoAndList() = runBlocking {
        val repo = client.addRepo("abc")
        assertEquals("Test Repo", repo.name)
        val list = client.listAvailable(repo)
        assertEquals(1, list.size)
        assertEquals("sha256-abc", list[0].fileHash)
        assertEquals(listOf("Movie"), list[0].tvTypes)
    }

    @Test fun jsdelivr() {
        val c = RepoClient(http, useJsDelivr = true)
        assertEquals("https://cdn.jsdelivr.net/gh/u/r@builds/repo.json",
            c.convertRawGitUrl("https://raw.githubusercontent.com/u/r/builds/repo.json"))
    }

    @Test fun hashMismatchRejected() = runBlocking {
        val dir = Files.createTempDirectory("cs-test").toFile()
        val h = FakeHttp(http.map + ("https://x/A.cs3" to "not a zip".toByteArray()), http.redirects)
        val repo = DesktopPluginRepository(dir, h)
        val info = repo.addRepo("https://x/repo.json").let { repo.listAvailable(it.url).single() }
        val e = assertFailsWith<IllegalStateException> { repo.install(info) }
        assertTrue("hash mismatch" in e.message!!)
        assertTrue(repo.installed().isEmpty())
        dir.deleteRecursively()
    }
}

class MissingClassTest {
    @Test fun extractsNoClassDef() {
        val t = RuntimeException("w", java.lang.reflect.InvocationTargetException(
            NoClassDefFoundError("android/content/SharedPreferences")))
        assertEquals(listOf("android.content.SharedPreferences"), PluginLoader.missingClasses(t))
        val f = PluginLoader.failure("p", t)
        assertTrue(f.error.contains("NoClassDefFoundError"))
    }
}

/**
 * Opt-in (-Pcs.live=true): downloads real plugins from the user's repos, converts and EXECUTES them
 * in-process. Never run on CI.
 */
class LiveTest {
    private val live = System.getProperty("cs.live") == "true"

    /** -Pcs.live=true -Pcs.live.repos=<repo.json,...> -Pcs.live.names=<internalName,...> */
    @Test fun installAndLoadFromLiveRepos() {
        if (!live) return
        val repos = System.getProperty("cs.live.repos").orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val names = System.getProperty("cs.live.names").orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (repos.isEmpty() || names.isEmpty()) { println("cs.live.repos / cs.live.names not set: skipped"); return }
        runBlocking {
            val dir = Files.createTempDirectory("cs-live").toFile()
            val ctx = android.content.TestContext()
            val repo = DesktopPluginRepository(dir, loader = PluginLoader(entrypoint = DefaultEntrypoint { ctx }))
            val avail = repos.flatMap { u -> repo.listAvailable(repo.addRepo(u).url) }
            println("available: ${avail.size}")
            for (n in names) {
                val p = avail.firstOrNull { it.internalName == n } ?: run { println("NOT FOUND $n"); continue }
                repo.install(p)
            }
            val results = repo.loadAll()
            results.forEach { println("RESULT $it") }
            println("conversion warnings: ${repo.conversionWarnings.size}")
            println("allProviders: " + com.lagradost.cloudstream3.APIHolder.allProviders.map { it.name })
            assertTrue(results.any { it is LoadResult.Ok })
            // Unload everything: registries must be emptied again.
            repo.installed().forEach { repo.unload(it.info.internalName) }
            assertTrue(com.lagradost.cloudstream3.APIHolder.allProviders.isEmpty(), "providers left after unload")
            assertTrue(com.lagradost.cloudstream3.utils.extractorApis.none { it.sourcePlugin != null }, "extractors left after unload")
        }
    }
}
