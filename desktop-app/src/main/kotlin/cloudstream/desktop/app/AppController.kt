package cloudstream.desktop.app

import cloudstream.desktop.app.domain.MediaCard
import cloudstream.desktop.app.domain.Provider
import cloudstream.desktop.app.vm.DetailsViewModel
import cloudstream.desktop.app.vm.ExtensionsViewModel
import cloudstream.desktop.app.vm.HomeViewModel
import cloudstream.desktop.app.vm.LibraryViewModel
import cloudstream.desktop.app.vm.Navigator
import cloudstream.desktop.app.vm.PlayRequest
import cloudstream.desktop.app.vm.Screen
import cloudstream.desktop.app.vm.SearchViewModel
import cloudstream.desktop.app.vm.titleId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Owns the long-lived view models and navigation. UI-framework free, so it is unit-testable. */
class AppController(val services: AppServices) {
    val nav = Navigator()
    val extensions = ExtensionsViewModel(services.scope, services.extensions) { services.providersChanged() }
    // Lambdas, not bound references: services.settings / services.library change when the profile is switched.
    // Search: enabled providers minus this profile's Search filter.
    val search = SearchViewModel(services.scope, { services.catalog.providers() }, { services.settings.isEnabled(it) && services.settings.searchIncludes(it) })
    val home = HomeViewModel(services.scope) { services.catalog.providers().filter { services.settings.isEnabled(it.name) } }
    val library = LibraryViewModel({ services.library })
    private val detailsCache = HashMap<String, DetailsViewModel>()

    /** App self-updater (GitHub Releases of the repo set at build time; off when none). */
    val updater = cloudstream.desktop.app.update.AppUpdater(services.scope)

    fun start() {
        library.refresh() // statuses for the cards' right-click menus, Continue Watching on Home
        // App updates: shortly after start-up, then every 12 h, unless turned off in Settings > About.
        if (updater.configured && services.mode == AppServices.Mode.Real) services.scope.launch {
            kotlinx.coroutines.delay(20_000)
            while (true) {
                if (services.globalSettings.appUpdateCheck.value)
                    updater.check { rel -> services.toast("Version ${rel.version} is available: Settings > About") }?.join()
                kotlinx.coroutines.delay(12 * 60 * 60 * 1000L)
            }
        }
        val boot = extensions.start()
        // Extensions update almost daily: after start-up (Home first, so nothing waits on it) and every 6 h while running.
        services.scope.launch {
            boot.join()
            while (true) {
                val mode = services.globalSettings.extUpdates.value
                if (mode != ExtUpdateMode.Off) extensions.applyUpdates(install = mode == ExtUpdateMode.Auto).forEach { services.toast(it) }
                kotlinx.coroutines.delay(6 * 60 * 60 * 1000L)
            }
        }
    }

    fun providers(): List<Provider> = services.catalog.providers()
    fun providerByName(name: String): Provider? = providers().firstOrNull { it.name == name }

    fun detailsVm(card: MediaCard): DetailsViewModel = detailsCache.getOrPut(titleId(card.provider, card.url)) {
        DetailsViewModel(services.scope, { providerByName(card.provider) }, services.library, services.settings, card).also { it.load() }
    }

    fun openDetails(card: MediaCard) {
        (nav.current as? Screen.Details)?.let { if (it.card.url == card.url && it.card.provider == card.provider) return }
        nav.push(Screen.Details(card))
    }

    fun play(req: PlayRequest) { nav.push(Screen.Player(req)) }

    /** Library status from a card's right-click menu (null = remove); an open details page shows it at once. */
    fun setWatchStatus(card: MediaCard, status: cloudstream.desktop.contracts.WatchStatus?) {
        library.setStatus(card, status)
        detailsCache[titleId(card.provider, card.url)]?.syncStatus()
    }

    fun removeFromContinueWatching(card: MediaCard) = library.removeFromContinueWatching(card)

    /**
     * Episode / Play button: start the source lookup and open the player straight away; the player shows the loading
     * screen with "Skip loading" and starts the best source by priority when the lookup ends (or on skip).
     */
    fun playEpisode(vm: DetailsViewModel, ep: cloudstream.desktop.app.domain.EpisodeItem) {
        val d = (vm.state.value.load as? cloudstream.desktop.app.vm.DetailsLoad.Loaded)?.details ?: return
        val resume = vm.state.value.resumeMs[ep.data]
            ?: services.library.resume(cloudstream.desktop.app.vm.episodeId(vm.card.provider, ep.data))
                ?.takeIf { it.durationMs == 0L || it.positionMs < it.durationMs * 95 / 100 }?.positionMs ?: 0L
        vm.recordHistory(services.history::record, ep)
        library.refresh() // Continue Watching shows it on return
        vm.findSources(ep)
        play(PlayRequest(vm.card, d.name, ep, null, emptyList(), resume))
    }

    /** Main window fullscreen (driven by the player; applied by the window in Main.kt). */
    val fullscreen = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** Billboard "Play": open the title and look up sources for the episode to resume (or the first one). */
    fun openAndPlay(card: MediaCard) {
        openDetails(card)
        val vm = detailsVm(card)
        services.scope.launch {
            vm.state.first { it.load !is cloudstream.desktop.app.vm.DetailsLoad.Loading }
            vm.playTarget()?.let { playEpisode(vm, it) }
        }
    }

    fun back(): Boolean {
        val leaving = nav.current
        val ok = nav.back()
        if (ok && leaving is Screen.Details) detailsCache.remove(titleId(leaving.card.provider, leaving.card.url))?.dismissSources()
        return ok
    }

    /** Switches profile: per-profile library and settings take effect immediately; navigation restarts at Home. */
    fun switchProfile(id: String) {
        if (services.profiles.current.value.id == id) return
        detailsCache.values.forEach { it.dismissSources() }
        detailsCache.clear()
        services.profiles.switchTo(id)
        library.refresh()
        nav.goTo(Screen.Home)
    }

    fun goTo(s: Screen) {
        if (s == Screen.Library) library.refresh()
        detailsCache.clear()
        nav.goTo(s)
    }
}
