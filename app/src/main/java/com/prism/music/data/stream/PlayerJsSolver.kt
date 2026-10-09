package com.prism.music.data.stream

import android.content.Context
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.javascriptengine.EvaluationFailedException
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * Solves the challenges in signed-in stream URLs (the "s" signature and the "n" throttling parameter)
 * with YouTube's own player JS. NewPipe's regexes no longer find those functions, so this runs yt-dlp's
 * EJS solver (assets/ejs, Unlicense), which finds them in the player's syntax tree, on WebView's V8
 * through androidx.javascriptengine. Blocking: never call it on the main thread.
 */
class PlayerJsSolver(private val context: Context, private val http: OkHttpClient) {
    private class Player(val id: String, val code: String, val sts: Int)

    private val lock = Any()
    /** The latest player and the one before it, so songs resolved just before a player change still decipher. */
    private val players = LinkedHashMap<String, Player>()
    private var latestId: String? = null
    private var checkedAt = 0L
    private var sandbox: JavaScriptSandbox? = null
    private var isolate: JavaScriptIsolate? = null
    /** The player whose solvers the isolate holds. */
    private var loadedId: String? = null

    /** The current player's id and signature timestamp; player requests send the timestamp so their ciphers match it. */
    fun currentPlayer(): Pair<String, Int> = synchronized(lock) { latest().let { it.id to it.sts } }

    /** Solves [sig] and [n] (either may be null) with player [playerId]'s functions. */
    fun solve(playerId: String, sig: String?, n: String?): Pair<String?, String?> = synchronized(lock) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "PlayerJsSolver.solve on the main thread" }
        val iso = isolateFor(playerId)
        val args = buildJsonArray { add(sig); add(n) }.toString()
        val out = Json.parseToJsonElement(evaluate(iso, "prismSolve.apply(null, $args)", SOLVE_TIMEOUT_S)) as JsonObject
        out["error"]?.jsonPrimitive?.contentOrNull?.let { throw IOException("Player JS: $it") }
        out["sig"]?.jsonPrimitive?.contentOrNull to out["n"]?.jsonPrimitive?.contentOrNull
    }

    private fun latest(): Player {
        val now = System.currentTimeMillis()
        val known = latestId?.let { players[it] }
        if (known != null && now - checkedAt < RECHECK_MS) return known
        val id = try {
            PLAYER_ID.find(get("https://www.youtube.com/iframe_api"))?.groupValues?.get(1) ?: throw IOException("No player id in iframe_api")
        } catch (e: IOException) {
            return known ?: throw e
        }
        checkedAt = now
        latestId = id
        return player(id)
    }

    private fun player(id: String): Player {
        players[id]?.let { return it }
        val code = get("https://www.youtube.com/s/player/$id/player_ias.vflset/en_US/base.js")
        val sts = STS.find(code)?.groupValues?.get(1)?.toInt() ?: throw IOException("No signature timestamp in player $id")
        val p = Player(id, code, sts)
        players[id] = p
        while (players.size > 2) players.remove(players.keys.first { it != id })
        return p
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", DESKTOP_UA).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} for $url")
            return r.body.string()
        }
    }

    private fun isolateFor(playerId: String): JavaScriptIsolate {
        isolate?.let { if (loadedId == playerId) return it }
        val p = player(playerId)
        val iso = isolate ?: newIsolate()
        val result = evaluateLarge(iso, "prismLoad(${JsonPrimitive(p.code)})")
        if (result != "ok") throw IOException("Player JS $playerId: $result")
        loadedId = playerId
        return iso
    }

    private fun newIsolate(): JavaScriptIsolate {
        if (!JavaScriptSandbox.isSupported()) throw StreamException("Update Android System WebView to play this song")
        val sb = sandbox ?: try {
            JavaScriptSandbox.createConnectedInstanceAsync(context.applicationContext).get(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw IOException("Couldn't start the JavaScript sandbox", e.cause)
        }.also { sandbox = it }
        val iso = sb.createIsolate(IsolateStartupParameters())
        isolate = iso
        loadedId = null
        val lib = context.assets.open("ejs/yt.solver.lib.min.js").use { it.readBytes().decodeToString() }
        val core = context.assets.open("ejs/yt.solver.core.min.js").use { it.readBytes().decodeToString() }
        evaluate(iso, "$lib\nvar meriyah = lib.meriyah, astring = lib.astring;\n$core\n$GLUE", LOAD_TIMEOUT_S)
        return iso
    }

    /** The player is ~2.5 MB, past the binder limit older sandboxes have, so it goes through a file there. */
    private fun evaluateLarge(iso: JavaScriptIsolate, script: String): String {
        val sb = sandbox!!
        if (sb.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT)) {
            return evaluate(iso, script, LOAD_TIMEOUT_S)
        }
        if (!sb.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_EVALUATE_FROM_FD)) {
            throw StreamException("Update Android System WebView to play this song")
        }
        val file = File(context.cacheDir, "player-solver.js")
        try {
            file.writeText(script)
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                await(iso.evaluateJavaScriptAsync(fd), LOAD_TIMEOUT_S)
            }
        } finally {
            file.delete()
        }
    }

    private fun evaluate(iso: JavaScriptIsolate, script: String, timeoutS: Long): String =
        await(iso.evaluateJavaScriptAsync(script), timeoutS)

    /** Waits for an evaluation; anything but a JS exception means the isolate is gone or stuck, so it's dropped. */
    private fun await(future: java.util.concurrent.Future<String>, timeoutS: Long): String = try {
        future.get(timeoutS, TimeUnit.SECONDS)
    } catch (e: ExecutionException) {
        if (e.cause !is EvaluationFailedException) reset()
        throw IOException("Player JS failed: ${e.cause?.message}", e.cause)
    } catch (e: Exception) {
        reset()
        throw IOException("Player JS failed: $e", e)
    }

    private fun reset() {
        runCatching { isolate?.close() }
        isolate = null
        loadedId = null
        runCatching { sandbox?.close() }
        sandbox = null
    }

    private companion object {
        val PLAYER_ID = Regex("""player\\?/([0-9a-fA-F]{8})\\?/""")
        val STS = Regex("""(?:signatureTimestamp|sts)\s*:\s*(\d{5})""")
        const val RECHECK_MS = 60 * 60_000L
        const val CONNECT_TIMEOUT_S = 20L
        const val LOAD_TIMEOUT_S = 60L
        const val SOLVE_TIMEOUT_S = 15L

        /**
         * prismLoad preprocesses a player with EJS and keeps its solvers (what EJS's getFromPrepared does
         * with a preprocessed player), so each solve is just two calls. Results are always strings.
         */
        val GLUE = """
            var prismSolvers = null;
            function prismLoad(code) {
              try {
                var out = jsc({ type: "player", player: code, requests: [], output_preprocessed: true });
                if (out.type !== "result") return String(out.error).slice(0, 2000);
                var r = { n: null, sig: null };
                Function("_result", out.preprocessed_player)(r);
                if (!r.sig) return "no signature function found";
                prismSolvers = r;
                return "ok";
              } catch (e) {
                return String((e && e.stack) || e).slice(0, 2000);
              }
            }
            function prismSolve(sig, n) {
              try {
                return JSON.stringify({
                  sig: sig == null ? null : prismSolvers.sig(sig),
                  n: n == null || !prismSolvers.n ? n : prismSolvers.n(n),
                });
              } catch (e) {
                return JSON.stringify({ error: String(e).slice(0, 2000) });
              }
            }
            "ready";
        """.trimIndent()
    }
}
