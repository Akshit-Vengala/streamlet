package cloudstream.desktop.app.vm

import cloudstream.desktop.app.domain.MediaCard
import cloudstream.desktop.app.domain.Outcome
import cloudstream.desktop.app.domain.Provider
import cloudstream.desktop.app.domain.guarded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProviderResult(
    val provider: String,
    val lang: String,
    val status: Status,
    val items: List<MediaCard> = emptyList(),
    val message: String? = null,
    val elapsedMs: Long = 0,
) {
    enum class Status { Loading, Done, Error, Timeout }
}

data class SearchState(
    val query: String = "",
    val submitted: String? = null,
    val results: List<ProviderResult> = emptyList(),
    val noProviders: Boolean = false,
) {
    val running: Boolean get() = results.any { it.status == ProviderResult.Status.Loading }
    val totalItems: Int get() = results.sumOf { it.items.size }
}

/**
 * Queries every enabled provider in parallel. Each provider is isolated: its own coroutine, timeout and error slot.
 * Never blocks the caller (all work happens in [scope], which must not be the Swing/Compose main dispatcher for CPU/IO).
 */
class SearchViewModel(
    private val scope: CoroutineScope,
    private val providers: () -> List<Provider>,
    private val isEnabled: (String) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()
    private var job: Job? = null

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    fun search(query: String = _state.value.query): Job? {
        val q = query.trim()
        if (q.isEmpty()) return null
        job?.cancel()
        val list = providers().filter { isEnabled(it.name) }
        _state.value = SearchState(
            query = query, submitted = q, noProviders = list.isEmpty(),
            results = list.map { ProviderResult(it.name, it.lang, ProviderResult.Status.Loading) },
        )
        job = scope.launch {
            for (p in list) {
                launch {
                    val t0 = now()
                    val out = guarded(p.searchTimeoutMs) { p.search(q, 1) }
                    val dt = now() - t0
                    val res = when (out) {
                        is Outcome.Ok -> ProviderResult(p.name, p.lang, ProviderResult.Status.Done, dedupe(out.value), elapsedMs = dt)
                        is Outcome.Failed -> ProviderResult(p.name, p.lang, ProviderResult.Status.Error, message = out.message, elapsedMs = dt)
                        is Outcome.TimedOut -> ProviderResult(p.name, p.lang, ProviderResult.Status.Timeout, message = "Timed out after ${out.ms / 1000}s", elapsedMs = dt)
                    }
                    _state.update { s -> s.copy(results = s.results.map { if (it.provider == p.name) res else it }) }
                }
            }
        }
        return job
    }

    private fun dedupe(items: List<MediaCard>) = items.distinctBy { it.url }

    fun cancel() { job?.cancel() }
}
