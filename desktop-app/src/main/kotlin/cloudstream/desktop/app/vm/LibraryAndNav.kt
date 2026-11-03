package cloudstream.desktop.app.vm

import cloudstream.desktop.app.domain.EpisodeItem
import cloudstream.desktop.app.domain.MediaCard
import cloudstream.desktop.app.domain.SourceLink
import cloudstream.desktop.app.domain.SubtitleItem
import cloudstream.desktop.contracts.Bookmark
import cloudstream.desktop.contracts.HistoryEntry
import cloudstream.desktop.contracts.LibraryStore
import cloudstream.desktop.contracts.WatchStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class LibraryState(val bookmarks: List<Bookmark> = emptyList(), val history: List<HistoryEntry> = emptyList()) {
    fun statusOf(card: MediaCard): WatchStatus? = bookmarks.firstOrNull { it.id == titleId(card.provider, card.url) }?.status
}

class LibraryViewModel(private val storeOf: () -> LibraryStore, private val now: () -> Long = System::currentTimeMillis) {
    constructor(store: LibraryStore) : this({ store })
    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    fun refresh() { val store = storeOf(); _state.value = LibraryState(store.bookmarks(), store.history().take(50)) }
    fun removeBookmark(id: String) { storeOf().removeBookmark(id); refresh() }
    fun setStatus(card: MediaCard, status: WatchStatus?) { setLibraryStatus(storeOf(), card, status, now()); refresh() }
    /** CloudStream's "Remove" on a Continue Watching item: the title leaves that row (resume points stay). */
    fun removeFromContinueWatching(card: MediaCard) { storeOf().removeHistory(titleId(card.provider, card.url)); refresh() }
}

/**
 * Library write shared by every entry point: a new status keeps the title's original "added" time; null removes it.
 * [name]/[poster]/[type] (from loaded details) win over the card's when given.
 */
fun setLibraryStatus(
    store: LibraryStore, card: MediaCard, status: WatchStatus?, now: Long,
    name: String? = null, poster: String? = null, type: String? = null,
) {
    val id = titleId(card.provider, card.url)
    if (status == null) { store.removeBookmark(id); return }
    val old = store.bookmarks().firstOrNull { it.id == id }
    store.setBookmark(
        Bookmark(id, name ?: old?.name ?: card.name, card.provider, card.url, poster ?: old?.posterUrl ?: card.posterUrl,
            type ?: old?.type ?: card.type, old?.addedAt ?: now, status),
    )
}

fun Bookmark.toCard() = MediaCard(apiName, name, url, posterUrl, type)
fun HistoryEntry.toCard() = MediaCard(apiName, name, url, posterUrl, null)

/** What the player screen needs to start. */
data class PlayRequest(
    val card: MediaCard,
    val titleName: String,
    val episode: EpisodeItem,
    /** null: the player opens at once and shows the source loading screen (Skip loading), like CloudStream. */
    val link: SourceLink?,
    val subtitles: List<SubtitleItem>,
    val startMs: Long,
)

sealed interface Screen {
    data object Home : Screen
    data object Search : Screen
    data object Library : Screen
    data object Extensions : Screen
    data object Settings : Screen
    data class Details(val card: MediaCard) : Screen
    data class Player(val request: PlayRequest) : Screen

    val isTopLevel: Boolean get() = this is Home || this is Search || this is Library || this is Extensions || this is Settings
}

/** Back stack navigation. Top-level destinations replace the whole stack. */
class Navigator(start: Screen = Screen.Home) {
    private val _stack = MutableStateFlow(listOf(start))
    val stack: StateFlow<List<Screen>> = _stack.asStateFlow()
    val current: Screen get() = _stack.value.last()

    fun goTo(top: Screen) { require(top.isTopLevel); _stack.value = listOf(top) }
    fun push(s: Screen) = _stack.update { it + s }
    fun back(): Boolean {
        if (_stack.value.size <= 1) return false
        _stack.update { it.dropLast(1) }
        return true
    }
    /** The top-level screen under the current stack (for highlighting the rail). */
    val root: Screen get() = _stack.value.first()
}
