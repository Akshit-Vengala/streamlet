import cloudstream.desktop.loader.LargeMethodStubber
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LargeMethodStubberTest {
    /** A class with a 70,000-byte `suspend`-shaped method and a normal one. */
    private fun bigClass(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(V1_8, ACC_PUBLIC, "gen/Big", null, "java/lang/Object", null)
        cw.visitMethod(ACC_PUBLIC, "ok", "()I", null, null).apply { visitCode(); visitInsn(ICONST_1); visitInsn(IRETURN); visitMaxs(0, 0); visitEnd() }
        cw.visitMethod(ACC_PUBLIC, "huge", "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", null, null).apply {
            visitCode(); repeat(70_000) { visitInsn(NOP) }; visitInsn(ACONST_NULL); visitInsn(ARETURN); visitMaxs(0, 0); visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    @Test fun `detects and stubs only the oversized method`() {
        val big = bigClass()
        val found = LargeMethodStubber.oversizedMethods(big)
        assertEquals(listOf("huge"), found.map { it.name })
        val warnings = ArrayList<String>()
        val fixed = LargeMethodStubber.process(big, warnings)
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("gen/Big.huge"))
        assertTrue(LargeMethodStubber.oversizedMethods(fixed).isEmpty())
        // The result must be loadable, with the normal method intact and the stub returning Unit-or-null.
        val cl = object : ClassLoader(LargeMethodStubberTest::class.java.classLoader) {
            fun define() = defineClass("gen.Big", fixed, 0, fixed.size)
        }
        val c = cl.define() // would throw ClassFormatError if the oversized method were still there
        val inst = objenesisLike(c)
        assertEquals(1, c.getMethod("ok").invoke(inst))
    }

    @Test fun `leaves normal classes untouched`() {
        val small = ClassWriter(0).also { it.visit(V1_8, ACC_PUBLIC, "gen/Small", null, "java/lang/Object", null); it.visitEnd() }.toByteArray()
        assertTrue(LargeMethodStubber.process(small, ArrayList()) === small)
    }

    private fun objenesisLike(c: Class<*>): Any {
        val f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").also { it.isAccessible = true }.get(null)
        return f.javaClass.getMethod("allocateInstance", Class::class.java).invoke(f, c)
    }
}
