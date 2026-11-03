package cloudstream.desktop.app.sync

import cloudstream.desktop.contracts.fakes.InMemoryKeyValueStore
import kotlinx.coroutines.runBlocking
import java.io.File

/** `--sync-probe <dir>`: live check of the services that work without an account (network). Prints results, exit code 0/1. */
object SyncProbe {
    fun run(dir: File): Int = runBlocking {
        val m = SyncManager(InMemoryKeyValueStore())
        var bad = 0
        suspend fun step(name: String, f: suspend () -> String) {
            runCatching { f() }.onSuccess { println("OK   $name: $it") }.onFailure { bad++; println("FAIL $name: $it") }
        }
        step("AniList find (title 'Frieren', 2023)") { m.trackers[0].find(MatchQuery("Frieren: Beyond Journey's End", 2023, true, false, emptyMap())) ?: error("no match") }
        step("AniList find via MAL id 52991") { m.trackers[0].find(MatchQuery("x", null, true, false, mapOf("mal" to "52991"))) ?: error("no match") }
        step("AniList -> MAL id (154587)") { AniListTracker.malIdFor("154587") ?: error("none") }
        val movie = SubQuery("Inception", "en", "tt1375666", null, null, null, 2010)
        val ep = SubQuery("Breaking Bad", "en", "tt0903747", null, 1, 1, 2008)
        for (s in m.subtitles.filter { !it.requiresLogin }) {
            for (q in listOf(movie, ep)) step("${s.name} search ${q.title}${q.season?.let { " S${it}E${q.episode}" } ?: ""}") {
                val r = s.search(q); "${r.size} results; first=${r.firstOrNull()?.name?.take(60)}"
            }
        }
        // Download one from a service that needs no login.
        for (s in m.subtitles.filter { it.loginKind == null }) step("${s.name} download") {
            val r = s.search(movie).firstOrNull() ?: error("no results")
            val f = s.download(r, File(dir, s.id)); "${f.name} (${f.length()} bytes): ${f.readText().take(60).replace('\n', ' ')}"
        }
        if (bad == 0) 0 else 1
    }
}
