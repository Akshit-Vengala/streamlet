package com.lagradost.cloudstream3

import android.content.Context
import android.content.SharedPreferences
import cloudstream.desktop.androidshim.ShimRuntime
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson

/**
 * Desktop CloudStreamApp. Key helpers use the default SharedPreferences (backed by the KeyValueStore),
 * storing values as JSON strings under "folder/path" exactly like upstream's DataStore.
 */
class CloudStreamApp {
    companion object {
        private fun prefs(): SharedPreferences = androidx.preference.PreferenceManager.getDefaultSharedPreferences(ShimRuntime.context())

        val context: Context?
            get() = ShimRuntime.context()

        @JvmStatic
        fun getFolderName(folder: String, path: String) = "$folder/$path"

        @OptIn(InternalAPI::class)
        fun <T : Any> getKeyClass(path: String, valueType: Class<T>): T? =
            prefs().getString(path, null)?.let { runCatching { parseJson(it, valueType.kotlin) }.getOrNull() }

        fun <T : Any> setKeyClass(path: String, value: T) {
            prefs().edit().putString(path, value.toJson()).apply()
        }

        fun <T> setKey(path: String, value: T) {
            prefs().edit().putString(path, (value as Any).toJson()).apply()
        }

        fun <T> setKey(folder: String, path: String, value: T) = setKey(getFolderName(folder, path), value)

        fun removeKey(path: String) {
            prefs().edit().remove(path).apply()
        }

        fun removeKey(folder: String, path: String) = removeKey(getFolderName(folder, path))

        fun getKeys(folder: String): List<String>? =
            prefs().all.keys.filter { it.startsWith("$folder/") }

        fun removeKeys(folder: String): Int? {
            val keys = getKeys(folder) ?: return null
            val e = prefs().edit()
            keys.forEach { e.remove(it) }
            e.apply()
            return keys.size
        }

        inline fun <reified T : Any> getKey(path: String): T? = getKeyClass(path, T::class.java)
        inline fun <reified T : Any> getKey(path: String, defVal: T?): T? = getKeyClass(path, T::class.java) ?: defVal
        inline fun <reified T : Any> getKey(folder: String, path: String): T? = getKey(getFolderName(folder, path))
        inline fun <reified T : Any> getKey(folder: String, path: String, defVal: T?): T? = getKey(getFolderName(folder, path), defVal)

        fun openBrowser(url: String, fallbackWebView: Boolean = false, fragment: Any? = null) {
            ShimRuntime.openUrl(url)
        }
    }
}
