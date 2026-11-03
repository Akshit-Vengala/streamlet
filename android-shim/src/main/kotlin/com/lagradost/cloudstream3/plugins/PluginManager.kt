package com.lagradost.cloudstream3.plugins

/** Mirror of upstream's stored plugin record (only fields plugins read). */
data class PluginData(
    val internalName: String,
    val url: String?,
    val isOnline: Boolean,
    val filePath: String,
    val version: Int,
)

/**
 * Desktop surface of upstream `PluginManager` that plugins reference. The real loader
 * (Track B) updates [pluginsOnline] and sets [unloadHandler].
 */
object PluginManager {
    var pluginsOnline: Array<PluginData> = emptyArray()

    var currentlyLoading: String? = null

    /** Installed by the loader so a plugin can ask to be unloaded by its absolute path. */
    var unloadHandler: ((String) -> Unit)? = null

    fun unloadPlugin(absolutePath: String) {
        unloadHandler?.invoke(absolutePath)
    }
}
