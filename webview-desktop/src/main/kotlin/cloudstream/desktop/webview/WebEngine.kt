package cloudstream.desktop.webview

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** One request observed by the embedded browser. */
data class CapturedRequest(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    /** True for the top-level navigation (and its redirects). */
    val isMainFrame: Boolean = false,
)

/** What the engine should do with a request it just observed. */
enum class Verdict {
    /** Let the request through. */
    CONTINUE,

    /** Cancel just this request (used for images/fonts/etc, like the Android resolver's blacklist). */
    BLOCK,

    /** We have what we need: stop loading and close the browser. */
    STOP,
}

/** Everything the engine needs to start one page load. */
data class BrowseSpec(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val referer: String? = null,
    /** null = keep the browser's real user agent (required for Cloudflare). */
    val userAgent: String? = null,
    /** JS evaluated after every page load; its JSON-encoded result goes to [onScriptResult]. */
    val script: String? = null,
    val onScriptResult: ((String) -> Unit)? = null,
    /** Show a real window from the start (interactive challenge). */
    val visible: Boolean = false,
    /** Main-frame load started / finished (Android `WebViewClient.onPageStarted/onPageFinished`). */
    val onPageStarted: ((String) -> Unit)? = null,
    val onPageFinished: ((String) -> Unit)? = null,
    /**
     * Page script -> host channel: any `window.cefQuery({request: JSON.stringify({__wv: 1, ...})})` message is
     * delivered here as its JSON string (used for `addJavascriptInterface`).
     */
    val onBridge: ((String) -> Unit)? = null,
)

/** A running page load. */
interface BrowseHandle {
    fun setVisible(visible: Boolean)
    fun close()

    /** Navigate the same browser to [url] (after the initial page). */
    fun loadUrl(url: String) {}

    /** Evaluate [js] in the main frame; [onResult] gets the JSON-encoded result (`"null"` for none/undefined). */
    fun evaluate(js: String, onResult: ((String?) -> Unit)? = null) { onResult?.invoke(null) }

    /** Mouse input from code: [action] 0 = down, 1 = up, 2 = move, at view coordinates (CSS px). */
    fun mouse(action: Int, x: Int, y: Int) {}
}

/**
 * The browser backend. [JcefEngine] is the real one; tests use a fake so nothing
 * here needs CEF unless explicitly opted in.
 */
interface WebEngine {
    /** Real user agent of this browser (best effort before the first page load). */
    val userAgent: String

    fun open(spec: BrowseSpec, onRequest: (CapturedRequest) -> Verdict): BrowseHandle

    /** All cookies the browser would send to [url] (including HttpOnly). */
    suspend fun cookies(url: String): Map<String, String>

    suspend fun clearCookies()
}

/** Result of [runBrowseSession]. */
class SessionResult(val timedOut: Boolean, val requests: List<CapturedRequest>)

/**
 * Opens [spec], feeds requests to [onRequest] (which may STOP the session), and optionally polls
 * [poll] every [pollIntervalMs]; a true result ends the session. Always closes the browser.
 * [showAfterMs] reveals the window if the session is still running after that long (challenge
 * that needs a human).
 */
suspend fun runBrowseSession(
    engine: WebEngine,
    spec: BrowseSpec,
    timeoutMs: Long,
    pollIntervalMs: Long = 250,
    showAfterMs: Long? = null,
    poll: (suspend () -> Boolean)? = null,
    onRequest: (CapturedRequest) -> Verdict,
): SessionResult {
    val done = CompletableDeferred<Unit>()
    val seen = java.util.Collections.synchronizedList(mutableListOf<CapturedRequest>())
    val handle = engine.open(spec) { req ->
        seen += req
        val v = onRequest(req)
        if (v == Verdict.STOP) done.complete(Unit)
        v
    }
    try {
        val finished = withTimeoutOrNull(timeoutMs) {
            var elapsed = 0L
            var shown = spec.visible
            while (!done.isCompleted) {
                if (poll != null && poll()) break
                if (showAfterMs != null && !shown && elapsed >= showAfterMs) {
                    handle.setVisible(true); shown = true
                }
                delay(pollIntervalMs)
                elapsed += pollIntervalMs
            }
            true
        }
        return SessionResult(timedOut = finished == null, requests = synchronized(seen) { seen.toList() })
    } finally {
        runCatching { handle.close() }
    }
}
