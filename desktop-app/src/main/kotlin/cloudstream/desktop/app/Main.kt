package cloudstream.desktop.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import cloudstream.desktop.app.ui.AppRoot
import cloudstream.desktop.app.vm.Screen
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.window.WindowPlacement
import java.io.File
import kotlin.system.exitProcess

data class CliArgs(
    val fakes: Boolean = false,
    val smoke: Boolean = false,
    val screenshots: File? = null,
    val dataDir: File? = null,
    val cs3Dir: File? = null,
    val repo: String? = null,
    val install: Set<String> = emptySet(),
    val live: Boolean = false,
    val query: String = "the",
    val seed: Boolean = false,
) {
    companion object {
        fun parse(args: Array<String>): CliArgs {
            fun value(flag: String) = args.indexOf(flag).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
            return CliArgs(
                fakes = "--fakes" in args,
                smoke = "--smoke" in args,
                screenshots = value("--screenshots")?.let(::File),
                dataDir = value("--data-dir")?.let(::File),
                cs3Dir = value("--cs3")?.let(::File),
                repo = value("--repo"),
                install = value("--install")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty(),
                live = "--live" in args,
                query = value("--query") ?: "the",
                seed = "--seed" in args,
            )
        }
    }
}

fun main(argv: Array<String>) {
    // NOTE: compose.layers.type=WINDOW (popups as native windows) crashed natively in skiko's Direct3D redrawer
    // (re-entrant reshape during render). The player instead hides the video surface while one of its menus is open.
    // Log file, stdout/stderr capture and uncaught exceptions (UI thread included): <data>/logs/streamlet.0.log.
    AppLog.install()
    AppLog.banner(argv)
    // Draw on the discrete GPU on hybrid laptops (the high-refresh panel is usually wired to it); -D overrides.
    if (System.getProperty("skiko.directx.gpu.priority") == null) System.setProperty("skiko.directx.gpu.priority", "discrete")
    // CLOUDSTREAM_FPS=1: skiko prints the frame rate and long frames to the console (diagnosing stutter).
    if (System.getenv("CLOUDSTREAM_FPS") == "1") {
        System.setProperty("skiko.fps.enabled", "true"); System.setProperty("skiko.fps.periodSeconds", "2")
        System.setProperty("skiko.fps.longFrames.show", "true"); System.setProperty("skiko.hardwareInfo.enabled", "true")
    }
    val args = CliArgs.parse(argv)
    if (args.smoke) exitProcess(Smoke.run(args))
    if ("--home-shot" in argv) exitProcess(HomeShot.run(argv))
    if ("--player-shot" in argv) exitProcess(PlayerShot.run(argv))
    if ("--menu-shot" in argv) exitProcess(MenuShot.run(argv))
    argv.indexOf("--render-icons").takeIf { it >= 0 }?.let { exitProcess(IconExport.run(File(argv[it + 1]))) }
    // `--update-check OWNER/NAME [current]`: what the app updater sees for that repo's latest GitHub release.
    argv.indexOf("--update-check").takeIf { it >= 0 }?.let { i ->
        val u = cloudstream.desktop.app.update.AppUpdater(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO),
            repo = argv[i + 1], current = argv.getOrElse(i + 2) { "0.0.1" })
        kotlinx.coroutines.runBlocking { u.check()?.join() }
        when (val s = u.state.value) {
            is cloudstream.desktop.app.update.UpdateState.Available ->
                println("update check ${argv[i + 1]}: newer ${s.release.version}, installer=${s.release.assetName} (${s.release.assetSize} bytes) ${s.release.assetUrl}")
            else -> println("update check ${argv[i + 1]}: $s")
        }
        exitProcess(0)
    }
    if ("--real-play-shot" in argv)exitProcess(RealPlayShot.run(argv))
    if ("--play-flow-shot" in argv) exitProcess(PlayFlowShot.run(java.io.File(argv[argv.indexOf("--play-flow-shot") + 1])))
    argv.indexOf("--sync-probe").takeIf { it >= 0 }?.let { exitProcess(cloudstream.desktop.app.sync.SyncProbe.run(File(argv.getOrElse(it + 1) { "sync-probe" }))) }
    // `--relay-probe [wss://broker/mqtt ...]`: public MQTT brokers as watch party relays (latency, loss, live party).
    if ("--relay-probe" in argv) exitProcess(cloudstream.desktop.app.party.RelayProbe.run(argv))
    if (args.screenshots != null) exitProcess(Screenshots.run(args.screenshots))

    // The window opens at once with a splash; the services (storage, extension loader, ...) are built on a background
    // thread and the app appears when they are ready.
    val root = AppServices.appRoot(args.dataDir)
    val holder = mutableStateOf<AppController?>(null)
    val startupError = mutableStateOf<String?>(null)
    Thread({
        try {
            if (!ClasspathCheck.verify() && !args.fakes) {
                System.err.println("Refusing to start with the real modules: fix the classpath order (see above).")
                startupError.value = "Streamlet could not start: its libraries are in the wrong order (see the log)."
                return@Thread
            }
            val services = if (args.fakes) AppServices.fakes(seedInstalled = true) else AppServices.real(root)
            val controller = AppController(services)
            AppLog.get("app").info("Services ready (${services.mode})")
            // DNS over HTTPS for the extensions' HTTP client: now, and whenever it is changed in Settings.
            if (services.mode == AppServices.Mode.Real) services.scope.launch {
                services.globalSettings.dnsProvider.collect { p ->
                    runCatching { cloudstream.desktop.webview.NetworkDns.apply(p) }
                        .onSuccess { AppLog.get("network").info("DNS: ${p.label}") }
                        .onFailure { AppLog.get("network").warning("DNS ${p.label} not applied: $it") }
                }
            }
            AppLog.startFreezeWatch()
            if (!args.fakes) Thread({ runCatching { DiskCleanup.run() }.onFailure { AppLog.get("cleanup").warning("Cleanup failed: $it") } }, "disk-cleanup")
                .apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
            holder.value = controller
        } catch (t: Throwable) {
            AppLog.get("app").severe("Startup failed: $t")
            startupError.value = "Streamlet could not start: ${t.message ?: t::class.simpleName}"
        }
    }, "startup").apply { isDaemon = true }.start()
    runWindow(holder, startupError, File(root, "window.txt"))
    AppLog.get("app").info("Window closed, shutting down")
    holder.value?.services?.close()
    exitProcess(0)
}

