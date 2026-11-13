package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.trp
import com.prism.music.data.db.mergeUploads
import com.prism.music.ui.theme.lit
import androidx.compose.foundation.border
import com.prism.music.ui.components.PrismButton
import com.prism.music.ui.components.PrismFilledTonalButton
import com.prism.music.ui.theme.PrismIcons
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.data.db.ArtistPlays
import com.prism.music.data.db.MonthTotal
import com.prism.music.data.db.SongPlays
import com.prism.music.data.model.AlbumRef
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.components.EmptyState
import com.prism.music.ui.components.LoadingState
import com.prism.music.ui.components.SectionHeader
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.foundation.layout.heightIn
import kotlinx.coroutines.launch
import java.util.Calendar
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.IconButton
import androidx.compose.runtime.MutableState
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import com.prism.music.data.ShownMilestone
import com.prism.music.data.db.DayTotal
import com.prism.music.data.db.HourTotal
import com.prism.music.data.imports.PlayStats
import com.prism.music.ui.components.shareLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A stretch of Replay: today, this week, this month, the last 30 days, a calendar year (its Rewind), everything, or
 * (1.7.8, #149) any month ([year] + [month], 0–11) or any days ([fromDay]..[toDay], days since 1970 on the phone's clock).
 * 1.8.4 (D58, D59): yesterday and last week too (last month is its month, [lastMonth]).
 */
internal data class Period(val kind: Kind, val year: Int = 0, val month: Int = 0, val fromDay: Long = 0, val toDay: Long = 0) {
    enum class Kind { TODAY, WEEK, MONTH, DAYS30, YEAR, ALL, MONTH_OF, DAYS, YESTERDAY, LAST_WEEK }

    val isYear: Boolean get() = kind == Kind.YEAR
    val label: String get() = when (kind) {
        Kind.TODAY -> tr(R.string.today)
        Kind.YESTERDAY -> tr(R.string.yesterday)
        Kind.WEEK -> tr(R.string.this_week)
        Kind.LAST_WEEK -> tr(R.string.last_week)
        Kind.MONTH -> tr(R.string.this_month)
        Kind.DAYS30 -> tr(R.string.last_30_days)
        Kind.YEAR -> "$year"
        Kind.ALL -> tr(R.string.all_time)
        Kind.MONTH_OF -> java.time.YearMonth.of(year, month + 1).let { ym ->
            ym.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()) + " ${ym.year}"
        }
        Kind.DAYS -> {
            val fmt = java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
            val a = java.time.LocalDate.ofEpochDay(fromDay)
            val b = java.time.LocalDate.ofEpochDay(toDay)
            if (a == b) a.format(fmt) else "${a.format(fmt)} – ${b.format(fmt)}"
        }
    }
    /** For saving the pick ("y2025", "MONTH", "m2026-9", "d20380-20390"…). */
    val key: String get() = when (kind) {
        Kind.YEAR -> "y$year"
        Kind.MONTH_OF -> "m$year-$month"
        Kind.DAYS -> "d$fromDay-$toDay"
        else -> kind.name
    }

    companion object {
        val TODAY = Period(Kind.TODAY)
        val YESTERDAY = Period(Kind.YESTERDAY)
        val WEEK = Period(Kind.WEEK)
        val LAST_WEEK = Period(Kind.LAST_WEEK)
        val MONTH = Period(Kind.MONTH)
        val DAYS30 = Period(Kind.DAYS30)
        val ALL = Period(Kind.ALL)
        fun year(y: Int) = Period(Kind.YEAR, y)
        fun monthOf(y: Int, m: Int) = Period(Kind.MONTH_OF, y, m)
        /** The month before [today]'s (December of the year before, in January). */
        fun lastMonth(today: java.time.LocalDate = java.time.LocalDate.now()) = java.time.YearMonth.from(today).minusMonths(1).let { monthOf(it.year, it.monthValue - 1) }
        fun days(from: Long, to: Long) = Period(Kind.DAYS, fromDay = minOf(from, to), toDay = maxOf(from, to))
        fun thisYear() = year(Calendar.getInstance().get(Calendar.YEAR))
        fun of(key: String): Period {
            if (key.startsWith("y")) key.drop(1).toIntOrNull()?.let { return year(it) }
            if (key.startsWith("m")) key.drop(1).split("-").mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 }?.let { (y, m) -> return monthOf(y, m) }
            if (key.startsWith("d")) key.drop(1).split("-").mapNotNull { it.toLongOrNull() }.takeIf { it.size == 2 }?.let { (a, b) -> return days(a, b) }
            return Kind.entries.firstOrNull { it.name == key && it != Kind.YEAR && it != Kind.MONTH_OF && it != Kind.DAYS }?.let { Period(it) } ?: thisYear()
        }
    }
}

private data class ReplayData(
    val totalMs: Long,
    val plays: Int,
    val activeDays: Int,
    /** Every different song played in the period (the lists below are capped). */
    val songCount: Int,
    val songs: List<SongPlays>,
    val artists: List<ArtistPlays>,
    val months: List<MonthTotal>,
    val genres: List<Pair<String, Long>>,
    /** Up to 250 top songs: what Play and Shuffle play. Not shown. */
    val top250: List<SongPlays> = songs,
    /** #286/#287: the graph by hours, days or weeks for a shorter stretch (null: by month, [months]). */
    val timeline: ReplayTimeline? = null,
)

internal fun SongPlays.toSong() = Song(
    id = songId, title = title,
    artists = artistName.split(", ").mapIndexed { i, n -> ArtistRef(n, if (i == 0) artistId else null) },
    album = albumTitle?.let { AlbumRef(it) }, durationSec = durationSec, thumbnail = thumbnail,
)

/**
 * [p] from its first to its last millisecond, at [now] on the clock of [zone], the week starting on [firstDay] (the
 * phone's own unless a test says otherwise).
 */
internal fun range(
    p: Period,
    now: Long = System.currentTimeMillis(),
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    firstDay: java.time.DayOfWeek = java.time.temporal.WeekFields.of(java.util.Locale.getDefault()).firstDayOfWeek,
): Pair<Long, Long> {
    // Midnight of a day on the phone's clock (whatever daylight saving does).
    fun startOf(d: java.time.LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
    val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    // The week as the phone's region starts it (Monday or Sunday).
    val thisWeek = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(firstDay))
    return when (p.kind) {
        Period.Kind.TODAY -> startOf(today) to now
        // Midnight to midnight: 23 or 25 hours on the days the clocks change.
        Period.Kind.YESTERDAY -> startOf(today.minusDays(1)) to startOf(today) - 1
        Period.Kind.WEEK -> startOf(thisWeek) to now
        // The whole week before this one.
        Period.Kind.LAST_WEEK -> startOf(thisWeek.minusWeeks(1)) to startOf(thisWeek) - 1
        Period.Kind.MONTH -> startOf(today.withDayOfMonth(1)) to now
        Period.Kind.DAYS30 -> now - 30L * 86_400_000 to now
        // Midnight to midnight on the phone's clock (whatever daylight saving does), up to now for this year.
        Period.Kind.YEAR -> com.prism.music.data.RewindCalendar.bounds(p.year).let { (a, b) -> a to minOf(b, now) }
        Period.Kind.ALL -> 0L to now
        Period.Kind.MONTH_OF -> java.time.YearMonth.of(p.year, p.month + 1).let { ym -> startOf(ym.atDay(1)) to minOf(startOf(ym.plusMonths(1).atDay(1)) - 1, now) }
        Period.Kind.DAYS -> startOf(java.time.LocalDate.ofEpochDay(p.fromDay)) to minOf(startOf(java.time.LocalDate.ofEpochDay(p.toDay + 1)) - 1, now)
    }
}

/**
 * What a whole day, week or month already over is compared with: the day, the week or the month just before it (the one
 * that ends where [p] starts, at [from]), midnight to midnight on the clock of [zone]. Null for the other stretches (the
 * story works theirs out itself).
 */
