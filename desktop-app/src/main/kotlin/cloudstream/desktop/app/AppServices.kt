package cloudstream.desktop.app

import cloudstream.desktop.app.domain.DemoBackend
import cloudstream.desktop.app.domain.DemoCatalog
import cloudstream.desktop.app.domain.ExtensionsBackend
import cloudstream.desktop.app.domain.ProviderCatalog
import cloudstream.desktop.app.domain.RealCatalog
import cloudstream.desktop.contracts.HistoryEntry
import cloudstream.desktop.contracts.LibraryStore
import cloudstream.desktop.contracts.LoadResult
import cloudstream.desktop.contracts.Player
import cloudstream.desktop.contracts.PluginInfo
import cloudstream.desktop.contracts.PluginRepository
import cloudstream.desktop.contracts.fakes.FakePlayer
import cloudstream.desktop.contracts.fakes.InMemoryKeyValueStore
import cloudstream.desktop.contracts.fakes.InMemoryLibraryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.awt.Desktop
import java.io.File
import java.net.URI

/** Where a player came from, so the UI can say plainly when it is not the real one. */
data class PlayerHandle(val player: Player, val description: String, val real: Boolean, val embeddable: Boolean) {
    fun release() { runCatching { player.stop() }; (player as? AutoCloseable)?.let { runCatching { it.close() } } }
}

fun interface PlayerSource { fun create(choice: PlayerChoice): PlayerHandle }

/** Lazy, optional headless-browser (JCEF) control. Nothing is downloaded until [install] or first plugin use. */
interface BrowserControl {
    val label: String
    fun isInstalled(): Boolean
    /** Blocking; run on a worker thread. [onProgress] gets (stage, fraction or null). */
    fun install(onProgress: (String, Float?) -> Unit)
}

interface HistoryWriter { fun record(e: HistoryEntry) }

/**
 * Composition root. [real] wires the actual modules (storage -> shim -> loader -> webview -> player);
 * [fakes] wires the contract fakes plus [DemoCatalog] so the UI runs without network/CEF/libmpv.
 */
class AppServices(
    val mode: Mode,
    val rootDir: File,
    val extensions: ExtensionsBackend,
    val catalog: ProviderCatalog,
    val profiles: Profiles,
    /** Machine-wide preferences (window size/position); the default profile's settings store. */
    val globalSettings: AppSettings,
    val browser: BrowserControl,
    val players: PlayerSource,
    private val closeables: List<AutoCloseable> = emptyList(),
) : AutoCloseable {
    enum class Mode { Real, Fakes }

    // Per-profile: always read through these getters (never cache them), so a profile switch takes effect everywhere.
    val library: LibraryStore get() = profiles.data().library
    val history: HistoryWriter get() = profiles.data().history
    val settings: AppSettings get() = profiles.data().settings
    val sync: cloudstream.desktop.app.sync.SyncManager get() = profiles.data().sync

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Bumped whenever the provider set may have changed (extension loaded/unloaded). */
    private val _catalogRevision = MutableStateFlow(0)
    val catalogRevision: StateFlow<Int> = _catalogRevision
    fun providersChanged() {
        _catalogRevision.value++
        runCatching { cloudstream.desktop.app.domain.Guardrails.clearCache() } // upstream drops the load cache on plugin reload
    }

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()
    fun toast(msg: String) { AppLog.get("toast").info(msg.replace('\n', ' ')); _toasts.tryEmit(msg) }

    override fun close() {
        scope.cancel()
        closeables.forEach { runCatching { it.close() } }
    }

    companion object {
        fun appRoot(override: File? = null): File = override ?: cloudstream.desktop.contracts.AppDirs.root

        fun fakes(
            seedInstalled: Boolean = false,
            catalog: ProviderCatalog = DemoCatalog(),
            players: PlayerSource = PlayerSource { PlayerHandle(FakePlayer(), "Fake player (no video output)", real = false, embeddable = false) },
        ): AppServices {
            val backend = DemoBackend()
            if (seedInstalled) kotlinx.coroutines.runBlocking { backend.seed() }
            val defaultSettings = AppSettings(InMemoryKeyValueStore())
            val profiles = Profiles(InMemoryKeyValueStore(), open = { id ->
                val lib = InMemoryLibraryStore()
                val hist = java.util.concurrent.CopyOnWriteArrayList<HistoryEntry>()
                ProfileData(
                    object : LibraryStore by lib {
                        override fun history(): List<HistoryEntry> = hist.sortedByDescending { it.watchedAt }
                        override fun removeHistory(id: String) { hist.removeAll { it.id == id } }
                    },
                    object : HistoryWriter { override fun record(e: HistoryEntry) { hist.removeAll { it.id == e.id }; hist += e } },
                    if (id == Profiles.DEFAULT_ID) defaultSettings else AppSettings(InMemoryKeyValueStore()),
                )
            }, wipe = {})
            return AppServices(
                Mode.Fakes, File(System.getProperty("java.io.tmpdir"), "cloudstream-desktop-fakes"),
                backend, catalog, profiles, defaultSettings,
                object : BrowserControl {
                    override val label = "fake browser"
                    override fun isInstalled() = true
                    override fun install(onProgress: (String, Float?) -> Unit) {}
                },
                players,
            )
        }

        /** Wires the real modules. Must run with the classpath order checked by [ClasspathCheck]. */
        fun real(root: File = appRoot()): AppServices = RealWiring.build(root)
    }
}

