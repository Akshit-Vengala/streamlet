package cloudstream.desktop.storage

import cloudstream.desktop.contracts.Bookmark
import cloudstream.desktop.contracts.ResumePoint
import cloudstream.desktop.contracts.WatchStatus
import com.fasterxml.jackson.databind.JsonNode

data class ImportedRepo(val name: String, val url: String, val iconUrl: String?, val description: String?)

data class ImportReport(
    val repos: List<ImportedRepo>,
    val bookmarks: Int,
    val resumePoints: Int,
    val datastoreKeys: Int,
    val settingsKeys: Int,
    val skippedKeys: List<String>,
)

/**
 * Imports a CloudStream Android backup (BackupUtils.BackupFile):
 * `{"datastore":{"_Bool":{},"_Int":{},"_String":{},"_Float":{},"_Long":{},"_StringSet":{}},"settings":{...same...}}`
 *
 * - every key is restored verbatim into the "datastore" / "settings" namespaces (as upstream does),
 *   including `REPOSITORIES_KEY` (JSON array string of RepositoryData).
 * - additionally `<acct>/result_watch_state_data/<id>` (BookmarkedData) -> LibraryStore bookmarks and
 *   `<acct>/video_pos_dur/<id>` (PosDur) -> LibraryStore resume points.
 * Android backups already exclude tokens/paths; a few such keys are skipped defensively.
 */
class BackupImporter(
    private val datastore: JsonFileStore,
    private val settings: JsonFileStore,
    private val library: JsonLibraryStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val bookmarkKey = Regex("""^-?\d+/result_watch_state_data/(-?\d+)$""")
    private val watchStateKey = Regex("""^-?\d+/result_watch_state/(-?\d+)$""")
    private val posDurKey =Regex("""^-?\d+/video_pos_dur/(-?\d+)$""")
    private val unsafe = listOf("download_path_key", "backup_path_key", "backup_dir_path_key", "biometric_key", "_token", "_user")

    fun import(json: String): ImportReport {
        val root = sharedMapper.readTree(json)
        require(root != null && root.has("datastore") && root.has("settings")) { "Not a CloudStream backup file" }
        val skipped = mutableListOf<String>()
        var bm = 0; var rp = 0; var dsCount = 0; var stCount = 0
        val ds = flatten(root.get("datastore"))
        val st = flatten(root.get("settings"))
        // Watch status per title (WatchType id), applied to the bookmarks below.
        val states = ds.mapNotNull { (k, v) ->
            val id = watchStateKey.matchEntire(k)?.groupValues?.get(1) ?: return@mapNotNull null
            val n = (v as? Number)?.toInt() ?: (v as? String)?.trim('"')?.toIntOrNull() ?: return@mapNotNull null
            id to n
        }.toMap()
        for ((k, v) in ds) {
            if (unsafe.any { k.contains(it) }) { skipped += k; continue }
            datastore.set(k, v); dsCount++
            bookmarkKey.matchEntire(k)?.let { m ->
                val id = m.groupValues[1]
                val st = states[id]
                if (st == null || WatchStatus.fromCloudStream(st) != null) // NONE (5) = removed from the library
                    toBookmark(id, v as? String)?.let { b ->
                        library.setBookmark(st?.let(WatchStatus::fromCloudStream)?.let { b.copy(status = it) } ?: b); bm++
                    }
            }
            posDurKey.matchEntire(k)?.let { m ->
                toResume(m.groupValues[1], v as? String)?.let { library.setResume(it); rp++ }
            }
        }
        for ((k, v) in st) {
            if (unsafe.any { k.contains(it) }) { skipped += k; continue }
            settings.set(k, v); stCount++
        }
        datastore.flush(); settings.flush(); library.flush()
        return ImportReport(parseRepos(ds["REPOSITORIES_KEY"] as? String), bm, rp, dsCount, stCount, skipped)
    }

    private fun flatten(section: JsonNode?): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        if (section == null) return out
        fun each(name: String, conv: (JsonNode) -> Any?) {
            section.get(name)?.fields()?.forEach { (k, v) -> if (!v.isNull) conv(v)?.let { out[k] = it } }
        }
        each("_Bool") { if (it.isBoolean) it.asBoolean() else null }
        each("_Int") { if (it.isNumber) it.asInt() else null }
        each("_String") { it.asText() }
        each("_Float") { if (it.isNumber) it.floatValue() else null }
        each("_Long") { if (it.isNumber) it.asLong() else null }
        each("_StringSet") { n -> n.map { it.asText() }.toCollection(LinkedHashSet()) }
        return out
    }

    private fun opt(n: JsonNode, f: String): String? = n.get(f)?.takeIf { !it.isNull }?.asText()

    private fun parseRepos(raw: String?): List<ImportedRepo> = try {
        if (raw == null) emptyList() else sharedMapper.readTree(raw).map {
            ImportedRepo(it.get("name").asText(), it.get("url").asText(), opt(it, "iconUrl"), opt(it, "description"))
        }
    } catch (_: Exception) { emptyList() }

    private fun toBookmark(id: String, raw: String?): Bookmark? = try {
        val n = sharedMapper.readTree(raw)
        Bookmark(id, n.get("name").asText(), n.get("apiName").asText(), n.get("url").asText(),
            opt(n, "posterUrl"), opt(n, "type"), n.get("bookmarkedTime")?.asLong() ?: nowMs())
    } catch (_: Exception) { null }

    private fun toResume(id: String, raw: String?): ResumePoint? = try {
        val n = sharedMapper.readTree(raw)
        ResumePoint(id, n.get("position").asLong(), n.get("duration").asLong(), nowMs())
    } catch (_: Exception) { null }
}
