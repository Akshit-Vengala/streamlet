package cloudstream.desktop.loader

import java.io.File

/** `./gradlew :plugin-loader:scanPlugins [-Pdir=<plugins dir>]`: the safety scan of every installed extension (reads bytecode only). */
fun main(args: Array<String>) {
    val dir = args.firstOrNull()?.takeIf { it.isNotBlank() }?.let(::File) ?: defaultPluginsDir()
    val state = File(dir, "state.json")
    @Suppress("UNCHECKED_CAST")
    val installed = (json.readValue(state, Map::class.java)["installed"] as List<Map<String, Any?>>)
    for (rec in installed) {
        val name = (rec["info"] as Map<*, *>)["name"]
        val jar = File(rec["jarPath"] as String)
        if (!jar.isFile) { println("$name: (no jar)"); continue }
        val t0 = System.currentTimeMillis()
        val r = CapabilityScanner.scan(jar)
        println("$name  (${jar.length() / 1024} KB, ${System.currentTimeMillis() - t0} ms): " +
            if (r.findings.isEmpty()) "clean" else r.findings.joinToString("") { f ->
                "\n   ${if (f.capability.dangerous) "BLOCK" else "note "} ${f.capability}: ${f.examples.take(3).joinToString(" | ")}"
            })
    }
}
