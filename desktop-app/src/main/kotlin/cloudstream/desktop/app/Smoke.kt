package cloudstream.desktop.app

import cloudstream.desktop.app.domain.Outcome
import cloudstream.desktop.app.domain.guarded
import cloudstream.desktop.app.vm.ProviderResult
import cloudstream.desktop.contracts.LoadResult
import cloudstream.desktop.contracts.PluginInfo
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest

/**
 * Headless boot of the composition root with the REAL modules. Exit code != 0 on wiring errors.
 *
 *   --smoke                              wiring only (classpath origin check, storage -> shim, loader, player factory)
 *   --smoke --cs3 <dir> [--install A,B]  serves the local .cs3 files through a loopback repo, then add repo -> install -> load
 *   --smoke --repo <url> --install A,B   same against a real remote repo (network)
 *   --live [--query q]                   additionally: search every loaded provider, load the first hit, loadLinks of its first episode
 *   --data-dir <dir>                     work dir (default: a fresh temp dir)
 *
 * Executes plugin code when --cs3/--repo is given, so it is never part of `build`/`test`.
 */
object Smoke {
    private var failures = 0
    private fun ok(msg: String) = println("  [ok]   $msg")
    private fun info(msg: String) = println("  [info] $msg")
    private fun bad(msg: String) { failures++; println("  [FAIL] $msg") }

    fun run(a: CliArgs): Int {
        println("== classpath origin check")
        ClasspathCheck.run().forEach { r ->
            if (r.ok) ok("${r.className} <- ${r.jar}") else bad("${r.className} <- ${r.jar} (expected module '${r.expectedIn}' first on the classpath)")
        }

        println("== composition root (real modules)")
        val work = a.dataDir ?: java.nio.file.Files.createTempDirectory("cs-desktop-smoke").toFile()
        info("work dir: $work")
        val services = try { AppServices.real(work) } catch (t: Throwable) {
            t.printStackTrace()
            bad("AppServices.real failed: $t"); return 1
        }
        ok("AppServices.real built (mode=${services.mode})")
        val c = AppController(services)

        // storage -> shim round trip
        runCatching {
            val prefs = cloudstream.desktop.androidshim.ShimRuntime.keyValueStore()
            prefs.set("smoke/key", "value")
            check(prefs.get("smoke/key", String::class.java) == "value")
            prefs.remove("smoke/key")
        }.fold({ ok("storage -> ShimRuntime key/value round trip") }, { bad("storage -> shim wiring: $it") })

        runCatching { c.services.library.bookmarks() }.fold({ ok("LibraryStore reachable (${it.size} bookmarks)") }, { bad("LibraryStore: $it") })

        val ph = services.players.create(PlayerChoice.Auto)
        if (ph.real) ok("player backend: ${ph.description}") else info("no real player backend (expected without libmpv-2.dll): ${ph.description}")
        ph.release()
        info("browser component installed: ${services.browser.isInstalled()} (lazy, not downloaded by smoke)")

        runBlocking {
            val repoUrl = a.repo ?: a.cs3Dir?.let { startLocalRepo(it) }
            if (repoUrl != null) endToEnd(c, repoUrl, a)
        }

        println("== result: " + if (failures == 0) "OK" else "$failures FAILURE(S)")
        services.close()
        return if (failures == 0) 0 else 2
    }

