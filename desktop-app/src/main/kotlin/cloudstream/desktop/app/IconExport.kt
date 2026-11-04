package cloudstream.desktop.app

import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM
import org.jetbrains.skia.svg.SVGLength
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * `--render-icons <dir>`: renders the app logo (resources/logo.svg) into the installer icons jpackage needs:
 * `streamlet.png` (512 px, Linux .desktop entry) and `streamlet.ico` (16-256 px, Windows MSI / shortcuts).
 */
object IconExport {
    private val sizes = listOf(16, 24, 32, 48, 64, 128, 256)

    fun png(svg: ByteArray, px: Int): ByteArray {
        val dom = SVGDOM(Data.makeFromBytes(svg))
        dom.root?.let { it.width = SVGLength(px.toFloat()); it.height = SVGLength(px.toFloat()) }
        dom.setContainerSize(px.toFloat(), px.toFloat())
        val surface = Surface.makeRasterN32Premul(px, px)
        dom.render(surface.canvas)
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

    /** An ICO whose entries are PNG images (supported since Windows Vista; jpackage accepts it). */
    fun ico(pngs: List<Pair<Int, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        fun u32(v: Int) { u16(v and 0xFFFF); u16((v ushr 16) and 0xFFFF) }
        u16(0); u16(1); u16(pngs.size)
        var offset = 6 + 16 * pngs.size
        for ((px, data) in pngs) {
            out.write(if (px >= 256) 0 else px); out.write(if (px >= 256) 0 else px) // 0 = 256
            out.write(0); out.write(0); u16(1); u16(32); u32(data.size); u32(offset)
            offset += data.size
        }
        pngs.forEach { out.write(it.second) }
        return out.toByteArray()
    }

    fun run(dir: File): Int {
        val svg = IconExport::class.java.getResourceAsStream("/logo.svg")?.use { it.readBytes() } ?: error("logo.svg missing")
        dir.mkdirs()
        File(dir, "streamlet.png").writeBytes(png(svg, 512))
        File(dir, "streamlet.ico").writeBytes(ico(sizes.map { it to png(svg, it) }))
        println("icons written to ${dir.absolutePath}")
        return 0
    }
}
