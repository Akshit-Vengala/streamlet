package cloudstream.desktop.loader

import com.googlecode.d2j.Method
import com.googlecode.d2j.dex.Dex2jar
import com.googlecode.d2j.dex.DexExceptionHandler
import com.googlecode.d2j.node.DexMethodNode
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Parsed `manifest.json` from a `.cs3`. */
data class Cs3Manifest(
    val name: String? = null,
    val pluginClassName: String? = null,
    val requiresResources: Boolean = false,
    val version: Int? = null,
)

fun readCs3Manifest(cs3: File): Cs3Manifest = ZipFile(cs3).use { z ->
    val e = z.getEntry("manifest.json") ?: error("No manifest.json in ${cs3.name}")
    z.getInputStream(e).use { json.readValue(it, Cs3Manifest::class.java) }
}

/**
 * dex2jar conversion with a content-addressed cache: `<cacheDir>/<sha256 of .cs3>.jar`.
 * Non-dex entries of the `.cs3` (manifest.json, res/, assets) are copied into the jar.
 */
class PluginConverter(
    private val cacheDir: File,
    /**
     * dex2jar needs ~3 GB of heap for large plugins (~2 MB of dex). When this JVM's max heap is below
     * [inProcessMinHeapBytes] the conversion runs in a child JVM with -Xmx[childXmx] instead.
     */
    private val inProcessMinHeapBytes: Long = 3L shl 30,
    private val childXmx: String = "3g",
) {
    /** Methods dex2jar failed to translate (class -> reason); kept for diagnostics. */
    val warnings = java.util.concurrent.CopyOnWriteArrayList<String>()

    fun jarFor(cs3Sha256: String) = File(cacheDir, "$cs3Sha256-c$CONVERTER_VERSION.jar")

    @Synchronized
    fun convert(cs3: File, cs3Sha256: String = sha256Hex(cs3)): File {
        val out = jarFor(cs3Sha256)
        if (out.isFile && out.length() > 0) return out
        cacheDir.mkdirs()
        val raw = File.createTempFile("d2j", ".jar", cacheDir)
        val merged = File.createTempFile("merged", ".jar", cacheDir)
        try {
            if (Runtime.getRuntime().maxMemory() >= inProcessMinHeapBytes) translate(cs3, raw, warnings)
            else translateInChild(cs3, raw)
            mergeResources(cs3, raw, merged)
            Files.move(merged.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            raw.delete(); merged.delete()
        }
        return out
    }

    private fun translateInChild(cs3: File, out: File) {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val pb = ProcessBuilder(
            listOf(java, "-Xmx$childXmx") +
                listOfNotNull(System.getProperty("cloudstream.split.force")?.let { "-Dcloudstream.split.force=$it" }) +
                listOf("-cp", System.getProperty("java.class.path"), "cloudstream.desktop.loader.ConverterMainKt", cs3.absolutePath, out.absolutePath),
        ).redirectErrorStream(true)
        val p = pb.start()
        val log = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "dex2jar child process failed: ${log.take(2000)}" }
        log.lineSequence().filter { it.startsWith("WARN ") }.forEach { warnings += it.removePrefix("WARN ") }
    }

    private fun mergeResources(cs3: File, rawJar: File, outJar: File) {
        val seen = HashSet<String>()
        val hierarchy by lazy { Hierarchy(rawJar) } // only built when a class has an oversized method
        ZipOutputStream(outJar.outputStream().buffered()).use { zo ->
            ZipFile(rawJar).use { zj ->
                for (e in zj.entries()) {
                    if (!seen.add(e.name)) continue
                    zo.putNextEntry(ZipEntry(e.name))
                    if (!e.isDirectory) {
                        if (e.name.endsWith(".class")) {
                            zo.write(LargeMethodStubber.process(zj.getInputStream(e).use { it.readBytes() }, warnings) { hierarchy })
                        } else zj.getInputStream(e).use { it.copyTo(zo) }
                    }
                    zo.closeEntry()
                }
            }
            ZipFile(cs3).use { zc ->
                for (e in zc.entries()) {
                    val n = e.name
                    if (e.isDirectory || n.endsWith(".dex") || n.startsWith("META-INF/") || !seen.add(n)) continue
                    zo.putNextEntry(ZipEntry(n))
                    zc.getInputStream(e).use { it.copyTo(zo) }
                    zo.closeEntry()
                }
            }
        }
    }
}

internal fun translate(cs3: File, out: File, warnings: MutableCollection<String>) {
    val handler = object : DexExceptionHandler {
        override fun handleFileException(e: Exception) { warnings += "file: $e" }
        override fun handleMethodTranslateException(m: Method, n: DexMethodNode, mv: MethodVisitor, e: Exception) {
            warnings += "${m.owner}.${m.name}: $e"
        }
    }
    val d = Dex2jar.from(cs3)
        .withExceptionHandler(handler)
        .reUseReg(false).topoLogicalSort().skipDebug(true)
        .optimizeSynchronized(true).printIR(false).noCode(false)
        .skipExceptions(false).dontSanitizeNames(true)
    val nodes = translateToNodes(d, handler, sanitizeNames = false)
    splitOversized(nodes, warnings)
    ZipOutputStream(out.outputStream().buffered()).use { zo ->
        for (c in nodes) {
            val bytes = runCatching { ClassWriter(ClassWriter.COMPUTE_MAXS).also { c.accept(it) }.toByteArray() }
                .getOrElse { warnings += "write ${c.name}: $it"; continue }
            zo.putNextEntry(ZipEntry(c.name + ".class")); zo.write(bytes); zo.closeEntry()
        }
    }
}

