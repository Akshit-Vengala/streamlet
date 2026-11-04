package cloudstream.desktop.webview

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.browser.CefRequestContext
import org.cef.callback.CefCallback
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefMessageRouterHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.network.CefRequest
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Window
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JFrame
import javax.swing.SwingUtilities

/**
 * JCEF-backed [WebEngine].
 *
 * - Hidden mode: an off-screen-rendered (OSR) browser with no window at all.
 * - Visible mode ([BrowseHandle.setVisible]): the page is re-opened in a real window sharing the same
 *   cookie store, for challenges that need a human (e.g. Turnstile checkbox).
 * Every page load gets its own [CefClient] so handlers never leak between loads.
 */
class JcefEngine(private val runtime: JcefRuntime = JcefRuntime) : WebEngine {

    @Volatile
    private var observedUserAgent: String? = null

    override val userAgent: String
        get() = observedUserAgent ?: defaultUserAgent()

    private fun defaultUserAgent(): String = JcefRuntime.DEFAULT_USER_AGENT

    override fun open(spec: BrowseSpec, onRequest: (CapturedRequest) -> Verdict): BrowseHandle =
        Page(spec, onRequest).also { it.start() }

    override suspend fun cookies(url: String): Map<String, String> = withContext(Dispatchers.IO) {
        val result = CompletableDeferred<Map<String, String>>()
        val acc = LinkedHashMap<String, String>()
        runtime.app() // make sure CEF is up
        // Null until the first browser/request context exists (e.g. polled right after open()).
        val manager = org.cef.network.CefCookieManager.getGlobalManager() ?: return@withContext emptyMap()
        val started = manager.visitUrlCookies(url, true) { cookie, count, total, _ ->
                synchronized(acc) { acc[cookie.name] = cookie.value }
                if (count + 1 >= total) result.complete(synchronized(acc) { acc.toMap() })
                true
            }
        if (!started) return@withContext emptyMap()
        // With zero cookies CEF never calls the visitor, so don't wait long.
        withTimeoutOrNull(1_500) { result.await() } ?: synchronized(acc) { acc.toMap() }
    }

    override suspend fun clearCookies() = withContext(Dispatchers.IO) {
        runtime.app()
        org.cef.network.CefCookieManager.getGlobalManager()?.deleteCookies("", "")
        Unit
    }

