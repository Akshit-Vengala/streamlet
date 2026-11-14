package cloudstream.desktop.app

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Turns a fragmented MP4 (an fMP4/CMAF HLS download: ftyp + moov, then styp/sidx/moof/mdat per segment) into a plain
 * MP4: one moov whose sample tables index every sample (keyframes in stss), then one mdat. Nothing is re-encoded.
 *
 * Why: players seek poorly in a long stitched fragmented file (VLC lands between keyframes: blocky picture for a few
 * seconds after every seek). A plain MP4 seeks exactly. The output is written with the moov first (fast start).
 */
object Mp4Defrag {
    private class Box(val type: String, val start: Long, val size: Long, val header: Int) {
        val end get() = start + size
        val bodyStart get() = start + header
    }

    /** A node of the moov tree: containers keep children, leaves keep their raw body. */
    private class Node(val type: String, var body: ByteArray?, val children: MutableList<Node> = ArrayList()) {
        fun child(t: String) = children.firstOrNull { it.type == t }
        fun path(vararg t: String): Node? = t.fold(this as Node?) { n, x -> n?.child(x) }
    }

    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "mvex")

    private class Track(val id: Int, val trak: Node) {
        var trexDuration = 0; var trexSize = 0; var trexFlags = 0
        val offsets = LongList(); val sizes = IntList(); val durations = IntList(); val ctos = IntList(); val sync = BoolList()
        /** Chunks: (index of first sample, sample count, file offset in the source). */
        val chunkFirst = IntList(); val chunkCount = IntList()
    }

    /** The source's chunks in file order: (track, chunk index) so the output keeps the same interleaving. */
    private data class ChunkRef(val track: Track, val index: Int)

    /** True when [file] is a fragmented MP4 this can rewrite. */
    fun isFragmented(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { f -> topBoxes(f).let { b -> b.any { it.type == "moov" } && b.any { it.type == "moof" } } }
    }.getOrDefault(false)

    /** Writes the plain MP4 of [src] to [dst]. Throws when [src] is not a fragmented MP4 it understands. */
    fun defrag(src: File, dst: File) {
        RandomAccessFile(src, "r").use { f ->
            val boxes = topBoxes(f)
            val moovBox = boxes.firstOrNull { it.type == "moov" } ?: error("no moov")
            val moov = parse(read(f, moovBox.bodyStart, (moovBox.end - moovBox.bodyStart).toInt()), "moov")
            val tracks = moov.children.filter { it.type == "trak" }.map { t ->
                Track(ByteBuffer.wrap(t.child("tkhd")!!.body!!).let { b -> b.position(if (b.get(0).toInt() == 1) 20 else 12); b.int }, t)
            }.associateBy { it.id }
            check(tracks.isNotEmpty()) { "no tracks" }
            moov.child("mvex")?.children?.filter { it.type == "trex" }?.forEach { trex ->
                val b = ByteBuffer.wrap(trex.body!!)
                tracks[b.getInt(4)]?.apply { trexDuration = b.getInt(12); trexSize = b.getInt(16); trexFlags = b.getInt(20) }
            }
            val order = ArrayList<ChunkRef>()
            for (moofBox in boxes.filter { it.type == "moof" }) {
                val moof = read(f, moofBox.bodyStart, (moofBox.end - moofBox.bodyStart).toInt())
                readMoof(moof, moofBox.start, tracks, order)
            }
            write(f, dst, moov, tracks.values.toList(), order)
        }
    }

    private fun readMoof(moof: ByteArray, moofStart: Long, tracks: Map<Int, Track>, order: MutableList<ChunkRef>) {
        var prevTrafEnd = -1L
        for ((type, s, e) in children(moof, 0, moof.size)) {
            if (type != "traf") continue
            var track: Track? = null
            var base = -1L; var defDur = 0; var defSize = 0; var defFlags = 0; var baseIsMoof = false
            var dataPos = -1L
            for ((t, cs, ce) in children(moof, s, e)) {
                val b = ByteBuffer.wrap(moof, cs, ce - cs)
                when (t) {
                    "tfhd" -> {
                        val fl = b.int and 0xFFFFFF
                        track = tracks[b.int] ?: error("unknown track in tfhd")
                        defDur = track.trexDuration; defSize = track.trexSize; defFlags = track.trexFlags
                        if (fl and 0x1 != 0) base = b.long
                        if (fl and 0x2 != 0) b.int
                        if (fl and 0x8 != 0) defDur = b.int
                        if (fl and 0x10 != 0) defSize = b.int
                        if (fl and 0x20 != 0) defFlags = b.int
                        baseIsMoof = fl and 0x20000 != 0
                        if (base < 0) base = if (baseIsMoof || prevTrafEnd < 0) moofStart else prevTrafEnd
                        dataPos = base
                    }
                    "trun" -> {
                        val tr = track ?: error("trun before tfhd")
                        val fl = b.int and 0xFFFFFF
                        val count = b.int
                        if (fl and 0x1 != 0) dataPos = base + b.int
                        val first = if (fl and 0x4 != 0) b.int else null
                        if (count == 0) continue
                        tr.chunkFirst.add(tr.sizes.size); tr.chunkCount.add(count)
                        order += ChunkRef(tr, tr.chunkFirst.size - 1)
                        for (i in 0 until count) {
                            val dur = if (fl and 0x100 != 0) b.int else defDur
                            val size = if (fl and 0x200 != 0) b.int else defSize
                            val flags = if (fl and 0x400 != 0) b.int else if (i == 0 && first != null) first else defFlags
                            // Version 0 offsets are unsigned, version 1 signed; both fit an Int in practice.
                            val cto = if (fl and 0x800 != 0) b.int else 0
                            tr.offsets.add(dataPos); tr.sizes.add(size); tr.durations.add(dur); tr.ctos.add(cto)
                            tr.sync.add((flags ushr 16) and 1 == 0) // sample_is_non_sync_sample clear
                            dataPos += size
                        }
                    }
                }
            }
            prevTrafEnd = dataPos
        }
    }

    private fun write(f: RandomAccessFile, dst: File, moov: Node, tracks: List<Track>, order: List<ChunkRef>) {
        val ftyp = box("ftyp", ByteArrayOutputStream().apply {
            write("isom".toByteArray()); write(byteArrayOf(0, 0, 2, 0))
            listOf("isom", "iso2", "avc1", "mp41").forEach { write(it.toByteArray()) }
        }.toByteArray())
        // Chunk offsets are 64-bit entries, so the moov size does not depend on their values: lay it out once with
        // zeros, then again with the real offsets (data starts after ftyp + moov + the 16-byte mdat header).
        fun chunkOffsets(dataStart: Long): Map<Track, LongArray> {
            val out = tracks.associateWith { LongArray(it.chunkFirst.size) }
            var pos = dataStart
            for (c in order) {
                out.getValue(c.track)[c.index] = pos
                val first = c.track.chunkFirst[c.index]
                for (i in first until first + c.track.chunkCount[c.index]) pos += c.track.sizes[i]
            }
            return out
        }
        val draft = buildMoov(moov, tracks, chunkOffsets(0))
        val dataStart = ftyp.size + draft.size + 16L
        val moovBytes = buildMoov(moov, tracks, chunkOffsets(dataStart))
        check(moovBytes.size == draft.size)
        val dataSize = tracks.sumOf { t -> (0 until t.sizes.size).sumOf { t.sizes[it].toLong() } }
        RandomAccessFile(dst, "rw").use { out ->
            out.setLength(0)
            out.write(ftyp); out.write(moovBytes)
            out.writeInt(1); out.write("mdat".toByteArray()); out.writeLong(16 + dataSize)
            val buf = ByteArray(1 shl 20)
            for (c in order) {
                val first = c.track.chunkFirst[c.index]
                for (i in first until first + c.track.chunkCount[c.index]) {
                    var left = c.track.sizes[i]; var at = c.track.offsets[i]
                    while (left > 0) {
                        val n = minOf(left, buf.size)
                        f.seek(at); f.readFully(buf, 0, n); out.write(buf, 0, n)
                        left -= n; at += n
                    }
                }
            }
        }
    }

    private fun buildMoov(src: Node, tracks: List<Track>, offsets: Map<Track, LongArray>): ByteArray {
        val mvhd = src.child("mvhd")!!.body!!.copyOf()
        val movieScale = ByteBuffer.wrap(mvhd).let { if (it.get(0).toInt() == 1) it.getInt(20) else it.getInt(12) }
        var movieDur = 0L
        val traks = tracks.map { t ->
            val mdhd = t.trak.path("mdia", "mdhd")!!.body!!.copyOf()
            val scale = ByteBuffer.wrap(mdhd).let { if (it.get(0).toInt() == 1) it.getInt(20) else it.getInt(12) }
            val dur = (0 until t.durations.size).sumOf { t.durations[it].toLong() }
            setDuration(mdhd, dur)
            val inMovie = if (scale > 0) dur * movieScale / scale else 0
            movieDur = maxOf(movieDur, inMovie)
            val tkhd = t.trak.child("tkhd")!!.body!!.copyOf().also { setTkhdDuration(it, inMovie) }
            val stbl = t.trak.path("mdia", "minf", "stbl")!!
            val newStbl = Node("stbl", null, (listOf(stbl.child("stsd")!!) + sampleTables(t, offsets.getValue(t))).toMutableList())
            val minf = t.trak.path("mdia", "minf")!!
            val newMinf = Node("minf", null, minf.children.map { if (it.type == "stbl") newStbl else it }.toMutableList())
            val mdia = t.trak.child("mdia")!!
            val newMdia = Node("mdia", null, mdia.children.map {
                when (it.type) { "mdhd" -> Node("mdhd", mdhd); "minf" -> newMinf; else -> it }
            }.toMutableList())
            val edts = t.trak.child("edts")?.let { e ->
                Node("edts", null, e.children.map { if (it.type == "elst") Node("elst", fillEditList(it.body!!, inMovie)) else it }.toMutableList())
            }
            Node("trak", null, t.trak.children.map {
                when (it.type) { "tkhd" -> Node("tkhd", tkhd); "mdia" -> newMdia; "edts" -> edts!!; else -> it }
            }.toMutableList())
        }
        setDuration(mvhd, movieDur)
        val kids = src.children.filter { it.type != "mvex" && it.type != "trak" }.map { if (it.type == "mvhd") Node("mvhd", mvhd) else it } + traks
        return serialize(Node("moov", null, kids.toMutableList()))
    }

    private fun sampleTables(t: Track, chunkOffsets: LongArray): List<Node> {
        val n = t.sizes.size
        fun full(fill: DataOutputStream.() -> Unit) = ByteArrayOutputStream().also { DataOutputStream(it).fill() }.toByteArray()
        // stts: runs of equal durations.
        val runs = ArrayList<Pair<Int, Int>>()
        for (i in 0 until n) { val d = t.durations[i]; if (runs.isNotEmpty() && runs.last().second == d) runs[runs.size - 1] = runs.last().first + 1 to d else runs += 1 to d }
        val out = mutableListOf(Node("stts", full { writeInt(0); writeInt(runs.size); runs.forEach { writeInt(it.first); writeInt(it.second) } }))
        if ((0 until n).any { t.ctos[it] != 0 }) {
            val c = ArrayList<Pair<Int, Int>>()
            for (i in 0 until n) { val v = t.ctos[i]; if (c.isNotEmpty() && c.last().second == v) c[c.size - 1] = c.last().first + 1 to v else c += 1 to v }
            val v1 = c.any { it.second < 0 }
            out += Node("ctts", full { writeInt(if (v1) 0x01000000 else 0); writeInt(c.size); c.forEach { writeInt(it.first); writeInt(it.second) } })
        }
        if ((0 until n).any { !t.sync[it] }) {
            val keys = (0 until n).filter { t.sync[it] }
            out += Node("stss", full { writeInt(0); writeInt(keys.size); keys.forEach { writeInt(it + 1) } })
        }
        // stsc: chunk -> samples per chunk, run-length coded.
        val stsc = ArrayList<Pair<Int, Int>>() // first chunk (1-based), samples per chunk
        for (i in 0 until t.chunkCount.size) { val k = t.chunkCount[i]; if (stsc.isEmpty() || stsc.last().second != k) stsc += (i + 1) to k }
        out += Node("stsc", full { writeInt(0); writeInt(stsc.size); stsc.forEach { writeInt(it.first); writeInt(it.second); writeInt(1) } })
        out += Node("stsz", full { writeInt(0); writeInt(0); writeInt(n); for (i in 0 until n) writeInt(t.sizes[i]) })
        out += Node("co64", full { writeInt(0); writeInt(chunkOffsets.size); chunkOffsets.forEach { writeLong(it) } })
        return out
    }

    /**
     * In a fragmented file an edit of length 0 means "to the end, however long the fragments make it"; in a plain MP4
     * it means nothing is shown. Give such edits the track's real length (in the movie timescale, minus the edits
     * before it, e.g. a short empty edit that delays the start).
     */
    private fun fillEditList(elst: ByteArray, trackInMovie: Long): ByteArray {
        val b = ByteBuffer.wrap(elst.copyOf())
        val v1 = b.get(0).toInt() == 1
        val n = b.getInt(4)
        val entry = if (v1) 20 else 12
        var before = 0L
        for (i in 0 until n) {
            val at = 8 + i * entry
            val dur = if (v1) b.getLong(at) else b.getInt(at).toLong() and 0xFFFFFFFFL
            val mediaTime = if (v1) b.getLong(at + 8) else b.getInt(at + 4).toLong()
            if (dur == 0L && mediaTime != -1L) {
                val fill = (trackInMovie - before).coerceAtLeast(0)
                if (v1) b.putLong(at, fill) else b.putInt(at, fill.coerceAtMost(0xFFFFFFFFL).toInt())
            }
            if (mediaTime == -1L) before += dur
        }
        return b.array()
    }

    private fun setDuration(fullBox: ByteArray, dur: Long) {
        val b = ByteBuffer.wrap(fullBox)
        if (b.get(0).toInt() == 1) b.putLong(24, dur) else b.putInt(16, dur.coerceAtMost(0xFFFFFFFFL).toInt())
    }

    private fun setTkhdDuration(tkhd: ByteArray, dur: Long) {
        val b = ByteBuffer.wrap(tkhd)
        if (b.get(0).toInt() == 1) b.putLong(28, dur) else b.putInt(20, dur.coerceAtMost(0xFFFFFFFFL).toInt())
    }

    private fun topBoxes(f: RandomAccessFile): List<Box> {
        val out = ArrayList<Box>()
        var pos = 0L
        val len = f.length()
        while (pos + 8 <= len) {
            f.seek(pos)
            var size = f.readInt().toLong() and 0xFFFFFFFFL
            val type = ByteArray(4).also { f.readFully(it) }.toString(Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) { size = f.readLong(); header = 16 } else if (size == 0L) size = len - pos
            if (size < header || pos + size > len) break // a truncated tail: stop there
            out += Box(type, pos, size, header)
            pos += size
        }
        return out
    }

    private fun read(f: RandomAccessFile, at: Long, n: Int) = ByteArray(n).also { f.seek(at); f.readFully(it) }

    private fun children(b: ByteArray, from: Int, to: Int): List<Triple<String, Int, Int>> {
        val out = ArrayList<Triple<String, Int, Int>>()
        var p = from
        while (p + 8 <= to) {
            val size = ByteBuffer.wrap(b, p, 4).int
            if (size < 8 || p + size > to) break
            out += Triple(String(b, p + 4, 4, Charsets.ISO_8859_1), p + 8, p + size)
            p += size
        }
        return out
    }

    private fun parse(body: ByteArray, type: String): Node {
        val n = Node(type, null)
        for ((t, s, e) in children(body, 0, body.size)) {
            val sub = body.copyOfRange(s, e)
            n.children += if (t in CONTAINERS) parse(sub, t) else Node(t, sub)
        }
        return n
    }

    private fun serialize(n: Node): ByteArray = box(n.type, n.body ?: ByteArrayOutputStream().apply { n.children.forEach { write(serialize(it)) } }.toByteArray())

    private fun box(type: String, body: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray(Charsets.ISO_8859_1)).put(body).array()

    private class IntList { var a = IntArray(1024); var size = 0
        fun add(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        operator fun get(i: Int) = a[i] }
    private class LongList { var a = LongArray(1024); var size = 0
        fun add(v: Long) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        operator fun get(i: Int) = a[i] }
    private class BoolList { var a = BooleanArray(1024); var size = 0
        fun add(v: Boolean) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        operator fun get(i: Int) = a[i] }
}
