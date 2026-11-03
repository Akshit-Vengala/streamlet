package cloudstream.desktop.app

import cloudstream.desktop.androidshim.ShimRuntime
import cloudstream.desktop.app.domain.RealCatalog
import cloudstream.desktop.contracts.HistoryEntry
import cloudstream.desktop.contracts.fakes.FakePlayer
import cloudstream.desktop.loader.ContextProvider
import cloudstream.desktop.loader.DefaultEntrypoint
import cloudstream.desktop.loader.DesktopPluginRepository
import cloudstream.desktop.loader.PluginLoader
import cloudstream.desktop.player.ExternalPlayer
import cloudstream.desktop.player.LibMpvPlayer
import cloudstream.desktop.player.MpvLocator
import cloudstream.desktop.player.PlayerFactory
import cloudstream.desktop.storage.StorageManager
import cloudstream.desktop.webview.DesktopWebView
import cloudstream.desktop.webview.JcefProgress
import java.io.File

/** The one place that touches the real Track B/C/E/F/H modules. */
internal object RealWiring {
    fun build(root: File): AppServices {
        root.mkdirs()
        val storage = StorageManager(File(root, "data"))

        // 1. storage -> android-shim (SharedPreferences persistence, data dir). Must precede any plugin load.
        ShimRuntime.setKeyValueStore(storage.asKeyValueStore("shared_prefs"))
        ShimRuntime.setDataDir(File(root, "files").also { it.mkdirs() })
        cloudstream.desktop.webview.AndroidWebViewSupport.install() // real browser behind android.webkit.WebView
        com.lagradost.cloudstream3.CommonActivity.activity = androidx.appcompat.app.AppCompatActivity() // headless Activity: some plugins refuse to run without one
        ShimRuntime.setOpenUrlHandler { openInBrowser(it) }

        // 2. plugin loader: repos / install / load, entrypoint calls Plugin.load(shim Context).
        // Every extension is checked before its code loads (bytecode scan + a class loader walled off from the app).
        val guard = cloudstream.desktop.loader.PluginGuard(File(root, "plugins"))
        val loader = PluginLoader(entrypoint = DefaultEntrypoint(ContextProvider { ShimRuntime.context() }), guard = guard)
        val repo = DesktopPluginRepository(File(root, "plugins"), loader = loader)
        fun items(r: cloudstream.desktop.loader.ScanReport, allowed: Set<cloudstream.desktop.loader.Capability>) = r.findings.map { f ->
            cloudstream.desktop.app.domain.SafetyItem(f.capability.name, f.capability.label, f.capability.dangerous, f.capability in allowed, f.examples.firstOrNull().orEmpty())
        }
        val backend = DelegatingBackend(
            repo,
            removeRepoFn = { repo.removeRepo(it) },
            checkUpdatesFn = { repo.checkUpdates() },
            loadFn = { repo.load(it) },
            unloadFn = { repo.unload(it) },
            warningsFn = { repo.conversionWarnings },
            safetyFn = { name ->
                repo.safety(name)?.let { s ->
                    cloudstream.desktop.app.domain.ExtensionSafety(items(s.report, s.allowed),
                        s.pendingUpdate?.let { p -> items(p, s.allowed).filter { it.dangerous && !it.allowed } }.orEmpty())
                }
            },
            allowFn = { name, ids -> repo.allow(name, ids.mapNotNull { id -> cloudstream.desktop.loader.Capability.entries.firstOrNull { it.name == id } }.toSet()) },
            revokeFn = { repo.revokeSafety(it) },
        )

        // Profiles: each has its own library + UI-settings namespaces; "default" keeps the original ones.
        val defaultSettings = AppSettings(storage.namespace("desktop_ui"))
        val profiles = Profiles(storage.namespace("profiles"), open = { id ->
            val library = if (id == Profiles.DEFAULT_ID) storage.library
                else cloudstream.desktop.storage.JsonLibraryStore(storage.namespace(Profiles.ns("library", id)))
            ProfileData(
                library,
                object : HistoryWriter { override fun record(e: HistoryEntry) = library.recordHistory(e) },
                if (id == Profiles.DEFAULT_ID) defaultSettings else AppSettings(storage.namespace(Profiles.ns("desktop_ui", id))),
            )
        }, wipe = { id ->
            storage.namespace(Profiles.ns("library", id)).clear()
            storage.namespace(Profiles.ns("desktop_ui", id)).clear()
        })

        // 3. headless browser (JCEF) stays lazy: WebViewResolver/CloudflareKiller (webview-desktop) use it on demand.
        val browser = object : BrowserControl {
            override val label = "JCEF / Chromium 143"
            override fun isInstalled() = runCatching { DesktopWebView.isInstalled() }.getOrDefault(false)
            override fun install(onProgress: (String, Float?) -> Unit) {
                DesktopWebView.install { p: JcefProgress -> onProgress(p.stage.name.lowercase(), p.fraction) }
            }
        }

        // 4. player: libmpv -> mpv.exe -> VLC, else a fake so the UI still works (and says so).
        val players = PlayerSource { choice -> createPlayer(choice) }

        return AppServices(
            AppServices.Mode.Real, root, backend, RealCatalog(), profiles, defaultSettings, browser, players, listOf(storage),
        )
    }