internal fun rangeBefore(p: Period, from: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Pair<Long, Long>? {
    val first = java.time.Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
    val before = when (p.kind) {
        Period.Kind.YESTERDAY -> first.minusDays(1)
        Period.Kind.LAST_WEEK -> first.minusWeeks(1)
        Period.Kind.MONTH_OF -> first.minusMonths(1)
        else -> return null
    }
    return before.atStartOfDay(zone).toInstant().toEpochMilli() to from - 1
}

/** Replay's chips, in order (1.8.4): each stretch beside the one before it, then the [years], then everything. */
internal fun replayPresets(years: List<Int>, today: java.time.LocalDate = java.time.LocalDate.now()): List<Period> =
    listOf(Period.TODAY, Period.YESTERDAY, Period.WEEK, Period.LAST_WEEK, Period.MONTH, Period.lastMonth(today), Period.DAYS30) +
        years.map { Period.year(it) } + Period.ALL

/** A chip's words: the month before this one is "Last month" (its page still names the month). */
internal fun chipLabel(p: Period, lastMonth: Period): String = if (p == lastMonth) tr(R.string.last_month) else p.label

/** The "Other dates…" chip (it opens [ReplayDatesSheet]; never a period itself). */
private val PICK_DATES = Period(Period.Kind.DAYS, fromDay = -1, toDay = -1)

/** Replay for any month you listened in, or any days (#149): a list of the months, and a calendar for days. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ReplayDatesSheet(onPick: (Period) -> Unit, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val months by produceState(emptyList<Period>()) {
        value = runCatching { c.imports.replay().monthly(0, System.currentTimeMillis()) }.getOrDefault(emptyList())
            .mapNotNull { m -> m.month.split("-").mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 }?.let { (y, mo) -> Period.monthOf(y, mo - 1) } }
            .reversed()
    }
    var calendar by remember { mutableStateOf(false) }
    if (calendar) {
        val now = System.currentTimeMillis()
        val state = androidx.compose.material3.rememberDateRangePickerState(
            selectableDates = object : androidx.compose.material3.SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= now
            },
        )
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { calendar = false },
            confirmButton = {
                com.prism.music.ui.components.PrismTextButton(
                    onClick = {
                        // The picker's days are UTC midnights: their day numbers are the calendar days picked.
                        val a = state.selectedStartDateMillis ?: return@PrismTextButton
                        val b = state.selectedEndDateMillis ?: a
                        calendar = false
                        onPick(Period.days(a / 86_400_000, b / 86_400_000))
                    },
                    enabled = state.selectedStartDateMillis != null,
                ) { Text(tr(R.string.show)) }
            },
            dismissButton = { com.prism.music.ui.components.PrismTextButton(onClick = { calendar = false }) { Text(tr(R.string.cancel)) } },
        ) {
            androidx.compose.material3.DateRangePicker(state, Modifier.weight(1f), title = { Text(tr(R.string.your_replay_for), Modifier.padding(start = 24.dp, top = 16.dp)) })
        }
        return
    }
    com.prism.music.ui.components.PrismSheet(onDismiss) { close ->
        Column(Modifier.padding(bottom = 20.dp)) {
            Text(tr(R.string.other_dates), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            com.prism.music.ui.components.SheetAction(PrismIcons.Calendar, tr(R.string.pick_days_on_a_calendar)) { calendar = true }
            if (months.isNotEmpty()) Text(
                tr(R.string.a_month), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
            )
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(months.size) { i ->
                    val m = months[i]
                    Text(
                        m.label, style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable { close { onPick(m) } }.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

/** Replay / Rewind: a private, on-device recap of your listening. */
@Composable
fun ReplayScreen(bottomPadding: Dp) = ReplayContent(bottomPadding)

/**
 * The Replay screen, starting on the period [startPeriod] ([Period.key]; this year when null) and showing a story with
 * [showStory] (tests look at what opens without the story's own window).
 */
@Composable
internal fun ReplayContent(
    bottomPadding: Dp,
    startPeriod: String? = null,
    showStory: @Composable (ReplayStory, () -> Unit) -> Unit = { s, close -> ReplayStoryDialog(s, close) },
) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var periodKey by rememberSaveable { mutableStateOf(startPeriod ?: Period.thisYear().key) }
    val period = remember(periodKey) { Period.of(periodKey) }
    // Every year with listening (Rewind), newest first; this year is always there.
    val years by produceState(listOf(Period.thisYear().year)) {
        value = runCatching { c.rewind.years() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: value
    }
    // The imported histories Replay counts besides Prism's own plays (1.8.1): the page says so, and reads again on a change.
    val counted by c.imports.counted.collectAsState()
    val historyPaused = com.prism.music.data.HistoryPause.paused(LocalAppSettings.current.behavior)
    val data by produceState<ReplayData?>(null, period, counted) {
        value = null
        val (from, to) = range(period)
        val dao = c.imports.replay()
        // The top 250 are kept for Play / Shuffle; the screen itself shows the top 100 at most.
        val top = dao.topSongs(from, to, 300).mergeUploads().take(250)
        val songs = top.take(100)
        val genreMap = c.meta.cachedGenres(songs.map { it.toSong() })
        val genres = songs.filter { genreMap[it.songId] != null }.groupBy { genreMap.getValue(it.songId) }
            .mapValues { (_, v) -> v.sumOf { it.ms } }
            .toList().sortedByDescending { it.second }.take(6)
        value = ReplayData(
            totalMs = dao.totalMs(from, to), plays = dao.count(from, to), activeDays = dao.activeDays(from, to),
            songCount = dao.songCount(from, to), songs = songs, artists = dao.topArtists(from, to, 12), months = dao.monthly(from, to), genres = genres,
            top250 = top, timeline = runCatching { loadTimeline(dao, period, from, to) }.getOrNull(),
        )
        // Fill in genres for the top tracks in the background so the next visit is richer.
        songs.take(40).filter { it.songId !in genreMap }.forEach { runCatching { c.meta.genreFor(it.toSong()) } }
    }
    var story by remember { mutableStateOf<ReplayStory?>(null) }
    var storyLoading by remember { mutableStateOf(false) }
    val open: (Period) -> Unit = { p ->
        if (!storyLoading) scope.launch {
            storyLoading = true
            story = runCatching { loadReplayStory(c, p) }.onFailure { android.util.Log.w("PrismRewind", "story failed", it) }.getOrNull()
            if (story == null) Toast.makeText(context, if (p.isYear) tr(R.string.couldn_t_build_your_rewind) else tr(R.string.couldn_t_build_your_replay), Toast.LENGTH_SHORT).show()
            storyLoading = false
        }
    }
    val openStory: () -> Unit = { open(period) }
    // Home's Rewind card or the 1 December notification: straight to that year's story.
    val request by c.rewindRequest.collectAsState()
    LaunchedEffect(request) {
        com.prism.music.widget.ReplayWidgets.refresh(context) // Replay opened: the home screen's Replay widgets catch up.
        val y = request ?: return@LaunchedEffect
        c.rewindRequest.value = null
        periodKey = Period.year(y).key
        if (c.rewind.isOpen(y)) open(Period.year(y))
    }
    // 1.8.5: a month's recap (Home's card, prism://recap/<yyyy-MM>): that month picked, and its story, as its Watch button opens it.
    val recap by c.recapRequest.collectAsState()
    LaunchedEffect(recap) {
        val ym = recap ?: return@LaunchedEffect
        c.recapRequest.value = null
        val p = Period.monthOf(ym.year, ym.monthValue - 1)
        periodKey = p.key
        open(p)
    }
    story?.let { showStory(it) { story = null } }
    // The 1.8.5 sections' data, read once a period (and when the counted histories change), when the section first shows.
    val clockHeld = remember(period, counted) { mutableStateOf<ClockData?>(null) }
    val calendarHeld = remember(period, counted) { mutableStateOf<CalendarData?>(null) }
    val milestonesHeld = remember(counted) { mutableStateOf<List<ShownMilestone>?>(null) }
    val still = LocalAppSettings.current.let { it.performanceMode || it.reduceMotion }
    var pickingDates by remember { mutableStateOf(false) }
    if (pickingDates) ReplayDatesSheet(onPick = { periodKey = it.key; pickingDates = false }) { pickingDates = false }

    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    val scrolled by remember { androidx.compose.runtime.derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 24 } }
    val edge by androidx.compose.animation.core.animateFloatAsState(if (scrolled) 1f else 0f, tween(220), label = "edge")
    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = bottomPadding + 24.dp)) {
        item {
            com.prism.music.ui.components.ScreenHeader(
                tr(R.string.replay_2),
                subtitle = replayIncludes(counted) ?: tr(R.string.your_listening_recapped_on_device),
            )
        }
        // #296: while history is paused, Replay says so (and how to turn it back on).
        if (historyPaused) item { HistoryPausedNote() }
        item {
            // Any dates (#149): today, this week, any month, or the days you pick, beside the usual ones.
            // 1.8.4 (D58, D59): yesterday, last week and last month sit beside today, this week and this month.
            val lastMonth = Period.lastMonth()
            val presets = replayPresets(years)
            val chips = presets + (if (period !in presets) listOf(period) else emptyList()) + PICK_DATES
            com.prism.music.ui.components.ChipRow(
                chips, { it == period }, { if (it == PICK_DATES) tr(R.string.other_dates_2) else chipLabel(it, lastMonth) },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            ) { if (it == PICK_DATES) pickingDates = true else periodKey = it.key }
        }
        val d = data
        // Keyed, so the hero never takes over the spinner's slot (seen under Robolectric: the spinner stayed).
        if (d == null) { item(key = "loading") { LoadingState() }; return@LazyColumn }
        if (d.plays == 0) {
            item { EmptyState(PrismIcons.ReplayFilled, tr(R.string.nothing_to_replay_yet), tr(R.string.listen_for_a_while_songs_you)) }
            return@LazyColumn
        }
        item(key = "hero") { HeroCard(d, period, period.isYear && !c.rewind.isOpen(period.year), storyLoading, openStory) }
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val songs = remember(d) { d.top250.map { it.toSong() } }
                val replaySource = QueueSource(QueueKind.REPLAY, tr(R.string.replay_3, period.label))
                PrismButton(onClick = { c.player.playQueue(songs, 0, replaySource) }, Modifier.weight(1f).height(48.dp)) {
                    Icon(PrismIcons.Play, null); Spacer(Modifier.width(6.dp)); Text(tr(R.string.widget_play))
                }
                PrismFilledTonalButton(onClick = { c.player.playQueue(songs, 0, replaySource, shuffle = true) }, Modifier.weight(1f).height(48.dp)) {
                    Icon(PrismIcons.Shuffle, null); Spacer(Modifier.width(6.dp)); Text(tr(R.string.shuffle))
                }
                if (c.settings.current.isLoggedIn) androidx.compose.material3.FilledTonalIconButton(
                    onClick = {
                        scope.launch {
                            val name = if (period.isYear) tr(R.string.top_songs_of, period.year) else tr(R.string.replay_3, period.label)
                            val id = runCatching { c.ytm.createPlaylist(name, songs.take(100).map { it.id }, tr(R.string.made_with_prism_replay)) }.getOrNull()
                            Toast.makeText(context, if (id != null) tr(R.string.saved_to_youtube_music, name) else tr(R.string.couldn_t_create_playlist), Toast.LENGTH_SHORT).show()
                            if (id != null) {
                                c.library.created(id, name, minOf(songs.size, 100))
                                nav.go(Routes.playlist(id))
                            }
                        }
                    },
                    modifier = Modifier.size(48.dp),
                ) { Icon(PrismIcons.PlaylistAdd, tr(R.string.save_as_a_playlist)) }
            }
        }
        // A day by its hours, a week or a month by its days (#286, #287); longer stretches by month, as before.
        val timeline = d.timeline
        if (timeline != null) item {
            SectionHeader(timeline.heading())
            TimelineChart(timeline, Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(196.dp))
        } else if (d.months.size > 1) item {
            SectionHeader(if (LocalAppSettings.current.replayHours) tr(R.string.hours_by_month) else tr(R.string.minutes_by_month))
            // 176 dp since 1.8.6 (160 before): the 16 more are the numbers' line, the bars keep their height.
            MonthChart(d.months, Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(176.dp))
            // The busiest month, as the shorter graphs say theirs.
            d.months.maxByOrNull { it.ms }?.takeIf { it.ms > 0 }?.let { m ->
                val (y, mo) = m.month.split("-").mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 } ?: return@let
                val at = java.time.LocalDate.of(y, mo, 1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                Text(
                    tr(R.string.replay_busiest_month, formatAt(at, com.prism.music.localDates("MMMMy")), listenTime(m.ms)),
                    Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // 1.8.5: when in the week you listen, and each day of the period.
        if (ReplaySections.showsClock(period, d.plays)) item(key = "clock") { ClockSection(period, clockHeld, still) }
        if (ReplaySections.showsCalendar(period, d.activeDays)) item(key = "calendar") { CalendarSection(period, calendarHeld, still) }
        if (d.artists.isNotEmpty()) item {
            // Artist photos, not the cover of whichever of their songs came first.
            val thumbs by c.library.artistThumbs.collectAsState()
            LaunchedEffect(d.artists) { c.library.fetchArtistPhotos(d.artists.map { it.artistId to it.artistName }) }
            SectionHeader(tr(R.string.top_artists))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                itemsIndexed(d.artists) { i, a ->
                    Column(
                        Modifier.width(112.dp).clip(RoundedCornerShape(16.dp)).clickable(enabled = a.artistId != null) { a.artistId?.let { nav.go(Routes.artist(it)) } },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box {
                            Artwork(
                                c.library.artistPhoto(thumbs, a.artistId, a.artistName), Modifier.size(104.dp), CircleShape, size = 300,
                                placeholderIcon = PrismIcons.Person,
                            )
                            Text(
                                "${i + 1}", color = Color.White, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.align(Alignment.BottomStart).clip(CircleShape).background(com.prism.music.ui.theme.PrismTokens.spectrumRing()).padding(horizontal = 9.dp, vertical = 2.dp),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(a.artistName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listenTime(a.ms), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (d.genres.isNotEmpty()) item {
            SectionHeader(tr(R.string.top_genres))
            val max = d.genres.maxOf { it.second }.coerceAtLeast(1)
            // The bar runs primary → tertiary; with a pale accent (white) its labels go dark.
            val barInk = inkOn(MaterialTheme.colorScheme.primary)
            val endInk = inkOn(MaterialTheme.colorScheme.tertiary)
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                d.genres.forEachIndexed { i, (g, ms) ->
                    val anim = remember(g, period) { Animatable(0f) }
                    LaunchedEffect(g, period) { anim.animateTo(ms / max.toFloat(), tween(900, delayMillis = i * 90)) }
                    Box(Modifier.fillMaxWidth().height(40.dp).clip(com.prism.music.ui.theme.LocalUi.current.shape(12.dp)).background(com.prism.music.ui.components.quietFill())) {
                        Box(Modifier.fillMaxWidth(anim.value).height(40.dp).background(Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))))
                        // The labels twice: in the page's ink, then in the bar's ink clipped to the bar, so a name
                        // that runs past the end of a short bar changes color where the bar stops.
                        GenreLabels(com.prism.music.data.meta.Genres.shown(g), listenTime(ms), Color.Unspecified, Color.Unspecified, Modifier)
                        GenreLabels(
                            com.prism.music.data.meta.Genres.shown(g), listenTime(ms), barInk, endInk,
                            Modifier.drawWithContent { clipRect(right = size.width * anim.value) { this@drawWithContent.drawContent() } },
                        )
                    }
                }
            }
        }
        // 1.8.5: milestones (of all your listening, not the period's); nothing at all when there are none.
        item(key = "milestones") { MilestonesSection(milestonesHeld) }
        // "Finish the lyric" (1.8.5): draws nothing unless its setting is on and enough songs have saved lyrics.
        item(key = "finish_lyric") { FinishLyricCard() }
        item { SectionHeader(tr(R.string.top_songs_3)) }
        itemsIndexed(d.songs, key = { _, s -> s.songId }) { i, s ->
            val songs = remember(d) { d.songs.map { it.toSong() } }
            ReplaySongRow(i, s) { c.player.playQueue(songs, i, QueueSource(QueueKind.REPLAY, tr(R.string.replay_2))) }
        }
    }
    com.prism.music.ui.components.TopScrollEdge({ edge })
    }
}

@Composable
private fun HeroCard(d: ReplayData, period: Period, notYet: Boolean, loading: Boolean, onOpen: () -> Unit) {
    val total = listenTotal(d.totalMs)
    val anim = remember(period) { Animatable(0f) }
    LaunchedEffect(period, total.value) { anim.animateTo(total.value.toFloat(), tween(1400)) }
    val top = d.songs.firstOrNull()
    val themed = LocalAppSettings.current.replayThemeColors
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.padding(16.dp).fillMaxWidth().clip(com.prism.music.ui.theme.LocalUi.current.shape(30.dp))
            .background(
                Brush.linearGradient(
                    if (themed) listOf(scheme.primaryContainer.copy(alpha = 1f).compositeDark(), scheme.primary.compositeDark(0.55f), scheme.tertiary.compositeDark(0.55f))
                    else listOf(Color(0xFF1B0F3B).lit(), Color(0xFF7C5CFF).lit(), Color(0xFFFF3B5C).lit())
                )
            )
            .clickable(enabled = !notYet, onClick = onOpen)
            .padding(24.dp),
    ) {
        Column {
            Text(
                (if (period.isYear) tr(R.string.rewind_x, period.year) else tr(R.string.replay_x, period.label)).uppercase(),
                color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium,
                modifier = Modifier.testTag(ReplayTags.HERO_LABEL),
            )
            Spacer(Modifier.height(4.dp))
            Column(Modifier.toggleListenUnit()) {
                Text(total.text(anim.value.toLong()), color = Color.White, style = MaterialTheme.typography.displayLarge, maxLines = 1)
                Text(tr(R.string.listened_2, total.unit).trim(),color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                // In the listener's own number format ("1,358", "1.358"), as the rest of Prism writes counts.
                val n = java.text.NumberFormat.getIntegerInstance()
                Stat(n.format(d.plays), trp(R.plurals.plays_word, d.plays))
                Stat(n.format(d.songCount), trp(R.plurals.songs_word, d.songCount))
                Stat(n.format(d.activeDays), trp(R.plurals.days_word, d.activeDays))
            }
            if (top != null) {
                Spacer(Modifier.height(18.dp))
                Row(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.14f)).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(top.thumbnail, Modifier.size(52.dp), RoundedCornerShape(10.dp), size = 226)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(tr(R.string.your_1_song), color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelMedium)
                        Text(top.title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${minutes(top.ms)} · ${top.artistName}", color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            // The story: Prism's take on Wrapped / Replay. A year's is its Rewind, which opens on 1 December.
            if (notYet) Text(
                tr(R.string.your_rewind_opens_on_december_1, period.year),
                color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium,
            ) else Row(
                Modifier.clip(CircleShape).background(Color.White).padding(start = 14.dp, end = 18.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (loading) androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
                else Icon(PrismIcons.Play, null, Modifier.size(20.dp), tint = Color.Black)
                Spacer(Modifier.width(8.dp))
                Text(if (period.isYear) tr(R.string.watch_your_rewind) else tr(R.string.watch_your_replay), color = Color.Black, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * Listening time the way Replay is set to show it: "1,204 min", or "20h 4m" with hours on.
 * Anything under a minute shows "<1 min".
 */
@Composable
@ReadOnlyComposable
fun listenTime(ms: Long): String = listenTime(ms, LocalAppSettings.current.replayHours)

fun listenTime(ms: Long, hours: Boolean): String {
    val m = ms / 60_000
    return when {
        m == 0L && ms > 0 -> tr(R.string.t_1_min)
        !hours || m < 60 -> tr(R.string.d_min).format(m)
        else -> hoursAndMinutes(m)
    }
}

/** "8h 20m", or "8h" on the hour. */
fun hoursAndMinutes(totalMinutes: Long): String =
    if (totalMinutes % 60 == 0L) "%,dh".format(totalMinutes / 60) else tr(R.string.dh_dm).format(totalMinutes / 60, totalMinutes % 60)

@Composable
@ReadOnlyComposable
private fun minutes(ms: Long): String = listenTime(ms)

/** Tapping the headline listening time flips Replay between minutes and hours. */
@Composable
fun Modifier.toggleListenUnit(): Modifier {
    val c = LocalContainer.current
    val hours = LocalAppSettings.current.replayHours
    return clickable(interactionSource = null, indication = null, onClickLabel = if (hours) tr(R.string.show_minutes) else tr(R.string.show_hours)) {
        c.settings.setReplayHours(!hours)
    }
}

/**
 * The big headline time: [value] whole minutes, printed by [text] as "1,204" (with [unit] "minutes")
 * or, with hours on, as "20h 4m" (no unit). Animate [value] and print it with [text].
 */
class ListenTotal(val value: Long, val unit: String, val text: (Long) -> String) {
    /** The whole thing on one line: "1,204 minutes" / "20h 4m". */
    val full: String get() = (text(value) + " " + if (value == 1L && unit == "minutes") "minute" else unit).trim()
}

@Composable
@ReadOnlyComposable
fun listenTotal(ms: Long): ListenTotal =
    // Under an hour stays in minutes, so the hours view never shows "0h".
    if (LocalAppSettings.current.replayHours && ms >= 3_600_000) ListenTotal(ms / 60_000, "", ::hoursAndMinutes)
    else ListenTotal(ms / 60_000, "minutes") { "%,d".format(it) }

/** Replay's own colors, or the theme's when "Use theme colors" is on — always dark enough for white text. */
@Composable
fun replayGradient(): List<Color> {
    val scheme = MaterialTheme.colorScheme
    return if (LocalAppSettings.current.replayThemeColors) listOf(scheme.primary.compositeDark(0.6f), scheme.tertiary.compositeDark(0.5f), scheme.secondary.compositeDark(0.6f))
    else listOf(Color(0xFFFF3B5C).lit(), Color(0xFF7C5CFF).lit(), Color(0xFF00B4D8).lit())
}

/**
 * White text on [bg], or near-black when that reads better. 0.18 is where the two contrasts cross; the
 * old 0.5 cut put white on mid greys (the white accent's tertiary, g 0.70), which was hard to read.
 */
private fun inkOn(bg: Color): Color = if (bg.luminance() > 0.18f) Color(0xFF111111) else Color.White

/** Darkens a color towards black by [amount] (0 = as is), so white sits on it comfortably. */
private fun Color.compositeDark(amount: Float = 0.7f): Color = Color(red * (1 - amount), green * (1 - amount), blue * (1 - amount), 1f)

@Composable
private fun GenreLabels(name: String, time: String, nameInk: Color, timeInk: Color, modifier: Modifier) {
    Box(modifier.fillMaxSize()) {
        Text(name, style = MaterialTheme.typography.labelLarge, color = nameInk, modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp))
        Text(time, style = MaterialTheme.typography.labelMedium, color = timeInk, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp))
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(value, color = Color.White, style = MaterialTheme.typography.headlineSmall)
        Text(label, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelMedium)
    }
}

/** A song in one of Replay's lists: its place, cover, title, artist, time and plays. */
@Composable
private fun ReplaySongRow(i: Int, s: SongPlays, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val ui = com.prism.music.ui.theme.LocalUi.current
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = ui.gap(6.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${i + 1}", Modifier.width(36.dp), style = MaterialTheme.typography.titleLarge,
            color = if (i < 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium,
        )
        Artwork(s.thumbnail, Modifier.size(52.dp), ui.smallArt, size = 226)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(s.artistName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(minutes(s.ms), style = MaterialTheme.typography.labelMedium)
            Text(trp(R.plurals.n_plays, s.plays), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ── 1.8.5: the listening clock, the listening calendar and milestones ──

/** Test tags of Replay's 1.8.5 sections. */
internal object ReplayTags {
    const val HERO_LABEL = "replay.hero.label"
    const val CLOCK = "replay.clock"
    const val CLOCK_GRID = "replay.clock.grid"
    const val CLOCK_DAY = "replay.clock.day"
    const val CLOCK_BUSIEST = "replay.clock.busiest"
    const val CLOCK_BUSIEST_DAY = "replay.clock.busiestDay"
    const val CLOCK_SONG = "replay.clock.song"
    const val CLOCK_SHARE = "replay.clock.share"
    const val CALENDAR = "replay.calendar"
    const val CALENDAR_GRID = "replay.calendar.grid"
    const val CALENDAR_DAY = "replay.calendar.day"
    const val CALENDAR_SHARE = "replay.calendar.share"
    const val MILESTONES = "replay.milestones"
    const val MILESTONE = "replay.milestone"
    const val MILESTONES_MORE = "replay.milestones.more"
}

/** Which periods get the clock and the calendar, and how much listening they need first. */
internal object ReplaySections {
    /** Fewer plays than this make a clock of a few dots: it isn't shown. */
    const val CLOCK_MIN_PLAYS = 10
    /** A calendar needs at least this many days with music. */
    const val CALENDAR_MIN_DAYS = 2
    /** How many milestones show before "(+N more)". */
    const val MILESTONES_SHOWN = 5

    /** The clock: any stretch longer than a day (a day's hours are its timeline already), with [CLOCK_MIN_PLAYS]. */
    fun showsClock(p: Period, plays: Int): Boolean {
        if (plays < CLOCK_MIN_PLAYS) return false
        return when (p.kind) {
            Period.Kind.TODAY, Period.Kind.YESTERDAY -> false
            Period.Kind.DAYS -> p.toDay > p.fromDay
            else -> true
        }
    }

    /** The calendar: a month or longer (a week's days are its timeline), with [CALENDAR_MIN_DAYS]. */
    fun showsCalendar(p: Period, activeDays: Int): Boolean {
        if (activeDays < CALENDAR_MIN_DAYS) return false
        return when (p.kind) {
            Period.Kind.MONTH, Period.Kind.MONTH_OF, Period.Kind.DAYS30, Period.Kind.YEAR, Period.Kind.ALL -> true
            Period.Kind.DAYS -> p.toDay - p.fromDay >= 13
            else -> false
        }
    }
}

/** The listening clock's sums and words. Weekdays are 0 = Monday to 6 = Sunday, as [HourTotal] has them. */
internal object ClockGrid {
    /** Time in each cell, at `weekday * 24 + hour`. */
    fun cells(hours: List<HourTotal>): LongArray = LongArray(7 * 24).also { a ->
        hours.forEach { h -> if (h.weekday in 0..6 && h.hour in 0..23) a[h.weekday * 24 + h.hour] += h.ms }
    }

    /** Time in each hour of the day, all weekdays together. */
    fun byHour(hours: List<HourTotal>): LongArray = LongArray(24).also { a -> hours.forEach { h -> if (h.hour in 0..23) a[h.hour] += h.ms } }

    /** Time on each weekday. */
    fun byWeekday(hours: List<HourTotal>): LongArray = LongArray(7).also { a -> hours.forEach { h -> if (h.weekday in 0..6) a[h.weekday] += h.ms } }

    /** The index with the most time (the first of a tie), or null when all are empty. */
    fun busiest(a: LongArray): Int? = a.indices.maxByOrNull { a[it] }?.takeIf { a[it] > 0 }

    /** The weekdays top to bottom: the locale's first day of the week first. */
    fun rows(firstDay: DayOfWeek): IntArray = IntArray(7) { (firstDay.value - 1 + it) % 7 }

    /**
     * The hour under [x] on a grid [width] wide, or null off it. Right to left ([rtl]: Arabic, Hebrew…) midnight is at
     * the right, as the hour labels under the grid and the Rewind story's clock have it.
     */
    fun hourAt(x: Float, width: Float, rtl: Boolean = false): Int? =
        if (width <= 0f || x < 0f || x >= width) null else ((if (rtl) width - x else x) / width * 24).toInt().coerceIn(0, 23)

    /** The left edge of [hour]'s column on a grid [width] wide (mirrored when [rtl]). */
    fun hourLeft(hour: Int, width: Float, rtl: Boolean = false): Float {
        val w = width / 24f
        return if (rtl) width - (hour + 1) * w else hour * w
    }

    /** An hour as the phone tells time: "21:00", or "9 PM". */
    fun hourLabel(hour: Int, is24: Boolean, locale: Locale = Locale.getDefault()): String {
        val pattern = runCatching { android.text.format.DateFormat.getBestDateTimePattern(locale, if (is24) "mH" else "ha") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: if (is24) return "%02d:00".format(locale, hour) else "h a"
        return java.time.LocalTime.of(hour, 0).format(java.time.format.DateTimeFormatter.ofPattern(pattern, locale))
    }

    /** A weekday's name ("Wednesday", or "Wed" with [style] SHORT). */
    fun dayName(weekday: Int, locale: Locale = Locale.getDefault(), style: TextStyle = TextStyle.FULL): String =
        DayOfWeek.of(weekday + 1).getDisplayName(style, locale)
}

/** The listening calendar's grid: months side by side, each with its weeks in rows and weekdays in columns. */
internal object CalendarGrid {
    /** Blank cells before the 1st of [ym] in a week that starts on [firstDay]. */
    fun lead(ym: YearMonth, firstDay: DayOfWeek): Int = (ym.atDay(1).dayOfWeek.value - firstDay.value + 7) % 7

    /** How many week rows [ym] takes: 4 to 6. */
    fun weeks(ym: YearMonth, firstDay: DayOfWeek): Int = (lead(ym, firstDay) + ym.lengthOfMonth() + 6) / 7

    /** Every month from [from]'s to [to]'s, oldest first. */
    fun months(from: LocalDate, to: LocalDate): List<YearMonth> {
        val out = ArrayList<YearMonth>()
        var m = YearMonth.from(from)
        val last = YearMonth.from(to)
        while (m <= last) { out += m; m = m.plusMonths(1) }
        return out
    }

    /** Months an all-time calendar shows at most (the latest ones). */
    const val ALL_TIME_MONTHS = 12

    /** Where an all-time calendar starts: [first] (the first day with music), or eleven months before [last]'s month if that's later. */
    fun allTimeFrom(first: LocalDate, last: LocalDate): LocalDate =
        maxOf(first, YearMonth.from(last).minusMonths(ALL_TIME_MONTHS - 1L).atDay(1))

    /** The day of [ym] in column [col] (0..6) of week row [row], or null for a blank cell. */
    fun dayAt(ym: YearMonth, firstDay: DayOfWeek, col: Int, row: Int): LocalDate? {
        val n = row * 7 + col - lead(ym, firstDay) + 1
        return if (col in 0..6 && n in 1..ym.lengthOfMonth()) ym.atDay(n) else null
    }

    /** [d]'s column and week row in its month. */
    fun cellOf(d: LocalDate, firstDay: DayOfWeek): Pair<Int, Int> {
        val i = lead(YearMonth.from(d), firstDay) + d.dayOfMonth - 1
        return i % 7 to i / 7
    }

    /** How strongly a day is drawn: 0 for nothing, 1 to 4 by its time against the period's busiest day [max]. */
    fun level(ms: Long, max: Long): Int = if (ms <= 0 || max <= 0) 0 else kotlin.math.ceil(4.0 * ms / max).toInt().coerceIn(1, 4)

    /** Narrowest a month is drawn, and the largest a day's square gets (dp). */
    const val MONTH_MIN_DP = 100f
    const val CELL_MAX_DP = 28f
    const val GAP_DP = 2f
    const val MONTH_GAP_DP = 12f
    const val LABEL_DP = 20f
    /** Room under a month's name before its first week (dp). */
    const val LABEL_PAD_DP = 4f

    /**
     * The height of the months' names row: [LABEL_DP], or the tallest name ([textPx], measured) and a little room when
     * the text is larger (a big font size: at 2x the names ran into the first week).
     */
    fun labelPx(textPx: Float, dp: Float): Float = maxOf(LABEL_DP * dp, textPx + LABEL_PAD_DP * dp)

    /**
     * Where [count] months go across [width] px ([dp] px to a dp), with [labelPx] for the months' names. Every month has
     * room for six weeks, so rows line up.
     */
    class Layout(val width: Float, val count: Int, dp: Float, labelPx: Float = LABEL_DP * dp) {
        val perRow = (width / dp / MONTH_MIN_DP).toInt().coerceIn(1, 6).coerceAtMost(count.coerceAtLeast(1))
        val gap = GAP_DP * dp
        val monthGap = MONTH_GAP_DP * dp
        val label = labelPx
        val cell = minOf(((width - monthGap * (perRow - 1)) / perRow - gap * 6) / 7, CELL_MAX_DP * dp).coerceAtLeast(1f)
        val monthW = cell * 7 + gap * 6
        val monthH = label + cell * 6 + gap * 5
        val rows = (count + perRow - 1) / perRow
        val height = if (count <= 0) 0f else rows * monthH + (rows - 1) * monthGap

        fun monthOrigin(i: Int) = Offset((i % perRow) * (monthW + monthGap), (i / perRow) * (monthH + monthGap))
        fun cellOrigin(i: Int, col: Int, row: Int) = monthOrigin(i) + Offset(col * (cell + gap), label + row * (cell + gap))

        /** The month, column and row under (x, y), or null off the days. */
        fun hit(x: Float, y: Float): Triple<Int, Int, Int>? {
            if (x < 0f || y < 0f) return null
            val mc = (x / (monthW + monthGap)).toInt()
            val mr = (y / (monthH + monthGap)).toInt()
            if (mc >= perRow) return null
            val i = mr * perRow + mc
            if (i >= count) return null
            val o = monthOrigin(i)
            val lx = x - o.x
            val ly = y - o.y - label
            if (lx < 0f || lx > monthW || ly < 0f || ly > monthH - label) return null
            val col = (lx / (cell + gap)).toInt().coerceAtMost(6)
            val row = (ly / (cell + gap)).toInt().coerceAtMost(5)
            return Triple(i, col, row)
        }
    }
}

internal class ClockData(val cells: LongArray, val max: Long, val busiestHour: Int?, val hourMs: Long, val busiestDay: Int?, val dayMs: Long)

internal class CalendarData(val days: Map<LocalDate, DayTotal>, val months: List<YearMonth>, val max: Long, val busiest: LocalDate?)

/**
 * A section's data, or [empty] when reading it fails. A load that was cancelled (the section scrolled away while it
 * read) is not a failure: it is passed on and nothing is kept, so the section reads again when it's back. Before this
 * (found on the A16 in 1.8.5's check) a fling past the clock kept "nothing to show" for good.
 */
internal suspend fun <T> loadOrEmpty(empty: T, load: suspend () -> T): T = try {
    load()
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Exception) {
    android.util.Log.w("PrismReplay", "a Replay section couldn't be read", e)
    empty
}

private suspend fun loadClock(stats: PlayStats, from: Long, to: Long): ClockData {
    val hours = stats.byHour(from, to)
    return withContext(Dispatchers.Default) {
        val cells = ClockGrid.cells(hours)
        val byHour = ClockGrid.byHour(hours)
        val byDay = ClockGrid.byWeekday(hours)
        val h = ClockGrid.busiest(byHour)
        val d = ClockGrid.busiest(byDay)
        ClockData(cells, cells.maxOrNull() ?: 0L, h, h?.let { byHour[it] } ?: 0L, d, d?.let { byDay[it] } ?: 0L)
    }
}

private suspend fun loadCalendar(stats: PlayStats, period: Period, from: Long, to: Long): CalendarData {
    val list = stats.byDay(from, to)
    return withContext(Dispatchers.Default) {
        val zone = ZoneId.systemDefault()
        val days = list.mapNotNull { t -> runCatching { LocalDate.parse(t.day) }.getOrNull()?.let { it to t } }.toMap()
        if (days.isEmpty()) return@withContext CalendarData(emptyMap(), emptyList(), 0, null)
        val start = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
        // All time starts at the first month with music, not in 1970.
        val last = Instant.ofEpochMilli(to).atZone(zone).toLocalDate()
        // And shows its last twelve months: every month of a long history made a calendar many screens tall.
        val first = if (period.kind == Period.Kind.ALL) CalendarGrid.allTimeFrom(maxOf(start, days.keys.min()), last) else start
        // Only the days the grid shows: the minimum and TalkBack's "days with music" count those, not years before.
        val shown = days.filterKeys { it >= first && it <= last }
        val busiest = shown.maxByOrNull { it.value.ms }
        CalendarData(shown, CalendarGrid.months(first, maxOf(first, last)), busiest?.value?.ms ?: 0L, busiest?.key)
    }
}

/**
 * Draws the content as usual and records a copy [widthPx] wide on a [bg] background into [layer], for sharing a section
 * as a picture (the page's own background may be a moving backdrop or glass, which a picture can't carry).
 */
private fun Modifier.recordCard(layer: GraphicsLayer, bg: Color, widthPx: Int = 1080): Modifier = drawWithContent {
    val s = widthPx / size.width
    layer.record(size = IntSize(widthPx, (size.height * s).roundToInt())) {
        drawRect(bg)
        scale(s, s, pivot = Offset.Zero) { this@drawWithContent.drawContent() }
    }
    drawContent()
}

/** The share button of a section: the section's picture, with "My Prism Replay · [title] · [period]" beside it. */
@Composable
private fun SectionShare(layer: GraphicsLayer, title: String, period: Period, tag: String, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    IconButton(
        onClick = {
            scope.launch {
                val ok = shareLayer(context, layer, "prism-replay-section.png", tr(R.string.my_prism, tr(R.string.replay_2), "$title · ${period.label}"), tr(R.string.share_2, title))
                if (!ok) Toast.makeText(context, tr(R.string.couldn_t_make_the_picture), Toast.LENGTH_SHORT).show()
            }
        },
        modifier = modifier.testTag(tag),
    ) { Icon(PrismIcons.Share, tr(R.string.share_2, title)) }
}

private val CLOCK_ROW = 18.dp

/**
 * The listening clock: the period's weekdays by hours, darker where you listened more, with the busiest hour and day
 * in words. Tapping an hour lists its top 5 songs. Read once the section first shows ([held] keeps it for the period).
 */
@Composable
private fun ClockSection(period: Period, held: MutableState<ClockData?>, still: Boolean) {
    val c = LocalContainer.current
    val context = LocalContext.current
    LaunchedEffect(held) {
        if (held.value == null) {
            val (from, to) = range(period)
            held.value = loadOrEmpty(ClockData(LongArray(168), 0, null, 0, null, 0)) { loadClock(c.imports.replay(), from, to) }
        }
    }
    val d = held.value ?: return
    val busiestHour = d.busiestHour ?: return
    val locale = Locale.getDefault()
    val is24 = remember(context) { android.text.format.DateFormat.is24HourFormat(context) }
    val rows = remember(locale) { ClockGrid.rows(WeekFields.of(locale).firstDayOfWeek) }
    val title = tr(R.string.listening_clock)
    val hourLine = tr(R.string.replay_busiest_hour, ClockGrid.hourLabel(busiestHour, is24, locale), listenTime(d.hourMs))
    val dayLine = d.busiestDay?.let { tr(R.string.replay_busiest_day, ClockGrid.dayName(it, locale), listenTime(d.dayMs)) }
    var hour by remember(d) { mutableStateOf<Int?>(null) }
    val layer = rememberGraphicsLayer()
    val scheme = MaterialTheme.colorScheme
    val emptyCell = com.prism.music.ui.components.quietFill()
    val shown = remember(d) { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(shown) { if (!still) shown.animateTo(1f, tween(700)) }
    // Right to left the grid runs as its hour labels do (and as the Rewind story's clock): midnight at the right.
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // Rows as tall, and the names column as wide, as the weekday names are at the phone's font size (18 dp clipped them at 1.3x).
    val labelStyle = MaterialTheme.typography.labelSmall
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val dayNames = remember(rows, locale) { rows.map { ClockGrid.dayName(it, locale, TextStyle.SHORT) } }
    val (rowH, namesW) = remember(dayNames, labelStyle, density) {
        val sizes = dayNames.map { measurer.measure(it, labelStyle, maxLines = 1, softWrap = false).size }
        with(density) { maxOf(CLOCK_ROW, sizes.maxOf { it.height }.toDp() + 2.dp) to maxOf(40.dp, sizes.maxOf { it.width }.toDp() + 6.dp) }
    }
    Column(Modifier.fillMaxWidth().testTag(ReplayTags.CLOCK)) {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().recordCard(layer, scheme.surface).padding(bottom = 12.dp)) {
                SectionHeader(title)
                Row(Modifier.padding(horizontal = 20.dp)) {
                    Column(Modifier.width(namesW)) {
                        dayNames.forEach { name ->
                            Box(Modifier.height(rowH), contentAlignment = Alignment.CenterStart) {
                                Text(name, Modifier.testTag(ReplayTags.CLOCK_DAY), style = labelStyle, color = scheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        val a11y = listOfNotNull(title, hourLine, dayLine).joinToString(". ")
                        val action = tr(R.string.clock_top_at, ClockGrid.hourLabel(busiestHour, is24, locale))
                        Canvas(
                            Modifier.fillMaxWidth().height(rowH * 7).testTag(ReplayTags.CLOCK_GRID)
                                // One description for TalkBack, not 168 cells; its action lists the busiest hour's songs.
                                .semantics {
                                    contentDescription = a11y
                                    customActions = listOf(CustomAccessibilityAction(action) { hour = busiestHour; true })
                                }
                                .pointerInput(d, rtl) { detectTapGestures { o -> hour = ClockGrid.hourAt(o.x, size.width.toFloat(), rtl) } },
                        ) {
                            val w = size.width / 24f
                            val h = size.height / 7f
                            val g = 1.5.dp.toPx()
                            val r = CornerRadius(3.dp.toPx())
                            val a = shown.value
                            rows.forEachIndexed { ri, wd ->
                                for (hr in 0 until 24) {
                                    val ms = d.cells[wd * 24 + hr]
                                    val color = if (ms > 0 && d.max > 0) scheme.primary.copy(alpha = (0.2f + 0.8f * ms / d.max.toFloat()) * a) else emptyCell
                                    drawRoundRect(color, Offset(ClockGrid.hourLeft(hr, size.width, rtl) + g / 2, ri * h + g / 2), Size(w - g, h - g), r)
                                }
                            }
                            hour?.let { drawRoundRect(scheme.onSurface, Offset(ClockGrid.hourLeft(it, size.width, rtl), 0f), Size(w, size.height), r, style = Stroke(1.5.dp.toPx())) }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            listOf(0, 6, 12, 18).forEach {
                                Text(ClockGrid.hourLabel(it, is24, locale), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    }
                }
                Text(
                    hourLine, Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp).testTag(ReplayTags.CLOCK_BUSIEST),
                    style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                )
                if (dayLine != null) Text(
                    dayLine, Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp).testTag(ReplayTags.CLOCK_BUSIEST_DAY),
                    style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                )
            }
            SectionShare(layer, title, period, ReplayTags.CLOCK_SHARE, Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 8.dp))
        }
        hour?.let { h -> HourSongs(period, d, h, is24, locale) }
    }
}

/** The top 5 songs of [hour] in the period, playable like the top songs below. */
@Composable
private fun HourSongs(period: Period, d: ClockData, hour: Int, is24: Boolean, locale: Locale) {
    val c = LocalContainer.current
    val songs by produceState<List<SongPlays>?>(null, d, hour) {
        value = null
        val (from, to) = range(period)
        value = runCatching { c.imports.replay().topSongsInHour(from, to, hour, 5) }.getOrDefault(emptyList())
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text(
            tr(R.string.clock_top_at, ClockGrid.hourLabel(hour, is24, locale)), Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            style = MaterialTheme.typography.titleSmall,
        )
        val list = songs
        when {
            list == null -> Spacer(Modifier.height(64.dp))
            list.isEmpty() -> Text(
                tr(R.string.clock_no_plays), Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                val queue = remember(list) { list.map { it.toSong() } }
                val source = QueueSource(QueueKind.REPLAY, tr(R.string.replay_2))
                list.forEachIndexed { i, s -> ReplaySongRow(i, s, Modifier.testTag(ReplayTags.CLOCK_SONG)) { c.player.playQueue(queue, i, source) } }
            }
        }
    }
}

/**
 * The listening calendar: each month of the period as a block of days, darker where you listened more. Tapping a day
 * says its date, time and plays. Drawn on one canvas (a few years of days are a few thousand squares, not composables).
 */
@Composable
private fun CalendarSection(period: Period, held: MutableState<CalendarData?>, still: Boolean) {
    val c = LocalContainer.current
    LaunchedEffect(held) {
        if (held.value == null) {
            val (from, to) = range(period)
            held.value = loadOrEmpty(CalendarData(emptyMap(), emptyList(), 0, null)) { loadCalendar(c.imports.replay(), period, from, to) }
        }
    }
    val d = held.value ?: return
    if (d.days.size < ReplaySections.CALENDAR_MIN_DAYS || d.months.isEmpty()) return
    val locale = Locale.getDefault()
    val firstDay = remember(locale) { WeekFields.of(locale).firstDayOfWeek }
    val title = tr(R.string.listening_calendar)
    val dateFormat = remember(locale) { com.prism.music.localDates("yMMMEd") }
    fun dateText(day: LocalDate) = dateFormat.format(day)
    val busiestLine = d.busiest?.let { tr(R.string.replay_busiest_day, dateText(it), listenTime(d.days.getValue(it).ms)) }
    var picked by remember(d) { mutableStateOf<LocalDate?>(null) }
    val layer = rememberGraphicsLayer()
    val scheme = MaterialTheme.colorScheme
    val emptyCell = com.prism.music.ui.components.quietFill()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = scheme.onSurfaceVariant)
    val measurer = rememberTextMeasurer()
    val labels = remember(d, labelStyle, locale) {
        d.months.mapIndexed { i, ym ->
            val name = ym.month.getDisplayName(TextStyle.SHORT, locale)
            measurer.measure(if (i == 0 || ym.monthValue == 1) "$name ${ym.year}" else name, labelStyle)
        }
    }
    val shown = remember(d) { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(shown) { if (!still) shown.animateTo(1f, tween(700)) }
    val hours = LocalAppSettings.current.replayHours
    val line = picked?.let { day ->
        val t = d.days[day]
        if (t == null) "${dateText(day)} · ${tr(R.string.cal_no_music)}"
        else "${dateText(day)} · ${listenTime(t.ms, hours)} · ${trp(R.plurals.n_plays, t.plays)}"
    } ?: busiestLine
    Box(Modifier.fillMaxWidth().testTag(ReplayTags.CALENDAR)) {
        Column(Modifier.fillMaxWidth().recordCard(layer, scheme.surface).padding(bottom = 12.dp)) {
            SectionHeader(title)
            BoxWithConstraints(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
                val density = LocalDensity.current
                val widthPx = constraints.maxWidth.toFloat()
                // The names row as tall as the names are at the phone's font size.
                val labelPx = CalendarGrid.labelPx(labels.maxOfOrNull { it.size.height.toFloat() } ?: 0f, density.density)
                val layout = remember(widthPx, d.months.size, density.density, labelPx) { CalendarGrid.Layout(widthPx, d.months.size, density.density, labelPx) }
                val a11y = listOfNotNull(title, tr(R.string.cal_days_with_music, d.days.size), busiestLine).joinToString(". ")
                Canvas(
                    Modifier.fillMaxWidth().height(with(density) { layout.height.toDp() }).testTag(ReplayTags.CALENDAR_GRID)
                        .semantics { contentDescription = a11y }
                        .pointerInput(layout, d) {
                            detectTapGestures { o ->
                                layout.hit(o.x, o.y)?.let { (i, col, row) -> CalendarGrid.dayAt(d.months[i], firstDay, col, row)?.let { picked = it } }
                            }
                        },
                ) {
                    val r = CornerRadius(3.dp.toPx())
                    val a = shown.value
                    d.months.forEachIndexed { i, ym ->
                        drawText(labels[i], topLeft = layout.monthOrigin(i))
                        for (n in 1..ym.lengthOfMonth()) {
                            val day = ym.atDay(n)
                            val (col, row) = CalendarGrid.cellOf(day, firstDay)
                            val level = CalendarGrid.level(d.days[day]?.ms ?: 0, d.max)
                            val color = if (level == 0) emptyCell else scheme.primary.copy(alpha = (0.2f * level + 0.2f) * a)
                            val at = layout.cellOrigin(i, col, row)
                            drawRoundRect(color, at, Size(layout.cell, layout.cell), r)
                            if (day == picked) drawRoundRect(scheme.onSurface, at, Size(layout.cell, layout.cell), r, style = Stroke(1.5.dp.toPx()))
                        }
                    }
                }
            }
            if (line != null) Text(
                line, Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp).testTag(ReplayTags.CALENDAR_DAY),
                style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
            )
        }
        SectionShare(layer, title, period, ReplayTags.CALENDAR_SHARE, Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 8.dp))
    }
}

/**
 * Milestones: newest first, the first [ReplaySections.MILESTONES_SHOWN] here and the rest behind "(+N more)". New ones
 * carry a mark; what's been shown is marked seen. Nothing at all when there are none.
 */
@Composable
private fun MilestonesSection(held: MutableState<List<ShownMilestone>?>) {
    val c = LocalContainer.current
    LaunchedEffect(held) { if (held.value == null) held.value = loadOrEmpty(emptyList()) { c.milestones.all() } }
    val all = held.value ?: return
    if (all.isEmpty()) return
    val first = all.take(ReplaySections.MILESTONES_SHOWN)
    // Shown now: seen from the next look on (this one keeps its marks).
    LaunchedEffect(all) { c.milestones.markSeen(first) }
    var more by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag(ReplayTags.MILESTONES)) {
        SectionHeader(tr(R.string.milestones))
        first.forEach { MilestoneRow(it) }
        if (all.size > first.size) Text(
            tr(R.string.ms_and_more, all.size - first.size),
            Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(12.dp)).clickable { more = true }
                .padding(horizontal = 12.dp, vertical = 10.dp).testTag(ReplayTags.MILESTONES_MORE),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        )
    }
    if (more) {
        val rest = all.drop(first.size)
        LaunchedEffect(rest) { c.milestones.markSeen(rest) }
        com.prism.music.ui.components.PrismSheet({ more = false }) { _ ->
            Text(tr(R.string.milestones), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            LazyColumn(Modifier.heightIn(max = 520.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                items(rest, key = { it.milestone.key }) { MilestoneRow(it) }
            }
        }
    }
}

@Composable
private fun MilestoneRow(m: ShownMilestone) {
    val ui = com.prism.music.ui.theme.LocalUi.current
    val scheme = MaterialTheme.colorScheme
    val date = remember(m.milestone.at) { formatAt(m.milestone.at, com.prism.music.localDates("yMMMd")) }
    Row(
        // Read as one line by TalkBack.
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(horizontal = 16.dp, vertical = ui.gap(6.dp)).testTag(ReplayTags.MILESTONE),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(m.thumbnail, Modifier.size(48.dp), ui.smallArt, size = 226, placeholderIcon = PrismIcons.Star)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(m.text, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(date, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        if (m.fresh) Text(
            tr(R.string.ms_new), Modifier.padding(start = 8.dp).clip(CircleShape).background(scheme.primaryContainer).padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall, color = scheme.onPrimaryContainer, maxLines = 1,
        )
    }
}

/**
 * The number over a bar of "Hours by month" (1.8.6): the whole hours listened ("116h", "<1h" under one), or with Replay counting in
 * minutes the minutes alone ("940", "7.0k" from a thousand, "120k" from a hundred thousand). Nothing for an empty month.
 * Short on purpose: twelve bars leave each about as wide as four letters.
 */
internal fun monthBarLabel(ms: Long, hours: Boolean): String {
    if (ms <= 0) return ""
    val m = ms / 60_000
    // Whole hours, never rounded up: a bar must not say more than the "Busiest month" line under it (19h 36m is "19h").
    if (hours) return if (m < 60) "<1h" else "%,dh".format(m / 60)
    return when {
        m < 1 -> "<1"
        m < 1_000 -> m.toString()
        m < 100_000 -> "%.1fk".format(m / 1000.0)
        else -> "%,dk".format((m + 500) / 1000)
    }
}

@Composable
private fun MonthChart(months: List<MonthTotal>, modifier: Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val anim = remember(months) { Animatable(0f) }
    LaunchedEffect(months) { anim.animateTo(1f, tween(900)) }
    val names = listOf("J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D")
    // 1.8.6: each bar says its own total over it ("116h"), so the graph reads without guessing from the tallest one.
    val hours = LocalAppSettings.current.replayHours
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val valueStyle = MaterialTheme.typography.labelSmall.copy(color = label)
    val values = remember(months, hours, valueStyle) { months.map { measurer.measure(monthBarLabel(it.ms, hours), valueStyle, maxLines = 1, softWrap = false) } }
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val max = months.maxOf { it.ms }.coerceAtLeast(1)
            val gap = 8.dp.toPx()
            val w = (size.width - gap * (months.size - 1)) / months.size
            // The tallest bar stops short of the top: its number sits there.
            val room = (values.maxOfOrNull { it.size.height } ?: 0) + 4.dp.toPx()
            val full = (size.height - room).coerceAtLeast(0f)
            months.forEachIndexed { i, m ->
                val h = full * (m.ms / max.toFloat()) * anim.value
                drawRoundRect(
                    Brush.verticalGradient(listOf(tertiary, primary)),
                    topLeft = Offset(i * (w + gap), size.height - h),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(6.dp.toPx()),
                )
                val text = values[i]
                // Centred over its bar; a number wider than the bar runs over the gaps, kept inside the graph's edges.
                val x = (i * (w + gap) + (w - text.size.width) / 2).coerceIn(0f, (size.width - text.size.width).coerceAtLeast(0f))
                drawText(text, topLeft = Offset(x, size.height - h - text.size.height - 3.dp.toPx()), alpha = anim.value)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            months.forEach { m ->
                val idx = m.month.substringAfter("-").toIntOrNull()?.minus(1) ?: 0
                Text(names[idx.coerceIn(0, 11)], Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = label, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}
