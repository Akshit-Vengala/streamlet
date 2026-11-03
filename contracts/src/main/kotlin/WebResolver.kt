package cloudstream.desktop.contracts

/** Track E implements this (JCEF). */
interface HeadlessBrowser {
    /** Load [url], intercept requests matching [interceptRegex], return the first match + cookies. */
    suspend fun resolve(url: String, interceptRegex: Regex, headers: Map<String, String>, timeoutMs: Long): ResolveResult

    /** Solve a Cloudflare challenge; returns cookies + UA usable by NiceHttp. */
    suspend fun clearance(url: String, timeoutMs: Long): Clearance?
}

data class ResolveResult(val matchedUrl: String?, val cookies: Map<String, String>, val userAgent: String)
data class Clearance(val cookies: Map<String, String>, val userAgent: String)
