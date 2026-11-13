package com.lagradost.cloudstream3.syncproviders

import cloudstream.desktop.androidshim.Stub
import kotlin.coroutines.Continuation

/**
 * Stub of the app's SyncRepo (no sync accounts on desktop). Hand-written in Kotlin rather than generated Java because
 * upstream's `suspend fun library(): Result<...>` compiles to the JVM name `library-IoAF18A`, which Java cannot declare.
 */
open class SyncRepo() {
    constructor(@Suppress("UNUSED_PARAMETER") api: SyncAPI) : this() { Stub.hit("com.lagradost.cloudstream3.syncproviders.SyncRepo.<init>") }

    open fun authUser(): AuthUser { Stub.hit("com.lagradost.cloudstream3.syncproviders.SyncRepo.authUser"); return AuthUser() }

    /**
     * `library()` as plugins call it: the unboxed `Result<LibraryMetadata?>`. null is the boxed form of
     * `Result.success(null)`, i.e. "no library", never a failure.
     */
    @JvmName("library-IoAF18A")
    fun library(@Suppress("UNUSED_PARAMETER") continuation: Continuation<*>): Any? {
        Stub.hit("com.lagradost.cloudstream3.syncproviders.SyncRepo.library")
        return null
    }
}
