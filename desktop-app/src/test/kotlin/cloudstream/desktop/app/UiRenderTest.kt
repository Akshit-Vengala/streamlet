package cloudstream.desktop.app

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import cloudstream.desktop.app.ui.AppRoot
import cloudstream.desktop.app.vm.Screen
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.Test
import kotlin.test.assertTrue

/** Renders the real UI offscreen against fake services: no window, network, CEF or libmpv. */
class UiRenderTest {
    @Test fun `every top level screen renders with fakes`() {
        for (screen in listOf(Screen.Home, Screen.Search, Screen.Library, Screen.Extensions, Screen.Settings)) {
            val services = AppServices.fakes(seedInstalled = true)
            val c = AppController(services)
            c.goTo(screen)
            val scene = ImageComposeScene(900, 600, Density(1f)) { AppRoot(c) }
            try {
                var t = 0L
                repeat(8) { scene.render(t); t += 50_000_000L; Thread.sleep(20) }
                val bytes = scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes
                assertTrue(bytes.size > 5_000, "$screen rendered an (almost) empty image: ${bytes.size} bytes")
            } finally { scene.close(); services.close() }
        }
    }
}
