package cloudstream.desktop.app

import cloudstream.desktop.contracts.ResumePoint
import cloudstream.desktop.contracts.isWatchedAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EpisodeFlowTest {
    private val min = 60_000L

    @Test fun `leaving in the credits counts as watched`() {
        assertTrue(isWatchedAt(22 * min, 24 * min))           // 24 min episode: last 2.4 min
        assertFalse(isWatchedAt(21 * min, 24 * min))
        assertTrue(isWatchedAt(41 * min + 30_000, 45 * min))  // 45 min: last 4 min
        assertFalse(isWatchedAt(40 * min, 45 * min))
        assertTrue(isWatchedAt(115 * min, 120 * min))         // 2 h film: last 6 min
        assertFalse(isWatchedAt(110 * min, 120 * min))
        assertFalse(isWatchedAt(0, 0))
    }

    @Test fun `a watched episode starts again from the beginning, an unfinished one resumes`() {
        assertEquals(0, ResumePoint("e", 23 * min, 24 * min, 0).startMs)
        assertEquals(10 * min, ResumePoint("e", 10 * min, 24 * min, 0).startMs)
        assertTrue(ResumePoint.markedWatched("e", 0).watched)
        assertTrue(ResumePoint("e", 0, 24 * min, 0).watched, "finished in 1.0.0 (saved as position 0)")
        assertEquals(0, ResumePoint("e", 0, 24 * min, 0).startMs)
        assertFalse(ResumePoint.cleared("e", 0).watched)
    }

    @Test fun `chapters named like an intro or credits become skip segments`() {
        val ch = listOf(0.0 to "Prologue", 90.0 to "Opening", 180.0 to "Part A", 1300.0 to "Ending", 1390.0 to "Preview")
        val segs = SkipTimes.fromChapters(ch, 1420_000)
        assertEquals(listOf(SkipSegment.Kind.Recap, SkipSegment.Kind.Intro, SkipSegment.Kind.Credits), segs.map { it.kind })
        assertEquals(SkipSegment(SkipSegment.Kind.Intro, 90_000, 180_000), segs[1])
        // "Operation" / "Edward" are not intros or endings.
        assertEquals(emptyList(), SkipTimes.fromChapters(listOf(0.0 to "Operation", 60.0 to "Edward", 600.0 to "Chapter 3"), 900_000))
    }

    @Test fun `upcoming episodes show a countdown instead of the word`() {
        assertEquals("Episode 6", cloudstream.desktop.app.ui.withoutUpcoming("[Upcoming] Episode 6"))
        assertEquals("", cloudstream.desktop.app.ui.withoutUpcoming("Upcoming"))
        assertEquals("The Return", cloudstream.desktop.app.ui.withoutUpcoming("Coming soon: The Return"))
        assertEquals("Upcomingness", cloudstream.desktop.app.ui.withoutUpcoming("Upcomingness"))
        val now = 1_000_000_000_000L
        assertEquals("in 2d 4h 13m", cloudstream.desktop.app.ui.countdownText(now + ((2 * 24 + 4) * 3600 + 13 * 60) * 1000L, now))
        assertEquals("in 04:13:22", cloudstream.desktop.app.ui.countdownText(now + (4 * 3600 + 13 * 60 + 22) * 1000L, now))
        assertEquals("now", cloudstream.desktop.app.ui.countdownText(now - 5000, now))
        assertEquals(1_700_000_000_000L, cloudstream.desktop.app.domain.epochMs(1_700_000_000L))
        assertEquals(1_700_000_000_000L, cloudstream.desktop.app.domain.epochMs(1_700_000_000_000L))
    }

    @Test fun `ids a plugin wrapped in JSON are unpacked`() {
        val n = cloudstream.desktop.app.domain.normalizeSyncIds(listOf("mal" to "{\"AniList\":\"172192\"}", "imdb" to "tt123"))
        assertEquals(mapOf("anilist" to "172192", "imdb" to "tt123"), n)
        val both = cloudstream.desktop.app.domain.normalizeSyncIds(listOf("anilist" to "5", "mal" to "{\"MAL\":\"9\",\"AniList\":\"7\"}"))
        assertEquals(mapOf("anilist" to "5", "mal" to "9"), both)
    }

    @Test fun `chapters win over online times of the same kind`() {
        val chapters = listOf(SkipSegment(SkipSegment.Kind.Intro, 90_000, 180_000))
        val online = listOf(SkipSegment(SkipSegment.Kind.Intro, 95_000, 185_000), SkipSegment(SkipSegment.Kind.Credits, 1300_000, 1390_000))
        assertEquals(listOf(chapters[0], online[1]), SkipTimes.merge(chapters, online))
    }
}
