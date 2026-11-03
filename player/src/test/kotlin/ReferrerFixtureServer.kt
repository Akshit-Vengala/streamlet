package cloudstream.desktop.player

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress

/**
 * Local fixture: every request must carry `Referer: <requiredReferer>` or gets 403.
 * Serves /index.m3u8, /sub.vtt, and /seg*.ts from [mediaDir] (a directory of generated TS segments,
 * e.g. `ffmpeg -i in.mp4 -f hls -hls_time 2 -hls_list_size 0 mediaDir/index.m3u8`).
 * If mediaDir has no index.m3u8, only the header check and the VTT are available.
 */
class ReferrerFixtureServer(val requiredReferer: String, private val mediaDir: File? = null) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val seenReferers = java.util.concurrent.CopyOnWriteArrayList<String?>()
    val port get() = server.address.port
    val baseUrl get() = "http://127.0.0.1:$port"

    init {
        server.createContext("/") { ex ->
            val ref = ex.requestHeaders.getFirst("Referer")
            seenReferers += ref
            val path = ex.requestURI.path
            val (code, type, body) = when {
                ref != requiredReferer -> Triple(403, "text/plain", "forbidden".toByteArray())
                path == "/sub.vtt" -> Triple(
                    200, "text/vtt",
                    "WEBVTT\n\n00:00:00.000 --> 00:00:05.000\nHello\n".toByteArray(),
                )
                mediaDir != null && File(mediaDir, path.trimStart('/')).isFile -> {
                    val f = File(mediaDir, path.trimStart('/'))
                    Triple(200, if (path.endsWith("m3u8")) "application/vnd.apple.mpegurl" else "video/mp2t", f.readBytes())
                }
                else -> Triple(404, "text/plain", "nf".toByteArray())
            }
            ex.responseHeaders.add("Content-Type", type)
            ex.sendResponseHeaders(code, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
    }

    override fun close() = server.stop(0)
}
