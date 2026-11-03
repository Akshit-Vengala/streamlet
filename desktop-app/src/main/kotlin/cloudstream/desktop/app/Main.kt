package cloudstream.desktop.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    installErrorLog()
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
    if (args.screenshots != null) exitProcess(Screenshots.run(args.screenshots))

    if (!ClasspathCheck.verify() && !args.fakes) {
        System.err.println("Refusing to start with the real modules: fix the classpath order (see above).")
        exitProcess(3)
    }
    val services = if (args.fakes) AppServices.fakes(seedInstalled = true)
    else AppServices.real(AppServices.appRoot(args.dataDir))
    val controller = AppController(services)
    runWindow(controller)
    services.close()
    exitProcess(0)
}

/**
 * Uncaught exceptions (UI thread included) go to `<data>/logs/errors.log` with their full trace. Plugins sometimes throw
 * exceptions whose own toString/printStackTrace fail, which left only "Exception in thread ..." on the console.
 */
private fun installErrorLog() {
    val file = File(cloudstream.desktop.contracts.AppDirs.root, "logs/errors.log")
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { t, e ->
        runCatching {
            file.parentFile.mkdirs()
            if (file.length() > 5_000_000) file.writeText("") // keep it small
            val sw = java.io.StringWriter()
            val trace = runCatching { e.printStackTrace(java.io.PrintWriter(sw)); sw.toString() }.getOrElse {
                // A broken toString/getMessage: fall back to class name + raw frames.
                "${e.javaClass.name} (message unavailable: $it)\n" + e.stackTrace.joinToString("\n") { "\tat $it" }
            }
            file.appendText("---- ${java.time.LocalDateTime.now()} thread=${t.name}\n$trace\n")
        }
        if (previous != null) previous.uncaughtException(t, e) else System.err.println("Uncaught on ${t.name}: ${e.javaClass.name} (see ${file.absolutePath})")
    }
}

@OptIn(FlowPreview::class)
private fun runWindow(c: AppController) = application(exitProcessOnExit = false) {
    val saved = c.services.globalSettings.window()
    val state = remember {
        WindowState(
            size = DpSize((saved?.width ?: 1280).dp, (saved?.height ?: 800).dp),
            position = if (saved?.x != null && saved.y != null) WindowPosition(saved.x.dp, saved.y.dp) else WindowPosition.PlatformDefault,
            placement = if (saved?.maximized == true) WindowPlacement.Maximized else WindowPlacement.Floating,
        )
    }
    LaunchedEffect(Unit) { c.start() }
    // Window-state persistence (debounced; not while in the player's fullscreen).
    LaunchedEffect(state) {
        snapshotFlow { Triple(state.size, state.position, state.placement) }.debounce(500).collect { (size, pos, placement) ->
            if (placement != WindowPlacement.Fullscreen && !c.fullscreen.value) c.services.globalSettings.saveWindow(
                WindowPrefs(size.width.value.toInt(), size.height.value.toInt(),
                    pos.takeIf { it.isSpecified }?.x?.value?.toInt(), pos.takeIf { it.isSpecified }?.y?.value?.toInt(),
                    placement == WindowPlacement.Maximized),
            )
        }
    }
    val searchFocus = remember { FocusRequester() }
    var wantSearchFocus by remember { mutableStateOf(0) }
    Window(
        onCloseRequest = ::exitApplication,
        state = state,
        icon = cloudstream.desktop.app.ui.AppLogo.painter,
        title = "Streamlet" + if (c.services.mode == AppServices.Mode.Fakes) " (fake data)" else "",
        onPreviewKeyEvent = { e ->
            // The player (when open) gets every key first, whatever has focus.
            if (c.nav.current is Screen.Player && e.type == KeyEventType.KeyDown &&
                cloudstream.desktop.app.ui.PlayerKeys.handler?.invoke(e.key, e.isShiftPressed, e.isAltPressed, e.isCtrlPressed) == true) return@Window true
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
        LaunchedEffect(Unit) {
            var saved: Pair<java.awt.Rectangle, Int>? = null
            c.fullscreen.collect { fs ->
                val w = window
                if (fs && saved == null) {
                    saved = w.bounds to w.extendedState
                    w.extendedState = java.awt.Frame.NORMAL
                    val screen = w.graphicsConfiguration.bounds
                    val ins = w.insets
                    // Title bar and borders just outside the screen; the client area covers the whole monitor.
                    w.setBounds(screen.x - ins.left, screen.y - ins.top, screen.width + ins.left + ins.right, screen.height + ins.top + ins.bottom)
                    w.isAlwaysOnTop = false
                    w.toFront()
                } else if (!fs && saved != null) {
                    val (b, st) = saved!!
                    saved = null
                    w.bounds = b
                    w.extendedState = st
                }
            }
        }
        AppRoot(c, searchFocus)
    }
}
