package com.prism.music.data.canvas

import com.prism.music.data.Heatmap
import com.prism.music.data.db.CanvasClipDao
import com.prism.music.data.db.CanvasClipEntity
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.CanvasPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * 1.8.6 Canvas, getting loops ready ahead (plan-canvas 4.8, §11a R2): the next two songs only, one at a time, on Wi-Fi
 * only and never with Data saver, nothing when Canvas or music videos are off (Performance mode too), a newer queue
 * cancels, a loop already kept costs no network; and downloads keep their loops and give them back when removed.
 */
class CanvasAheadTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun settings(
        prefs: CanvasPrefs = CanvasPrefs(useVideos = true), full: Boolean = false, dataSaver: Boolean = false,
        performance: Boolean = false, withDownloads: Boolean = true, downloadsCellular: Boolean = false,
    ): AppSettings = AppSettings().let {
        it.copy(
            canvasEnabled = true, canvasFillsBackground = full, dataSaver = dataSaver, performanceMode = performance,
            canvasWithDownloads = withDownloads, canvasWithDownloadsCellular = downloadsCellular,
            behavior = it.behavior.copy(canvas = prefs),
        )
    }.effective()

    private class Fake(@Volatile var s: AppSettings, @Volatile var onData: Boolean = false) : CanvasAhead.Facts {
        val prepared: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val started: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val cancelled: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val coverAsked: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val asks: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val unsaved: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var covers = setOf<String>()
        var hiddenIds = setOf<String>()
        var savedIds = setOf<String>()
        var videos = mapOf<String, String>()
        var choices = mapOf<String, CanvasSource>()
        @Volatile var gate: CompletableDeferred<Unit>? = null
        val running = AtomicInteger()
        @Volatile var most = 0

        override fun settings() = s
        override fun metered() = onData
        override fun hidden(songId: String) = songId in hiddenIds
        override fun saved(song: Song) = song.id in savedIds
        override fun savedById(songId: String) = songId in savedIds
        override suspend fun choice(songId: String) = choices[songId]
        override suspend fun hasCover(song: Song): Boolean { coverAsked += song.id; return song.id in covers }
        override suspend fun prepare(song: Song, height: Int, saved: Boolean, allowed: () -> Boolean): Boolean {
            val n = running.incrementAndGet()
            synchronized(this) { if (n > most) most = n }
            try {
                started += song.id
                asks += "${song.id}@$height${if (saved) " saved" else ""}"
                gate?.await()
                if (!allowed()) return false
                prepared += song.id
                return true
            } catch (e: CancellationException) {
                cancelled += song.id
                throw e
            } finally {
                running.decrementAndGet()
            }
        }
        override suspend fun videoOf(songId: String) = videos[songId]
        override fun unsave(videoId: String) { unsaved += videoId }
    }

    private fun song(id: String) = Song(id, "Song $id", listOf(ArtistRef("Artist")), durationSec = 200)
    private val a = song("a"); private val b = song("b"); private val c = song("c"); private val d = song("d")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private fun ahead(fake: Fake, waitMs: Long = 0) = CanvasAhead(scope, fake, Dispatchers.Default, waitMs)
    private fun join(job: kotlinx.coroutines.Job?) = runBlocking { withTimeout(5_000) { job?.join() } }
    private fun until(cond: () -> Boolean) = runBlocking { withTimeout(5_000) { while (!cond()) delay(5) } }

    @Test fun preparesOnlyTheNextTwo() {
        val f = Fake(settings())
        join(ahead(f).ahead(listOf(a, b, c)))
        assertEquals(listOf("a", "b"), f.prepared)
        assertEquals(listOf("a@720", "b@720"), f.asks)
    }

    @Test fun theHeightIsThePlayersAndSavedSongsStaySaved() {
        val f = Fake(settings(full = true)).apply { savedIds = setOf("b") }
        join(ahead(f).ahead(listOf(a, b)))
        assertEquals(listOf("a@1080", "b@1080 saved"), f.asks)
        val lighter = Fake(settings(full = true, prefs = CanvasPrefs(useVideos = true, lighter = true)))
        join(ahead(lighter).ahead(listOf(a)))
        assertEquals(listOf("a@480"), lighter.asks)
    }

    @Test fun neverOnMobileDataEvenWithTheChip() {
        val f = Fake(settings(prefs = CanvasPrefs(useVideos = true, videosOnCellular = true)), onData = true)
        join(ahead(f).ahead(listOf(a, b)))
        assertEquals(emptyList<String>(), f.started)
        assertEquals(emptyList<String>(), f.coverAsked)
    }

    @Test fun neverWithDataSaver() {
        val f = Fake(settings(dataSaver = true))
        join(ahead(f).ahead(listOf(a, b)))
        assertEquals(emptyList<String>(), f.started)
    }

    @Test fun nothingWhenCanvasOffVideosOffOrPerformanceMode() {
        val off = AppSettings().let { it.copy(canvasEnabled = false, behavior = it.behavior.copy(canvas = CanvasPrefs(useVideos = true))) }
        for (s in listOf(off, settings(prefs = CanvasPrefs(useVideos = false)), settings(performance = true))) {
            val f = Fake(s)
            join(ahead(f).ahead(listOf(a, b)))
            assertEquals(emptyList<String>(), f.started)
            assertEquals(emptyList<String>(), f.coverAsked)
        }
    }

    @Test fun aSongWithAnAnimatedCoverIsLeftUnlessTheVideoComesFirst() {
        val f = Fake(settings()).apply { covers = setOf("a") }
        join(ahead(f).ahead(listOf(a, b)))
        assertEquals(listOf("b"), f.prepared)
        assertEquals(listOf("a", "b"), f.coverAsked)
        // Video first (the setting or the song's choice): the cover isn't even looked up.
        val first = Fake(settings(prefs = CanvasPrefs(useVideos = true, videoFirst = true))).apply { covers = setOf("a", "b") }
        join(ahead(first).ahead(listOf(a, b)))
        assertEquals(listOf("a", "b"), first.prepared)
        assertEquals(emptyList<String>(), first.coverAsked)
        val chosen = Fake(settings()).apply { covers = setOf("a", "b"); choices = mapOf("b" to CanvasSource.VIDEO) }
        join(ahead(chosen).ahead(listOf(a, b)))
        assertEquals(listOf("b"), chosen.prepared)
    }

    @Test fun hiddenSongsAndNonSongsAreLeft() {
        val f = Fake(settings()).apply { hiddenIds = setOf("a") }
        join(ahead(f).ahead(listOf(a, song("${Song.LOCAL_PREFIX}x"))))
        assertEquals(emptyList<String>(), f.started)
    }

    @Test fun oneAtATime() {
        val f = Fake(settings()).apply { gate = CompletableDeferred() }
        val job = ahead(f).ahead(listOf(a, b))
        until { f.started.size == 1 }
        Thread.sleep(100)
        assertEquals(listOf("a"), f.started)
        f.gate!!.complete(Unit)
        join(job)
        assertEquals(listOf("a", "b"), f.prepared)
        assertEquals(1, f.most)
    }

    @Test fun aNewQueueCancelsWhatWasUnderWay() {
        val f = Fake(settings()).apply { gate = CompletableDeferred() }
        val prep = ahead(f)
        val first = prep.ahead(listOf(a, b))
        until { f.started.size == 1 }
        f.gate = null
        val second = prep.ahead(listOf(c, d))
        join(second)
        join(first)
        assertTrue(first!!.isCancelled)
        assertEquals(listOf("a"), f.cancelled)
        assertEquals(listOf("c", "d"), f.prepared)
    }

    @Test fun theSameSongsAgainLeaveItBe() {
        val f = Fake(settings()).apply { gate = CompletableDeferred() }
        val prep = ahead(f)
        val first = prep.ahead(listOf(a, b))
        assertSame(first, prep.ahead(listOf(a, b, c))) // only the first two count
        f.gate!!.complete(Unit)
        join(first)
        assertEquals(listOf("a", "b"), f.prepared)
    }

    @Test fun skippingThroughAQueuePreparesNothingButWhereItStops() {
        val f = Fake(settings())
        val prep = ahead(f, waitMs = 300)
        prep.ahead(listOf(a, b))
        prep.ahead(listOf(b, c))
        val last = prep.ahead(listOf(c, d))
        join(last)
        assertEquals(listOf("c", "d"), f.prepared)
    }

    @Test fun mobileDataMidWayStopsTheNetwork() {
        val f = Fake(settings()).apply { gate = CompletableDeferred() }
        val job = ahead(f).ahead(listOf(a))
        until { f.started.size == 1 }
        f.onData = true
        f.gate!!.complete(Unit)
        join(job)
        assertEquals(emptyList<String>(), f.prepared)
    }

    // ------------------------------------------------------------------ Downloads

    @Test fun aDownloadKeepsItsLoopSaved() {
        val f = Fake(settings(full = true))
        join(ahead(f).keepWithDownload(a))
        assertEquals(listOf("a@1080 saved"), f.asks)
        assertEquals(listOf("a"), f.prepared)
    }

    @Test fun aDownloadFollowsSaveWithDownloadsAndItsMobileDataSwitch() {
        val off = Fake(settings(withDownloads = false))
        join(ahead(off).keepWithDownload(a))
        assertEquals(emptyList<String>(), off.started)
        val onData = Fake(settings(), onData = true)
        join(ahead(onData).keepWithDownload(a))
        assertEquals(emptyList<String>(), onData.started)
        val allowed = Fake(settings(downloadsCellular = true), onData = true)
        join(ahead(allowed).keepWithDownload(a))
        assertEquals(listOf("a"), allowed.prepared)
        // Not wanted (the animated cover shows first): nothing kept.
        val cover = Fake(settings()).apply { covers = setOf("a") }
        join(ahead(cover).keepWithDownload(a))
        assertEquals(emptyList<String>(), cover.started)
    }

    @Test fun aRemovedDownloadsLoopGoesBackToRecent() {
        val f = Fake(settings()).apply { videos = mapOf("a" to "vidA", "b" to "vidB"); savedIds = setOf("b") }
        join(ahead(f).forgetDownloads(listOf("a", "b", "c")))
        // b is still kept another way (saved from the player); c has no loop.
        assertEquals(listOf("vidA"), f.unsaved)
    }

    // ------------------------------------------------------------------ The real repository

    private class MemoryDao : CanvasClipDao {
        val rows = HashMap<String, CanvasClipEntity>()
        override suspend fun get(songId: String) = rows[songId]
        override suspend fun put(e: CanvasClipEntity) { rows[e.songId] = e }
        override suspend fun delete(songId: String) { rows.remove(songId) }
        override suspend fun clearGuesses() {}
        override suspend fun count() = rows.values.count { it.videoId != null }
    }

    private class NoNet : ClipFinder.Net {
        val asked = AtomicInteger()
        override suspend fun findVideo(song: Song): String? { asked.incrementAndGet(); error("no network") }
        override suspend fun videoType(videoId: String): String? { asked.incrementAndGet(); error("no network") }
        override suspend fun player(videoId: String): PlayerInfo { asked.incrementAndGet(); error("no network") }
        override suspend fun frames(board: Storyboard): ClipFinder.Frames { asked.incrementAndGet(); error("no network") }
        override suspend fun heatmap(videoId: String): Heatmap? { asked.incrementAndGet(); error("no network") }
    }

    @Test fun aLoopAlreadyKeptAsksNothing() = runBlocking {
        val files = ClipFiles(File(tmp.root, "recent"), File(tmp.root, "saved"), { 100L shl 20 })
        val dao = MemoryDao()
        val net = NoNet()
        val http = AtomicInteger()
        val player = AtomicInteger()
        val repo = ClipRepository(
            dao, ClipFinder(net),
            ClipFetcher(
                OkHttpClient.Builder().addInterceptor { http.incrementAndGet(); error("no network") }.build(),
                { player.incrementAndGet(); error("no network") }, files,
            ),
        )
        val now = System.currentTimeMillis()
        dao.put(clipRow("a", VideoClip("vidA", 12_000, 19_000, 720), null, null, now, videoType = "MUSIC_VIDEO_TYPE_OMV", itag = 136))
        File(tmp.root, "recent").mkdirs()
        File(tmp.root, "recent/" + ClipFiles.name("vidA", 720, 136, 10_000, 30_000, false)).writeBytes(ByteArray(1024))
        val allowedAsked = AtomicInteger()
        assertTrue(repo.prepare(a, 720, saved = false) { allowedAsked.incrementAndGet(); true })
        assertEquals(0, net.asked.get())
        assertEquals(0, http.get())
        assertEquals(0, player.get())
        assertEquals(0, allowedAsked.get())
        // Kept with a download: the same file moves to the saved folder, still with no network.
        assertTrue(repo.prepare(a, 720, saved = true) { true })
        assertNotNull(files.of("vidA").singleOrNull { it.saved })
        assertEquals(0, http.get() + player.get() + net.asked.get())
        // Removed: back to recent.
        files.unsave("vidA")
        assertTrue(files.of("vidA").single().let { !it.saved })
    }

    @Test fun clearingSavedLoopsLeavesRecentOnes() {
        val files = ClipFiles(File(tmp.root, "recent"), File(tmp.root, "saved"), { 100L shl 20 })
        File(tmp.root, "recent").mkdirs(); File(tmp.root, "saved").mkdirs()
        File(tmp.root, "recent/" + ClipFiles.name("r", 720, 136, 0, 9_000, false)).writeBytes(ByteArray(10))
        File(tmp.root, "saved/" + ClipFiles.name("s", 720, 136, 0, 9_000, false)).writeBytes(ByteArray(20))
        assertEquals(20L, files.savedBytes)
        files.clearSaved()
        assertEquals(0L, files.savedBytes)
        assertEquals(10L, files.recentBytes)
    }
}
