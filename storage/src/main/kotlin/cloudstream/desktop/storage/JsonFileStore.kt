package cloudstream.desktop.storage

import cloudstream.desktop.contracts.KeyValueStore
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

// Computed getters (ResumePoint.watched / startMs) are written out too; reading them back must not fail on them.
internal val sharedMapper: ObjectMapper = jacksonObjectMapper()
    .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

/** Default data dir: `<AppDirs.root>/data` (%APPDATA% on Windows, XDG data dir on Linux). */
fun defaultDataDir(): File = File(cloudstream.desktop.contracts.AppDirs.root, "data")

/**
 * One namespace = one JSON file `<dir>/<namespace>.json`.
 *
 * File format: `{"format":1,"entries":{"key":{"t":"S","v":"x"}, ...}}` where t is one of
 * S (String) I (Int) L (Long) F (Float) B (Boolean) SS (Set<String>) J (arbitrary JSON tree).
 * The tag preserves the SharedPreferences type distinction that plain JSON numbers lose.
 *
 * Durability: write `<ns>.json.tmp` + fsync, copy the previous good file to `<ns>.json.bak`,
 * then atomic rename tmp over main. On load a missing/corrupt main falls back to `.bak`.
 * Writes are debounced ([flushDelayMs]; 0 = write synchronously on every mutation).
 */
