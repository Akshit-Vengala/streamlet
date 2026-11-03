package cloudstream.desktop.webview

import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/** Manual smoke runner: args = mode(resolve|clearance) url regex */
fun main(args: Array<String>): Unit = runBlocking {
    val (mode, url, regex) = args
    Thread.setDefaultUncaughtExceptionHandler { t, e -> println("UNCAUGHT in ${t.name}: $e"); e.stackTrace.take(10).forEach { println("   at $it") } }
    DesktopWebView.install { println("[jcef] ${it.stage} ${it.fraction ?: ""}") }
    val b = DesktopWebView.headlessBrowser()
    val t0 = System.currentTimeMillis()
    when (mode) {
        "clearance" -> println("clearance -> ${b.clearance(url, 120_000)}")
        else -> println("resolve -> ${b.resolve(url, Regex(regex), emptyMap(), 30_000)}")
    }
    println("took ${System.currentTimeMillis() - t0}ms")
    exitProcess(0)
}