/**
 * Same pipeline as `Dex2jar.doTranslate` (reader -> DexFileNode -> dex2jar's own translator `Dex2jar$2`, with all
 * of its IR optimisations), but each class is kept as an ASM [ClassNode] instead of being written straight away.
 * dex2jar ships a patched ClassWriter without the 64 KB check, which silently writes oversized methods with
 * truncated 16-bit exception-table offsets; holding the tree lets [splitOversized] fix such methods first.
 */
private fun translateToNodes(d: Dex2jar, handler: DexExceptionHandler, sanitizeNames: Boolean): List<ClassNode> {
    val fileNode = com.googlecode.d2j.node.DexFileNode()
    val readerConfig = Dex2jar::class.java.getDeclaredField("readerConfig").apply { isAccessible = true }.getInt(d)
    try { d.reader.accept(fileNode, readerConfig) } catch (e: Exception) { handler.handleFileException(e) }
    val nodes = java.util.Collections.synchronizedList(ArrayList<ClassNode>())
    val factory = com.googlecode.d2j.dex.ClassVisitorFactory { _ ->
        val node = ClassNode()
        com.googlecode.d2j.dex.LambadaNameSafeClassAdapter(object : ClassVisitor(org.objectweb.asm.Opcodes.ASM9, node) {
            override fun visitEnd() { super.visitEnd(); nodes += node }
        }, !sanitizeNames)
    }
    val asmClass = Class.forName("com.googlecode.d2j.dex.Dex2jar\$2")
    val asm = asmClass.getDeclaredConstructor(Dex2jar::class.java, DexExceptionHandler::class.java).apply { isAccessible = true }
        .newInstance(d, handler) as com.googlecode.d2j.dex.Dex2Asm
    asm.convertDex(fileNode, factory)
    return nodes
}

/** Methods over the JVM's 64 KB limit are split ([MethodSplitter]); if that fails they are stubbed (see LargeMethodStubber). */
private fun splitOversized(nodes: List<ClassNode>, warnings: MutableCollection<String>) {
    val hierarchy by lazy { Hierarchy(null, nodes) }
    // Test hook: -Dcloudstream.split.force=pkg/Cls.method,... splits those (normal-sized) methods to half their size,
    // so the splitter can be validated on code whose unsplit behaviour is known.
    val forced = System.getProperty("cloudstream.split.force").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    for (c in nodes) for (m in c.methods.toList()) {
        if ("${c.name}.${m.name}" in forced) {
            val before = codeLength(c, m)
            runCatching { MethodSplitter(hierarchy, (before / 2).toInt()) { warnings += "  $it" }.split(c, m) { codeLength(c, it) } }
                .onSuccess { warnings += "FORCED split ${c.name}.${m.name}: $before -> ${codeLength(c, m)} bytes, $it helper(s)" }
                .onFailure { warnings += "FORCED split ${c.name}.${m.name} failed: $it" }
            continue
        }
        if (m.instructions.size() < 8000) continue // far below 64 KB; skip the size write
        val size = codeLength(c, m)
        if (size <= LargeMethodStubber.LIMIT) continue
        // The splitter writes no StackMapTable: only valid for class files the JVM verifies by type inference (<= 50).
        val ok = if ((c.version and 0xFFFF) > 50) null else runCatching { MethodSplitter(hierarchy) { warnings += "  $it" }.split(c, m) { codeLength(c, it) } }
            .onFailure { warnings += "split of ${c.name}.${m.name} failed (${it.javaClass.simpleName}: ${it.message?.take(200)}), stubbing" }
            .getOrNull()
        if (ok != null && c.methods.all { codeLength(c, it) <= LargeMethodStubber.LIMIT }) {
            warnings += "SPLIT too-large method ($size bytes > ${LargeMethodStubber.LIMIT}) into $ok helper(s): ${c.name}.${m.name}${m.desc}"
        } else {
            c.methods.removeIf { "\$split\$" in it.name && it.name.startsWith(m.name) }
            LargeMethodStubber.stubNode(m)
            warnings += "STUBBED too-large method ($size bytes > ${LargeMethodStubber.LIMIT}): ${c.name}.${m.name}${m.desc}"
        }
    }
}

/** Bytecode length of [m] as the class writer would emit it (written alone into a scratch class). */
private fun codeLength(c: ClassNode, m: MethodNode): Long {
    val tmp = ClassNode().apply { version = c.version; access = c.access; name = c.name; superName = c.superName; methods.add(m) }
    val cw = ClassWriter(ClassWriter.COMPUTE_MAXS); tmp.accept(cw)
    // Upstream ASM refuses (MethodTooLargeException carries the size); dex2jar's patched copy writes it anyway.
    val bytes = try { cw.toByteArray() } catch (e: RuntimeException) {
        if (e.javaClass.simpleName == "MethodTooLargeException") return (e.javaClass.getMethod("getCodeSize").invoke(e) as Int).toLong()
        throw e
    }
    return LargeMethodStubber.oversizedMethods(bytes, threshold = 0).firstOrNull()?.codeLength ?: 0
}

/** Bump when conversion output changes (settings, post-processing) so cached jars are rebuilt. 3: LargeMethodStubber (code-length parser). */
internal const val CONVERTER_VERSION = 5 // 5: MethodSplitter (oversized methods split instead of stubbed)
