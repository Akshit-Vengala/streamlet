package cloudstream.desktop.storage

import cloudstream.desktop.contracts.KeyValueStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Entry point. Hands out one [JsonFileStore] per namespace under [baseDir]
 * (default `%APPDATA%/CloudStreamDesktop/data`; tests pass a temp dir).
 *
 * Namespaces used by convention:
 *  - "datastore": upstream `DataStore` (getKey/setKey) data, keys like `0/result_watch_state_data/123`
 *  - "settings":  Android default SharedPreferences (app settings)
 *  - "<prefsName>": one per `Context.getSharedPreferences(name)` call from plugins
 *  - "library":   used by [JsonLibraryStore]
 *
 * Adapter surface for Track C (SharedPreferences shim) / upstream DataStore helpers:
 *  - `namespace(name)` -> JsonFileStore: getAll(), putAll(map, clearFirst) for Editor.apply(),
 *    contains(), clear(), flush(); get(key, Int/Long/Float/Boolean/String/Set/obj class).
 *  - Objects set via set(key, obj) are stored as JSON trees; get(key, String::class.java) on such a
 *    key returns the JSON text, and get(key, Foo::class.java) on a JSON-string value parses it
 *    (matching upstream, which stores objects as JSON strings).
 */
class StorageManager(val baseDir: File = defaultDataDir(), private val flushDelayMs: Long = 250) : AutoCloseable {
    private val stores = ConcurrentHashMap<String, JsonFileStore>()

    fun namespace(name: String): JsonFileStore {
        require(name.matches(Regex("[A-Za-z0-9._-]{1,100}"))) { "bad namespace: $name" }
        return stores.computeIfAbsent(name) { JsonFileStore(baseDir, name, flushDelayMs) }
    }

    val datastore: JsonFileStore get() = namespace("datastore")
    val settings: JsonFileStore get() = namespace("settings")
    val library: JsonLibraryStore by lazy { JsonLibraryStore(namespace("library")) }

    fun asKeyValueStore(name: String): KeyValueStore = namespace(name)
    fun flushAll() = stores.values.forEach { it.flush() }
    override fun close() = stores.values.forEach { it.close() }
}
