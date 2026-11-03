package cloudstream.desktop.app

import cloudstream.desktop.app.domain.MediaCard
import cloudstream.desktop.app.vm.LibraryViewModel
import cloudstream.desktop.app.vm.titleId
import cloudstream.desktop.contracts.HistoryEntry
import cloudstream.desktop.contracts.WatchStatus
import cloudstream.desktop.contracts.fakes.InMemoryLibraryStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchStatusTest {
    private val card = MediaCard("Demo", "Some Show", "https://demo/show", "https://demo/p.jpg", "TvSeries")

    @Test fun `right-click statuses add, change and remove a title, keeping when it was added`() {
        var now = 1000L
        val store = InMemoryLibraryStore()
        val vm = LibraryViewModel({ store }, { now })
        vm.setStatus(card, WatchStatus.PlanToWatch)
        assertEquals(WatchStatus.PlanToWatch, vm.state.value.statusOf(card))
        now = 5000L
        vm.setStatus(card, WatchStatus.Completed)
        val b = store.bookmarks().single()
        assertEquals(WatchStatus.Completed, b.status)
        assertEquals(1000L, b.addedAt)
        vm.setStatus(card, null)
        assertNull(vm.state.value.statusOf(card))
        assertTrue(store.bookmarks().isEmpty())
    }

    @Test fun `remove from Continue Watching drops only that title`() {
        val store = object : cloudstream.desktop.contracts.LibraryStore by InMemoryLibraryStore() {
            val h = mutableListOf(
                HistoryEntry(titleId(card.provider, card.url), card.name, card.provider, card.url, null, 1, 1, 2),
                HistoryEntry("Demo|other", "Other", "Demo", "other", null, null, null, 1),
            )
            override fun history() = h.toList()
            override fun removeHistory(id: String) { h.removeAll { it.id == id } }
        }
        val vm = LibraryViewModel(store)
        vm.removeFromContinueWatching(card)
        assertEquals(listOf("Other"), vm.state.value.history.map { it.name })
    }
}