/** Opens [url] in the default browser; on Linux desktops without AWT Desktop support (some WMs, Wayland) uses xdg-open. */
internal fun openInBrowser(url: String) {
    val ok = runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) { Desktop.getDesktop().browse(URI(url)); true } else false
    }.getOrDefault(false)
    if (!ok) openWithSystem(url)
}

/** Opens a folder / file with the system handler (Explorer, xdg-open, open). */
internal fun openPath(f: File) {
    val ok = runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) { Desktop.getDesktop().open(f); true } else false
    }.getOrDefault(false)
    if (!ok) openWithSystem(f.absolutePath)
}

private fun openWithSystem(target: String) {
    val cmd = when {
        cloudstream.desktop.contracts.Os.isWindows -> listOf("rundll32", "url.dll,FileProtocolHandler", target)
        cloudstream.desktop.contracts.Os.isMac -> listOf("open", target)
        else -> listOf("xdg-open", target)
    }
    runCatching { ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start() }
}

/** Adapter helper so the loader's extra API (not on [PluginRepository]) is reachable through [ExtensionsBackend]. */
internal class DelegatingBackend(
    private val repo: PluginRepository,
    private val removeRepoFn: suspend (String) -> Unit,
    private val checkUpdatesFn: suspend () -> List<PluginInfo>,
    private val loadFn: suspend (String) -> LoadResult,
    private val unloadFn: (String) -> Boolean,
    private val warningsFn: () -> List<String>,
    private val safetyFn: (String) -> cloudstream.desktop.app.domain.ExtensionSafety? = { null },
    private val allowFn: (String, List<String>) -> Unit = { _, _ -> },
    private val revokeFn: (String) -> Unit = {},
) : ExtensionsBackend, PluginRepository by repo {
    override fun safety(internalName: String) = safetyFn(internalName)
    override fun allowCapabilities(internalName: String, ids: List<String>) = allowFn(internalName, ids)
    override fun revokeCapabilities(internalName: String) = revokeFn(internalName)
    override suspend fun removeRepo(url: String) = removeRepoFn(url)
    override suspend fun checkUpdates() = checkUpdatesFn()
    override suspend fun loadOne(internalName: String) = loadFn(internalName)
    override fun unloadOne(internalName: String) = unloadFn(internalName)
    override val warnings: List<String> get() = warningsFn()
}
