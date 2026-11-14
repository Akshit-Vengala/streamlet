package cloudstream.desktop.app

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A tiny fragmented MP4 (one video track) shaped like an fMP4 HLS stream: init segment, then styp/moof/mdat per segment. */
internal object FakeFmp4 {
    fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = parts.fold(ByteArray(0)) { a, b -> a + b }
        return ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray()).put(body).array()
    }
    private fun ints(vararg v: Int) = ByteBuffer.allocate(4 * v.size).apply { v.forEach { putInt(it) } }.array()

    const val SAMPLE_DURATION = 40
    const val PER_SEGMENT = 3

    val init: ByteArray = box("ftyp", "iso6".toByteArray(), ints(0)) + box("moov",
        box("mvhd", ints(0, 0, 0, 1000, 0), ByteArray(80)),
        box("trak",
            box("tkhd", ints(3, 0, 0, 1, 0, 0), ByteArray(60)),
            box("edts", box("elst", ints(0, 1, 0, 0, 0x00010000))), // one edit, length 0 = "the whole track" in fMP4
            box("mdia",
                box("mdhd", ints(0, 0, 0, 1000, 0, 0)),
                box("hdlr", ints(0, 0), "vide".toByteArray(), ByteArray(13)),
                box("minf", box("stbl", box("stsd", ints(0, 0)))),
            ),
        ),
        box("mvex", box("trex", ints(0, 1, 1, SAMPLE_DURATION, 0, 0x00010000))), // default: non-sync samples
    )

    /** Segment [i]: its samples (the first one a keyframe) and the bytes of styp + moof + mdat. */
    fun segment(i: Int): Pair<List<ByteArray>, ByteArray> {
        val samples = (0 until PER_SEGMENT).map { s -> ByteArray(100 + 10 * s) { (i * 16 + s).toByte() } }
        fun moof(dataOffset: Int) = box("moof",
            box("mfhd", ints(0, i + 1)),
            box("traf",
                box("tfhd", ints(0x00020000, 1)), // default-base-is-moof
                box("tfdt", ints(0, i * PER_SEGMENT * SAMPLE_DURATION)),
                box("trun", ints(0x000205, PER_SEGMENT, dataOffset, 0x02000000), *samples.map { ints(it.size) }.toTypedArray()),
            ),
        )
        val size = moof(0).size
        return samples to (box("styp", "msdh".toByteArray()) + moof(size + 8) + box("mdat", *samples.toTypedArray()))
    }

    /** Top-level box types of [b]. */
    fun topTypes(b: ByteArray): List<String> {
        val out = ArrayList<String>(); var p = 0
        while (p + 8 <= b.size) {
            var size = ByteBuffer.wrap(b, p, 4).int.toLong()
            if (size == 1L) size = ByteBuffer.wrap(b, p + 8, 8).long
            out += String(b, p + 4, 4); p += size.toInt()
        }
        return out
    }

    /** The body of the first box of [type] anywhere in [b] (a plain byte search: fine for these small files). */
    fun find(b: ByteArray, type: String): ByteBuffer {
        val t = type.toByteArray()
        val at = (0..b.size - 4).first { i -> (0 until 4).all { b[i + it] == t[it] } }
        val size = ByteBuffer.wrap(b, at - 4, 4).int
        return ByteBuffer.wrap(b.copyOfRange(at + 4, at - 4 + size))
    }
}

class Mp4DefragTest {
    @Test fun `fragmented mp4 becomes a plain mp4 with every sample indexed`() {
        val tmp = Files.createTempDirectory("defrag").toFile()
        try {
            val segs = (0 until 3).map { FakeFmp4.segment(it) }
            val src = File(tmp, "in.mp4").apply { writeBytes(FakeFmp4.init + segs.map { it.second }.reduce { a, b -> a + b }) }
            assertTrue(Mp4Defrag.isFragmented(src))
            val dst = File(tmp, "out.mp4")
            Mp4Defrag.defrag(src, dst)
            val out = dst.readBytes()
            assertEquals(listOf("ftyp", "moov", "mdat"), FakeFmp4.topTypes(out), "moov first, one mdat, no fragments")
            assertTrue(!Mp4Defrag.isFragmented(dst))
            val samples = segs.flatMap { it.first }

            val stsz = FakeFmp4.find(out, "stsz"); stsz.position(8)
            assertEquals(samples.size, stsz.int)
            assertEquals(samples.map { it.size }, List(samples.size) { stsz.int })
            val stss = FakeFmp4.find(out, "stss"); stss.position(4)
            assertEquals(listOf(1, 4, 7), List(stss.int) { stss.int }, "the first sample of each segment is a keyframe")
            val stts = FakeFmp4.find(out, "stts"); stts.position(4)
            assertEquals(listOf(9, FakeFmp4.SAMPLE_DURATION), List(stts.int * 2) { stts.int })
            val mdhd = FakeFmp4.find(out, "mdhd")
            assertEquals(9 * FakeFmp4.SAMPLE_DURATION, mdhd.getInt(16), "track duration filled in")
            val elst = FakeFmp4.find(out, "elst")
            assertEquals(9 * FakeFmp4.SAMPLE_DURATION, elst.getInt(8), "a 0-length edit gets the track's length")
            assertTrue(out.indexOf("mvex") < 0, "no fragment defaults left")

            // Chunk offsets point at the samples, in order.
            val co64 = FakeFmp4.find(out, "co64"); co64.position(4)
            val offsets = List(co64.int) { co64.long }
            assertEquals(3, offsets.size, "one chunk per segment")
            var k = 0
            offsets.forEach { off ->
                var p = off.toInt()
                repeat(FakeFmp4.PER_SEGMENT) { val s = samples[k++]; assertContentEquals(s, out.copyOfRange(p, p + s.size)); p += s.size }
            }
        } finally { tmp.deleteRecursively() }
    }

    private fun ByteArray.indexOf(type: String): Int {
        val t = type.toByteArray()
        return (0..size - 4).firstOrNull { i -> (0 until 4).all { this[i + it] == t[it] } } ?: -1
    }
}
