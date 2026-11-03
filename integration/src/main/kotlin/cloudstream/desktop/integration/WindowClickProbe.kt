package cloudstream.desktop.integration

import cloudstream.desktop.webview.BrowseSpec
import cloudstream.desktop.webview.JcefEngine
import cloudstream.desktop.webview.Verdict
import java.awt.GraphicsEnvironment
import java.awt.Robot
import java.awt.event.InputEvent
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

interface U32 : com.sun.jna.win32.StdCallLibrary {
    fun GetForegroundWindow(): com.sun.jna.Pointer?
    fun GetWindowTextW(h: com.sun.jna.Pointer?, buf: CharArray, n: Int): Int
    /** Like GetWindowText but never sends a message (GetWindowText on our own window hung the probe). */
    fun InternalGetWindowText(h: com.sun.jna.Pointer?, buf: CharArray, n: Int): Int
    fun FindWindowW(cls: com.sun.jna.WString?, title: com.sun.jna.WString?): com.sun.jna.Pointer?
    fun GetWindowRect(h: com.sun.jna.Pointer?, r: IntArray): Boolean
    fun WindowFromPoint(p: Long): com.sun.jna.Pointer?
    fun GetAncestor(h: com.sun.jna.Pointer?, flags: Int): com.sun.jna.Pointer?
    fun IsWindowEnabled(h: com.sun.jna.Pointer?): Boolean
    fun GetParent(h: com.sun.jna.Pointer?): com.sun.jna.Pointer?
    fun GetClassNameW(h: com.sun.jna.Pointer?, buf: CharArray, n: Int): Int
    fun GetWindowThreadProcessId(h: com.sun.jna.Pointer?, pid: IntArray): Int
    fun PostMessageW(h: com.sun.jna.Pointer?, msg: Int, wParam: Long, lParam: Long): Boolean
    fun ScreenToClient(h: com.sun.jna.Pointer?, p: IntArray): Boolean
}

/**
 * Does a click reach the page in the verification window? Opens a local page whose whole body is a button counting
 * clicks, makes the window visible the two ways the app does (visible from the start; hidden then setVisible(true),
 * a plugin's path), clicks the screen centre with the OS mouse and asks the page how many clicks it saw.
 */
