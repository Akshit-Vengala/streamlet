package com.lagradost.cloudstream3

import android.app.Activity
import android.content.Context
import cloudstream.desktop.androidshim.ShimRuntime
import com.lagradost.cloudstream3.utils.UiText

/** Desktop CommonActivity: toasts go to the app's notification sink; there is no Activity. */
object CommonActivity {
    const val TAG = "COMPACT"

    /** Always null on desktop unless the app installs a fake Activity. */
    var activity: Activity? = null

    fun showToast(message: Int, duration: Int? = null) {
        ShimRuntime.context().getString(message).takeIf { it.isNotEmpty() }?.let { ShimRuntime.toast(it) }
    }

    fun showToast(message: String?, duration: Int? = null) {
        if (message != null) ShimRuntime.toast(message)
    }

    fun showToast(message: UiText?, duration: Int? = null) {
        message?.asStringNull(ShimRuntime.context())?.let { ShimRuntime.toast(it) }
    }

    fun showToast(act: Activity?, message: String?, duration: Int? = null) = showToast(message, duration)

    fun showToast(act: Activity?, message: Int, duration: Int? = null) = showToast(message, duration)

    fun showToast(act: Activity?, text: UiText, duration: Int) = showToast(text, duration)

    fun showToast(context: Context?, message: String?, duration: Int? = null) = showToast(message, duration)
}
