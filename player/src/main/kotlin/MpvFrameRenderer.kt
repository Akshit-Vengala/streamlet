package cloudstream.desktop.player

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Software rendering through libmpv's render API (MPV_RENDER_API_TYPE_SW): mpv scales and draws each video frame
 * (subtitles included) into a memory buffer, and the UI paints that buffer like any image. No native child window,
 * so normal UI (controls, menus) can sit on top of the video in the same window.
 *
 * All render-API calls happen on one dedicated thread, as libmpv requires.
 */
class MpvFrameRenderer(private val api: MpvApi, mpv: Pointer) {
    /** One finished frame: BGRx bytes (B, G, R, unused), [width] * 4 bytes per row. */
    class Frame(val width: Int, val height: Int, val bytes: ByteArray)

    private val ctx: Pointer
    private val lock = Object()
    private var front: Frame? = null
    private val _seq = MutableStateFlow(0L)
    /** Bumped for every new frame; observe it and call [latest]. */
    val frameSeq: StateFlow<Long> = _seq
    @Volatile private var targetW = 0
    @Volatile private var targetH = 0
    @Volatile private var running = true
    private val thread: Thread

    init {
        val apiType = Memory(3).apply { setString(0, "sw", "US-ASCII") }
        val params = params(listOf(MPV_RENDER_PARAM_API_TYPE to apiType))
        val ref = PointerByReference()
        val rc = api.mpv_render_context_create(ref, mpv, params)
        check(rc >= 0) { "mpv_render_context_create failed: ${api.mpv_error_string(rc)}" }
        ctx = ref.value
        thread = Thread(::loop, "mpv-render").apply { isDaemon = true; start() }
    }

    /** Pixel size of the area the video is shown in (capped at ~2 MP; the UI scales the image up). */
    fun setSize(width: Int, height: Int) {
        var w = width.coerceAtLeast(2); var h = height.coerceAtLeast(2)
        val maxPixels = 1920 * 1080
        if (w.toLong() * h > maxPixels) {
            val s = Math.sqrt(maxPixels.toDouble() / (w.toLong() * h))
            w = (w * s).toInt(); h = (h * s).toInt()
        }
        targetW = w and 0x7FFFFFFE; targetH = h // even width keeps rows aligned
    }

    /** The most recent frame (copy it before the next one arrives; the buffer is reused). */
    fun <T> withLatest(block: (Frame) -> T): T? = synchronized(lock) { front?.let(block) }

    private fun loop() {
        var w = 0; var h = 0
        var pixels: Memory? = null
        var sizeMem = Memory(8); val strideMem = Memory(8); val format = Memory(5).apply { setString(0, "bgr0", "US-ASCII") }
        var back: ByteArray? = null
        while (running) {
            try {
                val tw = targetW; val th = targetH
                if (tw <= 0 || th <= 0) { Thread.sleep(20); continue }
                val resized = tw != w || th != h
                if (resized) {
                    w = tw; h = th
                    pixels = Memory(w.toLong() * h * 4)
                    sizeMem = Memory(8).apply { setInt(0, w); setInt(4, h) }
                    strideMem.setLong(0, w.toLong() * 4)
                    back = ByteArray(w * h * 4)
                }
                val flags = api.mpv_render_context_update(ctx)
                if (flags and MPV_RENDER_UPDATE_FRAME != 0L || resized) {
                    val p = params(listOf(
                        MPV_RENDER_PARAM_SW_SIZE to sizeMem, MPV_RENDER_PARAM_SW_FORMAT to format,
                        MPV_RENDER_PARAM_SW_STRIDE to strideMem, MPV_RENDER_PARAM_SW_POINTER to pixels!!,
                    ))
                    if (api.mpv_render_context_render(ctx, p) >= 0) {
                        val buf = back!!
                        pixels!!.read(0, buf, 0, buf.size)
                        synchronized(lock) {
                            // Swap: the UI reads `front`, we fill the previous one next time.
                            val old = front
                            front = Frame(w, h, buf)
                            back = if (old != null && old.bytes.size == buf.size) old.bytes else ByteArray(buf.size)
                        }
                        _seq.value = _seq.value + 1
                    }
                } else Thread.sleep(4)
            } catch (_: InterruptedException) {
                return
            } catch (t: Throwable) {
                System.err.println("[mpv-render] $t"); Thread.sleep(50)
            }
        }
    }

    /** Stops rendering and frees the render context (must happen before mpv_terminate_destroy). */
    fun release() {
        running = false
        thread.interrupt()
        runCatching { thread.join(1000) }
        api.mpv_render_context_free(ctx)
    }

    private fun params(entries: List<Pair<Int, Pointer>>): Memory {
        // struct mpv_render_param { int type; void *data; } on 64-bit = 16 bytes; terminated by type 0.
        val m = Memory(16L * (entries.size + 1)).apply { clear() }
        entries.forEachIndexed { i, (type, data) -> m.setInt(16L * i, type); m.setPointer(16L * i + 8, data) }
        return m
    }

    companion object {
        private const val MPV_RENDER_PARAM_API_TYPE = 1
        private const val MPV_RENDER_PARAM_SW_SIZE = 17
        private const val MPV_RENDER_PARAM_SW_FORMAT = 18
        private const val MPV_RENDER_PARAM_SW_STRIDE = 19
        private const val MPV_RENDER_PARAM_SW_POINTER = 20
        private const val MPV_RENDER_UPDATE_FRAME = 1L
    }
}