fun main() {
    // The blank "Exception in thread AWT-EventQueue-0" lines: print what they are (their toString fails).
    Thread.setDefaultUncaughtExceptionHandler { t, e ->
        val msg = runCatching { e.message }.getOrElse { "(message threw $it)" }
        println("UNCAUGHT on ${t.name}: ${e.javaClass.name}: $msg")
        e.stackTrace.take(12).forEach { println("    at $it") }
        var c = runCatching { e.cause }.getOrNull()
        while (c != null) { println("  caused by ${c.javaClass.name}"); c.stackTrace.take(6).forEach { println("    at $it") }; c = runCatching { c!!.cause }.getOrNull() }
    }
    val server =com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val html = """<!doctype html><html><body style="margin:0">
                <button id=b style="width:100vw;height:100vh;font-size:40px" onclick="window.__c=(window.__c||0)+1;this.textContent='clicked '+window.__c">click me</button>
                <script>window.__ev=[];['mousemove','mousedown','mouseup','pointerdown','pointerup','click','focus','blur'].forEach(function(t){window.addEventListener(t,function(){if(window.__ev.length<40&&(t!=='mousemove'||window.__ev[window.__ev.length-1]!=='mousemove'))window.__ev.push(t)},true)});</script>
                </body></html>""".toByteArray()
            ex.responseHeaders.add("Content-Type", "text/html"); ex.sendResponseHeaders(200, html.size.toLong()); ex.responseBody.use { it.write(html) }
        }
        start()
    }
    val url = "http://127.0.0.1:${server.address.port}/"
    val seenHeaders = java.util.concurrent.ConcurrentHashMap<String, String>()
    server.createContext("/ua") { ex ->
        listOf("User-Agent", "Sec-CH-UA", "Sec-CH-UA-Platform", "Sec-CH-UA-Mobile").forEach { k -> ex.requestHeaders.getFirst(k)?.let { seenHeaders[k] = it } }
        val html = "<!doctype html><html><body>ua</body></html>".toByteArray()
        ex.responseHeaders.add("Content-Type", "text/html"); ex.sendResponseHeaders(200, html.size.toLong()); ex.responseBody.use { it.write(html) }
    }
    val engine = JcefEngine()
    // 0) A custom user agent is the page's whole identity (header, navigator, client hints), not just the header.
    run {
        val androidUa = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
        val loaded = CompletableFuture<Unit>()
        val h = engine.open(BrowseSpec(url + "ua", userAgent = androidUa, onPageFinished = { if (it.contains("/ua")) loaded.complete(Unit) })) { Verdict.CONTINUE }
        runCatching { loaded.get(20, TimeUnit.SECONDS) }
        val js = CompletableFuture<String?>()
        h.evaluate("JSON.stringify({ua: navigator.userAgent, platform: navigator.platform, hints: navigator.userAgentData ? {platform: navigator.userAgentData.platform, mobile: navigator.userAgentData.mobile, brands: navigator.userAgentData.brands.map(function(b){return b.brand+' '+b.version}).join(', ')} : null})") { js.complete(it) }
        println("   page sees: ${runCatching { js.get(5, TimeUnit.SECONDS) }.getOrNull()}")
        println("   server got: $seenHeaders")
        val ok = seenHeaders["User-Agent"] == androidUa && seenHeaders["Sec-CH-UA-Platform"]?.contains("Android") != false
        println((if (ok) "PASS " else "FAIL ") + "custom user agent applied consistently")
        h.close(); Thread.sleep(500)
    }
    val robot = Robot()
    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
    var rc = 0
    fun clicks(h: cloudstream.desktop.webview.BrowseHandle): String {
        val f = CompletableFuture<String?>()
        h.evaluate("window.__c||0") { f.complete(it) }
        return runCatching { f.get(5, TimeUnit.SECONDS) }.getOrNull() ?: "no answer"
    }
    val user32 = com.sun.jna.Native.load("user32", U32::class.java)
    fun foregroundTitle(): String { val b = CharArray(256); val n = user32.InternalGetWindowText(user32.GetForegroundWindow(), b, b.size); return String(b, 0, n) }
    /** Clicks the middle of our window, only if that point on screen really belongs to it (never into another app). */
    fun clickCentre(clicksNow: () -> String): Boolean {
        println("   foreground window: \"${foregroundTitle()}\"")
        val hwnd = user32.FindWindowW(null, com.sun.jna.WString("CloudStream - verification required"))
        if (hwnd == null) { println("   our window not found"); return false }
        val r = IntArray(4); user32.GetWindowRect(hwnd, r)
        val x = (r[0] + r[2]) / 2; val y = (r[1] + r[3]) / 2
        // POINT by value packed into a 64-bit argument: low = x, high = y.
        val under = user32.WindowFromPoint((y.toLong() shl 32) or (x.toLong() and 0xffffffffL))
        val root = user32.GetAncestor(under, 2) // GA_ROOT
        fun title(h: com.sun.jna.Pointer?): String { val b = CharArray(256); val n = user32.InternalGetWindowText(h, b, b.size); return String(b, 0, n) }
        val ours = root == hwnd || title(root).contains("verification required")
        println("   our window rect=${r.toList()} point=($x,$y) under=${under} \"${title(under)}\" root=${root} \"${title(root)}\" ours=$ours")
        if (!ours) return false
        // The chain from the window under the cursor up to ours: class, enabled, owning thread/process.
        var w = under
        while (w != null) {
            val cb = CharArray(128); val cn = user32.GetClassNameW(w, cb, cb.size)
            val pid = IntArray(1); val tid = user32.GetWindowThreadProcessId(w, pid)
            println("     ${String(cb, 0, cn)} \"${title(w)}\" enabled=${user32.IsWindowEnabled(w)} thread=$tid pid=${pid[0]}")
            w = user32.GetParent(w)
        }
        println("     this process pid=${ProcessHandle.current().pid()}")
        // Win32 gives physical pixels; Robot works in Java's scaled (logical) coordinates.
        val scale = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX
        val lx = (x / scale).toInt(); val ly = (y / scale).toInt()
        println("   display scale=$scale -> robot click at ($lx,$ly)")
        robot.mouseMove(lx, ly); Thread.sleep(200)
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); Thread.sleep(60); robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK); Thread.sleep(900)
        println("   after OS click: page clicks=${clicksNow()}")
        // Same click posted straight to Chromium's window (no OS hit-testing): does Chromium handle input at all?
        for (target in listOf(under, user32.GetParent(under))) {
            val p = intArrayOf(x, y); user32.ScreenToClient(target, p)
            val lp = ((p[1].toLong() and 0xffff) shl 16) or (p[0].toLong() and 0xffff)
            user32.PostMessageW(target, 0x0200, 0, lp) // WM_MOUSEMOVE
            user32.PostMessageW(target, 0x0201, 1, lp) // WM_LBUTTONDOWN, MK_LBUTTON
            Thread.sleep(80)
            user32.PostMessageW(target, 0x0202, 0, lp) // WM_LBUTTONUP
            Thread.sleep(600)
            val cb = CharArray(128); val cn = user32.GetClassNameW(target, cb, cb.size)
            println("   after posted click to ${String(cb, 0, cn)}: page clicks=${clicksNow()}")
        }
        val shot = robot.createScreenCapture(java.awt.Rectangle((r[0] / scale).toInt(), (r[1] / scale).toInt(), ((r[2] - r[0]) / scale).toInt(), ((r[3] - r[1]) / scale).toInt()))
        val out = java.io.File(System.getProperty("java.io.tmpdir"), "window-click-${System.currentTimeMillis()}.png")
        javax.imageio.ImageIO.write(shot, "png", out); println("   screenshot ${out.absolutePath}")
        return true
    }
    for (mode in listOf("visible from start", "hidden then setVisible(true)")) {
        val loaded = CompletableFuture<Unit>()
        val h = engine.open(BrowseSpec(url, visible = mode == "visible from start", onPageFinished = { loaded.complete(Unit) })) { Verdict.CONTINUE }
        runCatching { loaded.get(20, TimeUnit.SECONDS) }
        if (mode != "visible from start") {
            Thread.sleep(800)
            val again = CompletableFuture<Unit>()
            // setVisible re-creates the browser in a window: wait for that load too.
            val t0 = System.currentTimeMillis()
            h.setVisible(true)
            while (System.currentTimeMillis() - t0 < 8000 && clicks(h) == "no answer") Thread.sleep(300)
            again.complete(Unit)
        }
        Thread.sleep(1500)
        val state = CompletableFuture<String?>()
        h.evaluate("JSON.stringify({vis: document.visibilityState, hidden: document.hidden, focus: document.hasFocus(), w: innerWidth, h: innerHeight})") { state.complete(it) }
        println("   page state: ${runCatching { state.get(5, TimeUnit.SECONDS) }.getOrNull()}")
        // Sanity: does the counter work? A script click, then reset.
        val synth = CompletableFuture<String?>()
        h.evaluate("document.getElementById('b').click(); var n = window.__c||0; window.__c = 0; n") { synth.complete(it) }
        println("   script click counted: ${runCatching { synth.get(5, TimeUnit.SECONDS) }.getOrNull()}")
        val before = clicks(h)
        val inFront = clickCentre { clicks(h) }
        val after = clicks(h)
        val ev = CompletableFuture<String?>()
        h.evaluate("window.__ev.join(',')") { ev.complete(it) }
        println("   events the page saw: ${runCatching { ev.get(5, TimeUnit.SECONDS) }.getOrNull()}")
        val ok = inFront && after != before && after != "no answer"
        println((if (ok) "PASS " else "FAIL ") + "$mode: window in front=$inFront, clicks before=$before after=$after")
        if (!ok) rc = 1
        h.close(); Thread.sleep(800)
    }
    server.stop(0)
    println(if (rc == 0) "RESULT: WINDOW CLICKS OK" else "RESULT: FAILED")
    System.out.flush(); Runtime.getRuntime().halt(rc)
}
