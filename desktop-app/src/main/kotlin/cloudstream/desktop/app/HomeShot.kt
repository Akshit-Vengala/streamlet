package cloudstream.desktop.app

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import cloudstream.desktop.app.ui.AppRoot
import cloudstream.desktop.app.vm.Screen
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * `--home-shot <png> [--data-dir <dir>] [--wait <sec>]`: boots the REAL app (installed extensions, network), shows Home
 * and renders it offscreen to a PNG after rows and artwork have had time to load. Runs plugin code.
 */
object HomeShot {
    fun run(argv: Array<String>): Int {
        val out = File(argv[argv.indexOf("--home-shot") + 1])
        val wait = argv.indexOf("--wait").takeIf { it >= 0 }?.let { argv[it + 1].toLong() } ?: 45
        val root = AppServices.appRoot(argv.indexOf("--data-dir").takeIf { it >= 0 }?.let { File(argv[it + 1]) })
        val t0 = System.currentTimeMillis()
        fun lap(what: String) = println("TIMING %-34s %6.1f s".format(what, (System.currentTimeMillis() - t0) / 1000.0))
        val services = AppServices.real(root)
        val c = AppController(services)
        lap("services ready")
        runBlocking { c.extensions.start().join() } // load installed extensions
        lap("extensions loaded")
        if ("--apply-updates" in argv) runBlocking {
            c.extensions.applyUpdates(install = true).ifEmpty { listOf("no updates") }.forEach { println("UPDATE $it") }
            lap("updates applied")
            println("providers after update: ${c.providers().joinToString { it.name }}")
        }
        argv.indexOf("--home").takeIf { it >= 0 }?.let { services.settings.setHomeProvider(argv[it + 1]) }
        println("providers: ${c.providers().joinToString { it.name }}; home=${services.settings.homeProvider.value}")
        services.profiles.setAskOnStart(false)
        c.goTo(Screen.Home)
        val scene = ImageComposeScene(1440, 900, Density(1f)) { AppRoot(c) }
        try {
            var t = 0L
            val end = System.currentTimeMillis() + wait * 1000
            if (wait == 0L) repeat(12) { scene.render(t); t += 100_000_000L } // --wait 0: capture the loading state right away
            var firstRow = false; var twoRows = false
            while (System.currentTimeMillis() < end) {
                scene.render(t); t += 100_000_000L; Thread.sleep(100)
                val secs = c.home.state.value.sections
                if (!firstRow && secs.any { it.items.isNotEmpty() }) { firstRow = true; lap("first row on screen (hero too)") }
                if (!twoRows && secs.count { it.items.isNotEmpty() } >= 2) { twoRows = true; lap("second row on screen") }
            }
            val s = c.home.state.value
            println("home provider=${s.provider}; sections=" + s.sections.joinToString { "${it.title}:${it.items.size}${it.error?.let { e -> " ERR $e" } ?: ""}" })
            val png = scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes
            out.parentFile?.mkdirs(); out.writeBytes(png)
            println("wrote ${out.absolutePath}")
        } finally { scene.close(); services.close() }
        // Storage is flushed by close(); halt instead of exit so a lingering shutdown hook (CEF) cannot hang the run.
        System.out.flush(); Runtime.getRuntime().halt(0)
        return 0
    }
}