    private inner class Page(
        val spec: BrowseSpec,
        val listener: (CapturedRequest) -> Verdict,
    ) : BrowseHandle {
        private val closed = AtomicBoolean(false)
        private var client: CefClient? = null
        private var browser: CefBrowser? = null
        private var router: CefMessageRouter? = null
        private var frame: JFrame? = null
        private var inWindow = false

        fun start() = onEdt { create(spec.visible) }

        private fun create(visible: Boolean) {
            if (closed.get()) return
            val app = runtime.app()
            val c = app.createClient()
            client = c

            c.addRequestHandler(object : CefRequestHandlerAdapter() {
                override fun getResourceRequestHandler(
                    browser: CefBrowser?, frame: CefFrame?, request: CefRequest?,
                    isNavigation: Boolean, isDownload: Boolean, requestInitiator: String?,
                    disableDefaultHandling: BoolRef?,
                ): CefResourceRequestHandler = resourceHandler

                override fun onCertificateError(
                    browser: CefBrowser?, certError: org.cef.handler.CefLoadHandler.ErrorCode?,
                    requestUrl: String?, callback: CefCallback?,
                ): Boolean {
                    callback?.Continue() // Android resolver ignores SSL errors too.
                    return true
                }
            })

            run {
                val r = CefMessageRouter.create()
                r.addHandler(object : CefMessageRouterHandlerAdapter() {
                    override fun onQuery(
                        browser: CefBrowser?, frame: CefFrame?, queryId: Long,
                        request: String?, persistent: Boolean, callback: CefQueryCallback?,
                    ): Boolean {
                        val msg = request ?: "null"
                        when {
                            msg.startsWith("{\"__eval\":") -> runCatching {
                                val o = com.google.gson.JsonParser.parseString(msg).asJsonObject
                                pendingEvals.remove(o.get("__eval").asLong)?.invoke(o.get("r")?.takeUnless { it.isJsonNull }?.asString)
                            }
                            msg.startsWith("{\"__wv\":") -> runCatching { spec.onBridge?.invoke(msg) }
                            else -> runCatching { spec.onScriptResult?.invoke(msg) }
                        }
                        callback?.success("")
                        return true
                    }
                }, true)
                c.addMessageRouter(r)
                router = r
                c.addLoadHandler(object : CefLoadHandlerAdapter() {
                    override fun onLoadStart(browser: CefBrowser?, frame: CefFrame?, t: CefRequest.TransitionType?) {
                        if (frame?.isMain == true) runCatching { spec.onPageStarted?.invoke(frame.url) }
                    }

                    override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                        if (frame?.isMain != true) return
                        spec.script?.let { frame.executeJavaScript(wrapScript(it), frame.url, 0) }
                        runCatching { spec.onPageFinished?.invoke(frame.url) }
                    }
                })
            }

            c.addLifeSpanHandler(object : org.cef.handler.CefLifeSpanHandlerAdapter() {
                override fun onAfterCreated(browser: CefBrowser?) {
                    // Only now is the native browser guaranteed to accept navigation.
                    dbg("onAfterCreated closed=${closed.get()}")
                    if (!closed.get()) try {
                        // loadRequest(CefRequest) silently never navigated in OSR on CEF 143; loadURL does.
                        // Custom headers/referer are applied in onBeforeResourceLoad for the main frame instead.
                        val ua = spec.userAgent
                        if (ua != null && browser != null) {
                            // A custom user agent must be the page's whole identity (header, navigator.userAgent,
                            // client hints), as with Android's WebSettings.setUserAgentString. A header-only change
                            // contradicted the browser's own Chrome/Windows hints and Cloudflare looped forever.
                            applyUserAgent(browser, ua).whenComplete { _, _ -> onEdt { if (!closed.get()) browser.loadURL(spec.url) } }
                        } else browser?.loadURL(spec.url)
                    } catch (t: Throwable) {
                        dbg("loadRequest failed: $t"); t.stackTrace.take(8).forEach { dbg("  at $it") }
                    }
                }

                override fun onBeforePopup(
                    browser: CefBrowser?, frame: CefFrame?, targetUrl: String?, targetFrameName: String?,
                ): Boolean {
                    // Popups/ad windows: navigate in place instead of spawning windows.
                    if (targetUrl != null) browser?.loadURL(targetUrl)
                    return true
                }
            })
            c.addLoadHandler(object : CefLoadHandlerAdapter() {
                override fun onLoadError(
                    browser: CefBrowser?, frame: CefFrame?, errorCode: org.cef.handler.CefLoadHandler.ErrorCode?,
                    errorText: String?, failedUrl: String?,
                ) = dbg("loadError $errorCode $errorText $failedUrl")

                override fun onLoadStart(browser: CefBrowser?, frame: CefFrame?, t: CefRequest.TransitionType?) =
                    dbg("loadStart ${frame?.url}")
            })
            dbg("creating browser visible=$visible")
            // Off-screen rendered and drawn by Swing (SwingOsrBrowser): JCEF's GL canvas fails here and Chromium's own
            // window got no mouse input, so a Turnstile checkbox could not be clicked. Hidden = no frames copied.
            val b = org.cef.browser.SwingOsrBrowser(c, "about:blank", CefRequestContext.getGlobalContext())
            browser = b
            b.createImmediately()
            if (visible) showInWindow(b)
        }

        /** Puts the browser in a real window, in front, with keyboard focus (same browser: the page is not reloaded). */
        private fun showInWindow(b: CefBrowser) {
            val f = JFrame("CloudStream - verification required")
            f.type = Window.Type.NORMAL
            f.layout = BorderLayout()
            f.add(b.uiComponent, BorderLayout.CENTER)
            f.size = Dimension(1000, 720)
            f.setLocationRelativeTo(null)
            f.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
            f.addWindowListener(object : java.awt.event.WindowAdapter() {
                // A window opened for a solver (visible from the start) is its page; an extension's WebView shown on
                // demand only goes back to hidden - closing it must not destroy the extension's browser.
                override fun windowClosing(e: java.awt.event.WindowEvent?) = if (spec.visible) close() else setVisible(false)
            })
            (b as? org.cef.browser.SwingOsrBrowser)?.setPainting(true)
            f.isVisible = true
            bringToFront(f)
            b.uiComponent.requestFocusInWindow()
            runCatching { b.setFocus(true) }
            frame = f
            inWindow = true
        }

        /** Main-frame navigation we started: carries the caller's headers/referer (like WebView.loadUrl(url, headers)). */
        private var initialNavigationDone = false

