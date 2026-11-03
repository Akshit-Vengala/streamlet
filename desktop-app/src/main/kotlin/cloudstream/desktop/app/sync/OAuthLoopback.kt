package cloudstream.desktop.app.sync

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder

/**
 * One-shot OAuth redirect receiver on http://localhost:[PORT]/callback (register exactly this URL as the redirect URI
 * of your AniList / MAL app). Handles both query-string codes (MAL) and URL-fragment tokens (AniList implicit grant:
 * the fragment never reaches a server, so a tiny page posts it back to /fragment).
 */
object OAuthLoopback {
    const val PORT = 47811
    const val REDIRECT_URI = "http://localhost:$PORT/callback"

    private const val PAGE_DONE = "<html><body style='background:#0b0b0e;color:#eee;font-family:sans-serif;text-align:center;padding-top:80px'>" +
        "<h2>Signed in. You can close this tab and go back to Streamlet.</h2></body></html>"
    private const val PAGE_FRAGMENT = "<html><body style='background:#0b0b0e;color:#eee;font-family:sans-serif'><script>" +
        "var h=location.hash.substring(1);if(h){fetch('/fragment?'+h).then(function(){document.body.innerHTML=" +
        "'<h2 style=\"text-align:center;padding-top:80px\">Signed in. You can close this tab.</h2>'})}else{document.body.innerHTML='<h2>Waiting...</h2>'}" +
        "</script></body></html>"

    /** Starts the listener, runs [openBrowser], and returns the callback parameters (query or fragment). */
    suspend fun await(timeoutMs: Long = 5 * 60_000L, openBrowser: () -> Unit): Map<String, String> {
        val result = CompletableDeferred<Map<String, String>>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), 0)
        fun parse(q: String?) = q.orEmpty().split('&').filter { '=' in it }
            .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
        fun reply(ex: com.sun.net.httpserver.HttpExchange, html: String) {
            val b = html.toByteArray(); ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        server.createContext("/callback") { ex ->
            val q = parse(ex.requestURI.rawQuery)
            if (q.containsKey("code") || q.containsKey("error")) { reply(ex, PAGE_DONE); result.complete(q) }
            else reply(ex, PAGE_FRAGMENT) // implicit grant: token is in the fragment
        }
        server.createContext("/fragment") { ex -> reply(ex, "ok"); result.complete(parse(ex.requestURI.rawQuery)) }
        server.start()
        try {
            openBrowser()
            return withTimeout(timeoutMs) { result.await() }
        } finally { server.stop(0) }
    }
}
