package cloudstream.desktop.loader

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.File
import java.util.zip.ZipFile

/**
 * What an extension's code can do that a streaming extension has no business doing. Found by reading its bytecode
 * (never by running it). [dangerous] ones block loading until the user allows them for that extension; the others
 * are only shown.
 */
enum class Capability(val label: String, val dangerous: Boolean) {
    RunPrograms("run programs on your computer", true),
    NativeCode("load native code", true),
    DefineCode("load or rewrite program code at run time", true),
    ControlInput("control the mouse and keyboard", true),
    // Shown only: extensions commonly run a local playback proxy (seen in real extensions).
    ListenNetwork("accept network connections (open a server port)", false),
    AppInternals("reach into the app's own code (library, settings, accounts)", true),
    WriteFiles("write or delete files", false),
}

/** One capability with where it was seen (a few examples, "class.method -> target"). */
data class Finding(val capability: Capability, val examples: List<String>)

data class ScanReport(val findings: List<Finding>) {
    val capabilities: Set<Capability> get() = findings.mapTo(LinkedHashSet()) { it.capability }
    val dangerous: Set<Capability> get() = capabilities.filterTo(LinkedHashSet()) { it.dangerous }
}

/** Bytecode scan of a converted plugin jar (ASM): method calls, field accesses, type references and string constants. */
object CapabilityScanner {
    private val APP_PREFIXES = listOf("cloudstream/desktop/")

    /** Owner internal name -> methods (null = any member, including constructors). */
    private val CALLS: Map<Capability, Map<String, Set<String>?>> = mapOf(
        Capability.RunPrograms to mapOf(
            "java/lang/Runtime" to setOf("exec"),
            "java/lang/ProcessBuilder" to null,
            "java/lang/ProcessHandle" to null,
            "java/awt/Desktop" to setOf("open", "edit", "print"),
        ),
        Capability.NativeCode to mapOf(
            "java/lang/System" to setOf("load", "loadLibrary"),
            "java/lang/Runtime" to setOf("load", "loadLibrary"),
            "sun/misc/Unsafe" to null,
            "jdk/internal/misc/Unsafe" to null,
        ),
        Capability.DefineCode to mapOf(
            "java/lang/ClassLoader" to setOf("defineClass", "defineClass1"),
            "java/security/SecureClassLoader" to setOf("defineClass"),
            "java/net/URLClassLoader" to null,
            "java/lang/invoke/MethodHandles\$Lookup" to setOf("defineClass", "defineHiddenClass"),
            "java/lang/instrument/Instrumentation" to null,
            "dalvik/system/DexClassLoader" to null,
            "dalvik/system/PathClassLoader" to null,
            "dalvik/system/InMemoryDexClassLoader" to null,
        ),
        Capability.ControlInput to mapOf("java/awt/Robot" to null),
        Capability.ListenNetwork to mapOf(
            "java/net/ServerSocket" to null,
            "java/nio/channels/ServerSocketChannel" to null,
            "java/nio/channels/AsynchronousServerSocketChannel" to null,
            "com/sun/net/httpserver/HttpServer" to null,
        ),
        Capability.WriteFiles to mapOf(
            "java/io/FileOutputStream" to setOf("<init>"),
            "java/io/FileWriter" to setOf("<init>"),
            "java/io/RandomAccessFile" to setOf("<init>"),
            "java/io/File" to setOf("delete", "deleteOnExit", "renameTo"),
            "java/nio/file/Files" to setOf("write", "writeString", "newOutputStream", "newBufferedWriter", "delete",
                "deleteIfExists", "move", "copy", "createFile", "createDirectory", "createDirectories"),
            "kotlin/io/FilesKt__FileReadWriteKt" to setOf("writeBytes", "writeText", "appendText", "appendBytes"),
            "kotlin/io/FilesKt__UtilsKt" to setOf("deleteRecursively", "copyTo", "copyRecursively"),
        ),
    )

