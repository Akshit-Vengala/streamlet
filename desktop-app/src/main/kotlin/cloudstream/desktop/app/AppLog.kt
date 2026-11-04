package cloudstream.desktop.app

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.logging.FileHandler
import java.util.logging.Formatter
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogManager
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.logging.StreamHandler

/**
 * The app's log: `<data>/logs/streamlet.0.log` (rotated: streamlet.0.log newest ... streamlet.3.log, 4 x 5 MB).
 *
 * Everything goes there: java.util.logging (the app's own `streamlet.*` loggers and extensions' android.util.Log),
 * and stdout/stderr, which an installed app has no console for. The console still shows it all when there is one.
 *
 * Level: INFO by default; `STREAMLET_DEBUG=1` or `-Dstreamlet.log=FINE` (FINER, ALL, ...) for extension debug lines.
 */
object AppLog {
    val dir: File get() = File(cloudstream.desktop.contracts.AppDirs.root, "logs")
    /** The current file (FileHandler's generation 0; older ones are streamlet.1.log ... streamlet.3.log). */
    val file: File get() = File(dir, "streamlet.0.log")
    @Volatile private var installed = false

    fun get(name: String): Logger = Logger.getLogger("streamlet.$name")

    private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    private object LineFormat : Formatter() {
        override fun format(r: LogRecord): String {
            val sb = StringBuilder()
            sb.append(LocalDateTime.now().format(time)).append(' ')
                .append(r.level.let { if (it == Level.SEVERE) "ERROR" else if (it == Level.WARNING) "WARN" else it.name }.padEnd(5)).append(' ')
                .append('[').append(Thread.currentThread().name.take(32)).append("] ")
                .append(r.loggerName?.removePrefix("streamlet.") ?: "-").append(": ")
                .append(runCatching { formatMessage(r) }.getOrElse { r.message ?: "" })
                .append(System.lineSeparator())
            r.thrown?.let { sb.append(traceOf(it)).append(System.lineSeparator()) }
            return sb.toString()
        }
    }

    /** A stack trace that survives exceptions whose own toString/getMessage throw (some extensions have those). */
    fun traceOf(e: Throwable): String = runCatching {
        val sw = java.io.StringWriter(); e.printStackTrace(java.io.PrintWriter(sw)); sw.toString().trimEnd()
    }.getOrElse { "${e.javaClass.name} (message unavailable: $it)\n" + e.stackTrace.joinToString("\n") { "\tat $it" } }

    private fun level(): Level {
        val p = System.getProperty("streamlet.log")?.takeIf { it.isNotBlank() }
            ?: if (System.getenv("STREAMLET_DEBUG") == "1") "FINE" else null
        return p?.let { runCatching { Level.parse(it.uppercase()) }.getOrNull() } ?: Level.INFO
    }

