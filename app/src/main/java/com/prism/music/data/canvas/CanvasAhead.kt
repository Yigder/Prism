package com.prism.music.data.canvas

import com.prism.music.data.model.Song
import com.prism.music.data.prefs.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Canvas loops got ready before they show (1.8.6, plan-canvas §3.7 "Looking ahead", §11a R2, row 4.8):
 * - [ahead]: while a song plays, the next two songs' loops are found and fetched, one at a time, on Wi-Fi only (never
 *   on mobile data, whatever its chip says, and never with Data saver), after a short wait so skipping through a queue
 *   prepares nothing; a newer queue cancels what was under way.
 * - [keepWithDownload]: a downloaded song keeps its loop ("Save with downloads"), on Wi-Fi unless the downloads' own
 *   mobile-data switch allows it, as its animated cover does.
 * - [forgetDownloads]: a removed download's loop goes back to the recent folder (pruned like any other).
 *
 * Whether a loop is wanted at all is [canvasWant], the same rule the player uses. A loop already on the phone costs no
 * network ([ClipRepository.artworkFor] finds the kept file first).
 */
class CanvasAhead(
    private val scope: CoroutineScope,
    private val facts: Facts,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val waitMs: Long = WAIT_MS,
) {
    /** What the preparation reads and does; the app container wires the real ones, tests pass fakes. */
    interface Facts {
        /** The settings Prism uses ([AppSettings.effective]). */
        fun settings(): AppSettings
        /** On mobile data (a fresh check). */
        fun metered(): Boolean
        /** The song's Canvas loop is switched off from the player (its animated cover's switch is [hasCover]'s). */
        fun hidden(songId: String): Boolean
        /** The song's Canvas is kept for good (saved from the player, its album saved, or downloaded). */
        fun saved(song: Song): Boolean
        /** The same, by id alone (a song saved by its album doesn't count: its loop goes back to recent, and returns when played). */
        fun savedById(songId: String): Boolean
        suspend fun choice(songId: String): CanvasSource?
        /** Whether the song has an animated cover that may play ([canvasCoverWanted]; looked up if not known yet). */
        suspend fun hasCover(song: Song): Boolean
        /** Finds and fetches the loop ([ClipRepository.prepare]); true when it is on the phone. */
        suspend fun prepare(song: Song, height: Int, saved: Boolean, allowed: () -> Boolean): Boolean
        /** The video a song's loop is cut from, if it has one. */
        suspend fun videoOf(songId: String): String?
        /** Moves [videoId]'s saved loops back to recent ([ClipFiles.unsave]). */
        fun unsave(videoId: String)
    }

    private val lock = Any()
    private var job: Job? = null
    private var lastIds: List<String>? = null

    /**
     * Prepares the loops of [next] (the songs after the one playing, in play order; only the first [AHEAD] count).
     * A call with other songs cancels the one before; the same songs again leave it be. Returns what is under way.
     */
    fun ahead(next: List<Song>): Job? {
        val songs = next.take(AHEAD)
        val ids = songs.map { it.id }
        synchronized(lock) {
            val running = job
            if (ids == lastIds && running != null && !running.isCancelled) return running
            running?.cancel()
            lastIds = ids
            job = if (songs.isEmpty()) null else scope.launch(dispatcher) {
                delay(waitMs)
                // One at a time: each waits for the one before.
                for (song in songs) quietly { prepareAhead(song) }
            }
            return job
        }
    }

    private suspend fun prepareAhead(song: Song): Boolean {
        val s = facts.settings()
        if (!canvasMayPrepare(s, facts.metered())) return false
        val want = wanted(song, s, metered = false) ?: return false
        return facts.prepare(song, want.height, facts.saved(song)) { canvasMayPrepare(facts.settings(), facts.metered()) }
    }

    /**
     * The decision for [song] ([canvasWant]), the animated cover looked up only when it decides it (the default order
     * shows a song's animated cover first, so its loop is only wanted when it has none). Null: no loop wanted.
     */
    suspend fun wanted(song: Song, s: AppSettings, metered: Boolean): CanvasWant? {
        val hidden = facts.hidden(song.id)
        val isSong = ClipGate.isSong(song)
        val noCover = canvasWant(s, metered, hasCover = false, songChoice = null, hidden = hidden, isSong = isSong)
        if (!noCover.video) return null
        val choice = facts.choice(song.id)
        if (canvasWant(s, metered, hasCover = true, songChoice = choice, hidden = hidden, isSong = isSong).video) return noCover
        return if (facts.hasCover(song)) null else noCover
    }

    /**
     * A downloaded song keeps its loop when "Save with downloads" is on and a loop is wanted for it: kept with the
     * download (saved), on Wi-Fi unless the downloads' mobile-data switch for Canvas allows it.
     */
    fun keepWithDownload(song: Song): Job = scope.launch(dispatcher) {
        quietly {
            val s = facts.settings()
            val allowed = { !facts.metered() || facts.settings().canvasWithDownloadsCellular }
            if (!s.canvasWithDownloads || !allowed()) return@quietly
            val want = wanted(song, s, facts.metered()) ?: return@quietly
            facts.prepare(song, want.height, true, allowed)
        }
    }

    /** Removed downloads: their loops go back to the recent folder (unless the song is still kept another way). */
    fun forgetDownloads(ids: Collection<String>): Job = scope.launch(dispatcher) {
        for (id in ids) quietly {
            if (facts.savedById(id)) return@quietly
            facts.videoOf(id)?.let { facts.unsave(it) }
        }
    }

    /** A failure here is only a loop not ready: the player asks again when the song plays. */
    private suspend fun quietly(block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) {
            android.util.Log.w("PrismCanvas", "Couldn't get a loop ready: $e")
        }
    }

    companion object {
        /** How many songs ahead are prepared (plan §11a R2: two). */
        const val AHEAD = 2
        /** How long a song plays before the ones after it are prepared: skipping through a queue prepares nothing. */
        const val WAIT_MS = 8_000L
    }
}
