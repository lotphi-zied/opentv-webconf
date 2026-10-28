/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.config

import android.content.Context
import android.util.Log
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Keeps the provider list in sync with a remote `conf.json`.
 *
 * The user gives the address of a `conf.json` once (on the TV or from the web form). At every
 * launch [refresh] downloads it in the background and compares it with the last version
 * applied. If it changed, the listed sources are created or updated; if the download or the
 * parsing fails for any reason, nothing is touched and the existing sources keep working.
 *
 * ## Expected file format
 *
 * ```json
 * {
 *   "version": "2026-10-28",
 *   "sources": [
 *     { "name": "Home", "type": "xtream", "url": "http://host:8080",
 *       "username": "user", "password": "pass", "epg": "http://host/xmltv.php" },
 *     { "name": "Playlist", "type": "m3u", "url": "http://host/list.m3u" },
 *     { "name": "Portal", "type": "stalker", "url": "http://host/c/", "mac": "00:1A:79:00:00:00" }
 *   ]
 * }
 * ```
 *
 * A flat single-provider file is accepted too, and common key names are understood:
 * `url` / `xtream_server` / `server` / `host` / `m3u_url`, `username` / `xtream_user` / `user`,
 * `password` / `xtream_pass` / `pass`, `type` / `kind`, `mac`, `epg` / `epg_url`, `name`:
 *
 * ```json
 * { "type": "xtream", "xtream_server": "http://host:8080",
 *   "xtream_user": "user", "xtream_pass": "pass" }
 * ```
 *
 * `version` (or `last_updated`) is optional; without it the SHA-256 of the whole file is used
 * to detect a change. A single source may also be given directly at the top level.
 */
object ConfigUpdater {

    sealed interface Result {
        /** Nothing to do: no address configured, or the file is the one already applied. */
        data object Unchanged : Result

        /** The file changed and [count] sources were created or updated. */
        data class Updated(val count: Int) : Result

        /** Download or parsing failed; existing sources were left untouched. */
        data class Failed(val reason: String) : Result
    }

    private const val TAG = "ConfigUpdater"
    private const val PREFS = "opentv_conf"
    private const val KEY_URL = "conf_url"
    private const val KEY_FINGERPRINT = "conf_fingerprint"
    private const val CONNECT_TIMEOUT_MILLIS = 10_000
    private const val READ_TIMEOUT_MILLIS = 15_000
    private const val MAX_BYTES = 1024 * 1024

    // ---- Saved address ---------------------------------------------------------------------

