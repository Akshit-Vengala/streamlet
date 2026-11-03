package cloudstream.desktop.player

import cloudstream.desktop.contracts.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.awt.Component
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * Fallback: spawns mpv.exe (state polled over its JSON IPC named pipe) or VLC (fire and forget,
 * no position reporting). Opens its own window; attachTo is a no-op.
 */
class ExternalPlayer(private val kind: Kind) : DesktopPlayer {
    enum class Kind { MPV, VLC }

    private val st = MutableStateFlow(PlayerState(0, 0, false, false, null))
    override val state: StateFlow<PlayerState> get() = st
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var proc: Process? = null
    private var pipe: Ipc? = null
    private var reqId = 0
    private var pollJob: Job? = null
    private val pipeName = "cloudstream-mpv-${ProcessHandle.current().pid()}"
    /** mpv --input-ipc-server: a named pipe on Windows, a Unix domain socket elsewhere. */
    private val ipcPath: String = if (cloudstream.desktop.contracts.Os.isWindows) "\\\\.\\pipe\\$pipeName"
        else File(System.getProperty("java.io.tmpdir"), "$pipeName.sock").absolutePath

    /** Byte stream to mpv's JSON IPC. */
    private interface Ipc { fun write(b: ByteArray); fun read(): Int; fun close() }

    private class PipeIpc(path: String) : Ipc {
        private val f = RandomAccessFile(path, "rw")
        override fun write(b: ByteArray) = f.write(b)
        override fun read() = f.read()
        override fun close() = f.close()
    }

    private class UnixIpc(path: String) : Ipc {
        private val ch = java.nio.channels.SocketChannel.open(java.net.StandardProtocolFamily.UNIX)
            .apply { connect(java.net.UnixDomainSocketAddress.of(path)) }
        private val input = java.nio.channels.Channels.newInputStream(ch)
        private val output = java.nio.channels.Channels.newOutputStream(ch)
        override fun write(b: ByteArray) { output.write(b); output.flush() }
        override fun read() = input.read()
        override fun close() = ch.close()
    }

    override fun attachTo(component: Component) {}

    override fun open(source: PlaybackSource) {
        stop()
        val args = when (kind) {
            Kind.MPV -> {
                val exe = MpvLocator.findExe() ?: error("mpv.exe not found")
                val cfg = MpvOptions.map(source)
                listOf(exe.absolutePath, "--input-ipc-server=$ipcPath", "--force-window=yes",
                    "--keep-open=yes") + cfg.toCommandLine()
            }
            Kind.VLC -> {
                val exe = MpvLocator.findVlc() ?: error("vlc.exe not found")
                val l = arrayListOf(exe.absolutePath)
                (source.referer ?: source.headers.entries.firstOrNull { it.key.equals("referer", true) }?.value)
                    ?.let { l += "--http-referrer=$it" }
                source.headers.entries.firstOrNull { it.key.equals("user-agent", true) }
                    ?.let { l += "--http-user-agent=${it.value}" }
                if (source.startMs > 0) l += "--start-time=${source.startMs / 1000}"
                source.subtitles.firstOrNull()?.let { l += "--sub-file=${it.url}" }
                l + source.url
            }
        }
        try {
            proc = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        } catch (e: Exception) {
            st.value = PlayerState(0, 0, false, false, "Failed to launch ${kind}: ${e.message}")
            return
        }
        st.value = PlayerState(source.startMs, 0, true, true, null)
        if (kind == Kind.MPV) pollJob = scope.launch { pollLoop() }
    }

    private fun connect(): Ipc? {
        repeat(20) {
            try { return if (cloudstream.desktop.contracts.Os.isWindows) PipeIpc(ipcPath) else UnixIpc(ipcPath) } catch (_: Exception) { Thread.sleep(250) }
        }
        return null
    }

    @Synchronized
    private fun request(vararg command: String): String? {
        val p = pipe ?: return null
        val id = ++reqId
        val json = command.joinToString(",", "[", "]") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }
        p.write("{\"command\":$json,\"request_id\":$id}\n".toByteArray())
        while (true) {
            val line = readLine(p) ?: return null
            if (line.contains("\"request_id\":$id")) return line
        }
    }

    private fun readLine(p: Ipc): String? {
        val sb = java.io.ByteArrayOutputStream()
        while (true) {
            val b = p.read()
            if (b < 0) return null
            if (b == '\n'.code) return sb.toString(Charsets.UTF_8)
            sb.write(b)
        }
    }

    private fun get(name: String): String? {
        val r = request("get_property", name) ?: return null
        if (!r.contains("\"error\":\"success\"")) return null
        return Regex("\"data\":(\"[^\"]*\"|[^,}]+)").find(r)?.groupValues?.get(1)?.trim('"')
    }

    private suspend fun pollLoop() {
        pipe = connect()
        if (pipe == null) {
            st.value = st.value.copy(error = "mpv IPC pipe unavailable"); return
        }
        try {
            while (currentCoroutineContext().isActive && proc?.isAlive == true) {
                val pos = get("time-pos")?.toDoubleOrNull() ?: 0.0
                val dur = get("duration")?.toDoubleOrNull() ?: 0.0
                val paused = get("pause") == "true"
                val cache = get("paused-for-cache") == "true"
                st.value = PlayerState((pos * 1000).toLong(), (dur * 1000).toLong(), !paused, cache, null)
                delay(500)
            }
        } catch (_: Exception) { }
        st.value = st.value.copy(playing = false)
    }

    override fun play() { request("set_property", "pause", "no") }
    override fun pause() { request("set_property", "pause", "yes") }
    override fun seek(ms: Long) { request("seek", "%.3f".format(Locale.ROOT, ms / 1000.0), "absolute") }
    override fun setSubtitle(track: SubtitleSource?) {
        if (track == null) request("set_property", "sid", "no") else request("sub-add", track.url, "select")
    }

    override fun stop() {
        pollJob?.cancel(); pollJob = null
        try { pipe?.close() } catch (_: Exception) {}
        pipe = null
        proc?.destroy(); proc = null
        st.value = PlayerState(0, 0, false, false, null)
    }

    override fun setVolume(percent: Int) { request("set_property", "volume", percent.toString()) }
    override fun setSpeed(speed: Double) { request("set_property", "speed", speed.toString()) }
    override fun audioTracks(): List<MediaTrack> = emptyList()
    override fun subtitleTracks(): List<MediaTrack> = emptyList()
    override fun setAudioTrack(id: Int) { request("set_property", "aid", id.toString()) }
    override fun setSubtitleTrack(id: Int?) { request("set_property", "sid", id?.toString() ?: "no") }
    override fun toggleFullscreen() { request("cycle", "fullscreen") }
    override fun release() { stop(); scope.cancel() }
}
