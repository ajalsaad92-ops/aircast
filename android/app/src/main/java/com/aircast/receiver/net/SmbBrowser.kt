package com.aircast.receiver.net

import com.aircast.receiver.core.Logger
import com.aircast.receiver.core.Prefs
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Read-only SMB2/3 media browser. Walks a configured share on demand and caches the
 * result for a short window so the web UI stays snappy. jcifs-ng is only asked to
 * *list and stream*; no write/delete operations are ever exposed.
 *
 * Supported entry shapes in `prefs.smbServers` (JSON array):
 *   {"name":"NAS","host":"192.168.1.10","share":"Movies","user":"","pass":"","base":"/"}
 */
object SmbBrowser {

    private val cache = ConcurrentHashMap<String, Pair<Long, String>>()
    private const val CACHE_MS = 60_000L

    /** Browse a configured server. `path` is a slash-path relative to the share root. */
    fun browse(index: Int, path: String, filter: String): String {
        val prefs = PrefsHolder.prefs ?: throw IllegalStateException("SmbBrowser not initialised")
        val servers = JSONArray(prefs.smbServers)
        val entry = servers.optJSONObject(index) ?: throw IllegalArgumentException("server $index missing")

        val host = entry.optString("host").trim()
        val share = entry.optString("share").trim()
        if (host.isBlank() || share.isBlank()) throw IllegalArgumentException("host/share missing")

        val key = "$index:$path:$filter"
        cache[key]?.let { (ts, json) -> if (System.currentTimeMillis() - ts < CACHE_MS) return json }

        val ctx = configFor(entry)
        val base = "smb://$host/$share/${entry.optString("base", "/").trimStart('/')}".let {
            it.trimEnd('/') + if (path.isBlank()) "" else "/" + path.trim('/')
        }

        val dir = SmbFile(base, ctx).also { it.connect() }
        if (!dir.isDirectory) throw IllegalArgumentException("not a directory")

        val items = JSONArray()
        val media = setOf(".mkv", ".mp4", ".avi", ".mov", ".wmv", ".webm", ".m4v", ".flv",
            ".mp3", ".m4a", ".aac", ".flac", ".ogg", ".wma", ".opus", ".wav")

        dir.listFiles().sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .forEach { f ->
                if (f.name.startsWith(".")) return@forEach
                if (f.isDirectory) {
                    items.put(JSONObject().put("name", f.name).put("dir", true))
                } else if (f.name.lowercase().let { filter.isBlank() || media.any { ext -> it.endsWith(ext) } }) {
                    items.put(JSONObject()
                        .put("name", f.name)
                        .put("dir", false)
                        .put("size", try { f.length() } catch (_: Exception) { 0 }))
                }
            }

        val title = dir.parent?.substringAfterLast('/')
        val json = JSONObject()
            .put("server", entry.optString("name", host))
            .put("path", path)
            .put("title", if (path.isBlank()) share else title)
            .put("items", items)
            .toString()
        cache[key] = System.currentTimeMillis() to json
        return json
    }

    fun resolveStreamUrl(index: Int, path: String): String {
        val prefs = PrefsHolder.prefs ?: throw IllegalStateException("SmbBrowser not initialised")
        val servers = JSONArray(prefs.smbServers)
        val entry = servers.optJSONObject(index) ?: throw IllegalArgumentException("server $index missing")
        return "/smb/$index/${path.trim('/')}"
    }

    private fun configFor(entry: JSONObject): CIFSContext {
        val props = java.util.Properties()
        props.setProperty("jcifs.smb.client.minVersion", "SMB202")
        props.setProperty("jcifs.smb.client.maxVersion", "SMB311")
        props.setProperty("jcifs.smb.client.responseTimeout", "15000")
        props.setProperty("jcifs.smb.client.soTimeout", "15000")
        props.setProperty("jcifs.smb.client.attrExpirationPeriod", "0")
        val config = PropertyConfiguration(props)
        val user = entry.optString("user").trim()
        val pass = entry.optString("pass").trim()
        val auth = if (user.isNotBlank()) {
            NtlmPasswordAuthenticator(user, pass)
        } else {
            NtlmPasswordAuthenticator()
        }
        return BaseContext(config)
    }
}

/** Lazy holder so Prefs can be assigned from the service at start.
 * The initial value must be a real object (never a throw), because a throwing
 * initializer turns every read before the service runs into an
 * ExceptionInInitializerError that kills the whole process. */
object PrefsHolder {
    var prefs: Prefs? = null
}
