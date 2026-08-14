package com.aircast.receiver.net

import com.aircast.receiver.core.HttpRequest
import com.aircast.receiver.core.HttpResponse
import com.aircast.receiver.core.Logger
import jcifs.smb.SmbFile
import jcifs.smb.SmbFileInputStream
import org.json.JSONArray

/** Serves SMB media over HTTP with byte-range support for seeking in ExoPlayer. */
object SmbStream {

    fun handle(req: HttpRequest): HttpResponse? {
        if (!req.path.startsWith("/smb/")) return null
        val parts = req.path.substring(5).split("/", limit = 2)
        if (parts.size < 2) return HttpResponse.notFound()

        val index = parts[0].toIntOrNull() ?: return HttpResponse.notFound()
        val remote = parts[1]

        return try {
            val prefs = PrefsHolder.prefs ?: return HttpResponse.notFound()
            val entry = JSONArray(prefs.smbServers).optJSONObject(index)
                ?: return HttpResponse.notFound()
            val host = entry.optString("host").trim()
            val share = entry.optString("share").trim()
            val user = entry.optString("user").trim()
            val pass = entry.optString("pass").trim()
            if (host.isBlank() || share.isBlank()) return HttpResponse.notFound()

            val smb = SmbFile("smb://$host/$share/$remote", configFor(user, pass)).apply { connect() }
            if (smb.isDirectory) return HttpResponse.notFound()

            val range = req.header("range")
            val total = smb.length()

            if (range != null && range.startsWith("bytes=")) {
                val spec = range.substring(6).substringBefore(",")
                val (start, end) = parseRange(spec, total)
                val stream = SmbFileInputStream(smb)
                if (start > 0) stream.skip(start)
                val res = HttpResponse(
                    status = 206,
                    contentType = mimeOf(remote),
                    headers = mutableMapOf(
                        "Content-Range" to "bytes $start-$end/$total",
                        "Accept-Ranges" to "bytes",
                        "Content-Length" to "${end - start + 1}",
                        "Cache-Control" to "no-store",
                    ),
                    stream = stream,
                    streamLength = end - start + 1,
                )
                return res
            }

            return HttpResponse(
                status = 200,
                contentType = mimeOf(remote),
                headers = mutableMapOf(
                    "Accept-Ranges" to "bytes",
                    "Content-Length" to "$total",
                    "Cache-Control" to "no-store",
                ),
                stream = SmbFileInputStream(smb),
                streamLength = total,
            )
        } catch (e: Exception) {
            Logger.w("smb", "stream $remote failed: ${e.message}")
            HttpResponse.text("SMB stream unavailable", 503)
        }
    }

    private fun parseRange(spec: String, total: Long): Pair<Long, Long> {
        val parts = spec.split("-")
        val start = parts.getOrNull(0)?.toLongOrNull() ?: 0L
        val end = parts.getOrNull(1)?.toLongOrNull()?.let { if (it >= total) total - 1 else it }
            ?: total - 1
        return (start.coerceIn(0, total - 1)) to end
    }

    private fun configFor(user: String, pass: String): jcifs.CIFSContext {
        val props = java.util.Properties()
        props.setProperty("jcifs.smb.client.minVersion", "SMB202")
        props.setProperty("jcifs.smb.client.maxVersion", "SMB311")
        props.setProperty("jcifs.smb.client.responseTimeout", "15000")
        props.setProperty("jcifs.smb.client.soTimeout", "30000")
        val config = jcifs.config.PropertyConfiguration(props)
        val auth = if (user.isNotBlank()) jcifs.smb.NtlmPasswordAuthenticator(user, pass)
        else jcifs.smb.NtlmPasswordAuthenticator()
        return jcifs.context.BaseContext(config)
    }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.').lowercase()
        return when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "wmv" -> "video/x-ms-wmv"
            "flv" -> "video/x-flv"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "ogg" -> "audio/ogg"
            "opus" -> "audio/opus"
            "m4a", "aac" -> "audio/mp4"
            "wma" -> "audio/x-ms-wma"
            "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }
    }
}