class JsonFileStore(
    private val dir: File,
    val namespace: String,
    private val flushDelayMs: Long = 250,
) : KeyValueStore, AutoCloseable {
    private val mapper = sharedMapper
    private val main = File(dir, "$namespace.json")
    private val tmp = File(dir, "$namespace.json.tmp")
    private val bak = File(dir, "$namespace.json.bak")
    private val lock = Any()
    private val entries = LinkedHashMap<String, JsonNode>()
    private var dirty = false
    private var pending: ScheduledFuture<*>? = null
    @Volatile private var closed = false

    /** True if main was unreadable at load and the backup was used. */
    var recoveredFromBackup = false
        private set

    init {
        dir.mkdirs()
        load()
        StoreRegistry.register(this)
    }

    // ---- KeyValueStore ----

    override fun <T> get(key: String, type: Class<T>): T? {
        val node = synchronized(lock) { entries[key] } ?: return null
        return decode(node, type)
    }

    override fun set(key: String, value: Any?) {
        if (value == null) return remove(key)
        val node = encode(value)
        synchronized(lock) { entries[key] = node; dirty = true }
        afterMutation()
    }

    override fun remove(key: String) {
        synchronized(lock) { if (entries.remove(key) != null) dirty = true }
        afterMutation()
    }

    override fun keys(prefix: String): List<String> =
        synchronized(lock) { entries.keys.filter { it.startsWith(prefix) } }

    // ---- adapter surface (for SharedPreferences shim / DataStore helpers) ----

    fun contains(key: String): Boolean = synchronized(lock) { entries.containsKey(key) }

    /** Snapshot of all entries decoded to their natural Java type (String/Int/Long/Float/Boolean/Set<String>/JsonNode). */
    fun getAll(): Map<String, Any?> = synchronized(lock) { entries.mapValues { (_, n) -> natural(n) } }

    /** Atomically apply many mutations (SharedPreferences.Editor.apply). null value removes. */
    fun putAll(values: Map<String, Any?>, clearFirst: Boolean = false) {
        val encoded = values.mapValues { (_, v) -> v?.let { encode(it) } }
        synchronized(lock) {
            if (clearFirst) entries.clear()
            for ((k, n) in encoded) if (n == null) entries.remove(k) else entries[k] = n
            dirty = true
        }
        afterMutation()
    }

    fun clear() = putAll(emptyMap(), clearFirst = true)

    /** Write pending changes now (blocking). */
    fun flush() {
        val snapshot: String
        synchronized(lock) {
            pending?.cancel(false); pending = null
            if (!dirty) return
            dirty = false
            snapshot = serialize()
        }
        try { writeAtomically(snapshot) } catch (e: Exception) {
            synchronized(lock) { dirty = true }
            throw e
        }
    }

    override fun close() {
        if (closed) return
        flush(); closed = true; StoreRegistry.unregister(this)
    }

    // ---- internals ----

    private fun afterMutation() {
        if (flushDelayMs <= 0) { flush(); return }
        synchronized(lock) {
            if (pending == null && dirty) {
                pending = StoreRegistry.scheduler.schedule({
                    synchronized(lock) { pending = null }
                    try { flush() } catch (_: Exception) { /* retried on next mutation/shutdown */ }
                }, flushDelayMs, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun serialize(): String {
        val root = mapper.createObjectNode().put("format", 1)
        val obj = root.putObject("entries")
        entries.forEach { (k, v) -> obj.set<JsonNode>(k, v) }
        return mapper.writeValueAsString(root)
    }

    private fun writeAtomically(text: String) {
        synchronized(fileLock) {
            dir.mkdirs()
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            // Only promote main to .bak if it is itself valid, so .bak is always a good copy.
            if (main.exists() && parseFile(main) != null) {
                Files.copy(main.toPath(), bak.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            try {
                Files.move(tmp.toPath(), main.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), main.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun parseFile(f: File): Map<String, JsonNode>? = try {
        if (!f.isFile || f.length() == 0L) null else {
            val root = mapper.readTree(f)
            val e = root?.get("entries")
            if (root == null || root.get("format") == null || e == null || !e.isObject) null
            else LinkedHashMap<String, JsonNode>().also { m -> (e as ObjectNode).fields().forEach { m[it.key] = it.value } }
        }
    } catch (_: Exception) { null }

    private fun load() {
        val fromMain = parseFile(main)
        val loaded = fromMain ?: parseFile(bak).also { if (it != null) recoveredFromBackup = true }
        if (loaded != null) entries.putAll(loaded)
    }

    internal companion object {
        // Serialises renames across store instances in this JVM.
        private val fileLock = Any()

        fun encode(value: Any): JsonNode {
            val m = sharedMapper
            val n = m.createObjectNode()
            when (value) {
                is String -> n.put("t", "S").put("v", value)
                is Int -> n.put("t", "I").put("v", value)
                is Long -> n.put("t", "L").put("v", value)
                is Float -> n.put("t", "F").put("v", value)
                is Boolean -> n.put("t", "B").put("v", value)
                is Set<*> -> if (value.all { it is String }) {
                    n.put("t", "SS"); val a = n.putArray("v"); value.forEach { a.add(it as String) }
                } else n.put("t", "J").set<JsonNode>("v", m.valueToTree(value))
                is JsonNode -> n.put("t", "J").set<JsonNode>("v", value)
                else -> n.put("t", "J").set<JsonNode>("v", m.valueToTree(value))
            }
            return n
        }

        fun natural(n: JsonNode): Any? {
            val v = n.get("v")
            return when (n.get("t")?.asText()) {
                "S" -> v.asText(); "I" -> v.asInt(); "L" -> v.asLong(); "F" -> v.floatValue()
                "B" -> v.asBoolean()
                "SS" -> v.map { it.asText() }.toCollection(LinkedHashSet())
                else -> v
            }
        }

        @Suppress("UNCHECKED_CAST")
        fun <T> decode(n: JsonNode, type: Class<T>): T? {
            val v = n.get("v") ?: return null
            val tag = n.get("t")?.asText()
            val r: Any? = try {
                when {
                    type == String::class.java -> if (v.isTextual) v.asText() else sharedMapper.writeValueAsString(v) // upstream stores objects as JSON strings
                    type == Int::class.javaObjectType || type == Int::class.javaPrimitiveType -> if (v.isNumber) v.asInt() else v.asText().toIntOrNull()
                    type == Long::class.javaObjectType || type == Long::class.javaPrimitiveType -> if (v.isNumber) v.asLong() else v.asText().toLongOrNull()
                    type == Float::class.javaObjectType || type == Float::class.javaPrimitiveType -> if (v.isNumber) v.floatValue() else v.asText().toFloatOrNull()
                    type == Double::class.javaObjectType || type == Double::class.javaPrimitiveType -> if (v.isNumber) v.asDouble() else v.asText().toDoubleOrNull()
                    type == Boolean::class.javaObjectType || type == Boolean::class.javaPrimitiveType -> if (v.isBoolean) v.asBoolean() else v.asText().toBooleanStrictOrNull()
                    Set::class.java.isAssignableFrom(type) && tag == "SS" -> natural(n)
                    type == JsonNode::class.java -> v
                    // object stored as a JSON string (upstream style) or as a J tree
                    v.isTextual && tag == "S" -> sharedMapper.readValue(v.asText(), type)
                    else -> sharedMapper.treeToValue(v, type)
                }
            } catch (_: Exception) { null }
            return r as T?
        }
    }
}

internal object StoreRegistry {
    val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "kv-flush").apply { isDaemon = true } }
    private val stores = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<JsonFileStore, Boolean>())

    init {
        Runtime.getRuntime().addShutdownHook(Thread({ flushAll() }, "kv-shutdown-flush"))
    }

    fun register(s: JsonFileStore) { stores.add(s) }
    fun unregister(s: JsonFileStore) { stores.remove(s) }
    fun flushAll() { stores.forEach { try { it.flush() } catch (_: Exception) {} } }
}
