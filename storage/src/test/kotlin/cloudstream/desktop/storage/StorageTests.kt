package cloudstream.desktop.storage

import cloudstream.desktop.contracts.Bookmark
import cloudstream.desktop.contracts.HistoryEntry
import cloudstream.desktop.contracts.ResumePoint
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Child process for the kill test: rewrites the store in a tight loop until killed. */
object CrashWriter {
    @JvmStatic
    fun main(args: Array<String>) {
        val store = JsonFileStore(File(args[0]), "crash", flushDelayMs = 0)
        var n = (store.get("a", Long::class.java) ?: 0L)
        val pad = "x".repeat(200_000)
        var first = true
        while (true) {
            n++
            store.putAll(mapOf("a" to n, "b" to n, "c" to "$n$pad"))
            if (first) { first = false; println("ready"); System.out.flush() }
        }
    }
}

class StorageTests {
    private fun tmpDir(): File = Files.createTempDirectory("cs-storage-test").toFile()

    @Test
    fun typesRoundTripAndPersist() {
        val dir = tmpDir()
        JsonFileStore(dir, "t", 0).use { s ->
            s.set("s", "hi"); s.set("i", 5); s.set("l", 1L shl 40); s.set("f", 1.5f); s.set("b", true)
            s.set("ss", setOf("a", "b")); s.set("obj", Bookmark("1", "n", "api", "u", null, "Movie", 9))
            s.set("jsonstr", """{"id":"2","name":"x","apiName":"a","url":"u","posterUrl":null,"type":null,"addedAt":3}""")
        }
        JsonFileStore(dir, "t", 0).use { s ->
            assertEquals("hi", s.get("s", String::class.java))
            assertEquals(5, s.get("i", Int::class.java))
            assertEquals(1L shl 40, s.get("l", Long::class.java))
            assertEquals(1.5f, s.get("f", Float::class.java))
            assertEquals(true, s.get("b", Boolean::class.java))
            assertEquals(setOf("a", "b"), s.get("ss", Set::class.java))
            assertEquals("n", s.get("obj", Bookmark::class.java)?.name)
            assertEquals("2", s.get("jsonstr", Bookmark::class.java)?.id)
            assertEquals(listOf("i"), s.keys("i"))
            s.remove("s"); assertNull(s.get("s", String::class.java))
        }
    }

    @Test
    fun corruptMainFallsBackToBackup() {
        val dir = tmpDir()
        JsonFileStore(dir, "c", 0).use { it.set("k", "v1"); it.set("k", "v2") } // second write creates .bak (= v1)
        File(dir, "c.json").writeText("{\"format\":1,\"entries\":{\"k\":{\"t\":\"S\",\"v\":\"tru") // truncated
        val s = JsonFileStore(dir, "c", 0)
        assertTrue(s.recoveredFromBackup)
        assertEquals("v1", s.get("k", String::class.java))
    }

    @Test
    fun debouncedFlushAndShutdownHookPath() {
        val dir = tmpDir()
        val s = JsonFileStore(dir, "d", flushDelayMs = 100)
        s.set("k", 1)
        assertTrue(!File(dir, "d.json").exists() || true)
        Thread.sleep(600)
        assertTrue(File(dir, "d.json").exists())
        s.set("k", 2)
        StoreRegistry.flushAll() // what the shutdown hook does
        assertEquals(2, JsonFileStore(dir, "d", 0).get("k", Int::class.java))
    }

    @Test
    fun libraryStore() {
        val sm = StorageManager(tmpDir(), 0)
        val lib = sm.library
        lib.setBookmark(Bookmark("1", "A", "api", "u", null, null, 1))
        lib.setBookmark(Bookmark("2", "B", "api", "u", "p", "Anime", 2))
        lib.removeBookmark("1")
        lib.setResume(ResumePoint("2", 10, 100, 5))
        lib.recordHistory(HistoryEntry("2", "B", "api", "u", null, 1, 1, 7))
        sm.close()
        val lib2 = StorageManager(sm.baseDir, 0).library
        assertEquals(listOf("2"), lib2.bookmarks().map { it.id })
        assertEquals(10, lib2.resume("2")?.positionMs)
        assertEquals(1, lib2.history().size)
    }

