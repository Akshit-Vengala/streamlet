package cloudstream.desktop.player

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/** Minimal libmpv client API. Strings are UTF-8. */
interface MpvApi : Library {
    fun mpv_create(): Pointer?
    fun mpv_initialize(h: Pointer): Int
    fun mpv_terminate_destroy(h: Pointer)
    fun mpv_set_option_string(h: Pointer, name: String, value: String): Int
    fun mpv_set_property_string(h: Pointer, name: String, value: String): Int
    fun mpv_get_property_string(h: Pointer, name: String): Pointer?
    fun mpv_command(h: Pointer, args: Array<String?>): Int
    /** Queues the command and returns at once; the result arrives as MPV_EVENT_COMMAND_REPLY (id 5) with [reply]. */
    fun mpv_command_async(h: Pointer, reply: Long, args: Array<String?>): Int
    fun mpv_wait_event(h: Pointer, timeout: Double): Pointer
    fun mpv_free(p: Pointer?)
    fun mpv_error_string(err: Int): String
    fun mpv_request_log_messages(h: Pointer, minLevel: String): Int

    // Render API (client.h / render.h): mpv draws frames into our memory; the app paints them itself.
    fun mpv_render_context_create(res: com.sun.jna.ptr.PointerByReference, mpv: Pointer, params: Pointer): Int
    fun mpv_render_context_render(ctx: Pointer, params: Pointer): Int
    fun mpv_render_context_update(ctx: Pointer): Long
    fun mpv_render_context_free(ctx: Pointer)

    /** libc, only for setlocale on Linux/macOS. */
    interface LibC : Library { fun setlocale(category: Int, locale: String?): Pointer? }

    companion object {
        fun load(): MpvApi {
            val opts = mapOf(Library.OPTION_STRING_ENCODING to "UTF-8")
            // libmpv refuses to run ("Non-C locale detected", mpv_create returns NULL) unless LC_NUMERIC is "C";
            // the JVM sets the user's locale at start-up on Linux. LC_NUMERIC = 1 (glibc), 4 (macOS).
            if (!cloudstream.desktop.contracts.Os.isWindows) runCatching {
                Native.load("c", LibC::class.java).setlocale(if (cloudstream.desktop.contracts.Os.isMac) 4 else 1, "C")
            }
            val tries = listOfNotNull(MpvLocator.findDll()?.absolutePath) + MpvLocator.systemLibNames
            var last: Throwable? = null
            for (lib in tries) {
                try { return Native.load(lib, MpvApi::class.java, opts) } catch (e: UnsatisfiedLinkError) { last = e }
            }
            throw IllegalStateException("libmpv not found/loadable (tried ${tries.joinToString()}). ${MpvLocator.installHint} (${last?.message})", last)
        }
    }
}
