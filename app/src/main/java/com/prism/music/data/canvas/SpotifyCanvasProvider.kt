/*
 * Spotify Canvas lookup is based on BitChord's Android implementation:
 * https://github.com/kushagrasinghx/BitChord
 * Licensed under GPL-3.0, compatible with Prism's GPL-3.0-or-later license.
 */
package com.prism.music.data.canvas

import android.content.Context
import android.content.SharedPreferences
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream

/** Resolves a Spotify track and asks Spotify's web-player Canvas API for its video. */
class SpotifyCanvasProvider(
    context: Context,
    private val http: okhttp3.OkHttpClient,
    private val preferences: SharedPreferences,
) {
    private val token = SpotifyTokenSession(context.applicationContext, preferences, http)

    fun connected() = preferences.getString("sp_dc", null).orEmpty().isNotBlank()

    fun disconnect() {
        preferences.edit().remove("sp_dc").remove("sp_key").apply()
        token.invalidate()
    }

    /** Find the closest Spotify track, then use Pathfinder with protobuf fallback for its Canvas. */
    suspend fun find(title: String, artist: String, album: String?): CanvasArtwork? {
        val accessToken = token.accessToken() ?: return null
        val clientToken = token.clientToken()
        val track = searchTrack(title, artist, album, accessToken, clientToken) ?: return null
        val trackUri = track.str("uri") ?: track.str("id")?.let { "spotify:track:$it" } ?: return null
        val titleOnSpotify = track.str("name") ?: title
        val artists = (track["artists"] as? JsonArray)?.mapNotNull { it.str("name") }

        val url = pathfinderCanvas(trackUri, accessToken, clientToken)
            ?: canvazCanvas(trackUri, accessToken, clientToken)
            ?: return null
        return CanvasArtwork(
            url = url,
            title = titleOnSpotify,
            artist = artists?.joinToString(", ") ?: artist,
            album = album,
            source = "spotify",
        )
    }

    private fun authHeaders(accessToken: String, clientToken: String?): Map<String, String> = buildMap {
        put("Authorization", "Bearer $accessToken")
        put("User-Agent", WEB_PLAYER_UA)
        if (!clientToken.isNullOrBlank()) put("Client-Token", clientToken)
    }

    private fun searchTrack(
        title: String,
        artist: String,
        album: String?,
        accessToken: String,
        clientToken: String?,
    ): JsonObject? {
        searchViaPathfinder(title, artist, album, accessToken, clientToken)?.let { return it }
        val queries = listOfNotNull(
            if (!album.isNullOrBlank()) "track:$title artist:$artist album:$album" else null,
            "track:$title artist:$artist",
        ).distinct()
        for (query in queries) {
            val url = SEARCH_URL.toHttpUrl().newBuilder()
                .addQueryParameter("q", query).addQueryParameter("type", "track").addQueryParameter("limit", "10").build()
            val json = requestJson(Request.Builder().url(url).get().apply {
                authHeaders(accessToken, clientToken).forEach { (name, value) -> header(name, value) }
            }.build()) ?: continue
            val items = ((json["tracks"] as? JsonObject)?.get("items") as? JsonArray).orEmpty()
            val wantedTitle = title.normalizeForMatch()
            val wantedArtists = splitArtists(artist)
            val exact = items.mapNotNull { it as? JsonObject }.firstOrNull { item ->
                val gotTitle = item.str("name")?.normalizeForMatch().orEmpty()
                val gotArtists = (item["artists"] as? JsonArray)?.mapNotNull { it.str("name") }?.flatMap(::splitArtists).orEmpty()
                (gotTitle == wantedTitle || gotTitle.startsWith("$wantedTitle ") || wantedTitle.startsWith("$gotTitle ")) &&
                    wantedArtists.isNotEmpty() && wantedArtists.any { it in gotArtists }
            }
            if (exact != null) return exact
        }
        return null
    }

    private fun searchViaPathfinder(
        title: String,
        artist: String,
        album: String?,
        accessToken: String,
        clientToken: String?,
    ): JsonObject? {
        if (clientToken.isNullOrBlank()) return null
        val variables = buildJsonObject {
            put("searchTerm", listOfNotNull(title, artist, album).joinToString(" "))
            put("offset", 0)
            put("limit", 10)
            put("numberOfTopResults", 5)
            put("includeAudiobooks", false)
            put("includePreReleases", false)
        }
        val extensions = buildJsonObject {
            putJsonObject("persistedQuery") {
                put("version", 1)
                put("sha256Hash", SEARCH_HASH)
            }
        }
        val url = PATHFINDER_URL.toHttpUrl().newBuilder()
            .addQueryParameter("operationName", "searchTracks")
            .addQueryParameter("variables", variables.toString())
            .addQueryParameter("extensions", extensions.toString())
            .build()
        val json = requestJson(Request.Builder().url(url).get().apply {
            authHeaders(accessToken, clientToken).forEach { (name, value) -> header(name, value) }
            header("App-platform", "WebPlayer")
        }.build()) ?: return null
        val data = json["data"] as? JsonObject ?: return null
        val searchV2 = data["searchV2"] as? JsonObject ?: return null
        val tracksV2 = searchV2["tracksV2"] as? JsonObject ?: return null
        val items = tracksV2["items"] as? JsonArray ?: return null
        val firstItem = items.firstOrNull() as? JsonObject ?: return null
        val trackData = ((firstItem["item"] as? JsonObject)?.get("data") as? JsonObject) ?: return null
        val uri = (trackData["uri"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            ?: (trackData["id"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.let { "spotify:track:$it" }
            ?: return null
        return buildJsonObject {
            put("uri", uri)
            put("name", title)
        }
    }

    private fun pathfinderCanvas(trackUri: String, accessToken: String, clientToken: String?): String? {
        val hash = canvasQueryHash()
        val body = buildJsonObject {
            put("operationName", "canvas")
            putJsonObject("variables") { put("trackUri", trackUri) }
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") { put("version", 1); put("sha256Hash", hash) }
            }
        }.toString()
        val request = Request.Builder().url(CANVAS_PATHFINDER_URL)
            .post(body.toRequestBody("application/json".toMediaType()))
            .apply { authHeaders(accessToken, clientToken).forEach { (name, value) -> header(name, value) } }
            .header("App-platform", "WebPlayer")
            .header("Accept", "application/json")
            .header("Accept-Language", "en")
            .build()
        val json = requestJson(request) ?: return null
        val canvas = (((json["data"] as? JsonObject)?.get("trackUnion") as? JsonObject)?.get("canvas") as? JsonObject)
            ?: return null
        val url = (canvas["url"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: return null
        return url.takeIf { it.startsWith("https://") && VIDEO_URL.containsMatchIn(it) }
    }

    private fun canvasQueryHash(): String {
        val now = System.currentTimeMillis()
        if (cachedCanvasHash != null && now - canvasHashCheckedAt < HASH_TTL_MS) return cachedCanvasHash!!
        cachedCanvasHash = null
        canvasHashCheckedAt = now
        val html = requestText(Request.Builder().url("https://open.spotify.com/").header("User-Agent", WEB_PLAYER_UA).get().build())
            ?: return KNOWN_CANVAS_HASH
        val scripts = WEB_PLAYER_SCRIPT.findAll(html).map { it.value }.distinct().take(8)
        for (script in scripts) {
            val source = requestText(Request.Builder().url(script).header("User-Agent", WEB_PLAYER_UA).get().build()) ?: continue
            val hash = CANVAS_HASH.findAll(source).firstOrNull { it.groupValues[1] == "canvas" }?.groupValues?.get(2)
            if (hash != null) {
                cachedCanvasHash = hash
                return hash
            }
        }
        return KNOWN_CANVAS_HASH
    }

    private fun canvazCanvas(trackUri: String, accessToken: String, clientToken: String?): String? {
        val inner = protobufStringField(1, trackUri)
        val payload = protobufBytesField(1, inner)
        val request = Request.Builder().url(CANVAS_URL)
            .post(payload.toRequestBody("application/protobuf".toMediaType()))
            .apply { authHeaders(accessToken, clientToken).forEach { (name, value) -> header(name, value) } }
            .header("Accept", "application/protobuf")
            .header("Accept-Language", "en")
            .header("User-Agent", SPOTIFY_APP_UA)
            .build()
        val bytes = runCatching {
            http.newCall(request).execute().use { response -> if (response.isSuccessful) response.body?.bytes() else null }
        }.getOrNull() ?: return null
        val canvases = decodeCanvases(bytes)
        val structured = canvases.firstOrNull { it.trackUri == trackUri }?.url ?: canvases.firstOrNull()?.url
        if (structured != null) return structured
        return VIDEO_URL_TEXT.find(String(bytes, Charsets.ISO_8859_1))?.value
    }

    private data class CanvasHit(val url: String, val trackUri: String?)

    private fun decodeCanvases(bytes: ByteArray): List<CanvasHit> = runCatching {
        val input = ProtoReader(bytes)
        buildList {
            while (!input.isAtEnd) {
                val tag = input.readVarint().toInt()
                if (tag == 0) break
                if ((tag ushr 3) == 1 && (tag and 7) == 2) decodeCanvas(input.readBytes())?.let(::add)
                else input.skip(tag)
            }
        }
    }.getOrDefault(emptyList())

    private fun decodeCanvas(bytes: ByteArray): CanvasHit? = runCatching {
        val input = ProtoReader(bytes)
        var url: String? = null
        var uri: String? = null
        while (!input.isAtEnd) {
            val tag = input.readVarint().toInt()
            if (tag == 0) break
            if ((tag and 7) == 2) {
                val value = input.readBytes().toString(Charsets.UTF_8)
                when (tag ushr 3) {
                    2 -> url = value
                    5 -> uri = value
                }
            } else input.skip(tag)
        }
        url?.takeIf { it.startsWith("https://") }?.let { CanvasHit(it, uri) }
    }.getOrNull()

    private fun requestJson(request: Request): JsonObject? = requestText(request)?.let {
        runCatching { InnerTube.json.parseToJsonElement(it) as? JsonObject }.getOrNull()
    }

    private fun requestText(request: Request): String? = runCatching {
        http.newCall(request).execute().use { response -> if (response.isSuccessful) response.body?.string() else null }
    }.getOrNull()

    private class ProtoReader(private val bytes: ByteArray) {
        private var position = 0
        val isAtEnd get() = position >= bytes.size
        fun readBytes(): ByteArray {
            val size = readVarint().toInt().coerceAtLeast(0)
            val end = (position + size).coerceAtMost(bytes.size)
            return bytes.copyOfRange(position, end).also { position = end }
        }
        fun skip(tag: Int) {
            when (tag and 7) {
                0 -> readVarint()
                1 -> position = (position + 8).coerceAtMost(bytes.size)
                2 -> readBytes()
                5 -> position = (position + 4).coerceAtMost(bytes.size)
                else -> position = bytes.size
            }
        }
        fun readVarint(): Long {
            var value = 0L
            var shift = 0
            while (position < bytes.size && shift < 64) {
                val b = bytes[position++].toInt() and 0xff
                value = value or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            return value
        }
    }

    private fun protobufStringField(field: Int, value: String) = protobufBytesField(field, value.toByteArray(Charsets.UTF_8))
    private fun protobufBytesField(field: Int, bytes: ByteArray): ByteArray = byteArrayOutputStream {
        writeVarint((field shl 3 or 2).toLong())
        writeVarint(bytes.size.toLong())
        write(bytes)
    }
    private fun byteArrayOutputStream(write: ByteArrayOutputStream.() -> Unit) = ByteArrayOutputStream().apply(write).toByteArray()
    private fun ByteArrayOutputStream.writeVarint(value: Long) {
        var remaining = value
        while ((remaining and 0x7f.inv().toLong()) != 0L) {
            write(((remaining.toInt() and 0x7f) or 0x80))
            remaining = remaining ushr 7
        }
        write(remaining.toInt())
    }

    companion object {
        private const val SEARCH_URL = "https://api.spotify.com/v1/search"
        private const val PATHFINDER_URL = "https://api-partner.spotify.com/pathfinder/v1/query"
        private const val CANVAS_PATHFINDER_URL = "https://api-partner.spotify.com/pathfinder/v2/query"
        private const val CANVAS_URL = "https://spclient.wg.spotify.com/canvaz-cache/v0/canvases"
        private const val SEARCH_HASH = "bc1ca2fcd0ba1013a0fc88e6cc4f190af501851e3dafd3e1ef85840297694428"
        private const val KNOWN_CANVAS_HASH = "575138ab27cd5c1b3e54da54d0a7cc8d85485402de26340c2145f0f6bb5e7a9f"
        private const val WEB_PLAYER_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        private const val SPOTIFY_APP_UA = "Spotify/9.0.34.593 iOS/18.4 (iPhone15,3)"
        private const val HASH_TTL_MS = 12 * 60 * 60 * 1000L
        private val VIDEO_URL = Regex("""\.mp4(?:[?#]|$)""", RegexOption.IGNORE_CASE)
        private val VIDEO_URL_TEXT = Regex("""https://[^\"'\s\x00-\x1F]+\.cnvs\.mp4""")
        private val WEB_PLAYER_SCRIPT = Regex("""https://open\.spotifycdn\.com/cdn/build/web-player/[A-Za-z0-9._~-]+\.js""")
        private val CANVAS_HASH = Regex("""[\"'](\w+)[\"']\s*,\s*[\"']query[\"']\s*,\s*[\"']([0-9a-f]{64})[\"']""")
        @Volatile private var cachedCanvasHash: String? = null
        @Volatile private var canvasHashCheckedAt = 0L
    }
}
