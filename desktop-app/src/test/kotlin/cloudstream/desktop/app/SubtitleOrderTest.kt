package cloudstream.desktop.app

import cloudstream.desktop.app.domain.SubtitleItem
import cloudstream.desktop.app.ui.sortedSubtitles
import kotlin.test.Test
import kotlin.test.assertEquals

class SubtitleOrderTest {
    private fun s(lang: String) = SubtitleItem(lang, "https://subs/$lang", emptyMap())

    @Test fun `only exactly same-named subtitles share a numbered row`() {
        val groups = cloudstream.desktop.app.ui.groupSameNamed(listOf(
            "English" to "u1", "english " to "u2", "English 2" to "u3", "English (SDH)" to "u4", "English" to "u5", "French" to "u6",
        ))
        assertEquals(listOf("English" to listOf("u1", "u2", "u5"), "English 2" to listOf("u3"), "English (SDH)" to listOf("u4"), "French" to listOf("u6")),
            groups.map { it.name to it.keys })
    }

    @Test fun `subtitles are listed by language, case-insensitive, numbers in natural order`() {
        val out = sortedSubtitles(listOf(s("spanish"), s("English 10"), s("Arabic"), s("English 2"), s("english"), s("French")))
        assertEquals(listOf("Arabic", "english", "English 2", "English 10", "French", "spanish"), out.map { it.lang })
    }
}