        private val resourceHandler = object : CefResourceRequestHandlerAdapter() {
            override fun onBeforeResourceLoad(browser: CefBrowser?, frame: CefFrame?, request: CefRequest?): Boolean =
                try { handle(request) } catch (t: Throwable) { System.err.println("[jcef] request handler failed: $t"); t.printStackTrace(); false }

            private fun handle(request: CefRequest?): Boolean {
                if (request == null || closed.get()) return closed.get()
                if (!initialNavigationDone && request.resourceType == CefRequest.ResourceType.RT_MAIN_FRAME) {
                    initialNavigationDone = true
                    spec.headers.forEach { (k, v) -> request.setHeaderByName(k, v, true) }
                    spec.referer?.let { request.setReferrer(it, CefRequest.ReferrerPolicy.REFERRER_POLICY_DEFAULT) }
                }
                spec.userAgent?.let { request.setHeaderByName("User-Agent", it, true) }
                val headers = HashMap<String, String>()
                request.getHeaderMap(headers)
                headers["User-Agent"]?.let { if (observedUserAgent == null) observedUserAgent = it }
                val captured = CapturedRequest(
                    url = request.url,
                    method = request.method ?: "GET",
                    headers = headers,
                    isMainFrame = request.resourceType == CefRequest.ResourceType.RT_MAIN_FRAME,
                )
                return when (runCatching { listener(captured) }.getOrDefault(Verdict.CONTINUE)) {
                    Verdict.CONTINUE -> false
                    Verdict.BLOCK, Verdict.STOP -> true
                }
            }
        }

        private val evalIds = java.util.concurrent.atomic.AtomicLong()
        private val pendingEvals = java.util.concurrent.ConcurrentHashMap<Long, (String?) -> Unit>()

        override fun loadUrl(url: String) {
            if (closed.get()) return
            onEdt { runCatching { browser?.loadURL(url) } }
        }

        override fun evaluate(js: String, onResult: ((String?) -> Unit)?) {
            val b = browser
            if (closed.get() || b == null) { onResult?.invoke(null); return }
            val id = evalIds.incrementAndGet()
            if (onResult != null) pendingEvals[id] = onResult
            // Straight to the renderer (CEF allows any thread). An EDT hop here made scripts a plugin injects from
            // onPageStarted (a plugin's crypto/fetch hooks) run after the page's own scripts, so they caught nothing.
            runCatching { b.mainFrame?.executeJavaScript(evalWrapper(js, id), b.url ?: "", 0) }
                .onFailure { onEdt { runCatching { b.mainFrame?.executeJavaScript(evalWrapper(js, id), b.url ?: "", 0) } } }
        }

        override fun mouse(action: Int, x: Int, y: Int) {
            if (closed.get()) return
            (browser as? org.cef.browser.SwingOsrBrowser)?.let { b -> onEdt { runCatching { b.injectMouse(action, x, y) } } }
        }

        /**
         * Shown only when the extension itself puts its WebView on screen (e.g. inside a dialog it shows), as on
         * Android; hidden again when it takes it away. Never on a timer or a guess.
         */
        override fun setVisible(visible: Boolean) {
            if (closed.get()) return
            onEdt {
                val f = frame
                when {
                    visible && f != null -> { (browser as? org.cef.browser.SwingOsrBrowser)?.setPainting(true); f.isVisible = true; bringToFront(f) }
                    visible -> browser?.let { showInWindow(it) } // same browser, same page, now in a window
                    f != null -> { f.isVisible = false; (browser as? org.cef.browser.SwingOsrBrowser)?.setPainting(false) }
                }
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            onEdt { teardown() }
        }

        private fun teardown() {
            runCatching { browser?.stopLoad() }
            runCatching { browser?.close(true) }
            runCatching { router?.dispose() }
            frame?.let { f -> runCatching { f.isVisible = false; f.dispose() } }
            val c = client
            browser = null; client = null; router = null; frame = null; inWindow = false
            // CefClient must outlive browser close; dispose it shortly after.
            if (c != null) Thread { Thread.sleep(500); runCatching { c.dispose() } }.apply { isDaemon = true }.start()
        }
    }

    /**
     * Sets [ua] for one browser through DevTools (`Emulation.setUserAgentOverride`), with client-hint metadata derived
     * from it so `Sec-CH-UA*` headers and `navigator.userAgentData` agree with the string. Completes either way.
     */
    private fun applyUserAgent(browser: CefBrowser, ua: String): java.util.concurrent.CompletableFuture<*> = try {
        val params = com.google.gson.JsonObject().apply {
            addProperty("userAgent", ua)
            addProperty("platform", navigatorPlatform(ua))
            userAgentMetadata(ua)?.let { add("userAgentMetadata", it) }
        }
        browser.devToolsClient.executeDevToolsMethod("Emulation.setUserAgentOverride", params.toString())
            .handle { _, t -> if (t != null) dbg("UA override failed: $t"); null }
    } catch (t: Throwable) {
        dbg("UA override failed: $t"); java.util.concurrent.CompletableFuture.completedFuture(null)
    }

