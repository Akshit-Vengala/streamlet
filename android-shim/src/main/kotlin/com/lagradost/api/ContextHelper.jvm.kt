@file:JvmName("ContextHelper_jvmKt")

package com.lagradost.api

import cloudstream.desktop.androidshim.ShimRuntime

// Shadows library-jvm's ContextHelper_jvmKt (whose getContext() is always null). Needs android-shim ahead of
// library-jvm on the runtime classpath; see patches/README and IntegrationSmoke's origin check.
fun getContext(): Any? = ShimRuntime.context()

fun setContext(context: Any?) {
    if (context is android.content.Context) ShimRuntime.setContext(context)
}
