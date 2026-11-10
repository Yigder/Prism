package com.prism.music.data.meta

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves countries of origin for musical artists (Issue #443).
 * Uses local JSON disk caching, MusicBrainz, and Wikidata.
 */
class ArtistCountryResolver(
    private val dir: File,
    private val http: OkHttpClient,
) {
    private val file = File(dir, "artist_countries.json")
    private val cache = ConcurrentHashMap<String, String>()
    private val mutex = Mutex()
    private var lastRequestMs = 0L

    init {
        loadCache()
    }

    private fun loadCache() {
        if (!file.exists()) return
        runCatching {
            val text = file.readText()
            val parsed = Json.parseToJsonElement(text).jsonObject
            parsed.forEach { (k, v) ->
                val code = v.jsonPrimitive.content.trim().uppercase()
                if (code.isNotEmpty()) cache[k] = code
            }
        }
    }

    private fun saveCache() {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "artist_countries.json.tmp")
            val content = buildString {
                append("{\n")
                val entries = cache.entries.toList()
                entries.forEachIndexed { i, (k, v) ->
                    append("  \"").append(k.replace("\"", "\\\"")).append("\": \"").append(v).append("\"")
                    if (i < entries.size - 1) append(",")
                    append("\n")
                }
                append("}")
            }
            tmp.writeText(content)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    fun countryName(code: String): String =
        Locale("", code).getDisplayCountry(Locale.getDefault()).takeIf { it.isNotBlank() } ?: code

    fun flagEmoji(code: String): String = Companion.flagEmoji(code)

    suspend fun resolveCountry(artistName: String): String? = withContext(Dispatchers.IO) {
        val key = artistName.trim().lowercase(Locale.ROOT)
        if (key.isBlank()) return@withContext null
        cache[key]?.let { return@withContext it.takeIf { c -> c.isNotEmpty() } }

        val code = fetchCountry(artistName)
        cache[key] = code ?: ""
        saveCache()
        code
    }

    suspend fun resolveCountries(artistNames: List<String>): Map<String, String> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, String>()
        for (name in artistNames) {
            val c = resolveCountry(name)
            if (c != null) result[name] = c
        }
        result
    }

    private suspend fun fetchCountry(name: String): String? {
        // Fast demonym lookup from Wikidata first (no strict rate limit)
        val fromWd = fetchFromWikidata(name)
        if (fromWd != null) return fromWd
        // Fallback to MusicBrainz (exact ISO code)
        return fetchFromMusicBrainz(name)
    }

    private suspend fun throttleMusicBrainz() {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val diff = now - lastRequestMs
            if (diff < 1000) {
                delay(1000 - diff)
            }
            lastRequestMs = System.currentTimeMillis()
        }
    }

    private suspend fun fetchFromMusicBrainz(name: String): String? = runCatching {
        throttleMusicBrainz()
        val query = "artist:\"${name.replace("\"", "")}\""
        val url = "https://musicbrainz.org/ws/2/artist/?query=${URLEncoder.encode(query, "UTF-8")}&fmt=json"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Prism/1.8.3 (https://github.com/Yigder/Prism)")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            val body = resp.body?.string() ?: return@runCatching null
            val root = Json.parseToJsonElement(body).jsonObject
            val artists = root["artists"]?.jsonArray ?: return@runCatching null
            val first = artists.firstOrNull()?.jsonObject ?: return@runCatching null
            val country = first["country"]?.jsonPrimitive?.content?.takeIf { it.length == 2 }
            if (country != null) return@runCatching country.uppercase()
            val area = first["area"]?.jsonObject
            val areaType = area?.get("type")?.jsonPrimitive?.content
            if (areaType == "Country") {
                val areaName = area["name"]?.jsonPrimitive?.content
                areaName?.let { countryCodeFromName(it) }
            } else null
        }
    }.getOrNull()

    private suspend fun fetchFromWikidata(name: String): String? = runCatching {
        val url = "https://www.wikidata.org/w/api.php?action=wbsearchentities&search=${URLEncoder.encode(name, "UTF-8")}&language=en&format=json"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Prism/1.8.3 (https://github.com/Yigder/Prism; contact@prism.music)")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            val body = resp.body?.string() ?: return@runCatching null
            val root = Json.parseToJsonElement(body).jsonObject
            val search = root["search"]?.jsonArray ?: return@runCatching null
            for (item in search) {
                val desc = item.jsonObject["description"]?.jsonPrimitive?.content?.lowercase(Locale.ROOT) ?: continue
                for ((demonym, code) in DEMONYMS) {
                    if (desc.contains(demonym)) return@runCatching code
                }
            }
            null
        }
    }.getOrNull()

    companion object {
        fun flagEmoji(code: String): String {
            if (code.length != 2) return ""
            val c1 = code[0].uppercaseChar()
            val c2 = code[1].uppercaseChar()
            if (c1 !in 'A'..'Z' || c2 !in 'A'..'Z') return ""
            return String(Character.toChars(0x1F1E6 + (c1 - 'A'))) +
                   String(Character.toChars(0x1F1E6 + (c2 - 'A')))
        }

        fun countryCodeFromName(name: String): String? {
            return Locale.getISOCountries().firstOrNull { code ->
                Locale("", code).getDisplayCountry(Locale.ENGLISH).equals(name, ignoreCase = true)
            }
        }

        val DEMONYMS = mapOf(
            "american" to "US", "british" to "GB", "english" to "GB", "scottish" to "GB", "welsh" to "GB",
            "canadian" to "CA", "australian" to "AU", "french" to "FR", "german" to "DE", "italian" to "IT",
            "spanish" to "ES", "turkish" to "TR", "japanese" to "JP", "korean" to "KR", "south korean" to "KR",
            "swedish" to "SE", "dutch" to "NL", "brazilian" to "BR", "mexican" to "MX", "indian" to "IN",
            "irish" to "IE", "filipino" to "PH", "nigerian" to "NG", "puerto rican" to "PR", "jamaican" to "JM",
            "colombian" to "CO", "argentinian" to "AR", "argentine" to "AR", "russian" to "RU", "chinese" to "CN",
            "norwegian" to "NO", "finnish" to "FI", "danish" to "DK", "belgian" to "BE", "swiss" to "CH",
            "austrian" to "AT", "polish" to "PL", "portuguese" to "PT", "greek" to "GR", "south african" to "ZA",
            "ukrainian" to "UA", "chilean" to "CL", "peruvian" to "PE", "venezuelan" to "VE", "thai" to "TH",
            "indonesian" to "ID", "vietnamese" to "VN", "new zealand" to "NZ", "kiwi" to "NZ", "egyptian" to "EG",
            "moroccan" to "MA", "icelandic" to "IS",
        )
    }
}
