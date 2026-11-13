package cloudstream.desktop.app

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cloudstream.desktop.app.ui.Glyph
import cloudstream.desktop.app.ui.GlyphIcon
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** The round-button icons must sit in the middle of their box (text glyphs did not: side bearings, baseline). */
class GlyphCenteringTest {
    private val px = 64

    /** Centre of mass of the drawn pixels (alpha-weighted), in pixels. */
    private fun centroid(g: Glyph): Pair<Double, Double> {
        val scene = ImageComposeScene(px, px, Density(1f)) { GlyphIcon(g, px.dp) }
        try {
            val img = scene.render(0)
            System.getProperty("glyph.out")?.let { java.io.File(it, "$g.png").apply { parentFile.mkdirs() }.writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes) }
            val bmp = Bitmap.makeFromImage(img)
            var sx = 0.0; var sy = 0.0; var sw = 0.0
            for (y in 0 until px) for (x in 0 until px) {
                val a = (bmp.getColor(x, y) ushr 24) and 0xFF
                if (a > 0) { sx += (x + 0.5) * a; sy += (y + 0.5) * a; sw += a }
            }
            assertTrue(sw > 0, "$g drew nothing")
            return sx / sw to sy / sw
        } finally { scene.close() }
    }

    @Test fun `icons are centred`() {
        val c = px / 2.0
        for (g in Glyph.entries) {
            val (x, y) = centroid(g)
            // A chevron's mass leans to its tip, so it gets a little more room along the pointing axis.
            val tolX = if (g == Glyph.ChevronLeft || g == Glyph.ChevronRight) px * 0.08 else px * 0.03
            val tolY = if (g == Glyph.ChevronUp || g == Glyph.ChevronDown) px * 0.08 else px * 0.03
            assertTrue(abs(x - c) <= tolX && abs(y - c) <= tolY, "$g centroid (%.1f, %.1f), box centre %.1f".format(x, y, c))
        }
    }
}
