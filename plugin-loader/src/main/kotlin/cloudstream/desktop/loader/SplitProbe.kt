package cloudstream.desktop.loader

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import java.io.File
import java.util.zip.ZipFile

/** Diagnostics: `SplitProbe <raw.jar> <internal/class/Name>` runs the oversized-method processing on one class and reports. */
fun main(args: Array<String>) {
    val jar = File(args[0]); val cls = args[1]
    val bytes = ZipFile(jar).use { z -> z.getInputStream(z.getEntry("$cls.class")).use { it.readBytes() } }
    println("before: ${LargeMethodStubber.oversizedMethods(bytes)}")
    // Direct run with logging and full stack trace.
    runCatching {
        val node = ClassNode().also { ClassReader(bytes).accept(it, 0) }
        val big = LargeMethodStubber.oversizedMethods(bytes)
        for (o in big) MethodSplitter(Hierarchy(jar)) { println("  log: $it") }.split(node, node.methods.first { it.name == o.name && it.desc == o.desc })
    }.onFailure { it.printStackTrace(System.out) }
    val warnings = ArrayList<String>()
    val t0 = System.currentTimeMillis()
    val out = LargeMethodStubber.process(bytes, warnings) { Hierarchy(jar) }
    println("took ${System.currentTimeMillis() - t0} ms")
    warnings.forEach { println("WARN $it") }
    println("after: oversized=${LargeMethodStubber.oversizedMethods(out)}")
    val node = ClassNode().also { ClassReader(out).accept(it, 0) }
    node.methods.filter { "\$split\$" in it.name || it.instructions.size() > 5000 }.forEach { println("  ${it.name}${it.desc}: ${it.instructions.size()} insns") }
    File(jar.parentFile, cls.substringAfterLast('/') + ".split.class").writeBytes(out)
}
