package com.prism.music

import android.util.Base64
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.security.MessageDigest

/**
 * Prism's network in the in-app tests: every request is answered from a recording in the test APK's assets
 * (`recorded/<key>.json`), the same on every run. A request with no recording gets a 503 ("offline"), logged under
 * PrismTestNet with its key, and is never sent. With [record] the requests go out for real and their answers are kept in
 * [RECORDED] (audio and pictures aren't: tests play local files and don't look at covers).
 *
 * A request's key is its method, host and path, plus what picks the answer out: the query string without the API key,
 * or for YouTube's JSON requests the fields that name what's asked for (browseId, query, videoId, params, continuation,
 * playlistId, input); anything else in the body (visitor data, client version) changes from run to run.
 */
class RecordedNetwork(private val record: Boolean) : Interceptor {
    @Serializable
    private class Recording(val code: Int, val type: String? = null, val body: String, val base64: Boolean = false)

    private val json = Json { ignoreUnknownKeys = true }
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /** Keys asked for that had no recording, for a test to report. */
    val misses: MutableSet<String> = java.util.Collections.synchronizedSet(linkedSetOf())

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val key = keyOf(request)
        // A test's own answer comes first (GitHub announcing a newer Prism, for one test only).
        answers.entries.firstOrNull { (url, _) -> request.url.toString().startsWith(url) }?.let { (_, body) ->
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Test")
                .body(body.toResponseBody("application/json".toMediaTypeOrNull())).build()
        }
        if (record) return chain.proceed(request).also { keep(key, it) }
        val saved = runCatching {
            instrumentation.context.assets.open("recorded/$key.json").use { json.decodeFromString(Recording.serializer(), it.readBytes().decodeToString()) }
        }.getOrNull()
        if (saved == null) {
            misses += key
            Log.w(TAG, "no recording: $key (${request.method} ${request.url})")
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(503).message("Offline (test)")
                .body("".toResponseBody(null)).build()
        }
        val bytes = if (saved.base64) Base64.decode(saved.body, Base64.NO_WRAP) else saved.body.encodeToByteArray()
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(saved.code).message("Recorded")
            .body(bytes.toResponseBody(saved.type?.toMediaTypeOrNull())).build()
    }

    private fun keep(key: String, response: Response) {
        val type = response.body.contentType()
        val kind = type?.let { "${it.type}/${it.subtype}" }.orEmpty()
        if (kind.startsWith("audio/") || kind.startsWith("video/") || kind.startsWith("image/")) return
        val bytes = response.peekBody(MAX_RECORDING).bytes()
        if (bytes.size >= MAX_RECORDING) return
        val text = type?.charset() != null || kind.contains("json") || kind.startsWith("text/") || kind.contains("xml") || kind.contains("javascript")
        // Google API keys out (YouTube's pages carry their web client's key): the recordings are published in the repo,
        // where GitHub's secret scanning flags one. Prism never reads a key from an answer.
        val rec = Recording(response.code, type?.toString(), if (text) GOOGLE_KEY.replace(bytes.decodeToString(), "REDACTED_GOOGLE_API_KEY") else Base64.encodeToString(bytes, Base64.NO_WRAP), !text)
        // To /sdcard through the test's shell (Prism's own files are cleared before the next test); tools/record.ps1
        // pulls them into src/androidTest/assets/recorded/.
        val ui = instrumentation.uiAutomation
        ui.executeShellCommand("mkdir -p $RECORDED").close()
        // One word after -c (the shell splits on spaces), and nothing echoed back: `tee` echoed, and with nobody reading
        // that while the answer was written, both ends waited on a full pipe.
        val (out, input) = ui.executeShellCommandRw("sh -c cat>$RECORDED/$key.json")
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(input).use { it.write(json.encodeToString(Recording.serializer(), rec).encodeToByteArray()) }
        android.os.ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes() }
        Log.i(TAG, "recorded: $key")
    }

    companion object {
        private const val TAG = "PrismTestNet"
        private const val MAX_RECORDING = 4L * 1024 * 1024
        const val RECORDED = "/sdcard/prism-tests/recorded"
        private val GOOGLE_KEY = Regex("AIza[0-9A-Za-z_-]{35}")

        /** Answers a test sets for itself, by the start of the address (each test runs in a fresh app). */
        val answers: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
        private val NAMING = listOf("browseId", "query", "videoId", "params", "continuation", "playlistId", "input")

        fun keyOf(request: Request): String {
            val url = request.url
            val query = url.queryParameterNames.filter { it != "key" && it != "prettyPrint" && it != "t" }.sorted()
                .joinToString("&") { n -> "$n=${url.queryParameterValues(n).joinToString(",")}" }
            val body = request.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }.orEmpty()
            val naming = if (body.isEmpty()) "" else runCatching {
                val obj = Json.parseToJsonElement(body).jsonObject
                NAMING.mapNotNull { n -> find(obj, n)?.let { "$n=$it" } }.joinToString("&")
            }.getOrElse { body }
            val slug = (url.host.removePrefix("www.") + url.encodedPath).replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').take(60)
            return "${slug}_" + sha1("${request.method} ${url.host}${url.encodedPath}?$query#$naming").take(12)
        }

        /** The first [name] anywhere in [obj] (YouTube nests some of them in the context). */
        private fun find(obj: JsonObject, name: String): String? {
            (obj[name] as? JsonPrimitive)?.let { return it.content }
            for (v in obj.values) (v as? JsonObject)?.let { inner -> find(inner, name)?.let { return it } }
            return null
        }

        private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
