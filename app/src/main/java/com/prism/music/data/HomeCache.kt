package com.prism.music.data

import android.util.Log
import com.prism.music.data.innertube.Parser
import com.prism.music.data.model.Shelf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import java.io.File

/**
 * Home as YouTube last sent it (#126): its pages kept on the phone, so the next start shows Home at once while the
 * new one loads behind it, instead of a spinner. Only the account's own Home is kept, and it goes when the account
 * does ([clear], from signing out and switching accounts).
 */
class HomeCache(dir: File) {
    private val file = File(dir, "home/feed.json")

    /** The shelves of the last Home, or null when none is kept. Parsed off the main thread. */
    suspend fun read(): List<Shelf>? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        runCatching { Json.parseToJsonElement(file.readText()).jsonArray.flatMap { Parser.shelves(it) }.distinctBy { it.title } }
            .onFailure { Log.w(TAG, "couldn't read the kept Home", it) }
            .getOrNull()?.takeIf { it.isNotEmpty() }
    }

    suspend fun write(pages: List<JsonObject>) = withContext(Dispatchers.IO) {
        if (pages.isEmpty()) return@withContext
        runCatching {
            file.parentFile?.mkdirs()
            // Written beside and moved over, so a start never reads half a file.
            val next = File(file.parentFile, "feed.json.new")
            next.writeText(JsonArray(pages).toString())
            if (!next.renameTo(file)) { file.delete(); next.renameTo(file) }
        }.onFailure { Log.w(TAG, "couldn't keep Home", it) }
    }

    fun clear() {
        runCatching { file.delete() }
    }

    private companion object {
        const val TAG = "PrismHome"
    }
}
