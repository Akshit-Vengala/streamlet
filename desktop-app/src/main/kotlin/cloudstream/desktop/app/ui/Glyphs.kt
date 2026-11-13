package cloudstream.desktop.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp

/**
 * Small vector icons drawn in a square box. Text glyphs ("‹", "▶", "❚❚") carry font side bearings and baseline
 * offsets, so they never sit in the middle of a round button; these are centred geometrically.
 */
enum class Glyph { ChevronLeft, ChevronRight, ChevronUp, ChevronDown, Play, Pause }

@Composable
fun GlyphIcon(glyph: Glyph, size: Dp, color: Color = Color.White, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        when (glyph) {
            Glyph.Play -> {
                // Triangle whose centroid ((x1+x2+x3)/3 = 0.5) is the box centre: looks centred, unlike a bbox-centred one.
                val p = Path().apply {
                    moveTo(w * 0.32f, h * 0.18f); lineTo(w * 0.86f, h * 0.5f); lineTo(w * 0.32f, h * 0.82f); close()
                }
                drawPath(p, color)
                drawPath(p, color, style = Stroke(width = w * 0.06f, join = StrokeJoin.Round))
            }
            Glyph.Pause -> {
                val bw = w * 0.22f
                val r = CornerRadius(bw * 0.25f)
                drawRoundRect(color, Offset(w * 0.22f, h * 0.18f), Size(bw, h * 0.64f), r)
                drawRoundRect(color, Offset(w * 0.56f, h * 0.18f), Size(bw, h * 0.64f), r)
            }
            else -> {
                // Chevron: an open "V" rotated per direction, its visual middle on the box centre.
                val s = Stroke(width = w * 0.13f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                val (a, b, c) = when (glyph) {
                    Glyph.ChevronLeft -> Triple(Offset(0.60f, 0.22f), Offset(0.36f, 0.5f), Offset(0.60f, 0.78f))
                    Glyph.ChevronRight -> Triple(Offset(0.40f, 0.22f), Offset(0.64f, 0.5f), Offset(0.40f, 0.78f))
                    Glyph.ChevronUp -> Triple(Offset(0.22f, 0.60f), Offset(0.5f, 0.36f), Offset(0.78f, 0.60f))
                    else -> Triple(Offset(0.22f, 0.40f), Offset(0.5f, 0.64f), Offset(0.78f, 0.40f))
                }
                val p = Path().apply { moveTo(a.x * w, a.y * h); lineTo(b.x * w, b.y * h); lineTo(c.x * w, c.y * h) }
                drawPath(p, color, style = s)
            }
        }
    }
}