    private fun createPlayer(choice: PlayerChoice): PlayerHandle {
        val failures = ArrayList<String>()
        try {
            val p = when (choice) {
                // libmpv in render mode (frames painted by the app, controls on top); external players only as fallback.
                PlayerChoice.Auto -> runCatching { LibMpvPlayer(renderMode = true) }.getOrElse { PlayerFactory.create() }
                PlayerChoice.Embedded -> LibMpvPlayer(renderMode = true)
                PlayerChoice.External -> when {
                    MpvLocator.findExe() != null -> ExternalPlayer(ExternalPlayer.Kind.MPV)
                    MpvLocator.findVlc() != null -> ExternalPlayer(ExternalPlayer.Kind.VLC)
                    else -> error("neither mpv.exe nor VLC found")
                }
            }
            val embeddable = p is LibMpvPlayer
            return PlayerHandle(p, p::class.simpleName ?: "player", real = true, embeddable = embeddable)
        } catch (t: Throwable) {
            failures += "${t::class.simpleName}: ${t.message}"
        }
        return PlayerHandle(
            FakePlayer(), "No player backend available (${failures.joinToString("; ").take(240)})", real = false, embeddable = false,
        )
    }
}

/** Startup assertion: the shadowing modules must win over library-jvm for these classes. */
object ClasspathCheck {
    data class Row(val className: String, val jar: String, val expectedIn: String, val ok: Boolean)

    private val expectations = listOf(
        "com.lagradost.api.ContextHelper_jvmKt" to "android-shim",
        "com.lagradost.cloudstream3.network.WebViewResolver" to "webview-desktop",
        "com.lagradost.cloudstream3.network.CloudflareKiller" to "webview-desktop",
        "com.lagradost.cloudstream3.plugins.Plugin" to "android-shim",
    )

    fun run(): List<Row> = expectations.map { (cls, expected) ->
        val loc = runCatching {
            Class.forName(cls, false, ClasspathCheck::class.java.classLoader).protectionDomain.codeSource.location.toString()
        }.getOrElse { "MISSING ($it)" }
        Row(cls, loc.substringAfterLast('/').ifBlank { loc }, expected, loc.contains(expected))
    }

    /** Logs a clear error for each violated expectation; returns true when everything is in order. */
    fun verify(log: (String) -> Unit = { System.err.println(it) }): Boolean {
        val rows = run()
        rows.filterNot { it.ok }.forEach {
            log("CLASSPATH ORDER ERROR: ${it.className} loaded from '${it.jar}', expected the '${it.expectedIn}' module. " +
                ":android-shim and :webview-desktop must precede :core/library-jvm on the runtime classpath.")
        }
        return rows.all { it.ok }
    }
}