    fun getConfUrl(context: Context): String? =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_URL, null)
            ?.takeIf { it.isNotBlank() }

    /**
     * Stores the address of the `conf.json`. A different address forgets the last applied
     * version, so the new file is always applied once. A blank value removes the feature.
     */
    fun saveConfUrl(context: Context, url: String?) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cleaned = url?.trim().orEmpty()
        val editor = prefs.edit()
        if (cleaned.isEmpty()) {
            editor.remove(KEY_URL).remove(KEY_FINGERPRINT)
        } else {
            if (cleaned != prefs.getString(KEY_URL, null)) editor.remove(KEY_FINGERPRINT)
            editor.putString(KEY_URL, cleaned)
        }
        editor.apply()
    }

    // ---- Refresh ---------------------------------------------------------------------------

    /**
     * Downloads the configured `conf.json` and applies it if it changed.
     *
     * @param existing the sources currently saved, used to update a source in place (same id)
     *   rather than creating a duplicate.
     * @param force apply the file even if it matches the last applied version.
     * @param saveSource saves one source and refreshes its channels; returns whether it worked.
     */
    suspend fun refresh(
        context: Context,
        existing: List<Source>,
        force: Boolean = false,
        saveSource: suspend (Source) -> Boolean,
    ): Result {
        val confUrl = getConfUrl(context) ?: return Result.Unchanged

        val body = try {
            download(confUrl)
        } catch (e: Exception) {
            Log.w(TAG, "Could not download conf.json", e)
            return Result.Failed("download: ${e.message ?: e.javaClass.simpleName}")
        }

        val parsed = try {
            parse(body)
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse conf.json", e)
            return Result.Failed("format: ${e.message ?: e.javaClass.simpleName}")
        }

        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val fingerprint = parsed.version?.let { "v:$it" } ?: "h:${sha256(body)}"
        if (!force && fingerprint == prefs.getString(KEY_FINGERPRINT, null)) return Result.Unchanged

        var saved = 0
        var failed = 0
        val known = existing.toMutableList()
        for (incoming in parsed.sources) {
            val match = known.firstOrNull { it.name.equals(incoming.name, ignoreCase = true) }
                ?: known.firstOrNull { it.kind == incoming.kind && it.url == incoming.url }
            val toSave = if (match != null) {
                match.copy(
                    name = incoming.name,
                    kind = incoming.kind,
                    url = incoming.url,
                    username = incoming.username,
                    password = incoming.password,
                    macAddress = incoming.mac,
                    epgUrl = incoming.epg,
                    userAgent = incoming.userAgent ?: match.userAgent,
                )
            } else {
                Source(name = incoming.name, kind = incoming.kind, url = incoming.url).copy(
                    username = incoming.username,
                    password = incoming.password,
                    macAddress = incoming.mac,
                    epgUrl = incoming.epg,
                    userAgent = incoming.userAgent ?: Source.DEFAULT_USER_AGENT,
                )
            }
            val ok = runCatching { saveSource(toSave) }.getOrDefault(false)
            if (ok) {
                saved++
                if (match != null) known.remove(match)
            } else {
                failed++
            }
        }

        // Remember this version only when everything went through, so a partial failure is
        // retried at the next launch.
        if (failed == 0) prefs.edit().putString(KEY_FINGERPRINT, fingerprint).apply()

        return if (failed == 0) Result.Updated(saved)
        else Result.Failed("$failed/${parsed.sources.size} sources")
    }

    // ---- Download & parsing ----------------------------------------------------------------

    private suspend fun download(address: String): String = withContext(Dispatchers.IO) {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-cache")
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > MAX_BYTES) throw IllegalStateException("conf.json too large")
                }
                out.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    private class Incoming(
        val name: String,
        val kind: SourceKind,
        val url: String,
        val username: String?,
        val password: String?,
        val mac: String?,
        val epg: String?,
        val userAgent: String?,
    )

    private class Parsed(val version: String?, val sources: List<Incoming>)

    private fun parse(raw: String): Parsed {
        val text = raw.trim().removePrefix("\uFEFF")
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            // Allow a bare array of sources.
            return Parsed(null, readSources(JSONArray(text)))
        }
        val version = listOf("version", "last_updated", "lastUpdated")
            .firstNotNullOfOrNull { key ->
                if (root.has(key) && !root.isNull(key)) root.get(key).toString().takeIf { it.isNotBlank() }
                else null
            }
        val sources = when {
            root.optJSONArray("sources") != null -> readSources(root.getJSONArray("sources"))
            URL_KEYS.any { root.has(it) } -> listOfNotNull(readSource(root))
            else -> emptyList()
        }
        if (sources.isEmpty()) throw IllegalStateException("No valid source in conf.json")
        return Parsed(version, sources)
    }

    private fun readSources(array: JSONArray): List<Incoming> =
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(::readSource)
        }

    /** First non-blank string value found under any of [keys]. */
    private fun pick(json: JSONObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key ->
            if (json.has(key) && !json.isNull(key)) json.get(key).toString().trim().takeIf { it.isNotEmpty() }
            else null
        }

    private val URL_KEYS = listOf("url", "xtream_server", "server", "host", "m3u_url", "playlist", "portal")

    private fun readSource(json: JSONObject): Incoming? {
        val url = pick(json, *URL_KEYS.toTypedArray()) ?: return null

        val username = pick(json, "username", "xtream_user", "user")
        val password = json.let { j ->
            listOf("password", "xtream_pass", "pass")
                .firstNotNullOfOrNull { k -> if (j.has(k) && !j.isNull(k)) j.get(k).toString().takeIf { it.isNotEmpty() } else null }
        }
        val mac = pick(json, "mac", "mac_address")

        val typeText = (pick(json, "type", "kind") ?: "").lowercase()
        val kind = when (typeText) {
            "xtream", "xtream_codes", "xc" -> SourceKind.XTREAM
            "m3u", "m3u8", "playlist" -> SourceKind.M3U
            "stalker", "portal" -> SourceKind.STALKER
            else -> when {
                mac != null -> SourceKind.STALKER
                username != null -> SourceKind.XTREAM
                else -> SourceKind.M3U
            }
        }

        val name = pick(json, "name", "title") ?: when (kind) {
            SourceKind.M3U -> "My playlist"
            else -> "My provider"
        }
        val epg = pick(json, "epg", "epg_url", "xmltv")
        val userAgent = pick(json, "user_agent", "userAgent")

        return Incoming(name, kind, url, username, password, mac, epg, userAgent)
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
