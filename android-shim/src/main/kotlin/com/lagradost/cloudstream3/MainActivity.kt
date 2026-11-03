package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.utils.Event

/**
 * Desktop MainActivity: only the static events plugins subscribe to. The desktop app should
 * invoke [afterPluginsLoadedEvent] once all plugins are loaded and [reloadHomeEvent] to refresh home.
 */
class MainActivity {
    companion object {
        val afterPluginsLoadedEvent = Event<Boolean>()
        val reloadHomeEvent = Event<Boolean>()
        val mainPluginsLoadedEvent = Event<Boolean>()
        val afterRepositoryLoadedEvent = Event<Boolean>()
        val bookmarksUpdatedEvent = Event<Boolean>()
        val reloadLibraryEvent = Event<Boolean>()
        val reloadAccountEvent = Event<Boolean>()
    }
}
