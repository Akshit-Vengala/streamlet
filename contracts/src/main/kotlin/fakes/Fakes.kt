package cloudstream.desktop.contracts.fakes

import cloudstream.desktop.contracts.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

class InMemoryKeyValueStore : KeyValueStore {
    private val m = ConcurrentHashMap<String, Any>()
    @Suppress("UNCHECKED_CAST")
    override fun <T> get(key: String, type: Class<T>): T? = m[key]?.takeIf { type.isInstance(it) } as T?
    override fun set(key: String, value: Any?) { if (value == null) m.remove(key) else m[key] = value }
    override fun remove(key: String) { m.remove(key) }
    override fun keys(prefix: String) = m.keys.filter { it.startsWith(prefix) }
}

class InMemoryLibraryStore : LibraryStore {
    private val b = ConcurrentHashMap<String, Bookmark>()
    private val r = ConcurrentHashMap<String, ResumePoint>()
    private val h = java.util.concurrent.CopyOnWriteArrayList<HistoryEntry>()
    override fun bookmarks() = b.values.sortedBy { it.addedAt }
    override fun setBookmark(b: Bookmark) { this.b[b.id] = b }
    override fun removeBookmark(id: String) { b.remove(id) }
    override fun resume(id: String) = r[id]
    override fun setResume(p: ResumePoint) { r[p.id] = p }
    override fun history() = h.sortedByDescending { it.watchedAt }
    override fun removeHistory(id: String) { h.removeAll { it.id == id } }
}

class FakePlayer : Player {
    private val s = MutableStateFlow(PlayerState(0, 0, false, false, null))
    override val state: StateFlow<PlayerState> get() = s
    override fun open(source: PlaybackSource) { s.value = PlayerState(source.startMs, 0, true, false, null) }
    override fun play() { s.value = s.value.copy(playing = true) }
    override fun pause() { s.value = s.value.copy(playing = false) }
    override fun seek(ms: Long) { s.value = s.value.copy(positionMs = ms) }
    override fun stop() { s.value = PlayerState(0, 0, false, false, null) }
    override fun setSubtitle(track: SubtitleSource?) {}
    override fun attachTo(component: java.awt.Component) {}
}

class FakeHeadlessBrowser : HeadlessBrowser {
    override suspend fun resolve(url: String, interceptRegex: Regex, headers: Map<String, String>, timeoutMs: Long) =
        ResolveResult(null, emptyMap(), "FakeBrowser")
    override suspend fun clearance(url: String, timeoutMs: Long): Clearance? = null
}

class FakePluginRepository : PluginRepository {
    override suspend fun addRepo(url: String) = RepoInfo(url, url, emptyList())
    override suspend fun listRepos() = emptyList<RepoInfo>()
    override suspend fun listAvailable(repoUrl: String) = emptyList<PluginInfo>()
    override suspend fun install(plugin: PluginInfo) = InstalledPlugin(plugin, "", "")
    override suspend fun uninstall(internalName: String) {}
    override suspend fun update(internalName: String): InstalledPlugin = error("fake")
    override fun installed() = emptyList<InstalledPlugin>()
    override suspend fun loadAll() = emptyList<LoadResult>()
}
