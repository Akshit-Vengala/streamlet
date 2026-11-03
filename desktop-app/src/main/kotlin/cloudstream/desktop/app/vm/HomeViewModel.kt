package cloudstream.desktop.app.vm

import cloudstream.desktop.app.domain.MainPageSection
import cloudstream.desktop.app.domain.MediaCard
import cloudstream.desktop.app.domain.Outcome
import cloudstream.desktop.app.domain.Provider
import cloudstream.desktop.app.domain.guarded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class SectionState(
    val section: MainPageSection,
    val title: String = section.name,
    val items: List<MediaCard> = emptyList(),
    val page: Int = 0,
    val hasNext: Boolean = true,
    val loading: Boolean = false,
    val error: String? = null,
    val horizontal: Boolean = section.horizontal,
    val loadedOnce: Boolean = false,
)

data class HomeState(
    val provider: String? = null,
    val sections: List<SectionState> = emptyList(),
)

/**
 * Home: pick a provider, its `mainPage` sections load lazily (as they scroll into view) and each row paginates
 * (`getMainPage(page, request)`) when the user reaches its end.
 */
class HomeViewModel(
    private val scope: CoroutineScope,
    private val providers: () -> List<Provider>,
) {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()
    private val limiter = Semaphore(3)

    fun selectProvider(name: String?) {
        val p = providers().firstOrNull { it.name == name }
        _state.value = HomeState(name, p?.mainPageSections.orEmpty().map { SectionState(it) })
    }

    fun ensureLoaded(index: Int) {
        val s = _state.value.sections.getOrNull(index) ?: return
        if (!s.loadedOnce && !s.loading) loadMore(index)
    }

    fun loadMore(index: Int) {
        val st = _state.value
        val sec = st.sections.getOrNull(index) ?: return
        if (sec.loading || !sec.hasNext) return
        val p = providers().firstOrNull { it.name == st.provider } ?: return
        val provName = st.provider
        update(index) { it.copy(loading = true, error = null) }
        scope.launch {
            val nextPage = sec.page + 1
            val out = limiter.withPermit { guarded(p.loadTimeoutMs) { p.mainPage(sec.section, nextPage) } }
            if (_state.value.provider != provName) return@launch // user switched provider meanwhile
            when (out) {
                is Outcome.Ok -> {
                    val page = out.value
                    // Providers may return several lists for a request; merge them into this section.
                    val items = page?.rows?.flatMap { it.items }.orEmpty()
                    val title = page?.rows?.singleOrNull()?.name?.takeIf { it.isNotBlank() }
                    update(index) { cur ->
                        val merged = (cur.items + items).distinctBy { it.url }
                        cur.copy(
                            items = merged, page = nextPage, loading = false, loadedOnce = true,
                            hasNext = (page?.hasNext ?: false) && merged.size > cur.items.size,
                            title = title ?: cur.title,
                            horizontal = page?.rows?.firstOrNull()?.horizontal ?: cur.horizontal,
                        )
                    }
                }
                is Outcome.Failed -> update(index) { it.copy(loading = false, loadedOnce = true, hasNext = false, error = out.message) }
                is Outcome.TimedOut -> update(index) { it.copy(loading = false, loadedOnce = true, hasNext = false, error = "Timed out after ${out.ms / 1000}s") }
            }
        }
    }

    private fun update(index: Int, f: (SectionState) -> SectionState) = _state.update { s ->
        s.copy(sections = s.sections.mapIndexed { i, x -> if (i == index) f(x) else x })
    }
}
