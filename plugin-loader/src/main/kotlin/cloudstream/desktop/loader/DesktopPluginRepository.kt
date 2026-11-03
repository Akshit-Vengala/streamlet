package cloudstream.desktop.loader

import cloudstream.desktop.contracts.InstalledPlugin
import cloudstream.desktop.contracts.LoadResult
import cloudstream.desktop.contracts.PluginInfo
import cloudstream.desktop.contracts.PluginRepository
import cloudstream.desktop.contracts.RepoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Default plugins dir: `<AppDirs.root>/plugins` (%APPDATA% on Windows, XDG data dir on Linux). */
fun defaultPluginsDir(): File = File(cloudstream.desktop.contracts.AppDirs.root, "plugins")

private data class InstalledRecord(val info: PluginInfo, val cs3Path: String, val jarPath: String, val sha256: String)
private data class State(val repos: List<RepoInfo> = emptyList(), val installed: List<InstalledRecord> = emptyList())

/**
 * Layout under [root]:
 * - `<repo-hash>/<internalName>.cs3`   downloaded plugins
 * - `jars/<sha256-of-cs3>.jar`         converted jar cache
 * - `state.json`                       repos + installed plugins
 */
class DesktopPluginRepository(
    private val root: File = defaultPluginsDir(),
    http: HttpFetcher = JdkHttpFetcher(),
    useJsDelivr: Boolean = false,
    private val loader: PluginLoader = PluginLoader(),
) : PluginRepository {
    private val client = RepoClient(http, useJsDelivr)
    private val converter = PluginConverter(File(root, "jars"))
    private val stateFile = File(root, "state.json")
    private val mutex = Mutex()
    @Volatile private var state: State = readState()

    val conversionWarnings: List<String> get() = converter.warnings

    private fun readState(): State = runCatching {
        if (stateFile.isFile) json.readValue(stateFile, State::class.java) else State()
    }.getOrDefault(State())

    private fun writeState(s: State) {
        state = s
        root.mkdirs()
        val tmp = File(root, "state.json.tmp")
        json.writerWithDefaultPrettyPrinter().writeValue(tmp, s)
        atomicMove(tmp, stateFile)
    }

    private fun atomicMove(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun repoDir(repoUrl: String) = File(root, sha256Hex(repoUrl.toByteArray()).take(16))
    private fun safeName(n: String) = n.replace(Regex("[^A-Za-z0-9._-]"), "_")

    override suspend fun addRepo(url: String): RepoInfo {
        val info = client.addRepo(url)
        mutex.withLock {
            val others = state.repos.filter { it.url != info.url }
            // Keep addition order when re-adding.
            val idx = state.repos.indexOfFirst { it.url == info.url }
            val list = if (idx >= 0) state.repos.toMutableList().also { it[idx] = info } else others + info
            writeState(state.copy(repos = list))
        }
        return info
    }

    override suspend fun listRepos(): List<RepoInfo> = state.repos

    suspend fun removeRepo(url: String) {
        val victims = state.installed.filter { it.info.repoUrl == url }
        victims.forEach { uninstall(it.info.internalName) }
        mutex.withLock { writeState(state.copy(repos = state.repos.filter { it.url != url })) }
    }

    override suspend fun listAvailable(repoUrl: String): List<PluginInfo> {
        val repo = state.repos.firstOrNull { it.url == repoUrl } ?: client.addRepo(repoUrl)
        return client.listAvailable(repo)
    }

    override suspend fun install(plugin: PluginInfo): InstalledPlugin = mutex.withLock { installLocked(plugin) }

    private suspend fun installLocked(plugin: PluginInfo): InstalledPlugin = withContext(Dispatchers.IO) {
        val bytes = client.downloadBytes(plugin.url)
        val hash = sha256Hex(bytes)
        plugin.fileHash?.let { expected ->
            val want = expected.removePrefix("sha256-").lowercase()
            check(want == hash) {
                "Extension hash mismatch for '${plugin.internalName}'! Expected: '$expected', got: 'sha256-$hash'."
            }
        }
        val dir = repoDir(plugin.repoUrl).also { it.mkdirs() }
        val cs3 = File(dir, "${safeName(plugin.internalName)}.cs3")
        val old = state.installed.firstOrNull { it.info.internalName == plugin.internalName }
        if (old != null) loader.unload(File(old.cs3Path)) // Windows cannot overwrite a file held open by a classloader.
        val tmp = File.createTempFile("download", ".tmp", dir)
        tmp.writeBytes(bytes)
        atomicMove(tmp, cs3)
        val jar = converter.convert(cs3, hash)
        readCs3Manifest(cs3) // validate early
        val rec = InstalledRecord(plugin, cs3.absolutePath, jar.absolutePath, hash)
        writeState(state.copy(installed = state.installed.filter { it.info.internalName != plugin.internalName } + rec))
        InstalledPlugin(plugin, cs3.absolutePath, jar.absolutePath)
    }

    override suspend fun uninstall(internalName: String) {
        mutex.withLock {
            val rec = state.installed.firstOrNull { it.info.internalName == internalName } ?: return
            val cs3 = File(rec.cs3Path)
            loader.unload(cs3)
            loader.guard?.revoke(PluginGuard.keyOf(cs3)) // a reinstall is checked afresh
            cs3.delete()
            // Jar cache is content-addressed; only drop it if no other install shares the hash.
            if (state.installed.none { it !== rec && it.sha256 == rec.sha256 }) File(rec.jarPath).delete()
            writeState(state.copy(installed = state.installed - rec))
        }
    }

    /**
     * Upstream's rules (PluginManager.OnlinePluginData): a plugin is outdated when the repository's version is higher
     * than the installed one, or is [PLUGIN_VERSION_ALWAYS_UPDATE]; a plugin the repository marks DOWN (status 0) is
     * returned too, so the caller can unload it instead of updating it. Never auto-applies.
     */
    suspend fun checkUpdates(): List<PluginInfo> {
        val out = ArrayList<PluginInfo>()
        for (repo in state.repos) {
            val avail = runCatching { client.listAvailable(repo) }.getOrNull() ?: continue
            for (rec in state.installed.filter { it.info.repoUrl == repo.url }) {
                val latest = avail.firstOrNull { it.internalName == rec.info.internalName } ?: continue
                val outdated = latest.version > rec.info.version || latest.version == PLUGIN_VERSION_ALWAYS_UPDATE
                if (latest.status == PROVIDER_STATUS_DOWN || outdated) out += latest
            }
        }
        return out
    }

    companion object {
        const val PLUGIN_VERSION_ALWAYS_UPDATE = -1
        const val PROVIDER_STATUS_DOWN = 0
    }

    override suspend fun update(internalName: String): InstalledPlugin = mutex.withLock {
        val rec = state.installed.firstOrNull { it.info.internalName == internalName }
            ?: error("Plugin '$internalName' is not installed")
        val repo = state.repos.firstOrNull { it.url == rec.info.repoUrl } ?: client.addRepo(rec.info.repoUrl)
        val latest = client.listAvailable(repo).firstOrNull { it.internalName == internalName }
            ?: error("Plugin '$internalName' not found in ${repo.url}")
        val cs3 = File(rec.cs3Path)
        val wasLoaded = loader.isLoaded(cs3)
        // Keep the working version: if the new one does not load, put the old .cs3 (and its cached jar) back.
        val backup = File(cs3.path + ".bak")
        runCatching { cs3.copyTo(backup, overwrite = true) }
        val inst = installLocked(latest)
        val res = loader.load(latest.name, File(inst.dexPath), File(inst.jarPath))
        if (res is LoadResult.Failed && backup.isFile) {
            loader.unload(cs3)
            atomicMove(backup, cs3)
            writeState(state.copy(installed = state.installed.filter { it.info.internalName != internalName } + rec))
            if (wasLoaded) loader.load(rec.info.name, cs3, File(rec.jarPath)) else loader.unload(cs3)
            val g = loader.guard
            if (res.blocked.isNotEmpty() && g != null) {
                // The new version gained something dangerous: keep the old one, offer "Allow and update".
                g.pendingUpdates[PluginGuard.keyOf(cs3)] = g.report(File(inst.jarPath))
                error("Version ${latest.version} of '${latest.name}' was blocked for safety: it can " +
                    res.blocked.mapNotNull { n -> Capability.entries.firstOrNull { it.name == n }?.label }.joinToString(", ") +
                    ". Kept version ${rec.info.version}; allow it in Extensions only if you trust it.")
            }
            error("Version ${latest.version} of '${latest.name}' failed to load (${res.error.take(200)}); kept version ${rec.info.version}")
        }
        loader.guard?.pendingUpdates?.remove(PluginGuard.keyOf(cs3))
        if (!wasLoaded) loader.unload(cs3)
        backup.delete()
        inst
    }

    override fun installed(): List<InstalledPlugin> =
        state.installed.map { InstalledPlugin(it.info, it.cs3Path, it.jarPath) }

    override suspend fun loadAll(): List<LoadResult> = withContext(Dispatchers.IO) {
        state.installed.filter { it.info.status != 0 }.map { rec ->
            val jar = File(rec.jarPath).takeIf { it.isFile }
                ?: runCatching { converter.convert(File(rec.cs3Path), rec.sha256) }.getOrElse {
                    return@map PluginLoader.failure(rec.info.name, it)
                }
            loader.load(rec.info.name, File(rec.cs3Path), jar)
        }
    }

    /** Load a single installed plugin (e.g. right after install). */
    suspend fun load(internalName: String): LoadResult = withContext(Dispatchers.IO) {
        val rec = state.installed.firstOrNull { it.info.internalName == internalName }
            ?: return@withContext LoadResult.Failed(internalName, "not installed", emptyList())
        loader.load(rec.info.name, File(rec.cs3Path), File(rec.jarPath))
    }

    /** What the safety check found in an installed extension (null without a guard or a converted jar). */
    data class SafetyStatus(val report: ScanReport, val allowed: Set<Capability>, val pendingUpdate: ScanReport?)

    fun safety(internalName: String): SafetyStatus? {
        val g = loader.guard ?: return null
        val rec = state.installed.firstOrNull { it.info.internalName == internalName } ?: return null
        val jar = File(rec.jarPath).takeIf { it.isFile } ?: return null
        val key = PluginGuard.keyOf(File(rec.cs3Path))
        return SafetyStatus(g.report(jar), g.allowed(key), g.pendingUpdates[key])
    }

    /** The user allows [caps] for this extension (kept across updates); call load / update afterwards. */
    fun allow(internalName: String, caps: Set<Capability>) {
        val g = loader.guard ?: return
        val rec = state.installed.firstOrNull { it.info.internalName == internalName } ?: return
        g.allow(PluginGuard.keyOf(File(rec.cs3Path)), caps)
    }

    /** Takes back every allowance of this extension (applies from its next load). */
    fun revokeSafety(internalName: String) {
        val g = loader.guard ?: return
        val rec = state.installed.firstOrNull { it.info.internalName == internalName } ?: return
        g.revoke(PluginGuard.keyOf(File(rec.cs3Path)))
    }

    fun unload(internalName: String): Boolean =
        state.installed.firstOrNull { it.info.internalName == internalName }?.let { loader.unload(File(it.cs3Path)) } ?: false
}
