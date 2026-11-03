import cloudstream.desktop.loader.Capability
import cloudstream.desktop.loader.CapabilityScanner
import cloudstream.desktop.loader.GuardedParentLoader
import cloudstream.desktop.loader.PluginGuard
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PluginGuardTest {
    /** A jar with one class whose `run()` executes [body] (ASM). */
    private fun jarWith(name: String, body: (org.objectweb.asm.MethodVisitor) -> Unit): File {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "test/$name", null, "java/lang/Object", null)
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "run", "()V", null, null).apply {
            visitCode(); body(this); visitInsn(Opcodes.RETURN); visitMaxs(0, 0); visitEnd()
        }
        cw.visitEnd()
        val f = Files.createTempFile(name, ".jar").toFile().apply { deleteOnExit() }
        ZipOutputStream(f.outputStream()).use { z -> z.putNextEntry(ZipEntry("test/$name.class")); z.write(cw.toByteArray()); z.closeEntry() }
        return f
    }

    private val clean = { mv: org.objectweb.asm.MethodVisitor ->
        mv.visitLdcInsn("https://example.com"); mv.visitInsn(Opcodes.POP)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J", false); mv.visitInsn(Opcodes.POP2)
    }

    @Test fun `a plain extension is clean`() {
        assertEquals(emptySet(), CapabilityScanner.scan(jarWith("Clean", clean)).capabilities)
    }

    @Test fun `running programs, native code, app internals and file writes are found`() {
        val jar = jarWith("Bad") { mv ->
            mv.visitTypeInsn(Opcodes.NEW, "java/lang/ProcessBuilder"); mv.visitInsn(Opcodes.POP)
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Runtime", "getRuntime", "()Ljava/lang/Runtime;", false)
            mv.visitLdcInsn("calc"); mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Runtime", "exec", "(Ljava/lang/String;)Ljava/lang/Process;", false); mv.visitInsn(Opcodes.POP)
            mv.visitLdcInsn("evil"); mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "loadLibrary", "(Ljava/lang/String;)V", false)
            mv.visitLdcInsn("cloudstream.desktop.app.sync.SyncManager"); mv.visitInsn(Opcodes.POP) // reflection by name
            mv.visitTypeInsn(Opcodes.NEW, "java/io/FileOutputStream"); mv.visitInsn(Opcodes.DUP); mv.visitLdcInsn("x")
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/io/FileOutputStream", "<init>", "(Ljava/lang/String;)V", false); mv.visitInsn(Opcodes.POP)
            mv.visitTypeInsn(Opcodes.NEW, "java/net/ServerSocket"); mv.visitInsn(Opcodes.POP)
        }
        val r = CapabilityScanner.scan(jar)
        assertEquals(setOf(Capability.RunPrograms, Capability.NativeCode, Capability.AppInternals, Capability.WriteFiles, Capability.ListenNetwork), r.capabilities)
        assertEquals(setOf(Capability.RunPrograms, Capability.NativeCode, Capability.AppInternals), r.dangerous) // the rest are notes
        assertTrue(r.findings.first { it.capability == Capability.RunPrograms }.examples.any { "Bad.run" in it })
    }

    @Test fun `dangerous abilities block until allowed, and allowances persist`() {
        val dir = Files.createTempDirectory("guard").toFile()
        val jar = jarWith("Exec") { mv -> mv.visitTypeInsn(Opcodes.NEW, "java/awt/Robot"); mv.visitInsn(Opcodes.POP) }
        val g = PluginGuard(dir)
        assertEquals(setOf(Capability.ControlInput), g.blockedFor("Exec.cs3", jar))
        g.allow("Exec.cs3", setOf(Capability.ControlInput))
        assertEquals(emptySet(), g.blockedFor("Exec.cs3", jar))
        assertEquals(emptySet(), PluginGuard(dir).blockedFor("Exec.cs3", jar), "saved in safety.json")
        assertEquals(setOf(Capability.ControlInput), PluginGuard(dir).blockedFor("Other.cs3", jar), "allowances are per extension")
        g.revoke("Exec.cs3")
        assertEquals(setOf(Capability.ControlInput), PluginGuard(dir).blockedFor("Exec.cs3", jar))
    }

    @Test fun `extensions cannot see the app's classes or blocked JDK classes`() {
        val app = javaClass.classLoader
        val walled = GuardedParentLoader(app, emptySet())
        assertFailsWith<ClassNotFoundException> { walled.loadClass("cloudstream.desktop.loader.PluginLoader") }
        assertFailsWith<ClassNotFoundException> { walled.loadClass("java.lang.ProcessBuilder") }
        assertFailsWith<ClassNotFoundException> { walled.loadClass("java.lang.ProcessBuilder\$Redirect") }
        assertFailsWith<ClassNotFoundException> { walled.loadClass("java.awt.Robot") }
        // What extensions use is still there.
        walled.loadClass("com.lagradost.cloudstream3.MainAPI"); walled.loadClass("java.lang.String"); walled.loadClass("kotlin.Unit")
        walled.loadClass("java.lang.Process") // a return type elsewhere (Runtime.exec is caught by the scan)
        // Allowed abilities open their classes again.
        GuardedParentLoader(app, setOf(Capability.RunPrograms)).loadClass("java.lang.ProcessBuilder")
    }
}
