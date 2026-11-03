package cloudstream.desktop.harness

/** Pure functions: Throwable / strings -> [ErrorInfo] + [Bucket]. Unit-tested without executing plugins. */
object Classifier {
    private val jdkPrefixes = listOf("java.", "javax.", "jdk.", "sun.", "kotlin.", "kotlinx.coroutines.")

    private fun StackTraceElement.isJdk() = jdkPrefixes.any { className.startsWith(it) }

    fun firstFrame(t: Throwable): String? =
        t.stackTrace.firstOrNull { !it.isJdk() }?.let { "${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }

    private fun chain(t: Throwable): List<Throwable> {
        val out = ArrayList<Throwable>()
        var cur: Throwable? = t
        while (cur != null && out.size < 30 && out.none { it === cur }) {
            out += cur
            cur = if (cur is java.lang.reflect.InvocationTargetException) cur.targetException else cur.cause
        }
        return out
    }

    fun rootOf(t: Throwable): Throwable = chain(t).last()

    private fun head(s: String?, n: Int = 300) = s?.replace('\n', ' ')?.take(n)

    fun describe(t: Throwable): ErrorInfo {
        val root = rootOf(t)
        val base = ErrorInfo(
            cls = t.javaClass.name, message = head(t.message), frame = firstFrame(t),
            rootCls = if (root !== t) root.javaClass.name else null,
            rootMessage = if (root !== t) head(root.message) else null,
            rootFrame = if (root !== t) firstFrame(root) else null,
            symbol = chain(t).firstNotNullOfOrNull { symbolFromMessage(it.javaClass.name, it.message) },
        )
        return base.copy(bucket = bucketOf(base, chain(t).map { it.javaClass.name }).id)
    }

    /** From a bare "cls: message" string (PluginLoader's LoadResult.Failed). */
    fun describe(error: String, missing: List<String>): ErrorInfo {
        val cls = error.substringBefore(':').trim()
        val msg = error.substringAfter(':', "").trim().ifEmpty { null }
        val sym = missing.firstOrNull()?.removePrefix("symbol:")
        val base = ErrorInfo(cls = cls, message = head(msg), symbol = sym ?: symbolFromMessage(cls, msg))
        return base.copy(bucket = bucketOf(base, emptyList()).id)
    }

    private val linkage = setOf(
        "java.lang.NoClassDefFoundError", "java.lang.ClassNotFoundException", "java.lang.NoSuchMethodError",
        "java.lang.NoSuchFieldError", "java.lang.AbstractMethodError", "java.lang.IncompatibleClassChangeError",
        "java.lang.UnsatisfiedLinkError", "java.lang.IllegalAccessError", "java.lang.InstantiationError",
    )

    private fun symbolFromMessage(cls: String, msg: String?): String? {
        if (cls !in linkage || msg == null) return null
        return msg.substringBefore(" (").replace('/', '.').trim().take(200)
    }

    private val webMarkers = listOf("WebViewResolver", "CloudflareKiller", "android.webkit", "WebView", "cf_clearance", "cloudflare", "just a moment", "turnstile")
    private val httpStatus = Regex("""\b[45]\d\d\b""")

    fun bucketOf(e: ErrorInfo, chainNames: List<String>): Bucket {
        val names = buildList { add(e.cls); e.rootCls?.let { add(it) }; addAll(chainNames) }
        val text = listOf(e.message, e.rootMessage, e.symbol, e.frame, e.rootFrame).joinToString(" ") { it ?: "" }
        if (names.any { it in linkage }) {
            val sym = e.symbol ?: text
            return if (listOf("WebViewResolver", "CloudflareKiller", "android.webkit").any { sym.contains(it, true) }) Bucket.WEBVIEW else Bucket.MISSING_SHIM
        }
        if (names.any { it.endsWith("ClassFormatError") || it.endsWith("VerifyError") || it.contains("Dex2jar", true) || it.contains("DexException") })
            return Bucket.DEX2JAR
        if (names.any { it == "java.net.SocketTimeoutException" || it.startsWith("java.net.http.Http") && it.contains("Timeout") })
            return Bucket.EXTERNAL
        if (names.any { it == "kotlinx.coroutines.TimeoutCancellationException" || it == "java.util.concurrent.TimeoutException" })
            return Bucket.TIMEOUT
        if (webMarkers.any { text.contains(it, true) }) return Bucket.WEBVIEW
        val net = names.any {
            it.startsWith("java.net.") || it.startsWith("javax.net.ssl") || it.startsWith("okhttp3.") || it.startsWith("okio.") ||
                it == "java.io.IOException" || it == "java.io.InterruptedIOException" || it.startsWith("java.nio.channels.")
        }
        if (net) return Bucket.EXTERNAL
        if (e.cls.contains("HttpException") || e.cls.contains("HttpStatus")) return Bucket.EXTERNAL
        val m = e.message ?: ""
        if (httpStatus.containsMatchIn(m) && (m.contains("HTTP", true) || m.contains("status", true) || m.contains("code", true)))
            return Bucket.EXTERNAL
        return Bucket.CORE
    }

    fun synthetic(cls: String, message: String, bucket: Bucket) = ErrorInfo(cls = cls, message = message, bucket = bucket.id)
}
