package com.lagradost.cloudstream3.actions

import android.content.Context
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.UiText
import java.util.concurrent.CopyOnWriteArrayList

/** Desktop registry. The app can read [allVideoClickActions] to offer plugin-provided "play with" actions. */
object VideoClickActionHolder {
    val allVideoClickActions: MutableList<VideoClickAction> = CopyOnWriteArrayList()

    fun getByUniqueId(uniqueId: String): VideoClickAction? =
        allVideoClickActions.firstOrNull { it.uniqueId() == uniqueId }
}

/** Same members as upstream except the Android-activity based launch helpers. */
abstract class VideoClickAction {
    abstract val name: UiText

    open val oneSource: Boolean = false
    open val isPlayer: Boolean = false
    open val sourceTypes: Set<ExtractorLinkType> = ExtractorLinkType.entries.toSet()

    /** Determines which plugin a given provider is from. This is the full path to the plugin. */
    var sourcePlugin: String? = null

    fun uniqueId() = "$sourcePlugin:${this::class.qualifiedName}"

    @Throws(Throwable::class)
    abstract fun shouldShow(context: Context?, video: ResultEpisode?): Boolean

    fun shouldShowSafe(context: Context?, video: ResultEpisode?): Boolean =
        try { shouldShow(context, video) } catch (_: Throwable) { false }

    @Throws(Throwable::class)
    abstract suspend fun runAction(context: Context?, video: ResultEpisode, result: LinkLoadingResult, index: Int?)
}
