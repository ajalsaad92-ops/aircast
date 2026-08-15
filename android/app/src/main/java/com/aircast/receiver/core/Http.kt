package com.aircast.receiver.core

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ServerSocketFactory

/**
 * A deliberately small, dependency-free HTTP/1.1 server.
 *
 * Why hand-rolled instead of NanoHTTPD/Ktor: three of the four protocols this app
 * speaks are *not* well-behaved HTTP. AirPlay sends binary property-list bodies on
 * `POST /play`, expects a `101 Switching Protocols` hijack on `POST /reverse`, and
 * UPnP/GENA uses the non-standard verbs `SUBSCRIBE`/`UNSUBSCRIBE`/`NOTIFY`. Owning
 * the socket loop keeps all of that straightforward.
 */
class HttpRequest(
    val method: String,
    val rawTarget: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray,
    val remoteIp: String,
    val localIp: String,
    val localPort: Int,
    val secure: Boolean,
) {
    fun header(name: String): String? = headers[name.lowercase(Locale.US)]
    fun bodyText(): String = String(body, Charsets.UTF_8)
    /** Latin-1 view of the body — used to scrape URLs out of binary plists. */
    fun bodyLatin1(): String = String(body, Charsets.ISO_8859_1)

    val origin: String
        get() = (if (secure) "https://" else "http://") + localIp + ":" + localPort
}

class HttpResponse(
    val status: Int = 200,
    val contentType: String? = "text/plain; charset=utf-8",
    val body: ByteArray = EMPTY,
    val headers: MutableMap<String, String> = LinkedHashMap(),
    /** When true the socket is *not* closed after the response (AirPlay reverse channel). */
    val hijack: Boolean = false,
    val stream: InputStream? = null,
    val streamLength: Long = -1,
) {
    companion object {
        private val EMPTY = ByteArray(0)

        fun text(s: String, status: Int = 200) =
            HttpResponse(status, "text/plain; charset=utf-8", s.toByteArray(Charsets.UTF_8))

        fun html(s: String, status: Int = 200) =
            HttpResponse(status, "text/html; charset=utf-8", s.toByteArray(Charsets.UTF_8))

        fun xml(s: String, status: Int = 200) =
            HttpResponse(status, "text/xml; charset=\"utf-8\"", s.toByteArray(Charsets.UTF_8))

        fun json(s: String, status: Int = 200) =
            HttpResponse(status, "application/json; charset=utf-8", s.toByteArray(Charsets.UTF_8))

        fun bytes(b: ByteArray, type: String, status: Int = 200) = HttpResponse(status, type, b)

        fun empty(status: Int = 200) = HttpResponse(status, null, EMPTY)

        fun notFound() = text("Not Found", 404)

        fun statusText(code: Int): String = when (code) {
            101 -> "Switching Protocols"
            200 -> "OK"
            204 -> "No Content"
            206 -> "Partial Content"
            301 -> "Moved Permanently"
            302 -> "Found"
            304 -> "Not Modified"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            412 -> "Precondition Failed"
            500 -> "Internal Server Error"
            501 -> "Not Implemented"
            503 -> "Service Unavailable"
            else -> "OK"
        }
    }
}

typealias HttpHandler = (HttpRequest) -> HttpResponse?

