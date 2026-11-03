package com.lagradost.cloudstream3.plugins

import android.content.Context

/** TEST-ONLY stand-in for the app-only `Plugin` class (Track C provides the real one). */
abstract class Plugin : BasePlugin() {
    var resources: Any? = null
    var openSettings: ((Context) -> Unit)? = null
    open fun load(context: Context) { load() }
}