    private fun navigatorPlatform(ua: String) = when {
        "Android" in ua -> "Linux armv81"
        "Windows" in ua -> "Win32"
        "Mac OS X" in ua -> "MacIntel"
        "aarch64" in ua -> "Linux aarch64"
        else -> "Linux x86_64"
    }

    /** Chrome-style client hints for a Chrome [ua]; null for other browsers (they send none). */
    internal fun userAgentMetadata(ua: String): com.google.gson.JsonObject? {
        val m = Regex("""Chrome/(\d+)((?:\.\d+)*)""").find(ua) ?: return null
        val major = m.groupValues[1]
        val full = major + m.groupValues[2].ifEmpty { ".0.0.0" }
        fun brands(v: (String) -> String) = com.google.gson.JsonArray().apply {
            for ((b, ver) in listOf("Chromium" to v(major), "Google Chrome" to v(major), "Not_A Brand" to "24")) {
                add(com.google.gson.JsonObject().apply { addProperty("brand", b); addProperty("version", ver) })
            }
        }
        val android = "Android" in ua
        return com.google.gson.JsonObject().apply {
            add("brands", brands { it })
            add("fullVersionList", brands { if (it == major) full else it })
            addProperty("fullVersion", full)
            addProperty("platform", when { android -> "Android"; "Windows" in ua -> "Windows"; "Mac OS X" in ua -> "macOS"; else -> "Linux" })
            addProperty("platformVersion", Regex("""Android (\d+(?:\.\d+)*)""").find(ua)?.groupValues?.get(1)?.let { "$it.0.0".split('.').take(3).joinToString(".") }
                ?: if ("Windows" in ua) "10.0.0" else "")
            addProperty("architecture", if (android) "" else if ("aarch64" in ua || "arm" in System.getProperty("os.arch").orEmpty().lowercase()) "arm" else "x86")
            addProperty("model", if (android) Regex("""Android [^;)]*; ([^;)]+)""").find(ua)?.groupValues?.get(1)?.trim().orEmpty() else "")
            addProperty("mobile", "Mobile" in ua)
            addProperty("bitness", if (android) "" else "64")
            addProperty("wow64", false)
        }
    }

    /**
     * Windows keeps a new window from a background process behind the active one (it opened under the browser /
     * the app and could not be clicked). Briefly on top, then a normal window again, with keyboard focus.
     */
    private fun bringToFront(f: JFrame) {
        f.extendedState = f.extendedState and java.awt.Frame.ICONIFIED.inv()
        f.isAlwaysOnTop = true
        f.toFront(); f.requestFocus()
        javax.swing.Timer(600) { f.isAlwaysOnTop = false; f.toFront() }.apply { isRepeats = false; start() }
    }

    private fun dbg(msg: String) {
        if (System.getProperty("cloudstream.jcef.debug") != null) println("[jcef-debug] $msg")
    }

    private fun onEdt(block: () -> Unit) {
        val safe = Runnable {
            try { block() } catch (t: Throwable) { System.out.println("[jcef] EDT task failed: $t"); t.printStackTrace(System.out) }
        }
        if (SwingUtilities.isEventDispatchThread()) safe.run() else SwingUtilities.invokeLater(safe)
    }

    /** A JSON string literal is a valid JS string literal (Gson also escapes U+2028/2029). */
    private fun jsLiteral(script: String): String = com.google.gson.Gson().toJson(script)

    private fun wrapScript(script: String): String =
        "(function(){var r=null;try{r=eval(${jsLiteral(script)});}catch(e){r=null;}" +
            "if(r===undefined)r=null;" +
            "window.cefQuery({request:JSON.stringify(r),onSuccess:function(){},onFailure:function(){}});})();"

    /** Result goes back tagged with [id]; value is JSON-encoded like Android's `evaluateJavascript`. */
    private fun evalWrapper(js: String, id: Long): String =
        "(function(){var r=null;try{r=(0,eval)(${jsLiteral(js)});}catch(e){r=null;}" +
            "if(r===undefined)r=null;var s;try{s=JSON.stringify(r);}catch(e){s='null';}" +
            "window.cefQuery({request:JSON.stringify({__eval:$id,r:s}),onSuccess:function(){},onFailure:function(){}});})();"
}