class HttpServer(
    private val port: Int,
    private val label: String,
    private val secure: Boolean = false,
    private val serverSocketFactory: ServerSocketFactory? = null,
    /** Per-sender server token — "AirTunes/220.68" on the AirPlay port, matching AirScreen v2.16.1 h9/c. */
    private val serverToken: String? = null,
    private val handler: HttpHandler,
) {
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool() as ThreadPoolExecutor

    val boundPort: Int get() = serverSocket?.localPort ?: port

    @Throws(IOException::class)
    fun start() {
        if (running.get()) return
        val factory = serverSocketFactory ?: ServerSocketFactory.getDefault()
        var lastError: IOException? = null
        // A restart rebinds the same port moments after the old socket closed; the OS
        // may still be releasing it, so retry a few times before giving up.
        repeat(6) { attempt ->
            try {
                val ss = factory.createServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port), 64)
                serverSocket = ss
                running.set(true)
                acceptThread = Thread({ acceptLoop(ss) }, "http-$label-accept").apply {
                    isDaemon = true
                    start()
                }
                Logger.i(label, "listening on port ${ss.localPort}${if (secure) " (TLS)" else ""}")
                return
            } catch (e: java.net.BindException) {
                lastError = e
                if (attempt < 5) try { Thread.sleep(200) } catch (_: InterruptedException) {}
            }
        }
        throw lastError ?: IOException("could not bind $label port $port")
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        // Wait for the accept thread to unblock and the OS to release the port,
        // so an immediate restart (settings change) can rebind without EADDRINUSE.
        try { acceptThread?.join(700) } catch (_: Exception) {}
        acceptThread = null
        pool.shutdownNow()
        Logger.i(label, "stopped")
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            val socket = try {
                ss.accept()
            } catch (e: Exception) {
                if (running.get()) Logger.w(label, "accept failed: ${e.message}")
                break
            }
            try {
                pool.execute { serve(socket) }
            } catch (_: Exception) {
                closeQuietly(socket)
            }
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 30_000
            val input = BufferedInputStream(socket.getInputStream(), 16 * 1024)
            val output = socket.getOutputStream()
            val remoteIp = (socket.inetAddress as? InetAddress)?.hostAddress ?: "?"
            val localIp = socket.localAddress?.hostAddress ?: "127.0.0.1"

            while (running.get() && !socket.isClosed) {
                val req = try {
                    readRequest(input, remoteIp, localIp, socket.localPort) ?: break
                } catch (_: SocketTimeoutException) {
                    break
                }

                val res = try {
                    handler(req) ?: HttpResponse.notFound()
                } catch (e: Exception) {
                    Logger.w(label, "handler error on ${req.method} ${req.path}: ${e.message}")
                    HttpResponse.text("Internal Server Error", 500)
                }

                writeResponse(output, req, res)

                if (res.hijack) {
                    // AirPlay's reverse channel: the sender keeps this socket for events.
                    socket.soTimeout = 0
                    try {
                        val buf = ByteArray(1024)
                        while (input.read(buf) >= 0) { /* drain until the sender hangs up */ }
                    } catch (_: Exception) {
                    }
                    break
                }

                val connection = req.header("connection")?.lowercase(Locale.US)
                if (connection == "close" || req.header("http-version") == "HTTP/1.0") break
            }
        } catch (_: Exception) {
            // client vanished; nothing actionable
        } finally {
            closeQuietly(socket)
        }
    }

    private fun readRequest(
        input: BufferedInputStream,
        remoteIp: String,
        localIp: String,
        localPort: Int,
    ): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        if (requestLine.isBlank()) return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase(Locale.US)
        val target = parts[1]
        val version = if (parts.size > 2) parts[2] else "HTTP/1.1"

        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            headers[line.substring(0, idx).trim().lowercase(Locale.US)] =
                line.substring(idx + 1).trim()
        }
        headers["http-version"] = version

        val length = headers["content-length"]?.toLongOrNull() ?: 0L
        val chunked = headers["transfer-encoding"]?.contains("chunked", true) == true
        val body: ByteArray = when {
            chunked -> readChunked(input)
            length > 0 -> readExactly(input, length.toInt())
            else -> ByteArray(0)
        }

        val qIdx = target.indexOf('?')
        val path = if (qIdx >= 0) target.substring(0, qIdx) else target
        val query = if (qIdx >= 0) parseQuery(target.substring(qIdx + 1)) else emptyMap()

        return HttpRequest(
            method = method,
            rawTarget = target,
            path = decodePath(path),
            query = query,
            headers = headers,
            body = body,
            remoteIp = remoteIp,
            localIp = localIp,
            localPort = localPort,
            secure = secure,
        )
    }

    private fun writeResponse(out: OutputStream, req: HttpRequest, res: HttpResponse) {
        val sb = StringBuilder(256)
        sb.append("HTTP/1.1 ").append(res.status).append(' ')
            .append(HttpResponse.statusText(res.status)).append("\r\n")

        val hasEntity = res.stream != null || res.body.isNotEmpty()
        val declaredLength = when {
            res.stream != null -> res.streamLength
            else -> res.body.size.toLong()
        }

        if (res.contentType != null && hasEntity) sb.append("Content-Type: ").append(res.contentType).append("\r\n")
        if (res.status != 101) {
            if (declaredLength >= 0) sb.append("Content-Length: ").append(declaredLength).append("\r\n")
        }
        sb.append("Date: ").append(httpDate()).append("\r\n")
        sb.append("Server: ").append(serverToken ?: SERVER_TOKEN).append("\r\n")
        // AirScreen v2.16.1 h9/c echoes the request sequence number back on every
        // response; Apple senders treat a missing CSeq on /play and friends as fatal.
        val cseq = req.header("cseq")
        if (!cseq.isNullOrBlank()) sb.append("CSeq: ").append(cseq).append("\r\n")
        // The React UI and the sender page both call this server cross-origin.
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Headers: *\r\n")
        sb.append("Access-Control-Allow-Methods: GET, POST, PUT, OPTIONS, SUBSCRIBE, UNSUBSCRIBE\r\n")
        for ((k, v) in res.headers) sb.append(k).append(": ").append(v).append("\r\n")
        if (res.status != 101 && !res.headers.containsKey("Connection")) {
            sb.append("Connection: keep-alive\r\n")
        }
        sb.append("\r\n")

        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (req.method != "HEAD" && res.status != 101) {
            val stream = res.stream
            if (stream != null) {
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                try { stream.close() } catch (_: Exception) {}
            } else if (res.body.isNotEmpty()) {
                out.write(res.body)
            }
        }
        out.flush()
    }

    companion object {
        const val SERVER_TOKEN = "AirCast/1.0 UPnP/1.0 DLNADOC/1.50"

        private val HTTP_DATE = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }

        fun httpDate(): String = synchronized(HTTP_DATE) { HTTP_DATE.format(Date()) }

        fun closeQuietly(socket: Socket?) {
            try { socket?.close() } catch (_: Exception) {}
        }

        /** Reads a CRLF/LF terminated line without buffering past it (binary bodies stay intact). */
        fun readLine(input: InputStream): String? {
            val buf = ByteArrayOutputStream(128)
            while (true) {
                val c = input.read()
                if (c < 0) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
                if (c == '\n'.code) break
                if (c != '\r'.code) buf.write(c)
            }
            return buf.toString("ISO-8859-1")
        }

        fun readExactly(input: InputStream, length: Int): ByteArray {
            val out = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(out, read, length - read)
                if (n < 0) break
                read += n
            }
            return if (read == length) out else out.copyOf(read)
        }

        private fun readChunked(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            while (true) {
                val sizeLine = readLine(input) ?: break
                val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
                if (size == 0) { readLine(input); break }
                out.write(readExactly(input, size))
                readLine(input)
            }
            return out.toByteArray()
        }

        fun parseQuery(raw: String): Map<String, String> {
            if (raw.isEmpty()) return emptyMap()
            val map = LinkedHashMap<String, String>()
            for (pair in raw.split('&')) {
                if (pair.isEmpty()) continue
                val i = pair.indexOf('=')
                val k = if (i >= 0) pair.substring(0, i) else pair
                val v = if (i >= 0) pair.substring(i + 1) else ""
                map[urlDecode(k)] = urlDecode(v)
            }
            return map
        }

        fun urlDecode(s: String): String = try {
            URLDecoder.decode(s, "UTF-8")
        } catch (_: Exception) {
            s
        }

        private fun decodePath(p: String): String = try {
            URLDecoder.decode(p, "UTF-8")
        } catch (_: Exception) {
            p
        }
    }
}
