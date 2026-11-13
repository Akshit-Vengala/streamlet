package cloudstream.desktop.app.vm

import cloudstream.desktop.app.domain.ExtensionsBackend
import cloudstream.desktop.app.domain.describe
import cloudstream.desktop.contracts.LoadResult
import cloudstream.desktop.contracts.PluginInfo
import cloudstream.desktop.contracts.RepoInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RepoSection(
    val repo: RepoInfo,
    val plugins: List<PluginInfo> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

data class InstalledRow(
    val info: PluginInfo,
    /** null = not (yet) loaded this session. */
    val load: LoadResult? = null,
    val updateTo: PluginInfo? = null,
    /** What the safety check found (null = not checked yet / demo mode). */
    val safety: cloudstream.desktop.app.domain.ExtensionSafety? = null,
    /** When the extension was installed or last updated (epoch ms; 0 = unknown). */
    val updatedAt: Long = 0,
) {
    val hasSettingsUi: Boolean get() = (load as? LoadResult.Ok)?.hasSettingsUi == true
}

data class ExtensionsState(
    val repos: List<RepoSection> = emptyList(),
    val installed: List<InstalledRow> = emptyList(),
    /** internalName -> short activity text ("Installing...", "Loading...") */
    val busy: Map<String, String> = emptyMap(),
    val addingRepo: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val languageFilter: String? = null,
    val typeFilter: String? = null,
    val query: String = "",
    val startupLoading: Boolean = false,
    /** False until the saved repositories / installed extensions were read (the screen shows a skeleton, not "none"). */
    val listsLoaded: Boolean = false,
) {
    val installedNames: Set<String> get() = installed.map { it.info.internalName }.toSet()

    val languages: List<String>
        get() = repos.flatMap { r -> r.plugins.mapNotNull { it.language } }.distinct().sorted()
    val types: List<String>
        get() = repos.flatMap { r -> r.plugins.flatMap { it.tvTypes } }.distinct().sorted()

    fun visible(r: RepoSection): List<PluginInfo> = r.plugins.filter { p ->
        (languageFilter == null || p.language == languageFilter) &&
            (typeFilter == null || typeFilter in p.tvTypes) &&
            (query.isBlank() || p.name.contains(query, true) || p.internalName.contains(query, true))
    }
}

class ExtensionsViewModel(
    private val scope: CoroutineScope,
    private val backend: ExtensionsBackend,
    /** Called whenever providers may have been added/removed. */
    private val onProvidersChanged: () -> Unit = {},
) {
    private val _state = MutableStateFlow(ExtensionsState())
    val state: StateFlow<ExtensionsState> = _state.asStateFlow()

    private fun setBusy(name: String, text: String?) = _state.update {
        it.copy(busy = if (text == null) it.busy - name else it.busy + (name to text))
    }

    private fun launchGuarded(what: String, block: suspend () -> Unit): Job = scope.launch {
        try { block() } catch (e: CancellationException) { throw e } catch (t: Throwable) {
            _state.update { it.copy(error = "$what failed: ${describe(t)}") }
        }
    }

    /** Reads persisted repos/installed plugins and loads every installed plugin (registers providers). */
    fun start(): Job = launchGuarded("Startup") {
        refreshLists()
        _state.update { it.copy(startupLoading = true) }
        val results = try { backend.loadAll() } finally { _state.update { it.copy(startupLoading = false) } }
        applyLoadResults(results)
        onProvidersChanged()
        _state.value.repos.forEach { refreshRepo(it.repo.url) }
    }

    private suspend fun refreshLists() {
        val repos = backend.listRepos()
        val installed = backend.installed()
        _state.update { s ->
            s.copy(
                listsLoaded = true,
                repos = repos.map { r -> s.repos.firstOrNull { it.repo.url == r.url } ?: RepoSection(r) },
                installed = installed.map { i ->
                    val old = s.installed.firstOrNull { it.info.internalName == i.info.internalName }
                    // The downloaded file is written on install and rewritten on update: its time is "updated at".
                    val at = runCatching { java.io.File(i.dexPath).lastModified() }.getOrDefault(0L)
                    InstalledRow(i.info, old?.load, safety = old?.safety, updatedAt = at)
                },
            )
        }
        refreshSafety()
    }

    /** Re-reads the safety findings (bytecode scan, cached per jar) off the UI thread. */
    private suspend fun refreshSafety() {
        val names = _state.value.installed.map { it.info.internalName }
        val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            names.associateWith { n -> runCatching { backend.safety(n) }.getOrNull() }
        }
        _state.update { s -> s.copy(installed = s.installed.map { r -> found[r.info.internalName]?.let { r.copy(safety = it) } ?: r }) }
    }

    /**
     * The user trusts this extension with what the safety check blocked: allow it, then load it (or, when it was a
     * blocked update, apply that update).
     */
    fun allowAndLoad(internalName: String): Job = launchGuarded("Allow") {
        val row = _state.value.installed.firstOrNull { it.info.internalName == internalName } ?: return@launchGuarded
        val sf = row.safety
        val ids = (sf?.blocked.orEmpty() + sf?.pendingUpdate.orEmpty()).map { it.id }.distinct() +
            (row.load as? LoadResult.Failed)?.blocked.orEmpty()
        backend.allowCapabilities(internalName, ids.distinct())
        if (sf?.pendingUpdate?.isNotEmpty() == true) update(internalName).join()
        else { setBusy(internalName, "Loading..."); try { loadOne(internalName) } finally { setBusy(internalName, null) } }
        refreshSafety()
    }

    /** Takes back what was allowed; the extension is reloaded under the check (and blocked again if it needs it). */
    fun revokeAndReload(internalName: String): Job = launchGuarded("Revoke") {
        backend.revokeCapabilities(internalName)
        setBusy(internalName, "Loading..."); try { loadOne(internalName) } finally { setBusy(internalName, null) }
        refreshSafety()
    }

    private fun applyLoadResults(results: List<LoadResult>) = _state.update { s ->
        s.copy(installed = s.installed.map { row ->
            val r = results.firstOrNull { nameOf(it) == row.info.name || nameOf(it) == row.info.internalName }
            if (r != null) row.copy(load = r) else row
        })
    }

    private fun nameOf(r: LoadResult) = when (r) { is LoadResult.Ok -> r.name; is LoadResult.Failed -> r.name }

    fun addRepo(url: String): Job = launchGuarded("Adding repository") {
        val u = url.trim()
        if (u.isEmpty()) return@launchGuarded
        _state.update { it.copy(addingRepo = true, error = null, message = null) }
        try {
            val info = backend.addRepo(u)
            refreshLists()
            _state.update { it.copy(message = "Added repository \"${info.name}\"") }
            refreshRepo(info.url)
        } finally {
            _state.update { it.copy(addingRepo = false) }
        }
    }

    fun refreshRepo(url: String): Job = launchGuarded("Refreshing repository") {
        mutateRepo(url) { it.copy(loading = true, error = null) }
        try {
            val list = backend.listAvailable(url)
            mutateRepo(url) { it.copy(plugins = list, loading = false) }
        } catch (e: CancellationException) { throw e } catch (t: Throwable) {
            mutateRepo(url) { it.copy(loading = false, error = describe(t)) }
        }
    }

    private fun mutateRepo(url: String, f: (RepoSection) -> RepoSection) = _state.update { s ->
        s.copy(repos = s.repos.map { if (it.repo.url == url) f(it) else it })
    }

    fun removeRepo(url: String): Job = launchGuarded("Removing repository") {
        backend.removeRepo(url)
        refreshLists()
        onProvidersChanged()
    }

    /** Download + convert + load. Load failures are reported per plugin, never thrown. */
    fun install(info: PluginInfo): Job = launchGuarded("Install of ${info.name}") {
        val key = info.internalName
        setBusy(key, "Installing (downloading & converting, may take a while)...")
        try {
            backend.install(info)
            refreshLists()
            setBusy(key, "Loading...")
            val res = loadOne(key)
            _state.update { it.copy(message = when (res) {
                is LoadResult.Ok -> "${info.name} installed: ${res.apis.size} provider(s)"
                is LoadResult.Failed -> "${info.name} installed but failed to load"
            }, error = null) }
        } catch (e: CancellationException) { throw e } catch (t: Throwable) {
            _state.update { it.copy(error = "Install of ${info.name} failed: ${describe(t)}") }
        } finally {
            setBusy(key, null)
        }
    }

    private suspend fun loadOne(internalName: String): LoadResult {
        backend.unloadOne(internalName)
        val res = backend.loadOne(internalName)
        _state.update { s -> s.copy(installed = s.installed.map { if (it.info.internalName == internalName) it.copy(load = res) else it }) }
        onProvidersChanged()
        return res
    }

    fun reload(internalName: String): Job = launchGuarded("Reload") {
        setBusy(internalName, "Loading...")
        try { loadOne(internalName) } finally { setBusy(internalName, null) }
    }

    fun uninstall(internalName: String): Job = launchGuarded("Uninstall") {
        setBusy(internalName, "Removing...")
        try {
            backend.unloadOne(internalName)
            backend.uninstall(internalName)
            refreshLists()
            onProvidersChanged()
        } finally { setBusy(internalName, null) }
    }

    fun update(internalName: String): Job = launchGuarded("Update") {
        setBusy(internalName, "Updating...")
        try {
            try { backend.update(internalName) } catch (t: Throwable) { afterFailedUpdate(); throw t }
            refreshLists()
            loadOne(internalName)
            _state.update { s -> s.copy(message = "Updated $internalName") }
        } finally { setBusy(internalName, null); refreshSafety() } // a blocked update shows up as "Allow and update"
    }

    /**
     * Background updates, like CloudStream's "auto update plugins": checks the repos and (if [install]) downloads,
     * converts and hot-reloads each newer extension one by one. A version that fails to load is rolled back by the
     * repository. Returns one line per update for a toast.
     */
    suspend fun applyUpdates(install: Boolean): List<String> {
        val updates = runCatching { backend.checkUpdates() }.getOrElse { return listOf("Extension update check failed: ${describe(it)}") }
        _state.update { s -> s.copy(installed = s.installed.map { row -> row.copy(updateTo = updates.firstOrNull { it.internalName == row.info.internalName }) }) }
        if (!install) return if (updates.isEmpty()) emptyList() else listOf("${updates.size} extension update(s) available: ${updates.joinToString { it.name }} (Extensions tab)")
        return updates.map { u ->
            // Marked DOWN by its author in the repository: unloaded, not updated (upstream behaviour).
            if (u.status == 0) {
                backend.unloadOne(u.internalName); onProvidersChanged()
                return@map "${u.name} was disabled by its author in the repository and has been unloaded"
            }
            val from = _state.value.installed.firstOrNull { it.info.internalName == u.internalName }?.info?.version
            setBusy(u.internalName, "Updating to v${u.version}...")
            try {
                backend.update(u.internalName)
                refreshLists()
                loadOne(u.internalName)
                _state.update { s -> s.copy(installed = s.installed.map { if (it.info.internalName == u.internalName) it.copy(updateTo = null) else it }) }
                "Updated ${u.name}: v$from -> v${u.version}"
            } catch (e: CancellationException) { throw e } catch (t: Throwable) {
                afterFailedUpdate()
                "Update of ${u.name} failed: ${describe(t)}"
            } finally { setBusy(u.internalName, null); refreshSafety() }
        }
    }

    /**
     * A failed update was rolled back by the repository: the new version's load unloaded the old one first, and the
     * old one was then loaded again with fresh provider instances. Re-read the list and tell the app, or the UI keeps
     * the unloaded instances and the extension's providers disappear until restart.
     */
    private suspend fun afterFailedUpdate() {
        runCatching { refreshLists() }
        onProvidersChanged()
    }

    fun checkUpdates(): Job = launchGuarded("Update check") {
        val updates = backend.checkUpdates()
        _state.update { s ->
            s.copy(
                installed = s.installed.map { row -> row.copy(updateTo = updates.firstOrNull { it.internalName == row.info.internalName }) },
                message = if (updates.isEmpty()) "All extensions are up to date" else "${updates.size} update(s) available",
            )
        }
    }

    fun setLanguageFilter(l: String?) = _state.update { it.copy(languageFilter = l) }
    fun setTypeFilter(t: String?) = _state.update { it.copy(typeFilter = t) }
    fun setQuery(q: String) = _state.update { it.copy(query = q) }
    fun dismissMessage() = _state.update { it.copy(message = null, error = null) }
}
