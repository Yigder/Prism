package com.prism.music

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.data.canvas.ClipFinder
import com.prism.music.data.canvas.ClipGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 1.8.6 Canvas, plan §5.3 (only with `record=true`, on the owner's word): how many of a real library's liked songs
 * would get a loop from their music video. It runs inside a copy of Prism that is set up and signed in by its owner,
 * so it changes nothing there: no [Fixture] (that resets the copy), nothing written to the database, no video fetched.
 * It only asks, for up to `songs=<n>` liked songs (default 300), what the finder says, and logs the counts ("CanvasCount").
 */
@Live
@RunWith(AndroidJUnit4::class)
class CanvasLibraryCountLiveTest {
    @Test fun howManyLikedSongsGetALoop() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("live test: run with record=true", args.getString("record") == "true")
        val c = InstrumentationRegistry.getInstrumentation().targetContext.container
        val signedIn = c.settings.current.isLoggedIn
        val liked = withContext(Dispatchers.IO) { c.db.songs().liked() }.map { it.toSong() }
        android.util.Log.i("CanvasCount", "signed in $signedIn, liked songs on the phone ${liked.size}")
        assumeTrue("no liked songs in this copy", liked.isNotEmpty())
        val take = liked.shuffled(java.util.Random(186)).take(args.getString("songs")?.toIntOrNull() ?: 300)
        val finder = ClipFinder.create(c.ytm, c.innerTube, c.http)
        val gate = Semaphore(4)
        val results = withContext(Dispatchers.IO) {
            take.map { song ->
                async {
                    gate.withPermit {
                        if (!ClipGate.isSong(song)) return@withPermit "NOT_A_SONG"
                        try {
                            val row = finder.find(song, null, 1080)
                            row.why?.ifEmpty { null } ?: if (row.videoId != null && row.endMs > row.startMs) "LOOP" else "NONE"
                        } catch (e: Exception) {
                            "ERROR ${e.javaClass.simpleName}"
                        }
                    }
                }
            }.awaitAll()
        }
        val counts = results.groupingBy { it }.eachCount().toList().sortedByDescending { it.second }
        android.util.Log.i("CanvasCount", "asked ${take.size}: " + counts.joinToString(", ") { "${it.first} ${it.second}" })
    }
}
