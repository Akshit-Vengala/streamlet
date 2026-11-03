package cloudstream.desktop.app

import cloudstream.desktop.contracts.KeyValueStore
import cloudstream.desktop.contracts.LibraryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Profile(val id: String, val name: String, val color: Long) {
    val initial: String get() = name.trim().firstOrNull()?.uppercase() ?: "?"
}

/** Everything that is per profile: library (bookmarks, history, resume points) and UI settings. */
class ProfileData(val library: LibraryStore, val history: HistoryWriter, val settings: AppSettings) {
    /** Tracker and subtitle accounts of this profile. */
    val sync: cloudstream.desktop.app.sync.SyncManager by lazy { cloudstream.desktop.app.sync.SyncManager(settings.kv) }
}

/**
 * User profiles. Each one gets its own storage namespaces (see [open]); the profile list itself lives in [index].
 * The "default" profile uses the pre-profiles namespaces, so existing data shows up there unchanged.
 */
class Profiles(
    private val index: KeyValueStore,
    private val open: (id: String) -> ProfileData,
    private val wipe: (id: String) -> Unit,
) {
    private val cache = HashMap<String, ProfileData>()

    private val _list = MutableStateFlow(load())
    val list: StateFlow<List<Profile>> = _list.asStateFlow()

    private val _current = MutableStateFlow(
        index.get("current", String::class.java)?.let { id -> _list.value.firstOrNull { it.id == id } } ?: _list.value.first(),
    )
    val current: StateFlow<Profile> = _current.asStateFlow()

    /** Whether the start-up profile picker should be shown (only once there is more than one profile). */
    val askOnStart: Boolean get() = (index.get("askOnStart", Boolean::class.javaObjectType) ?: true) && _list.value.size > 1
    fun setAskOnStart(b: Boolean) = index.set("askOnStart", b)

    @Synchronized
    fun data(p: Profile = _current.value): ProfileData = cache.getOrPut(p.id) { open(p.id) }

    fun switchTo(id: String) {
        val p = _list.value.firstOrNull { it.id == id } ?: return
        _current.value = p
        index.set("current", id)
    }

    fun create(name: String): Profile {
        val clean = name.trim().ifBlank { "Profile ${_list.value.size + 1}" }.take(40)
        val id = "p" + System.currentTimeMillis().toString(36) + (0..999).random()
        val p = Profile(id, clean, PALETTE[_list.value.size % PALETTE.size])
        save(_list.value + p)
        return p
    }

    fun rename(id: String, name: String) {
        val clean = name.trim().takeIf { it.isNotEmpty() }?.take(40) ?: return
        save(_list.value.map { if (it.id == id) it.copy(name = clean) else it })
        if (_current.value.id == id) _current.value = _list.value.first { it.id == id }
    }

    /** Deletes the profile and its data. The last remaining profile cannot be deleted. */
    fun delete(id: String): Boolean {
        if (_list.value.size <= 1 || _list.value.none { it.id == id }) return false
        val rest = _list.value.filter { it.id != id }
        save(rest)
        synchronized(this) { cache.remove(id) }
        runCatching { wipe(id) }
        if (_current.value.id == id) switchTo(rest.first().id)
        return true
    }

    private fun save(l: List<Profile>) {
        _list.value = l
        // One line per profile: id <TAB> color <TAB> name (names are trimmed and cannot contain tabs/newlines).
        index.set("profiles", l.joinToString("\n") { "${it.id}\t${it.color}\t${it.name.replace('\t', ' ').replace('\n', ' ')}" })
    }

    private fun load(): List<Profile> {
        val l = index.get("profiles", String::class.java).orEmpty().lines().mapNotNull { line ->
            val parts = line.split('\t', limit = 3)
            if (parts.size == 3) Profile(parts[0], parts[2], parts[1].toLongOrNull() ?: PALETTE[0]) else null
        }
        return l.ifEmpty { listOf(Profile(DEFAULT_ID, "Default", PALETTE[0])) }
    }

    companion object {
        const val DEFAULT_ID = "default"
        val PALETTE = listOf(0xFF5B8DEF, 0xFFE0705B, 0xFF4CAF7A, 0xFFB57BE0, 0xFFE0B04C, 0xFF4CB8C4, 0xFFE06AA0, 0xFF8A9BA8)

        /** Storage namespace for [base] ("library", "desktop_ui") in profile [id]; the default profile keeps the old names. */
        fun ns(base: String, id: String) = if (id == DEFAULT_ID) base else "$base.$id"
    }
}
