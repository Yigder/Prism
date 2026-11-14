package com.prism.music

import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import com.prism.music.data.db.PlayEventEntity
import com.prism.music.data.db.toEntity
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.ui.lockscreen.LockScreenActivity
import com.prism.music.ui.player.CanvasStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/**
 * Footage for the 1.8.6 launch film (`.claude/video-1.8.6/captures/record.ps1` runs one shot end to end): each test is
 * one shot, recorded with `screenrecord` from inside the test (so it starts on the action) into /sdcard/prism-film/, or a
 * still there. Live (real songs, real music videos, muted by the phone): run only with `record=true`.
 *
 * Every shot is in the film's look (CanvasLoopLiveTest's `mono`: dark, pure black, white light, neutral surfaces, no
 * play ring, black player), Performance mode and Reduce motion off, the status bar hidden (the app's own setting, and
 * for windows the setting doesn't reach, a dialog's or the lock screen's, hidden here). The log (tag PrismFilm) says
 * where in each recording the action starts ("mark <name> <label> <seconds>"), which record.ps1 trims to.
 *
 * Replay and Rewind need a year of plays: [aSeedYear] writes one (invented numbers, real songs), once; it stays in the
 * test copy's data between runs (am instrument without the orchestrator keeps it).
 */
@Live
@RunWith(AndroidJUnit4::class)
class FilmCaptureTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val c get() = Fixture.container
    private val device: UiDevice by lazy {
        Configurator.getInstance().waitForIdleTimeout = 0
        Configurator.getInstance().actionAcknowledgmentTimeout = 0
        UiDevice.getInstance(instrumentation)
    }
    private val zone: ZoneId = ZoneId.systemDefault()
    @Volatile private var keepHiding = true
    private var hider: Thread? = null

    @Before fun start() {
        assumeTrue("film capture: run with record=true", args.getString("record") == "true")
        shell("cmd media_session volume --stream 3 --set 0")
        shell("mkdir -p /sdcard/prism-film")
        Fixture.ready()
        look()
        // Immersive for the footage: the status bar (also in windows the app's "Hide the status bar" doesn't cover, a
        // story's dialog, the lock screen) and the gesture pill, hidden in every window of the app, kept hidden.
        hider = Thread {
            while (keepHiding) {
                runCatching { instrumentation.runOnMainSync { hideStatusBars() } }
                Thread.sleep(40)
            }
        }.apply { isDaemon = true; start() }
    }

    @After fun cleanUp() {
        keepHiding = false
        LockScreenActivity.pretendLocked = false
        runCatching { instrumentation.runOnMainSync { LockScreenActivity.live?.finish(); c.player.stop() } }
        shell("cmd media_session volume --stream 3 --set 0")
    }

    // ------------------------------------------------------------------------------------------------ Canvas

    /** Every Canvas song once, player open, until its loop shows: kept on the phone, so the shots open on it at once. */
    @Test fun aWarmLoops() = runBlocking {
        canvasLook()
        Fixture.launch()
        val list = (args.getString("songs") ?: CANVAS_SONGS.joinToString("+")).split('+').map { it.replace('_', ' ') }
        list.forEachIndexed { i, words ->
            val song = find(words) ?: run { log("warm \"$words\": no song"); return@forEachIndexed }
            CanvasStats.showing = null
            play(listOf(song))
            if (i == 0) openPlayer()
            val shown = waitLoop(45_000)
            val row = c.canvasClips.row(song.id)
            log("warm $i \"$words\" = ${song.id} \"${song.title}\": ${if (shown) "LOOP" else "NO LOOP (${row?.why})"} video ${row?.videoId} ${row?.height}p ${row?.startMs}-${row?.endMs}; cover ${c.canvas.cached(song) != null}")
            if (shown) { delay(5_500); Fixture.screenshot("film-warm-${i.toString().padStart(2, '0')}") }
        }
    }

    /** canvas-reveal: Houdini's loop, the player opening on it with its controls, which fade, then the loop alone. */
    @Test fun canvasReveal() = runBlocking {
        canvasLook()
        Fixture.launch()
        val song = find(args.getString("song")?.replace('_', ' ') ?: "Houdini Dua Lipa")!!
        play(listOf(song))
        openPlayer()
        assertTrue("its loop shows (${CanvasStats.showing})", waitLoop(45_000))
        // Closed, then opened again on the kept loop: the controls show, and fade by themselves.
        delay(1_500)
        device.pressBack()
        delay(2_500)
        val rec = Recording("canvas-reveal", 22)
        delay(1_200)
        rec.mark("start")
        openPlayer()
        delay(19_500)
        rec.finish()
    }

    /** canvas-a/b/c (`shot=canvas-a song=...`): a loop moving, the controls away. */
    @Test fun canvasLoop() = runBlocking {
        canvasLook()
        Fixture.launch()
        val name = args.getString("shot") ?: "canvas-a"
        val song = find(args.getString("song")!!.replace('_', ' '))!!
        play(listOf(song))
        openPlayer()
        assertTrue("its loop shows (${CanvasStats.showing})", waitLoop(45_000))
        delay(6_000)
        val rec = Recording(name, 13)
        delay(1_000)
        rec.mark("start")
        delay(11_500)
        rec.finish()
    }

    /** canvas-next (`songs=A+B`): one loop, then next (no touch), the next song's loop. */
    @Test fun canvasNext() = runBlocking {
        canvasLook()
        Fixture.launch()
        val songs = args.getString("songs")!!.split('+').map { find(it.replace('_', ' '))!! }
        play(songs)
        openPlayer()
        assertTrue("its loop shows (${CanvasStats.showing})", waitLoop(45_000))
        delay(6_000)
        val rec = Recording("canvas-next", 24)
        delay(1_000)
        rec.mark("start")
        delay(4_000)
        val first = CanvasStats.showing
        rec.mark("next")
        instrumentation.runOnMainSync { c.player.next() }
        val end = System.currentTimeMillis() + 9_000
        while (System.currentTimeMillis() < end && (CanvasStats.showing == first || CanvasStats.showing?.startsWith("file://") != true)) delay(100)
        log("next: ${first} -> ${CanvasStats.showing}")
        // What shows each second after (a check: the song before's loop must not come back).
        repeat(14) { delay(1_000); log("next +${it + 1}s: ${c.player.currentSong.value?.title} showing ${CanvasStats.showing?.substringAfterLast('/')}") }
        rec.finish()
    }

    /**
     * canvas-menu: a tap brings the controls back, ⋯, down to "Pick another video", the second video; once its loop is
     * on, ⋯ again, "Pick another part", another part. By hand's pace, through UiAutomator.
     */
    @Test fun canvasMenu() = runBlocking {
        canvasLook()
        Fixture.launch()
        val song = find(args.getString("song")?.replace('_', ' ') ?: "Espresso Sabrina Carpenter")!!
        // `startVideo=<id>`: the song's loop from that video to begin with (a run before may have picked another).
        args.getString("startVideo")?.let { id ->
            if (c.canvasClips.row(song.id)?.videoId != id) log("menu: back to $id: ${c.canvasClips.pickVideo(song, id, com.prism.music.data.canvas.canvasHeight(c.settings.current))}")
        }
        play(listOf(song))
        openPlayer()
        assertTrue("its loop shows (${CanvasStats.showing})", waitLoop(45_000))
        val videos = withContext(Dispatchers.IO) { c.canvasClips.videos(song) }
        val current = c.canvasClips.row(song.id)?.videoId
        log("menu: videos ${videos.map { it.id + " " + it.title }}; current $current")
        delay(6_000)
        val rec = Recording("canvas-menu", 60)
        delay(1_500)
        rec.mark("start")
        // The controls back.
        device.click(device.displayWidth / 2, device.displayHeight * 2 / 5)
        delay(1_200)
        openMenuAndTap("Pick another video")
        val sheetTitle = waitFor(txt("Pick another video"), 8_000)!!
        // The videos' row, under the sheet's eyebrow: wait for its pictures to come in.
        delay(1_800)
        val row = clickablesBelow(sheetTitle.visibleBounds, 260)
        log("menu: ${row.size} videos in the sheet")
        // `videoText=<words in its title>`: along the row (a slow swipe at a time) to that video; else `video=<n>` (the second).
        val wanted = args.getString("videoText")?.replace('_', ' ')
        var pick = row.getOrNull(args.getString("video")?.toIntOrNull() ?: 1) ?: row.last()
        if (wanted != null) {
            val y = row.firstOrNull()?.visibleBounds?.centerY() ?: (sheetTitle.visibleBounds.bottom + 200)
            for (k in 0 until 6) {
                val hit = device.findObject(By.textContains(wanted))
                // Whole on screen, with room for its picture: there.
                if (hit != null && hit.visibleBounds.left > 20 && hit.visibleBounds.left + 450 < device.displayWidth) break
                // A flick along the row, as a hand does.
                device.swipe(device.displayWidth * 4 / 5, y, device.displayWidth / 5, y, 14)
                delay(1_100)
            }
            delay(700)
        }
        val before = CanvasStats.showing
        rec.mark("pickVideo")
        val target = wanted?.let { w -> device.findObject(By.textContains(w))?.visibleBounds }
        // On its picture, just above the title.
        if (target != null) device.click(target.left + 150, target.top - 120) else pick.click()
        val until = System.currentTimeMillis() + 40_000
        while (System.currentTimeMillis() < until && (CanvasStats.showing == before || CanvasStats.showing?.startsWith("file://") != true)) delay(150)
        log("menu: video picked, showing ${CanvasStats.showing} (was $before), row ${c.canvasClips.row(song.id)?.videoId}")
        rec.mark("videoChanged")
        delay(3_000)
        // The controls may have gone meanwhile.
        if (device.findObject(By.desc("Song options")) == null) { device.click(device.displayWidth / 2, device.displayHeight * 2 / 5); delay(1_100) }
        openMenuAndTap("Pick another part")
        val partsTitle = waitFor(txt("Pick another part"), 8_000)!!
        delay(1_800)
        val parts = clickablesBelow(partsTitle.visibleBounds, 220)
        log("menu: ${parts.size} parts")
        val before2 = CanvasStats.showing
        rec.mark("pickPart")
        (parts.getOrNull(2) ?: parts.getOrNull(1) ?: parts.last()).click()
        val until2 = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < until2 && (CanvasStats.showing == before2 || CanvasStats.showing?.startsWith("file://") != true)) delay(150)
        log("menu: part picked, showing ${CanvasStats.showing} (was $before2)")
        rec.mark("partChanged")
        delay(5_000)
        rec.finish()
    }

    /** animated-cover: a song with an animated cover, Canvas's music videos off, the cover moving, controls up. */
    @Test fun animatedCover() = runBlocking {
        canvasLook()
        c.settings.setCanvasKinds(covers = true, videos = false)
        c.settings.setCanvasKeepControls(true)
        c.settings.editNow { }
        Fixture.launch()
        val song = find(args.getString("song")?.replace('_', ' ') ?: "Anti-Hero Taylor Swift")!!
        val art = withContext(Dispatchers.IO) { c.canvas.canvasFor(song) }
        log("cover: ${song.title}: $art")
        assumeTrue("an animated cover for ${song.title}", art != null)
        CanvasStats.showing = null
        play(listOf(song))
        openPlayer()
        val end = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < end && CanvasStats.showing == null) delay(100)
        log("cover: showing ${CanvasStats.showing}")
        delay(3_000)
        val rec = Recording("animated-cover", 12)
        delay(1_000)
        rec.mark("start")
        delay(10_500)
        rec.finish()
    }

    // ------------------------------------------------------------------------------------------------ Replay

    /**
     * A believable 2026 so far: ~40 well-known songs (and more by the same artists), evenings and Saturdays busiest
     * (19:00 the busiest hour), a few favourites far ahead, a spring streak, about a thousand hours. Once: `reseed=true`
     * writes it again. Lyrics and genres for the top songs are looked up the normal way, for "Finish the lyric" and the
     * Rewind's genre slides.
     */
    @Test fun aSeedYear() = runBlocking {
        val now = System.currentTimeMillis()
        val from = LocalDate.of(2026, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val had = c.db.plays().count(from, now)
        if (had > 5_000 && args.getString("reseed") != "true") { log("seed: $had plays already"); return@runBlocking }
        c.db.openHelper.writableDatabase.execSQL("DELETE FROM play_events")
        val headline = LinkedHashMap<String, Song>()
        // The artist's own recording (not a tribute or a cover that ranks first): one of its artists is named in the words.
        val notOriginal = Regex("(?i)tribute|cover|karaoke|instrumental|remix|sped up|slowed|lullaby|piano")
        fun original(s: Song, words: String) = s.artists.any { a -> a.name.isNotBlank() && words.contains(a.name, true) } && !notOriginal.containsMatchIn(s.title + " " + s.artistText)
        for (q in SEED_SONGS) {
            val s = runCatching { withContext(Dispatchers.IO) { c.ytm.search(q, SearchFilter.SONGS).items.filterIsInstance<SongItem>().map { it.song }.firstOrNull { original(it, q) } } }.getOrNull()
            if (s == null) log("seed: no song for \"$q\"") else { headline.putIfAbsent(s.id, s); log("seed: \"$q\" = ${s.title} / ${s.artistText}") }
        }
        val extra = LinkedHashMap<String, Song>()
        for (artist in SEED_ARTISTS) {
            val found = runCatching { withContext(Dispatchers.IO) { c.ytm.search(artist, SearchFilter.SONGS).items.filterIsInstance<SongItem>().map { it.song } } }.getOrDefault(emptyList())
            found.filter { s -> s.id !in headline && s.id !in extra && s.artists.any { it.name.equals(artist, true) } && s.durationSec in 120..330 && !notOriginal.containsMatchIn(s.title) }.take(5).forEach { extra[it.id] = it }
        }
        val songs = headline.values.toList() + extra.values.toList()
        val all = songs.map { s -> c.db.songs().get(s.id).let { s.toEntity(it) } }
        c.db.songs().upsertAll(all)
        log("seed: ${headline.size} headline songs, ${extra.size} more, ${songs.map { it.primaryArtist }.distinct().size} artists")

        // Weights: the headline songs in their order (the favourites first, far ahead), the rest well behind.
        // (A heavy listener's year: the top song a few hundred plays, the tail a few dozen each.)
        val weights = DoubleArray(songs.size) { i -> if (i < headline.size) 1.0 / Math.sqrt(i + 8.0) else 0.085 / Math.pow((i - headline.size + 1).toDouble(), 0.15) }
        for (i in 0 until minOf(3, weights.size)) weights[i] *= 1.15
        // By time listened, as Replay ranks: a long song gets fewer plays for the same place.
        for (i in weights.indices) weights[i] *= 200.0 / (songs[i].durationSec.takeIf { it > 0 } ?: 200)
        val cumulative = weights.runningReduce { a, b -> a + b }
        val rnd = Random(20261201)
        fun pickSong(): Int { val r = rnd.nextDouble() * cumulative.last(); return cumulative.indexOfFirst { it >= r }.coerceAtLeast(0) }
        // Songs Prism found (first heard on its radio, a little into the year) and kept playing.
        val finds = listOf(9, 14, 19, 24, 28, 33).filter { it < headline.size }
        val foundOn = finds.associateWith { LocalDate.of(2026, 2 + finds.indexOf(it), 6 + finds.indexOf(it) * 3) }
        val weekday = doubleArrayOf(0.3, 0.15, 0.1, 0.05, 0.05, 0.1, 0.4, 2.5, 3.0, 1.5, 1.2, 1.4, 2.2, 2.0, 1.4, 1.4, 1.8, 3.5, 6.0, 9.0, 7.0, 5.0, 3.0, 1.2)
        val weekend = doubleArrayOf(0.8, 0.5, 0.2, 0.1, 0.05, 0.05, 0.1, 0.3, 0.8, 1.6, 2.6, 3.2, 3.6, 3.4, 3.2, 3.2, 3.4, 4.2, 6.0, 8.6, 7.0, 5.5, 3.6, 2.0)
        fun pickHour(w: DoubleArray): Int { var r = rnd.nextDouble() * w.sum(); for (h in w.indices) { r -= w[h]; if (r <= 0) return h }; return 19 }
        val perDay = doubleArrayOf(142.0, 146.0, 150.0, 158.0, 192.0, 258.0, 196.0) // minutes, Monday first
        val events = ArrayList<PlayEventEntity>()
        val today = LocalDate.now(zone)
        var d = LocalDate.of(2026, 1, 1)
        val streak = LocalDate.of(2026, 3, 2)..LocalDate.of(2026, 6, 28)
        while (!d.isAfter(today)) {
            if (d !in streak && rnd.nextDouble() < 0.07) { d = d.plusDays(1); continue }
            val dow = d.dayOfWeek
            val season = 1.0 + 0.14 * Math.sin((d.dayOfYear - 100) / 365.0 * 2 * Math.PI)
            var minutes = perDay[dow.value - 1] * season * (0.65 + rnd.nextDouble() * 0.7)
            if (rnd.nextDouble() < 0.02) minutes *= 2.2
            var left = (minutes * 60_000).toLong()
            val hours = if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) weekend else weekday
            while (left > 0) {
                var i = pickSong()
                if (foundOn[i]?.isAfter(d) == true) i = pickSong().let { j -> if (foundOn[j]?.isAfter(d) == true) 0 else j }
                val dur = (songs[i].durationSec.takeIf { it > 0 } ?: 200) * 1000L
                val played = if (rnd.nextDouble() < 0.1) (dur * (0.2 + rnd.nextDouble() * 0.4)).toLong().coerceAtLeast(35_000) else (dur * (0.92 + rnd.nextDouble() * 0.08)).toLong()
                left -= played
                val at = d.atTime(pickHour(hours), rnd.nextInt(60), rnd.nextInt(60)).atZone(zone).toInstant().toEpochMilli()
                if (at > now - 15 * 60_000) continue
                val source = when (rnd.nextInt(100)) { in 0..5 -> "smart"; in 6..7 -> "dj"; in 8..15 -> "PLAYLIST"; in 16..22 -> "ALBUM"; else -> null }
                events += PlayEventEntity(songId = songs[i].id, timestamp = at, playedMs = played, source = source)
            }
            d = d.plusDays(1)
        }
        events.sortBy { it.timestamp }
        // Each song's first play: from Prism's radio for the finds, from a search or the library for the rest.
        val firstSeen = HashSet<String>()
        val findIds = finds.map { songs[it].id }.toSet()
        val out = events.map { e ->
            if (firstSeen.add(e.songId)) e.copy(source = if (e.songId in findIds) "radio" else "SEARCH") else e
        }
        c.db.withTransaction { for (e in out) c.db.plays().insert(e) }
        val hoursTotal = out.sumOf { it.playedMs } / 3_600_000.0
        log("seed: ${out.size} plays, %.0f hours, top ${songs.take(5).map { s -> s.title + " " + out.count { it.songId == s.id } }}".format(hoursTotal))

        // Lyrics (saved, as playing a song saves them) and genres for the top songs.
        for (s in songs.take(36)) {
            val l = runCatching { withContext(Dispatchers.IO) { c.lyrics.auto(s) } }.getOrNull()
            runCatching { withContext(Dispatchers.IO) { c.meta.genreFor(s) } }
            log("seed: lyrics for ${s.title}: ${l?.let { "${it.lines.size} lines" } ?: "none"}")
        }
    }

    /** replay-clock: Replay from its top, a slow scroll to the listening clock, held, then the busiest hour tapped. */
    @Test fun replayClock() = runBlocking {
        noSong()
        Fixture.launch()
        openReplay()
        delay(3_000)
        val rec = Recording("replay-clock", 36)
        delay(1_500)
        rec.mark("start")
        val grid = slowScrollToCentre(By.descStartsWith("Listening clock"))!!
        rec.mark("rest")
        delay(3_000)
        val b = grid.visibleBounds
        log("clock grid $b")
        rec.mark("tap")
        device.click(b.left + (b.width() * 19.5f / 24f).toInt(), b.centerY())
        delay(2_500)
        // Its top songs under it: up a little so they're on screen.
        slowDrag(device.displayHeight * 3 / 4, device.displayHeight * 3 / 4 - 520)
        delay(4_000)
        rec.finish()
    }

    /** replay-calendar: on to the listening calendar, held, a day tapped. */
    @Test fun replayCalendar() = runBlocking {
        noSong()
        Fixture.launch()
        openReplay()
        delay(2_500)
        // Down to the clock first (out of the shot), so the shot is the last stretch to the calendar.
        slowScrollToCentre(By.descStartsWith("Listening clock"), fast = true)
        delay(1_500)
        val rec = Recording("replay-calendar", 30)
        delay(1_500)
        rec.mark("start")
        val grid = slowScrollToCentre(By.descStartsWith("Listening calendar"))!!
        rec.mark("rest")
        delay(3_000)
        val b = grid.visibleBounds
        // A Saturday in early summer, somewhere in the grid's middle rows; the line under it says the day.
        rec.mark("tap")
        device.click(b.left + b.width() * 46 / 100, b.top + b.height() * 45 / 100)
        delay(2_000)
        val line = device.findObject(By.textContains(" · "))
        log("calendar: ${line?.text}")
        if (line == null || !line.text.contains("play")) { device.click(b.left + b.width() * 62 / 100, b.top + b.height() * 40 / 100); delay(2_000) }
        delay(4_000)
        rec.finish()
    }

    /**
     * rewind: the 2026 Rewind as it opens on 1 December (a debug build opens the year's Rewind early: Rewinds.early is
     * BuildConfig.DEBUG). Opened from Replay's "Watch your Rewind", then each slide in turn at a steady pace (`pace=<ms>`).
     */
    @Test fun rewind() = runBlocking {
        noSong()
        log("rewind open for 2026: ${c.rewind.isOpen(2026)} (early ${c.rewind.early})")
        assertTrue("Rewind 2026 opens in this build", c.rewind.isOpen(2026))
        // Worked out before the tap (it's kept ten minutes), so the story opens at once in the shot.
        val snap = c.rewind.snapshot(2026)
        log("rewind: ${snap.plays} plays, ${snap.totalMs / 3_600_000} h, finds ${snap.finds.size}, milestones ${snap.milestones.size}, top ${snap.songs.take(3).map { it.title }}")
        Fixture.launch()
        openReplay()
        delay(3_000)
        val pace = args.getString("pace")?.toLongOrNull() ?: 2_800L
        val slides = args.getString("slides")?.toIntOrNull() ?: 30
        val seconds = ((slides * pace) / 1000 + 12).toInt().coerceAtMost(180)
        val rec = Recording("rewind", seconds)
        delay(1_500)
        rec.mark("start")
        waitFor(txt("Watch your Rewind"), 10_000)!!.click()
        waitFor(By.desc("Share this slide"), 20_000)
        rec.mark("story")
        repeat(slides) {
            delay(pace)
            if (device.findObject(By.desc("Share this slide")) == null) return@repeat
            device.click(device.displayWidth * 4 / 5, device.displayHeight / 2)
        }
        delay(2_000)
        rec.finish()
    }

    /** replay-top.png and home*.png: Replay's top, and Home with its Replay card. */
    @Test fun stillsReplayAndHome() = runBlocking {
        noSong()
        Fixture.launch()
        delay(6_000)
        still("home-1")
        // Down to the Replay card if it isn't on the first screen.
        val card = device.findObject(By.textContains("2026"))
        log("home: a 2026 card ${card?.visibleBounds}")
        repeat(3) { k -> slowDrag(device.displayHeight * 3 / 4, device.displayHeight / 4); delay(2_000); still("home-${k + 2}") }
        openReplay()
        delay(4_000)
        still("replay-top")
    }

    /** settings-first.png, history-search.png, finish-the-lyric.png. */
    @Test fun stillsSmallThings() = runBlocking {
        noSong()
        c.settings.setFinishLyric(true)
        c.settings.editNow { }
        Fixture.launch()
        delay(4_000)
        val pool = com.prism.music.ui.screens.loadFinishLyricPool(c, 0, System.currentTimeMillis())
        log("finish the lyric: ${pool.lyrics.size} songs with saved lyrics, playable ${pool.playable}")
        if (args.getString("only") != "lyric") {
        waitFor(By.desc("Settings"), 15_000)!!.click()
        delay(3_000)
        still("settings-first")
        device.pressBack()
        delay(1_500)
        // Library → History, a search.
        waitFor(txt("Library"), 10_000)!!.click()
        delay(2_000)
        waitFor(txt("History"), 10_000)!!.click()
        delay(2_500)
        val field = waitFor(By.clazz("android.widget.EditText"), 8_000)
        field?.click(); delay(800)
        field?.text = args.getString("query") ?: "dua"
        delay(2_000)
        device.pressBack() // the keyboard away
        delay(1_500)
        still("history-search")
        }
        // Replay → Finish the lyric.
        openReplay()
        delay(3_000)
        // The card is one button (its texts merged), found by the words it holds.
        val card = slowScrollToCentre(By.textContains("Finish the lyric"), fast = true)
            ?: slowScrollToCentre(By.descContains("Finish the lyric"), fast = true)
        log("finish the lyric card: ${card?.visibleBounds}")
        if (card == null) still("finish-the-lyric-notfound")
        if (card != null) {
            card.click()
            delay(3_000)
            // A start button, if the game has one before its first question.
            device.findObject(By.textStartsWith("Start"))?.let { it.click(); delay(2_500) }
            device.findObject(By.textStartsWith("Play"))?.takeIf { device.findObject(txt("Finish the lyric")) != null }?.let { it.click(); delay(2_500) }
            still("finish-the-lyric")
        }
    }

    /** lockscreen.png: Prism's own lock-screen player, as if the phone were locked (no security setting changed). */
    @Test fun stillLockScreen() = runBlocking {
        canvasLook()
        c.settings.setLockScreen(true)
        c.settings.editNow { }
        Fixture.launch()
        val song = find(args.getString("song")?.replace('_', ' ') ?: "Espresso Sabrina Carpenter")!!
        play(listOf(song))
        delay(5_000)
        LockScreenActivity.pretendLocked = true
        Fixture.context.startActivity(android.content.Intent(Fixture.context, LockScreenActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val end = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < end && LockScreenActivity.live == null) delay(100)
        delay(4_000)
        still("lockscreen")
        c.settings.setLockScreen(false)
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private fun look() = runBlocking {
        val s = c.settings
        s.setThemeMode(com.prism.music.data.prefs.ThemeMode.DARK); s.setPureBlack(true)
        s.setLightStyle(com.prism.music.data.prefs.LightStyle.WHITE); s.setSurfaceTint(com.prism.music.data.prefs.SurfaceTint.NEUTRAL)
        s.setPlayRing(false); s.setPlayerBackground(com.prism.music.data.prefs.PlayerBackground.BLACK)
        // White as the accent too (taken from the cover, Replay's graphs would be the playing song's color).
        s.setColorSource(com.prism.music.data.prefs.ColorSource.CUSTOM); s.setAccent(0xFFFFFFFF.toInt())
        s.setPerformanceMode(false); s.setReduceMotion(false)
        s.setHideStatusBar(true); s.setHideStatusBarPlayer(true)
        s.setReplayHours(true)
        // Home's "Download songs before you need them?" answered (no), so it isn't in any shot.
        s.setSmartDownloads(false)
        s.editNow { }
    }

    private fun canvasLook() = runBlocking {
        val s = c.settings
        s.setImmersivePlayer(false); s.setFullBleedArtwork(false)
        s.setCanvasEnabled(true); s.setCanvasKinds(covers = true, videos = true)
        s.setCanvasFillsBackground(true); s.setCanvasUseVideos(true)
        s.setCanvasVideoFirst(true); s.setCanvasKeepControls(false)
        s.editNow { }
    }

    private suspend fun find(words: String): Song? =
        runCatching { withContext(Dispatchers.IO) { c.ytm.search(words, SearchFilter.SONGS).items.filterIsInstance<SongItem>().first().song } }.getOrNull()

    private suspend fun play(songs: List<Song>) {
        CanvasStats.showing = null
        withContext(Dispatchers.Main) { c.player.playQueue(songs, 0, QueueSource(QueueKind.OTHER, "Film")) }
    }

    private fun openPlayer() = Fixture.context.startActivity(
        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("prism://player"))
            .setClass(Fixture.context, PrismActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
    )

    private fun openReplay() = Fixture.context.startActivity(
        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("prism://replay"))
            .setClass(Fixture.context, PrismActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
    )

    private suspend fun waitLoop(ms: Long): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end && CanvasStats.showing?.startsWith("file://") != true) delay(100)
        return CanvasStats.showing?.startsWith("file://") == true
    }

    /** ⋯, then down the menu (slowly) until [item] shows, and a tap on it. */
    private suspend fun openMenuAndTap(item: String) {
        waitFor(By.desc("Song options"), 6_000)!!.click()
        delay(1_300)
        repeat(8) {
            val found = device.findObject(txt(item))
            if (found != null && found.visibleBounds.centerY() < device.displayHeight - 260) {
                delay(700); found.click(); delay(900); return
            }
            slowDrag(device.displayHeight * 17 / 20, device.displayHeight / 4, steps = 55)
            delay(900)
        }
        error("no \"$item\" in the menu")
    }

    /** The clickable things in a sheet's row under [title] (within [rowDp] dp), left to right. */
    private fun clickablesBelow(title: Rect, rowDp: Int): List<UiObject2> {
        val px = rowDp * device.displaySizeDp.let { device.displayWidth.toFloat() / it.x }
        return device.findObjects(By.clickable(true)).filter { o ->
            val b = o.visibleBounds
            b.top >= title.bottom - 4 && b.top < title.bottom + px && b.width() < device.displayWidth * 0.6 && b.height() > 120
        }.sortedBy { it.visibleBounds.left }
    }

    private fun waitFor(sel: BySelector, ms: Long): UiObject2? {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            device.findObject(sel)?.let { return it }
            Thread.sleep(150)
        }
        return null
    }

    /** A slow drag from [fromY] to [toY], held still at the end so the list doesn't fling on. */
    private fun slowDrag(fromY: Int, toY: Int, steps: Int = 0) {
        val x = device.displayWidth / 2
        val n = if (steps > 0) steps else (kotlin.math.abs(fromY - toY) / 7).coerceIn(30, 220)
        device.swipe(arrayOf(android.graphics.Point(x, fromY), android.graphics.Point(x, toY), android.graphics.Point(x, toY)), n)
    }

    /** Scrolls (slowly, or [fast] for out-of-shot moves) until [sel] is in the middle of the screen; it, or null. */
    private suspend fun slowScrollToCentre(sel: BySelector, fast: Boolean = false): UiObject2? {
        val h = device.displayHeight
        repeat(12) {
            val o = device.findObject(sel)
            if (o == null) {
                // Held at the end (no fling), so nothing on the way is skipped over between looks.
                if (fast) device.swipe(arrayOf(android.graphics.Point(device.displayWidth / 2, h * 4 / 5), android.graphics.Point(device.displayWidth / 2, h / 4), android.graphics.Point(device.displayWidth / 2, h / 4)), 25)
                else slowDrag(h * 4 / 5, h / 5)
                delay(if (fast) 500 else 300)
                return@repeat
            }
            val delta = o.visibleBounds.centerY() - h / 2
            // A grid only partly on screen: its visible part's middle isn't its middle; go on until it's whole.
            if (kotlin.math.abs(delta) < 40 && o.visibleBounds.top > 0 && o.visibleBounds.bottom < h) return o
            val move = delta.coerceIn(-(h * 3 / 5), h * 3 / 5)
            val start = if (move > 0) h * 4 / 5 else h / 5
            if (fast) device.swipe(arrayOf(android.graphics.Point(device.displayWidth / 2, start), android.graphics.Point(device.displayWidth / 2, start - move), android.graphics.Point(device.displayWidth / 2, start - move)), 20)
            else slowDrag(start, start - move)
            delay(if (fast) 500 else 400)
        }
        return device.findObject(sel)
    }

    private fun still(name: String) {
        Thread.sleep(300)
        shell("screencap -p /sdcard/prism-film/$name.png")
        log("still $name")
    }

    /** Nothing playing and nothing queued: no mini player over the page. */
    private fun noSong() { instrumentation.runOnMainSync { c.player.stop(); c.player.player?.clearMediaItems() } }

    /** Text as shown, in any case (an eyebrow is drawn in capitals). */
    private fun txt(s: String): BySelector = By.text(java.util.regex.Pattern.compile("(?i)" + java.util.regex.Pattern.quote(s)))

    private fun log(s: String) { android.util.Log.i("PrismFilm", s) }

    private fun shell(cmd: String): String =
        instrumentation.uiAutomation.executeShellCommand(cmd).use { java.io.FileInputStream(it.fileDescriptor).readBytes().toString(Charsets.UTF_8) }

    /** Every window of this process (the activity's, a dialog's, a sheet's): their status bars hidden. */
    private fun hideStatusBars() {
        val roots = runCatching {
            val wmg = Class.forName("android.view.WindowManagerGlobal")
            val inst = wmg.getMethod("getInstance").invoke(null)
            val f = wmg.getDeclaredField("mViews").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST") (f.get(inst) as List<View>).toList()
        }.getOrDefault(emptyList())
        for (v in roots) {
            val insets = v.rootWindowInsets ?: continue
            if (!insets.isVisible(WindowInsets.Type.statusBars()) && !insets.isVisible(WindowInsets.Type.navigationBars())) continue
            val ctl = v.windowInsetsController ?: continue
            ctl.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            // The navigation bar too: in gesture navigation that takes the pill away.
            ctl.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        }
    }

    /** screenrecord of [seconds] to /sdcard/prism-film/<name>.mp4, from now; [mark] logs where things happen in it. */
    private inner class Recording(val name: String, seconds: Int) {
        private val started = SystemClock.elapsedRealtime()
        private val pfd = instrumentation.uiAutomation.executeShellCommand(
            "screenrecord --bit-rate 20000000 --time-limit $seconds /sdcard/prism-film/$name.mp4",
        )
        private val done = Thread { runCatching { java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() } }; runCatching { pfd.close() } }.apply { start() }

        init { log("recording $name for $seconds s") }

        fun mark(label: String) = log("mark $name $label %.2f".format(java.util.Locale.US, (SystemClock.elapsedRealtime() - started) / 1000.0))

        fun finish() {
            done.join(200_000)
            log("recorded $name")
        }
    }

    companion object {
        val CANVAS_SONGS = listOf(
            "Houdini Dua Lipa", "As It Was Harry Styles", "Flowers Miley Cyrus", "Blinding Lights The Weeknd",
            "Espresso Sabrina Carpenter", "Anti-Hero Taylor Swift", "Levitating Dua Lipa", "Good Luck, Babe! Chappell Roan",
            "Birds of a Feather Billie Eilish", "Die With A Smile Lady Gaga Bruno Mars",
        )

        /** The year's songs, the favourites first. */
        val SEED_SONGS = listOf(
            "Espresso Sabrina Carpenter", "Birds of a Feather Billie Eilish", "Good Luck, Babe! Chappell Roan", "Houdini Dua Lipa",
            "Die With A Smile Lady Gaga Bruno Mars", "APT. ROSE Bruno Mars", "Please Please Please Sabrina Carpenter", "Not Like Us Kendrick Lamar",
            "As It Was Harry Styles", "Pink Pony Club Chappell Roan", "Blinding Lights The Weeknd", "Anti-Hero Taylor Swift",
            "Levitating Dua Lipa", "Flowers Miley Cyrus", "Too Sweet Hozier", "Taste Sabrina Carpenter",
            "Beautiful Things Benson Boone", "Lunch Billie Eilish", "vampire Olivia Rodrigo", "Cruel Summer Taylor Swift",
            "luther Kendrick Lamar SZA", "Kill Bill SZA", "HOT TO GO! Chappell Roan", "Training Season Dua Lipa",
            "Save Your Tears The Weeknd", "greedy Tate McRae", "Wildflower Billie Eilish", "Fortnight Taylor Swift",
            "Watermelon Sugar Harry Styles", "Dance The Night Dua Lipa", "good 4 u Olivia Rodrigo", "Snooze SZA",
            "Starboy The Weeknd", "360 Charli xcx", "Paint The Town Red Doja Cat", "Viva La Vida Coldplay",
            "Apple Charli xcx", "drivers license Olivia Rodrigo", "Yellow Coldplay", "Juno Sabrina Carpenter",
        )

        val SEED_ARTISTS = listOf(
            "Sabrina Carpenter", "Billie Eilish", "Chappell Roan", "Dua Lipa", "Taylor Swift", "The Weeknd", "Harry Styles",
            "Olivia Rodrigo", "Kendrick Lamar", "SZA", "Coldplay", "Charli xcx", "Hozier", "Miley Cyrus", "Tate McRae",
        )
    }
}
