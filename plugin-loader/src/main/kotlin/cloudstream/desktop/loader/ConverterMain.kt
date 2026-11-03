package cloudstream.desktop.loader

import java.io.File
import kotlin.system.exitProcess

/** Child-JVM entry point: `ConverterMain <in.cs3> <out.jar>`. Prints `WARN ...` lines for translation problems. */
fun main(args: Array<String>) {
    val warnings = ArrayList<String>()
    try {
        translate(File(args[0]), File(args[1]), warnings)
    } catch (t: Throwable) {
        t.printStackTrace()
        exitProcess(1)
    }
    warnings.forEach { println("WARN $it") }
}
