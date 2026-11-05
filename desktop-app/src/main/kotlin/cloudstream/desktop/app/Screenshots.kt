package cloudstream.desktop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import cloudstream.desktop.app.domain.MediaCard
import cloudstream.desktop.app.ui.AppRoot
import cloudstream.desktop.app.vm.Screen
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Offscreen rendering of the real UI against the fake services (no network/CEF/libmpv, no window needed).
 * `--screenshots <dir>` writes PNGs; used for docs and as a visual smoke test.
 */
object Screenshots {
    fun run(dir: File): Int {
        dir.mkdirs()
        // STREAMLET_SHOTS=new: only the episode / download screens.
        if (System.getenv("STREAMLET_SHOTS") == "new") return if (NewScreens.run(dir) == 0) 0 else 1
        var failed = 0
        val scenes: List<Pair<String, (AppController) -> Unit>> = listOf(
            "extensions" to { c -> c.goTo(Screen.Extensions); c.start(); Unit },
            "search" to { c -> c.goTo(Screen.Search); c.search.setQuery("naruto"); c.search.search() ; Unit },
            "details-sources" to { c ->
                val card = MediaCard("Demo Anime", "Naruto Demo", "demo://Demo Anime/Naruto-1", null, "Anime")
                c.goTo(Screen.Search); c.openDetails(card)
                c.detailsVm(card).let { vm -> Thread.sleep(400); vm.state.value.let { _ -> }; vm.visibleEpisodes().firstOrNull()?.let { vm.findSources(it) } }
            },
            "home" to { c -> c.goTo(Screen.Home) },
            "settings" to { c ->
                c.services.profiles.create("Kids"); c.services.profiles.setAskOnStart(false)
                c.services.settings.apply {
                    noteSources(listOf("Server A", "Server B", "Server C", "Server D", "Server E"))
                    moveSource("Server A", 0); moveSource("Server E", 0); setSourceHidden("Server D", true)
                }
                c.goTo(Screen.Settings)
            },
            "profile-picker" to { c -> c.services.profiles.create("Kids"); c.services.profiles.create("Guest"); Unit },
        )
        for ((name, setup) in scenes) {
            val services = AppServices.fakes(seedInstalled = true)
            val controller = AppController(services)
            setup(controller)
            val scene = ImageComposeScene(1280, 800, Density(1f)) { AppRoot(controller) }
            try {
                var t = 0L
                // ~3 s of frames so async loads (provider delays, timeouts) land in the picture.
                repeat(60) { scene.render(t); t += 50_000_000L; Thread.sleep(50) }
                if (name == "details-sources") { repeat(10) { scene.render(t); t += 50_000_000L; Thread.sleep(50) } }
                val data = scene.render(t).encodeToData(EncodedImageFormat.PNG)?.bytes
                if (data == null) { failed++; println("screenshot $name: encode failed") } else {
                    File(dir, "$name.png").writeBytes(data); println("screenshot $name -> ${File(dir, "$name.png").absolutePath} (${data.size / 1024} KB)")
                }
            } catch (t: Throwable) {
                failed++; println("screenshot $name failed: $t"); t.printStackTrace()
            } finally {
                scene.close(); services.close()
            }
        }
        // The Home loading skeleton on its own, captured mid-sweep (the shimmer is a 2 s loop).
        runCatching {
            val scene = ImageComposeScene(1280, 800, Density(1f)) {
                cloudstream.desktop.app.ui.CloudTheme {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(cloudstream.desktop.app.ui.Ink)) {
                        cloudstream.desktop.app.ui.HomeLoadingShimmer()
                    }
                }
            }
            var t = 0L
            repeat(14) { scene.render(t); t += 50_000_000L }
            File(dir, "home-loading.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.close(); println("screenshot home-loading -> ${File(dir, "home-loading.png").absolutePath}")
        }.onFailure { failed++; println("screenshot home-loading failed: $it") }
        failed += NewScreens.run(dir)
        return if (failed == 0) 0 else 1
    }
}