    /** Installs the file log, the stdout/stderr capture and the uncaught-exception handler. Safe to call twice. */
    @Synchronized fun install() {
        if (installed) return
        installed = true
        val console = System.err
        val lvl = level()
        LogManager.getLogManager().reset() // drop the default ConsoleHandler (it would write to the captured stderr: a loop)
        val root = Logger.getLogger("")
        root.level = lvl
        runCatching {
            dir.mkdirs()
            removeStaleLocks()
            val fh = FileHandler(File(dir, "streamlet.%g.log").absolutePath.replace("\\", "/"), 5_000_000, 4, true)
            fh.encoding = "UTF-8"; fh.formatter = LineFormat; fh.level = lvl
            root.addHandler(fh)
        }.onFailure { console.println("Streamlet: cannot open the log file in $dir: $it") }
        // Console: everything except the captured stdout/stderr lines (those reach the console directly).
        root.addHandler(object : StreamHandler(console, LineFormat) {
            override fun publish(r: LogRecord) { if (r.loggerName != OUT && r.loggerName != ERR) { super.publish(r); flush() } }
        }.apply { level = lvl; encoding = "UTF-8" })
        System.setOut(PrintStream(Tee(System.out, Logger.getLogger(OUT), Level.INFO), true, "UTF-8"))
        System.setErr(PrintStream(Tee(console, Logger.getLogger(ERR), Level.WARNING), true, "UTF-8"))

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { get("crash").log(Level.SEVERE, "Uncaught on thread ${t.name}:\n${traceOf(e)}") }
            previous?.uncaughtException(t, e)
        }
        Runtime.getRuntime().addShutdownHook(Thread { root.handlers.forEach { runCatching { it.flush() } } })
    }

    /**
     * Freeze detector: pings the UI thread every 200 ms; when one takes over [thresholdMs], logs how long it was blocked
     * and what the UI thread was doing then (its stack), plus GC time in that window (a pause vs. blocking work).
     */
    fun startFreezeWatch(thresholdMs: Long = 700) {
        val log = get("freeze")
        val edt = java.util.concurrent.atomic.AtomicReference<Thread?>(null)
        javax.swing.SwingUtilities.invokeLater { edt.set(Thread.currentThread()) }
        Thread({
            fun gcMs() = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime.coerceAtLeast(0) }
            while (true) {
                val done = java.util.concurrent.CountDownLatch(1)
                val t0 = System.nanoTime(); val gc0 = gcMs()
                javax.swing.SwingUtilities.invokeLater { done.countDown() }
                if (!done.await(thresholdMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    val stack = edt.get()?.stackTrace?.take(25)?.joinToString("\n\tat ", "\tat ").orEmpty()
                    done.await()
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    log.warning("UI thread blocked for $ms ms (GC ${gcMs() - gc0} ms meanwhile). It was at:\n$stack")
                }
                Thread.sleep(200)
            }
        }, "freeze-watch").apply { isDaemon = true; priority = Thread.MAX_PRIORITY }.start()
    }

    /**
     * A log lock left by a copy that was killed (no one holds it): without this, FileHandler starts a side file
     * (streamlet.0.log.1) instead of the normal log.
     */
    private fun removeStaleLocks() {
        dir.listFiles { f -> f.name.endsWith(".lck") }?.forEach { lck ->
            runCatching {
                val free = java.nio.channels.FileChannel.open(lck.toPath(), java.nio.file.StandardOpenOption.WRITE).use { ch ->
                    ch.tryLock()?.also { it.release() } != null
                }
                if (free) lck.delete()
            }
        }
    }

    /** One block at startup with what a bug report needs. */
    fun banner(args: Array<String>) {
        val rt = Runtime.getRuntime()
        get("app").info(buildString {
            append("==== Streamlet ").append(cloudstream.desktop.app.update.BuildInfo.version).append(" starting ====")
            append("\n  os: ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version"))
                .append(" (").append(System.getProperty("os.arch")).append(')')
            append("\n  java: ").append(System.getProperty("java.vendor")).append(' ').append(System.getProperty("java.runtime.version"))
            append("\n  memory: max ").append(rt.maxMemory() / (1 shl 20)).append(" MB, cpus ").append(rt.availableProcessors())
            append("\n  data: ").append(cloudstream.desktop.contracts.AppDirs.root.absolutePath)
            append("\n  app: ").append(System.getProperty("compose.application.resources.dir") ?: System.getProperty("user.dir"))
            append("\n  locale: ").append(java.util.Locale.getDefault()).append(", encoding ").append(System.getProperty("file.encoding"))
            if (args.isNotEmpty()) append("\n  args: ").append(args.joinToString(" "))
            append("\n  log level: ").append(Logger.getLogger("").level)
        })
    }

    private const val OUT = "stdout"
    private const val ERR = "stderr"

    /** Writes through to [console] and logs every complete line. */
    private class Tee(private val console: OutputStream, private val log: Logger, private val lvl: Level) : OutputStream() {
        private val line = ByteArrayOutputStream()
        private val inside = ThreadLocal.withInitial { false }
        override fun write(b: Int) = synchronized(this) {
            runCatching { console.write(b) }
            if (b == '\n'.code) emit() else line.write(b)
        }
        override fun write(b: ByteArray, off: Int, len: Int) = synchronized(this) {
            runCatching { console.write(b, off, len) }
            for (i in off until off + len) if (b[i] == '\n'.code.toByte()) emit() else line.write(b[i].toInt())
        }
        override fun flush() { runCatching { console.flush() } }
        private fun emit() {
            val s = line.toString(Charsets.UTF_8).trimEnd('\r'); line.reset()
            if (s.isEmpty() || inside.get()) return
            inside.set(true) // a handler that prints would recurse
            try { log.log(lvl, s) } finally { inside.set(false) }
        }
    }
}

/** Removes query strings and credentials from a URL before it is logged (stream links often carry tokens). */
fun redactUrl(url: String): String = runCatching {
    val u = java.net.URI(url)
    if (u.scheme == null || u.host == null) return url.substringBefore('?')
    "${u.scheme}://${u.host}${if (u.port > 0) ":${u.port}" else ""}${u.rawPath ?: ""}${if (u.rawQuery != null) "?…" else ""}"
}.getOrElse { url.substringBefore('?').take(200) }
