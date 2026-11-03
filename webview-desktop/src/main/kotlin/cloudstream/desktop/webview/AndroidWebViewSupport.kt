package cloudstream.desktop.webview

import cloudstream.desktop.androidshim.WebViewBridge
import java.util.function.Consumer

/**
 * Backs the shimmed `android.webkit.WebView` with a real browser. Call [install] once at start-up (app, harness,
 * integration tools); until then plugin WebViews stay inert stubs.
 */
object AndroidWebViewSupport {
    fun install(engine: () -> WebEngine = { DesktopWebView.engine }) {
        WebViewBridge.setBackend(WebViewBridge.Backend { ua, events -> EnginePage(engine(), ua, events) })
    }

    /**
     * One plugin WebView. Like an Android WebView it is invisible (headless) unless the plugin itself puts it on screen
     * by showing a dialog that contains it (see the shim's Dialog / DialogFragment) - never on a timer or a guess.
     */
    private class EnginePage(
        private val engine: WebEngine,
        private val userAgent: String?,
        private val events: WebViewBridge.Events,
    ) : WebViewBridge.Page {
        @Volatile private var handle: BrowseHandle? = null

        @Synchronized
        override fun loadUrl(url: String, headers: Map<String, String>) {
            val h = handle
            if (h != null) { h.loadUrl(url); return }
            val opened = engine.open(
                BrowseSpec(
                    url = url,
                    headers = headers,
                    userAgent = userAgent?.takeIf { it.isNotBlank() },
                    onPageStarted = { events.onPageStarted(it) },
                    onPageFinished = { events.onPageFinished(it) },
                    onBridge = { events.onBridgeMessage(it) },
                ),
            ) { req ->
                if (events.onRequest(req.url, req.method, req.headers, req.isMainFrame)) Verdict.BLOCK else Verdict.CONTINUE
            }
            handle = opened
            if (wantVisible) opened.setVisible(true) // the plugin showed its dialog before the page was created
        }

        /** Set by the shim when the plugin shows/hides a dialog containing this WebView. */
        @Volatile private var wantVisible = false

        override fun evaluate(js: String, result: Consumer<String?>?) {
            val h = handle
            if (h == null) { result?.accept(null); return }
            h.evaluate(js, result?.let { c -> { r: String? -> c.accept(r) } })
        }

        override fun setVisible(visible: Boolean) { wantVisible = visible; handle?.setVisible(visible) }

        override fun mouse(action: Int, x: Float, y: Float) { handle?.mouse(action, x.toInt(), y.toInt()) }

        override fun stopLoading() {}

        @Synchronized
        override fun destroy() { handle?.close(); handle = null }
    }
}
