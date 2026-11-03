package cloudstream.desktop.contracts

/** Track B implements this. */
interface PluginRepository {
    suspend fun addRepo(url: String): RepoInfo
    suspend fun listRepos(): List<RepoInfo>
    suspend fun listAvailable(repoUrl: String): List<PluginInfo>
    suspend fun install(plugin: PluginInfo): InstalledPlugin
    suspend fun uninstall(internalName: String)
    suspend fun update(internalName: String): InstalledPlugin
    fun installed(): List<InstalledPlugin>
    /** Registers MainAPIs/extractors into core. */
    suspend fun loadAll(): List<LoadResult>
}

data class RepoInfo(val url: String, val name: String, val pluginListUrls: List<String>)

data class PluginInfo(
    val internalName: String,
    val name: String,
    val version: Int,
    val url: String,
    val repoUrl: String,
    val language: String?,
    val tvTypes: List<String>,
    val iconUrl: String?,
    val status: Int,
    /** "sha256-<hex>" from plugins.json; verify on download. */
    val fileHash: String? = null,
)

data class InstalledPlugin(val info: PluginInfo, val dexPath: String, val jarPath: String)

sealed interface LoadResult {
    data class Ok(val name: String, val apis: List<String>, val hasSettingsUi: Boolean = false) : LoadResult
    /** [blocked]: refused by the safety check; the capability names the user would have to allow (empty = a real failure). */
    data class Failed(val name: String, val error: String, val missingClasses: List<String>, val blocked: List<String> = emptyList()) : LoadResult
}
