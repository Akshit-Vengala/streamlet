package cloudstream.desktop.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The app mark (`/logo.svg`, name-neutral): window / taskbar icon and the top bar. */
object AppLogo {
    /** Loaded once; null only if the resource is missing (the UI then simply has no logo). */
    val painter: Painter? by lazy {
        runCatching {
            AppLogo::class.java.getResourceAsStream("/logo.svg")!!.use { loadSvgPainter(it, Density(1f)) }
        }.getOrNull()
    }
}

@Composable
fun AppLogoImage(size: Dp = 28.dp, modifier: Modifier = Modifier) {
    val p = AppLogo.painter ?: return
    Image(p, contentDescription = "Streamlet", modifier = modifier.size(size))
}
