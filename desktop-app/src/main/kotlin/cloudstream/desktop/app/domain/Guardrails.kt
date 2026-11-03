package cloudstream.desktop.app.domain

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The limits CloudStream puts around extension calls (app/.../ui/APIRepository.kt), reproduced for the desktop app:
 *  - every provider call has a timeout: the provider's own value, default 2 min, clamped to [5 s, 8 min];
 *  - empty / "[]" / "about:blank" data is rejected before the plugin sees it;
 *  - load() results are cached for 10 minutes (rolling cache of 20);
 *  - providers with `sequentialMainPage` get home rows one at a time, `sequentialMainPageDelay` apart, and pagination
 *    waits `sequentialMainPageScrollDelay` after the previous home request.
 * Exceptions are isolated per call by the callers ([guarded]), as upstream's safeApiCall does.
 */
object Guardrails {
    private const val DEFAULT_TIMEOUT = 120_000L
    private const val MAX_TIMEOUT = 4 * DEFAULT_TIMEOUT
    private const val MIN_TIMEOUT = 5_000L

    fun timeout(desired: Long?): Long = (desired ?: DEFAULT_TIMEOUT).coerceIn(MIN_TIMEOUT, MAX_TIMEOUT)

    fun isInvalidData(data: String): Boolean = data.isEmpty() || data == "[]" || data == "about:blank"

    // ---- load cache (10 min, 20 entries, rolling)
    private class Saved(val at: Long, val key: Pair<String, String>, val response: LoadResponse)
    private const val CACHE_SIZE = 20
    private val cache = ArrayList<Saved>()
    private var cacheIndex = 0

    @Synchronized
    fun cachedLoad(api: String, url: String): LoadResponse? {
        val now = System.currentTimeMillis()
        return cache.firstOrNull { it.key == (api to url) && now - it.at < 10 * 60_000L }?.response
    }

    @Synchronized
    fun cacheLoad(api: String, url: String, r: LoadResponse) {
        val s = Saved(System.currentTimeMillis(), api to url, r)
        if (cache.size > CACHE_SIZE) { cache[cacheIndex] = s; cacheIndex = (cacheIndex + 1) % CACHE_SIZE } else cache += s
    }

    /** Dropped when plugins are reloaded (upstream clears it on afterPluginsLoadedEvent(forceReload = true)). */
    @Synchronized
    fun clearCache() { cache.clear(); cacheIndex = 0 }

    // ---- home page pacing
    private val homeLocks = HashMap<String, Mutex>()
    @Synchronized private fun lockFor(api: MainAPI) = homeLocks.getOrPut(api.name) { Mutex() }

    suspend fun <T> homeRequest(api: MainAPI, block: suspend () -> T): T {
        if (!api.sequentialMainPage) {
            waitForHomeDelay(api)
            api.lastHomepageRequest = System.currentTimeMillis()
            return block()
        }
        return lockFor(api).withLock {
            // One row at a time, spaced by the provider's delay (the first request goes straight through).
            val since = System.currentTimeMillis() - api.lastHomepageRequest
            if (api.lastHomepageRequest > 0 && since < api.sequentialMainPageDelay) delay(api.sequentialMainPageDelay - since)
            api.lastHomepageRequest = System.currentTimeMillis()
            block()
        }
    }

    private suspend fun waitForHomeDelay(api: MainAPI) {
        val delta = api.sequentialMainPageScrollDelay + api.lastHomepageRequest - System.currentTimeMillis()
        if (delta > 0) delay(delta)
    }
}