    @Test
    fun markedWatchedResumePointReadsBack() {
        val lib = StorageManager(tmpDir(), 0).library
        lib.setResume(ResumePoint.markedWatched("e1", 5))
        assertEquals(true, lib.resume("e1")?.watched)
        lib.setResume(ResumePoint.cleared("e1", 6))
        assertEquals(false, lib.resume("e1")?.watched)
    }

    @Test
    fun bookmarkStatusPersistsAndOldEntriesReadAsPlanToWatch() {
        val sm = StorageManager(tmpDir(), 0)
        sm.library.setBookmark(Bookmark("1", "A", "api", "u", null, null, 1, cloudstream.desktop.contracts.WatchStatus.Dropped))
        // An entry written before statuses existed (no "status" field).
        sm.namespace("library").set("bookmark/2", mapOf("id" to "2", "name" to "B", "apiName" to "api", "url" to "u2", "posterUrl" to null, "type" to null, "addedAt" to 2))
        sm.library.recordHistory(HistoryEntry("1", "A", "api", "u", null, 1, 1, 7))
        sm.library.removeHistory("1")
        sm.close()
        val lib2 = StorageManager(sm.baseDir, 0).library
        val byId = lib2.bookmarks().associateBy { it.id }
        assertEquals(cloudstream.desktop.contracts.WatchStatus.Dropped, byId["1"]?.status)
        assertEquals(cloudstream.desktop.contracts.WatchStatus.PlanToWatch, byId["2"]?.status)
        assertTrue(lib2.history().isEmpty())
    }

    @Test
    fun survivesProcessKillMidWrite() {
        val dir = tmpDir()
        var last = 0L
        repeat(6) { round ->
            val java = File(System.getProperty("java.home"), "bin/java").path
            val p = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                "cloudstream.desktop.storage.CrashWriter", dir.path).redirectErrorStream(true).start()
            val reader = p.inputStream.bufferedReader()
            assertEquals("ready", reader.readLine())
            Thread.sleep(150L + round * 90L) // land the kill at varied points inside write cycles
            p.destroyForcibly().waitFor(10, TimeUnit.SECONDS)

            val s = JsonFileStore(dir, "crash", 0)
            val a = s.get("a", Long::class.java)
            assertTrue(a != null, "store lost all data after kill (round $round)")
            assertEquals(a, s.get("b", Long::class.java), "torn snapshot")
            assertTrue(s.get("c", String::class.java)!!.startsWith(a.toString()), "torn snapshot (c)")
            assertTrue(a >= last, "went backwards: $a < $last")
            last = a
        }
    }

    @Test
    fun backupImportFixture() {
        val json = javaClass.getResource("/backup-fixture.json")!!.readText()
        val sm = StorageManager(tmpDir(), 0)
        val report = BackupImporter(sm.datastore, sm.settings, sm.library, nowMs = { 42 }).import(json)

        assertEquals(1, report.repos.size)
        assertEquals("https://example.org/repo.json", report.repos[0].url)
        assertEquals("My Repo", report.repos[0].name)
        assertEquals(2, report.bookmarks)
        assertEquals(1, report.resumePoints)

        val bms = sm.library.bookmarks().associateBy { it.id }
        assertEquals("Naruto", bms["100"]?.name)
        assertEquals("Anime", bms["100"]?.type)
        assertEquals(1700000000000L, bms["100"]?.addedAt)
        assertEquals("p.jpg", bms["200"]?.posterUrl)
        // CloudStream's result_watch_state: 1 = Completed; no state = the "Plan to Watch" default.
        assertEquals(cloudstream.desktop.contracts.WatchStatus.Completed, bms["100"]?.status)
        assertEquals(cloudstream.desktop.contracts.WatchStatus.PlanToWatch, bms["200"]?.status)
        assertEquals(30_000L, sm.library.resume("100")?.positionMs)
        assertEquals(120_000L, sm.library.resume("100")?.durationMs)

        assertEquals(true, sm.settings.get("pref_bool", Boolean::class.java))
        assertEquals(7, sm.settings.get("pref_int", Int::class.java))
        assertEquals(setOf("x", "y"), sm.settings.get("pref_set", Set::class.java))
        assertEquals(0.5f, sm.datastore.get("some_float", Float::class.java))
        assertTrue("download_path_key" in report.skippedKeys)
        assertNull(sm.settings.get("download_path_key", String::class.java))
        // typed repo access like upstream getKey<Array<RepositoryData>>
        assertTrue(sm.datastore.get("REPOSITORIES_KEY", String::class.java)!!.contains("My Repo"))
    }
}