/** Window size / position, kept in a tiny file so the window can open before the settings store is loaded. */
private fun readWindowFile(f: File): WindowPrefs? = runCatching {
    val p = f.readText().trim().split(',')
    WindowPrefs(p[0].toInt(), p[1].toInt(), p[2].toIntOrNull(), p[3].toIntOrNull(), p[4] == "1")
}.getOrNull()

private fun writeWindowFile(f: File, w: WindowPrefs) {
    runCatching { f.writeText("${w.width},${w.height},${w.x ?: ""},${w.y ?: ""},${if (w.maximized) 1 else 0}") }
}

@OptIn(FlowPreview::class)
private fun runWindow(holder: androidx.compose.runtime.State<AppController?>, startupError: androidx.compose.runtime.State<String?>, windowFile: File) = application(exitProcessOnExit = false) {
    val c = holder.value
    val saved = remember { readWindowFile(windowFile) }
    val state = remember {
        WindowState(
            size = DpSize((saved?.width ?: 1280).dp, (saved?.height ?: 800).dp),
            position = if (saved?.x != null && saved.y != null) WindowPosition(saved.x.dp, saved.y.dp) else WindowPosition.PlatformDefault,
            placement = if (saved?.maximized == true) WindowPlacement.Maximized else WindowPlacement.Floating,
        )
    }
    LaunchedEffect(c) { c?.start() }
    // First run after the window file existed nowhere: take the size from the settings store once it is loaded.
    LaunchedEffect(c) {
        if (c != null && saved == null) c.services.globalSettings.window()?.let {
            state.size = DpSize(it.width.dp, it.height.dp)
            if (it.x != null && it.y != null) state.position = WindowPosition(it.x.dp, it.y.dp)
            if (it.maximized) state.placement = WindowPlacement.Maximized
        }
    }
    // Window-state persistence (debounced; not while in the player's fullscreen).
    LaunchedEffect(state, c) {
        snapshotFlow { Triple(state.size, state.position, state.placement) }.debounce(500).collect { (size, pos, placement) ->
            if (placement != WindowPlacement.Fullscreen && c?.fullscreen?.value != true) {
                val prefs = WindowPrefs(size.width.value.toInt(), size.height.value.toInt(),
                    pos.takeIf { it.isSpecified }?.x?.value?.toInt(), pos.takeIf { it.isSpecified }?.y?.value?.toInt(),
                    placement == WindowPlacement.Maximized)
                writeWindowFile(windowFile, prefs)
                c?.services?.globalSettings?.saveWindow(prefs)
            }
        }
    }
    val searchFocus = remember { FocusRequester() }
    var wantSearchFocus by remember { mutableStateOf(0) }
    val playerKeysDown = remember { HashSet<Key>() }
    Window(
        onCloseRequest = ::exitApplication,
        state = state,
        icon = cloudstream.desktop.app.ui.AppLogo.painter,
        title = "Streamlet" + if (c?.services?.mode == AppServices.Mode.Fakes) " (fake data)" else "",
        onPreviewKeyEvent = { e ->
            val c = holder.value ?: return@Window false
            // The player (when open) gets every key first, whatever has focus. The release of a key it handled is
            // swallowed too: otherwise a focused button (e.g. the play button just clicked) "clicked" on Space's
            // release and undid the pause.
            if (c.nav.current is Screen.Player) {
                if (e.type == KeyEventType.KeyDown &&
                    cloudstream.desktop.app.ui.PlayerKeys.handler?.invoke(e.key, e.isShiftPressed, e.isAltPressed, e.isCtrlPressed) == true) {
                    playerKeysDown += e.key; return@Window true
                }
                if (e.type == KeyEventType.KeyUp) {
                    val up = cloudstream.desktop.app.ui.PlayerKeys.keyUp?.invoke(e.key) == true
                    if (playerKeysDown.remove(e.key) || up) return@Window true
                }
            }
            if (e.type != KeyEventType.KeyDown) return@Window false
            when {
                e.isCtrlPressed && e.key == Key.One -> { c.goTo(Screen.Home); true }
                e.isCtrlPressed && e.key == Key.Two -> { c.goTo(Screen.Search); true }
                e.isCtrlPressed && e.key == Key.Three -> { c.goTo(Screen.Library); true }
                e.isCtrlPressed && e.key == Key.Four -> { c.goTo(Screen.Extensions); true }
                e.isCtrlPressed && e.key == Key.Five -> { c.goTo(Screen.Settings); true }
                e.isCtrlPressed && (e.key == Key.F || e.key == Key.K) -> { c.goTo(Screen.Search); wantSearchFocus++; true }
                (e.isAltPressed && e.key == Key.DirectionLeft) || e.key == Key.Escape -> c.back()
                else -> false
            }
        },
    ) {
        LaunchedEffect(wantSearchFocus) { if (wantSearchFocus > 0) runCatching { kotlinx.coroutines.delay(100); searchFocus.requestFocus() } }
        // Borderless fullscreen (like VLC / browsers), NOT Java's exclusive full-screen mode: that one minimised the
        // window on Alt+Tab and left it "always on top" and impossible to switch back to.
        LaunchedEffect(c) {
            if (c == null) return@LaunchedEffect
            var saved: Pair<java.awt.Rectangle, Int>? = null
            c.fullscreen.collect { fs ->
                val w = window
                // Linux (X11 / XWayland): ask the window manager for real fullscreen (_NET_WM_STATE_FULLSCREEN). The
                // off-screen-title-bar trick below is clamped by most Linux WMs (the title bar stays visible).
                if (cloudstream.desktop.contracts.Os.isLinux) {
                    val dev = w.graphicsConfiguration.device
                    if (fs && dev.fullScreenWindow !== w) runCatching { dev.fullScreenWindow = w }
                    else if (!fs && dev.fullScreenWindow === w) runCatching { dev.fullScreenWindow = null }
                    return@collect
                }
                if (fs && saved == null) {
                    saved = w.bounds to w.extendedState
                    w.extendedState = java.awt.Frame.NORMAL
                    // Windows: no caption, monitor-sized, marked full-screen for the taskbar (else it stays on top).
                    if (cloudstream.desktop.contracts.Os.isWindows && WinFullscreen.enter(w)) { w.toFront(); return@collect }
                    val screen = w.graphicsConfiguration.bounds
                    val ins = w.insets
                    // Title bar and borders just outside the screen; the client area covers the whole monitor.
                    w.setBounds(screen.x - ins.left, screen.y - ins.top, screen.width + ins.left + ins.right, screen.height + ins.top + ins.bottom)
                    w.isAlwaysOnTop = false
                    w.toFront()
                } else if (!fs && saved != null) {
                    val (b, st) = saved!!
                    saved = null
                    if (cloudstream.desktop.contracts.Os.isWindows) WinFullscreen.exit(w)
                    w.bounds = b
                    w.extendedState = st
                }
            }
        }
        if (c != null) AppRoot(c, searchFocus) else cloudstream.desktop.app.ui.StartupSplash(startupError.value)
    }
}