    /** Type references alone (new X, X.class, casts, field types) for these owners count too. */
    private val TYPES: Map<String, Capability> = buildMap {
        for ((cap, owners) in CALLS) for ((owner, members) in owners) if (members == null) put(owner, cap)
        put("com/sun/jna/Native", Capability.NativeCode); put("com/sun/jna/Library", Capability.NativeCode)
    }

    fun scan(jar: File): ScanReport {
        val hits = LinkedHashMap<Capability, LinkedHashSet<String>>()
        fun hit(c: Capability, where: String) { val s = hits.getOrPut(c) { LinkedHashSet() }; if (s.size < 5) s += where }
        ZipFile(jar).use { zip ->
            for (e in zip.entries()) {
                if (!e.name.endsWith(".class")) continue
                val bytes = zip.getInputStream(e).use { it.readBytes() }
                runCatching { ClassReader(bytes).accept(Visitor(::hit), ClassReader.SKIP_FRAMES or ClassReader.SKIP_DEBUG) }
            }
        }
        return ScanReport(Capability.entries.mapNotNull { c -> hits[c]?.let { Finding(c, it.toList()) } })
    }

    private fun isApp(internalName: String) = APP_PREFIXES.any { internalName.startsWith(it) }

    private class Visitor(val hit: (Capability, String) -> Unit) : ClassVisitor(Opcodes.ASM9) {
        var cls = ""
        override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
            cls = name.substringAfterLast('/')
            (listOfNotNull(superName) + interfaces.orEmpty()).forEach { type(it, "extends") }
        }
        fun type(t: String, where: String) {
            val internal = if (t.startsWith("[") || t.startsWith("L")) runCatching { Type.getType(t).let { if (it.sort == Type.ARRAY) it.elementType else it }.internalName }.getOrNull() ?: return else t
            TYPES[internal]?.let { hit(it, "$cls.$where -> ${internal.replace('/', '.')}") }
            if (isApp(internal)) hit(Capability.AppInternals, "$cls.$where -> ${internal.replace('/', '.')}")
        }
        fun call(owner: String, name: String, where: String) {
            type(owner, where)
            for ((cap, owners) in CALLS) {
                val members = owners[owner] ?: continue
                if (name in members) hit(cap, "$cls.$where -> ${owner.replace('/', '.')}.$name")
            }
        }
        fun string(s: String, where: String) {
            // Reflection by name: Class.forName("cloudstream.desktop...") / "java.lang.ProcessBuilder".
            val n = s.replace('.', '/')
            if (isApp(n)) hit(Capability.AppInternals, "$cls.$where -> \"$s\"")
            TYPES[n]?.let { hit(it, "$cls.$where -> \"$s\"") }
        }
        override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor? {
            type(descriptor, name); (value as? String)?.let { string(it, name) }; return null
        }
        override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
            val where = name
            return object : MethodVisitor(Opcodes.ASM9) {
                override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) = call(owner, name, where)
                override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) = type(owner, where)
                override fun visitTypeInsn(opcode: Int, type: String) = type(type, where)
                override fun visitLdcInsn(value: Any?) {
                    when (value) {
                        is String -> string(value, where)
                        is Type -> if (value.sort == Type.OBJECT) type(value.internalName, where)
                        is Handle -> call(value.owner, value.name, where)
                    }
                }
                override fun visitInvokeDynamicInsn(name: String, descriptor: String, bsm: Handle, vararg args: Any?) {
                    args.forEach { (it as? Handle)?.let { h -> call(h.owner, h.name, where) } }
                }
            }
        }
    }
}

/**
 * The extension safety gate, consulted before an extension's code is loaded:
 * - scans the jar ([CapabilityScanner]; results cached per jar, which is content-addressed);
 * - refuses to load when it has a [Capability.dangerous] capability the user hasn't allowed for that extension
 *   (an update that gains a new one is refused too, and the update keeps the old version);
 * - gives the extension a class loader that cannot see the app's own classes nor the blocked JDK classes
 *   ([GuardedParentLoader]) unless allowed.
 * Approvals live in `<plugins>/safety.json` as extension file name -> allowed capabilities.
 */
class PluginGuard(private val dir: File) {
    private val approvalsFile = File(dir, "safety.json")
    private val cache = java.util.concurrent.ConcurrentHashMap<String, ScanReport>()
    @Volatile private var approvals: Map<String, Set<Capability>> = readApprovals()

