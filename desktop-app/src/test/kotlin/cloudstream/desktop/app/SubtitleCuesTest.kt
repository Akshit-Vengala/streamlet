package cloudstream.desktop.app

import cloudstream.desktop.app.ui.SubCue
import cloudstream.desktop.app.ui.activeCueIndex
import cloudstream.desktop.app.ui.nearestCueIndex
import cloudstream.desktop.app.ui.parseSubtitleCues
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubtitleCuesTest {
    @Test fun `srt lines are parsed with times and tags removed`() {
        val srt = "﻿1\r\n00:00:01,500 --> 00:00:03,000\r\n<i>How...</i>\r\n\r\n2\r\n00:00:04,000 --> 00:00:06,250\r\nHow will\r\nthey?\r\n"
        assertEquals(listOf(SubCue(1500, 3000, "How..."), SubCue(4000, 6250, "How will\nthey?")), parseSubtitleCues(srt))
    }

    @Test fun `vtt short timestamps and cue settings work`() {
        val vtt = "WEBVTT\n\nintro\n00:05.000 --> 00:07.500 align:start\n<v Bob>Come closer.\n\n01:00:00.000 --> 01:00:01.000\nCloser."
        assertEquals(listOf(SubCue(5000, 7500, "Come closer."), SubCue(3_600_000, 3_601_000, "Closer.")), parseSubtitleCues(vtt))
    }

    @Test fun `ass dialogue keeps commas in text and drops override tags`() {
        val ass = "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:00:10.50,0:00:12.00,Default,,0,0,0,,{\\an8}Was there,\\Nany female ghosts?\n"
        assertEquals(listOf(SubCue(10_500, 12_000, "Was there,\nany female ghosts?")), parseSubtitleCues(ass))
    }

    @Test fun `the active line follows the time`() {
        val cues = listOf(SubCue(1000, 2000, "a"), SubCue(3000, 4000, "b"), SubCue(5000, 6000, "c"))
        assertNull(activeCueIndex(cues, 500))
        assertEquals(0, activeCueIndex(cues, 1500))
        assertNull(activeCueIndex(cues, 2500))
        assertEquals(1, nearestCueIndex(cues, 2500))
        assertEquals(2, activeCueIndex(cues, 5999))
        assertEquals(2, nearestCueIndex(cues, 9000))
    }
}
