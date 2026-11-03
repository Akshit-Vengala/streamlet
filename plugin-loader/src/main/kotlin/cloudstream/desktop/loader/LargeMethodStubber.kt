package cloudstream.desktop.loader

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.LdcInsnNode

/**
 * The JVM caps a method's bytecode at 65535 bytes; Dalvik allows ~128 KB, and dex2jar emits the bigger
 * method anyway, so the class then fails to define (`ClassFormatError: Invalid method Code length`). A large
 * aggregator extension's link resolver (about 74.7 KB, a coroutine state machine) is the known case, and it takes
 * every other source in the class down with it.
 *
 * Splitting a state machine safely is not generally possible, so such a method is replaced by a stub that
 * returns a harmless default (for a `suspend fun`, `Unit`, i.e. "completed with nothing") and a warning is
 * recorded. The plugin keeps working except for that one source.
 */
internal object LargeMethodStubber {
    const val LIMIT = 65535

    /**
     * Returns [bytes] unchanged unless a method is too large. Such a method is first SPLIT into helper methods
     * ([MethodSplitter]); only if that fails (or the result is still too large) is it stubbed. One line per method
     * goes to [warnings]. [hierarchy] supplies the plugin's class hierarchy for the splitter's type analysis.
     */
    fun process(bytes: ByteArray, warnings: MutableCollection<String>, hierarchy: () -> Hierarchy = { Hierarchy(null) }): ByteArray {
        // A method over the limit makes the class file itself bigger than the limit.
        if (bytes.size <= LIMIT) return bytes
        val tooLarge = oversizedMethods(bytes)
        if (tooLarge.isEmpty()) return bytes
        // No splitting here: a class written with an oversized method already has truncated 16-bit exception-table
        // offsets (dex2jar's patched writer), so its code cannot be trusted. Splitting happens earlier, on the tree
        // (see translate/splitOversized); this byte-level pass is only the safety net.
        val node = ClassNode()
        ClassReader(bytes).accept(node, 0)
        for ((name, desc, size) in tooLarge) {
            val m = node.methods.first { it.name == name && it.desc == desc }
            stub(m)
            warnings += "STUBBED too-large method ($size bytes > $LIMIT): ${node.name}.$name$desc"
        }
        // COMPUTE_MAXS only: a stub has no branches, and existing frames are carried over untouched.
        val out = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(out)
        return out.toByteArray()
    }

    /** Splits every oversized method; returns null if any method still does not fit afterwards. */
    private fun trySplit(bytes: ByteArray, tooLarge: List<Oversized>, warnings: MutableCollection<String>, h: Hierarchy): ByteArray? {
        val node = ClassNode()
        ClassReader(bytes).accept(node, 0)
        val splitter = MethodSplitter(h)
        val notes = ArrayList<String>()
        for ((name, desc, size) in tooLarge) {
            val m = node.methods.first { it.name == name && it.desc == desc }
            val helpers = splitter.split(node, m)
            notes += "SPLIT too-large method ($size bytes > $LIMIT) into $helpers helper(s): ${node.name}.$name$desc"
        }
        val out = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(out)
        val result = out.toByteArray()
        val still = oversizedMethods(result)
        if (still.isNotEmpty()) { warnings += "split left oversized: ${still.joinToString { "${it.name} (${it.codeLength})" }}"; return null }
        warnings += notes
        return result
    }

    data class Oversized(val name: String, val desc: String, val codeLength: Long)

    /**
     * Reads each method's Code attribute length straight from the class file. (The ASM that ships inside
     * dex2jar has no method-size check, so asking a ClassWriter would not say.)
     */
    fun oversizedMethods(b: ByteArray, threshold: Int = LIMIT): List<Oversized> {
        val buf = java.nio.ByteBuffer.wrap(b)
        buf.position(8)
        val n = buf.short.toInt() and 0xFFFF
        val utf = arrayOfNulls<String>(n)
        var i = 1
        while (i < n) {
            when (val tag = buf.get().toInt()) {
                1 -> { val len = buf.short.toInt() and 0xFFFF; val raw = ByteArray(len); buf.get(raw); utf[i] = String(raw, Charsets.UTF_8) }
                3, 4, 9, 10, 11, 12, 17, 18 -> buf.position(buf.position() + 4)
                5, 6 -> { buf.position(buf.position() + 8); i++ }
                7, 8, 16, 19, 20 -> buf.position(buf.position() + 2)
                15 -> buf.position(buf.position() + 3)
                else -> error("bad constant pool tag $tag")
            }
            i++
        }
        buf.position(buf.position() + 6) // access, this, super
        val interfaces = buf.short.toInt() and 0xFFFF
        buf.position(buf.position() + 2 * interfaces)
        fun skipAttrs() {
            repeat(buf.short.toInt() and 0xFFFF) {
                buf.position(buf.position() + 2)
                val len = buf.int
                buf.position(buf.position() + len)
            }
        }
        repeat(buf.short.toInt() and 0xFFFF) { buf.position(buf.position() + 6); skipAttrs() } // fields
        val out = ArrayList<Oversized>()
        repeat(buf.short.toInt() and 0xFFFF) {
            buf.position(buf.position() + 2)
            val name = utf[buf.short.toInt() and 0xFFFF]!!
            val desc = utf[buf.short.toInt() and 0xFFFF]!!
            repeat(buf.short.toInt() and 0xFFFF) {
                val attrName = utf[buf.short.toInt() and 0xFFFF]
                val len = buf.int
                val next = buf.position() + len
                if (attrName == "Code") {
                    buf.position(buf.position() + 4)
                    val codeLen = buf.int.toLong() and 0xFFFFFFFFL
                    if (codeLen > threshold) out += Oversized(name, desc, codeLen)
                }
                buf.position(next)
            }
        }
        return out
    }

    fun stubNode(m: org.objectweb.asm.tree.MethodNode) = stub(m)

    private fun stub(m: org.objectweb.asm.tree.MethodNode) {
        m.instructions.clear()
        m.tryCatchBlocks.clear()
        m.localVariables?.clear()
        m.visibleLocalVariableAnnotations?.clear()
        m.invisibleLocalVariableAnnotations?.clear()
        val ret = Type.getReturnType(m.desc)
        val il = m.instructions
        val isSuspend = ret.sort == Type.OBJECT && ret.internalName == "java/lang/Object" &&
            Type.getArgumentTypes(m.desc).lastOrNull()?.internalName == "kotlin/coroutines/Continuation"
        when (ret.sort) {
            Type.VOID -> il.add(InsnNode(RETURN))
            Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> { il.add(InsnNode(ICONST_0)); il.add(InsnNode(IRETURN)) }
            Type.LONG -> { il.add(InsnNode(LCONST_0)); il.add(InsnNode(LRETURN)) }
            Type.FLOAT -> { il.add(InsnNode(FCONST_0)); il.add(InsnNode(FRETURN)) }
            Type.DOUBLE -> { il.add(InsnNode(DCONST_0)); il.add(InsnNode(DRETURN)) }
            else -> {
                if (isSuspend) il.add(FieldInsnNode(GETSTATIC, "kotlin/Unit", "INSTANCE", "Lkotlin/Unit;"))
                else il.add(InsnNode(ACONST_NULL))
                il.add(InsnNode(ARETURN))
            }
        }
        m.maxStack = 0
        m.maxLocals = 0 // recomputed by COMPUTE_MAXS
    }
}