    private fun readApprovals(): Map<String, Set<Capability>> = runCatching {
        if (!approvalsFile.isFile) return emptyMap()
        @Suppress("UNCHECKED_CAST")
        val raw = json.readValue(approvalsFile, Map::class.java) as Map<String, List<String>>
        raw.mapValues { (_, v) -> v.mapNotNull { n -> Capability.entries.firstOrNull { it.name == n } }.toSet() }
    }.getOrDefault(emptyMap())

    @Synchronized private fun writeApprovals(m: Map<String, Set<Capability>>) {
        approvals = m
        dir.mkdirs()
        json.writerWithDefaultPrettyPrinter().writeValue(approvalsFile, m.mapValues { (_, v) -> v.map { it.name } })
    }

    /** Updates refused this session (extension key -> the new version's scan), offered as "Allow and update". */
    val pendingUpdates = java.util.concurrent.ConcurrentHashMap<String, ScanReport>()

    fun report(jar: File): ScanReport = cache.getOrPut(jar.absolutePath + "|" + jar.length() + "|" + jar.lastModified()) { CapabilityScanner.scan(jar) }

    fun allowed(key: String): Set<Capability> = approvals[key].orEmpty()

    /** Dangerous capabilities of [jar] the user hasn't allowed for extension [key] (empty = may load). */
    fun blockedFor(key: String, jar: File): Set<Capability> = report(jar).dangerous - allowed(key)

    fun allow(key: String, caps: Set<Capability>) = writeApprovals(approvals + (key to (allowed(key) + caps)))
    fun revoke(key: String) = writeApprovals(approvals - key)

    companion object {
        /** The key an extension's approvals are stored under (its .cs3 file name; stable across updates). */
        fun keyOf(cs3: File): String = cs3.name
    }
}

/** Thrown (as the load failure) when an extension needs a capability the user hasn't allowed. */
class BlockedExtensionException(val name: String, val key: String, val blocked: Set<Capability>, val report: ScanReport) :
    RuntimeException("Blocked for safety: '$name' can " + blocked.joinToString(", ") { it.label } +
        ". Allow it in Extensions only if you trust it. (" + report.findings.filter { it.capability in blocked }
            .joinToString("; ") { f -> f.examples.first() } + ")")

/**
 * Parent of every extension class loader: delegates to the app loader, except that the app's own classes
 * (`cloudstream.desktop.*`) and the JDK classes behind a not-allowed dangerous capability are invisible to the
 * extension (ClassNotFoundException; NoClassDefFoundError at the call site). Defence in depth next to the scan:
 * code that gets hold of another class loader can still get around it.
 */
class GuardedParentLoader(private val app: ClassLoader, allowed: Set<Capability>) : ClassLoader(null) {
    private val hidden: List<String> = buildList {
        if (Capability.AppInternals !in allowed) add("cloudstream.desktop.")
        if (Capability.RunPrograms !in allowed) { add("java.lang.ProcessBuilder"); add("java.lang.ProcessHandle") }
        if (Capability.ControlInput !in allowed) add("java.awt.Robot")
        if (Capability.DefineCode !in allowed) { add("java.net.URLClassLoader"); add("java.lang.instrument.") }
        if (Capability.NativeCode !in allowed) { add("sun.misc.Unsafe"); add("jdk.internal.misc.Unsafe"); add("com.sun.jna.") }
    }

    private fun isHidden(name: String) = hidden.any { if (it.endsWith(".")) name.startsWith(it) else name == it || name.startsWith("$it$") }

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (isHidden(name)) throw ClassNotFoundException("$name (not available to extensions)")
        return app.loadClass(name)
    }

    override fun getResource(name: String): java.net.URL? =
        if (name.startsWith("cloudstream/desktop/")) null else app.getResource(name)

    override fun getResources(name: String): java.util.Enumeration<java.net.URL> =
        if (name.startsWith("cloudstream/desktop/")) java.util.Collections.emptyEnumeration() else app.getResources(name)
}
