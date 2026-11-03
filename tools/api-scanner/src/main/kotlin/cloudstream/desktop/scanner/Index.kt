package cloudstream.desktop.scanner

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.zip.ZipFile

/** Shape of one known class: its supertypes and member keys ("name:desc"). */
class ClassInfo(val name: String, val supers: List<String>, val members: Set<String>)

enum class Origin { JDK, CORE, OTHER_LIB }

/** Index of every class on the "desktop classpath": JDK + upstream library jar + its runtime deps. */
class ClassIndex(libraryJar: File, depJars: List<File>) {
    private val core = HashMap<String, ByteArray>()
    private val other = HashMap<String, ByteArray>()
    private val cache = HashMap<String, ClassInfo?>()
    private val jdkLoader: ClassLoader = ClassLoader.getPlatformClassLoader()

    init {
        load(libraryJar, core)
        for (j in depJars) if (j.canonicalPath != libraryJar.canonicalPath) load(j, other)
    }

    private fun load(jar: File, into: MutableMap<String, ByteArray>) = ZipFile(jar).use { z ->
        for (e in z.entries()) {
            if (e.isDirectory || !e.name.endsWith(".class") || e.name.startsWith("META-INF/")) continue
            into.putIfAbsent(e.name.removeSuffix(".class"), z.getInputStream(e).readBytes())
        }
    }

    fun coreSize() = core.size

    fun originOf(internal: String): Origin? = when {
        internal in core -> Origin.CORE
        internal in other -> Origin.OTHER_LIB
        jdkBytes(internal) != null -> Origin.JDK
        else -> null
    }

    private fun jdkBytes(internal: String): ByteArray? {
        if (!(internal.startsWith("java/") || internal.startsWith("javax/") || internal.startsWith("jdk/") ||
                internal.startsWith("sun/") || internal.startsWith("org/w3c/") || internal.startsWith("org/xml/") ||
                internal.startsWith("org/ietf/"))) return null
        return jdkLoader.getResourceAsStream("$internal.class")?.use { it.readBytes() }
            ?: ClassLoader.getSystemResourceAsStream("$internal.class")?.use { it.readBytes() }
    }

    fun info(internal: String): ClassInfo? = cache.getOrPut(internal) {
        val bytes = core[internal] ?: other[internal] ?: jdkBytes(internal) ?: return@getOrPut null
        val members = HashSet<String>()
        val supers = ArrayList<String>()
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(v: Int, a: Int, n: String, s: String?, sup: String?, itf: Array<out String>?) {
                if (sup != null) supers += sup
                itf?.let { supers += it }
            }
            override fun visitMethod(a: Int, n: String, d: String, s: String?, e: Array<out String>?): MethodVisitor? {
                members += "$n:$d"; return null
            }
            override fun visitField(a: Int, n: String, d: String, s: String?, v: Any?): FieldVisitor? {
                members += "$n:$d"; return null
            }
        }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        ClassInfo(internal, supers, members)
    }

    /** True if [owner] (or a supertype) declares the member. */
    fun hasMember(owner: String, name: String, desc: String): Boolean {
        val key = "$name:$desc"
        val seen = HashSet<String>()
        fun walk(c: String): Boolean {
            if (!seen.add(c)) return false
            val i = info(c) ?: return false
            if (key in i.members) return true
            return i.supers.any { walk(it) }
        }
        if (walk(owner)) return true
        // Interfaces implicitly inherit Object's methods.
        return key in (info("java/lang/Object")?.members ?: emptySet())
    }
}