    private suspend fun endToEnd(c: AppController, repoUrl: String, a: CliArgs) {
        println("== add repo -> install -> load ($repoUrl)")
        val repo = try { c.services.extensions.addRepo(repoUrl) } catch (t: Throwable) { bad("addRepo: $t"); return }
        ok("repo \"${repo.name}\" with ${repo.pluginListUrls.size} list(s)")
        val avail = c.services.extensions.listAvailable(repo.url)
        ok("${avail.size} plugins available")
        val wanted: List<PluginInfo> =
            if (a.install.isEmpty()) avail.filter { it.status != 0 } else avail.filter { it.internalName in a.install || it.name in a.install }
        if (a.install.isNotEmpty()) (a.install - wanted.map { it.internalName }.toSet() - wanted.map { it.name }.toSet()).forEach { bad("requested plugin not in repo: $it") }
        for (p in wanted) {
            val t0 = System.currentTimeMillis()
            try { c.services.extensions.install(p) } catch (t: Throwable) { bad("install ${p.name}: $t"); continue }
            when (val r = c.services.extensions.loadOne(p.internalName)) {
                is LoadResult.Ok -> ok("${p.name}: installed + loaded in ${System.currentTimeMillis() - t0} ms, providers=${r.apis}, settingsUi=${r.hasSettingsUi}")
                is LoadResult.Failed -> bad("${p.name}: load failed: ${r.error.take(200)} missing=${r.missingClasses.take(5)}")
            }
        }
        c.services.extensions.warnings.forEach { info("converter warning: $it") }

        val providers = c.providers()
        println("== providers registered: ${providers.size} -> ${providers.joinToString { it.name }}")
        if (providers.isEmpty() && wanted.isNotEmpty()) bad("no providers registered after loading")
        if (!a.live) return

        println("== live: search \"${a.query}\" across all providers (parallel, per-provider timeouts)")
        c.search.search(a.query)?.join()
        val results = c.search.state.value.results
        for (r in results) {
            val line = "${r.provider}: ${r.status} ${r.items.size} items in ${r.elapsedMs} ms" +
                (r.message?.let { " -- $it" } ?: "") + (r.items.firstOrNull()?.let { " e.g. \"${it.name}\"" } ?: "")
            if (r.status == ProviderResult.Status.Done) ok(line) else info(line)
        }
        println("== live: details + sources for the first result of each provider with results")
        for (r in results.filter { it.items.isNotEmpty() }) {
            val card = r.items.first()
            val p = c.providerByName(r.provider) ?: continue
            val d = when (val o = guarded(p.loadTimeoutMs) { p.load(card.url) }) {
                is Outcome.Ok -> o.value
                is Outcome.Failed -> { info("${r.provider}: load(\"${card.name}\") failed: ${o.message}"); continue }
                is Outcome.TimedOut -> { info("${r.provider}: load timed out"); continue }
            }
            if (d == null) { info("${r.provider}: load returned null"); continue }
            ok("${r.provider}: details \"${d.name}\" type=${d.type} episodes=${d.episodes.size} poster=${d.posterUrl != null}")
            val ep = d.episodes.firstOrNull() ?: continue
            val links = java.util.Collections.synchronizedList(ArrayList<String>())
            val out = guarded(p.linksTimeoutMs) {
                p.loadLinks(ep.data, { links += "${it.name} ${it.qualityLabel}${if (it.isM3u8) " HLS" else ""} ${it.url.take(70)}" }, {})
            }
            val summary = when (out) { is Outcome.Ok -> "returned ${out.value}"; is Outcome.Failed -> "failed: ${out.message}"; is Outcome.TimedOut -> "timed out" }
            val linksList = synchronized(links) { links.toList() }
            if (linksList.isNotEmpty()) ok("${r.provider}: ${linksList.size} source link(s) ($summary), first: ${linksList.first()}")
            else info("${r.provider}: 0 source links ($summary)")
        }
    }

    /** Serves repo.json + plugins.json + the .cs3 files over loopback so the real RepoClient/downloader/hash check run. */
    private fun startLocalRepo(dir: File): String {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        val base = "http://127.0.0.1:$port"
        val files = dir.listFiles { f -> f.extension == "cs3" }!!.sortedBy { it.name }
        fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        val plugins = files.joinToString(",", "[", "]") { f ->
            val n = f.nameWithoutExtension
            """{"url":"$base/$n.cs3","status":1,"version":1,"name":"$n","internalName":"$n","authors":[],"description":"local","fileSize":${f.length()},"repositoryUrl":"$base","language":"en","tvTypes":["Movie"],"apiVersion":1,"fileHash":"sha256-${sha(f)}"}"""
        }
        val repoJson = """{"name":"Local test repo","description":"local","manifestVersion":1,"pluginLists":["$base/plugins.json"]}"""
        server.createContext("/") { ex ->
            val path = ex.requestURI.path.removePrefix("/")
            val (type, bytes) = when {
                path == "repo.json" -> "application/json" to repoJson.toByteArray()
                path == "plugins.json" -> "application/json" to plugins.toByteArray()
                files.any { it.name == path } -> "application/octet-stream" to files.first { it.name == path }.readBytes()
                else -> "text/plain" to ByteArray(0)
            }
            ex.responseHeaders.add("Content-Type", type)
            ex.sendResponseHeaders(if (bytes.isEmpty()) 404 else 200, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
        }
        server.executor = null
        server.start()
        info("local repo at $base/repo.json serving ${files.size} .cs3 file(s)")
        return "$base/repo.json"
    }
}

