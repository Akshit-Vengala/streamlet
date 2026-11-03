package cloudstream.desktop.scanner

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.File
import java.util.zip.ZipFile

/** Classification of an external class reference. */
enum class Cat { JDK, KOTLIN, CORE, OTHER_LIB, ANDROID, ANDROIDX, APP_ONLY, MISSING_OTHER }

/** Raw references from one plugin jar (statically read with ASM; nothing is loaded or run). */
class RawRefs {
    val classes = HashSet<String>()
    val members = HashSet<String>()      // "owner.name:desc"
    val superclasses = HashSet<String>()
    var internalClasses = 0
}

fun scanJar(jar: File): RawRefs {
    val refs = RawRefs()
    val own = HashSet<String>()
    ZipFile(jar).use { z ->
        for (e in z.entries()) if (e.name.endsWith(".class")) own += e.name.removeSuffix(".class")
        refs.internalClasses = own.size
        for (e in z.entries()) {
            if (!e.name.endsWith(".class")) continue
            val bytes = z.getInputStream(e).readBytes()
            ClassReader(bytes).accept(Collector(refs, own), ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        }
    }
    return refs
}

private class Collector(val r: RawRefs, val own: Set<String>) : ClassVisitor(Opcodes.ASM9) {
    private fun cls(n: String?) {
        if (n == null) return
        var s: String = n
        while (s.startsWith("[")) s = s.substring(1)
        if (s.startsWith("L") && s.endsWith(";")) s = s.substring(1, s.length - 1)
        if (s.length == 1 && s[0] in "ZBCSIJFDV") return
        if (s in own) return
        r.classes += s
    }
    private fun typ(t: Type) {
        when (t.sort) {
            Type.ARRAY -> typ(t.elementType)
            Type.OBJECT -> cls(t.internalName)
            Type.METHOD -> { t.argumentTypes.forEach(::typ); typ(t.returnType) }
        }
    }
    private fun desc(d: String?) { if (d != null) typ(Type.getType(d)) }
    private fun member(owner: String, name: String, d: String) {
        cls(owner); desc(d)
        if (owner.startsWith("[")) return
        if (owner !in own) r.members += "$owner.$name:$d"
    }

    override fun visit(v: Int, a: Int, n: String, s: String?, sup: String?, itf: Array<out String>?) {
        if (sup != null) { cls(sup); if (sup !in own) r.superclasses += sup }
        itf?.forEach { cls(it) }
    }
    override fun visitField(a: Int, n: String, d: String, s: String?, v: Any?): FieldVisitor? { desc(d); return null }
    override fun visitMethod(a: Int, n: String, d: String, s: String?, ex: Array<out String>?): MethodVisitor {
        desc(d); ex?.forEach { cls(it) }
        return object : MethodVisitor(Opcodes.ASM9) {
            override fun visitMethodInsn(op: Int, o: String, nm: String, ds: String, itf: Boolean) = member(o, nm, ds)
            override fun visitFieldInsn(op: Int, o: String, nm: String, ds: String) = member(o, nm, ds)
            override fun visitTypeInsn(op: Int, t: String) = cls(t)
            override fun visitMultiANewArrayInsn(d: String, dims: Int) = desc(d)
            override fun visitTryCatchBlock(s: Label?, e: Label?, h: Label?, t: String?) = cls(t)
            override fun visitLdcInsn(v: Any?) { if (v is Type) typ(v) }
            override fun visitInvokeDynamicInsn(n: String, d: String, bsm: Handle, vararg args: Any?) {
                desc(d)
                for (a in args) when (a) {
                    is Handle -> member(a.owner, a.name, a.desc)
                    is Type -> typ(a)
                }
            }
        }
    }
}

fun categorize(internal: String, index: ClassIndex): Cat {
    when (index.originOf(internal)) {
        Origin.JDK -> return Cat.JDK
        Origin.CORE -> return Cat.CORE
        Origin.OTHER_LIB ->
            return if (internal.startsWith("kotlin/") || internal.startsWith("kotlinx/")) Cat.KOTLIN else Cat.OTHER_LIB
        null -> {}
    }
    return when {
        internal.startsWith("androidx/") || internal.startsWith("android/support/") -> Cat.ANDROIDX
        internal.startsWith("android/") || internal.startsWith("dalvik/") || internal.startsWith("com/android/") ||
            internal.startsWith("com/google/android/") -> Cat.ANDROID
        internal.startsWith("com/lagradost/cloudstream3/") || internal.startsWith("com/lagradost/api/") -> Cat.APP_ONLY
        else -> Cat.MISSING_OTHER
    }
}

val WEBVIEW_MARKERS = listOf("android/webkit/", "com/lagradost/cloudstream3/network/WebViewResolver")
val CLOUDFLARE_MARKERS = listOf("com/lagradost/cloudstream3/network/CloudflareKiller")
val UI_PREFIXES = listOf(
    "android/view/", "android/widget/", "android/app/", "android/graphics/", "android/animation/",
    "androidx/fragment/", "androidx/appcompat/", "androidx/recyclerview/", "androidx/constraintlayout/",
    "androidx/cardview/", "com/google/android/material/", "android/content/res/",
)
