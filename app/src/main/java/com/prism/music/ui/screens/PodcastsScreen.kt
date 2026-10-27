package com.prism.music.ui.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.border
import com.prism.music.ui.components.PrismTextButton
import com.prism.music.ui.theme.PrismIcons
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import com.prism.music.ui.components.shareTarget
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.data.model.Episode
import com.prism.music.data.model.PodcastItem
import com.prism.music.data.model.PodcastPage
import com.prism.music.data.model.Shelf
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.ui.Load
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.components.EmptyState
import com.prism.music.ui.components.ErrorState
import com.prism.music.ui.components.ItemCarousel
import com.prism.music.ui.components.LoadingState
import com.prism.music.ui.components.PlayingBars
import com.prism.music.ui.components.RoundAction
import com.prism.music.ui.components.SectionHeader
import com.prism.music.ui.components.SongActionsSheet
import androidx.compose.ui.text.withLink
import com.prism.music.ui.components.SubPage
import com.prism.music.ui.components.quietFill
import com.prism.music.ui.rememberLoad
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalUi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** A search on the Podcasts page: null lists while it's asking. */
private class PodcastSearch(val query: String, val shows: List<PodcastItem>?, val episodes: List<Song>?)

/** What the Podcasts page shows: the account's shows and saved episodes (signed in), then YouTube Music's Podcasts page. */
private class PodcastsHome(val yourShows: List<PodcastItem>, val saved: List<Song>, val shelves: List<Shelf>)

/**
 * Podcasts, opened from the round button at the top of Home (as on Spotify's home page): episodes to finish,
 * the shows saved to the account, its "Episodes for later", then popular shows and episodes by category.
 */
@Composable
fun PodcastsScreen(bottomPadding: Dp) {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val nav = LocalNavigator.current
    val signedIn = s.isLoggedIn
    val load = rememberLoad("podcasts:$signedIn") {
        coroutineScope {
            val shows = async { if (signedIn) runCatching { c.ytm.libraryPodcasts() }.getOrDefault(emptyList()) else emptyList() }
            val saved = async { if (signedIn) c.ytm.savedEpisodes() else emptyList() }
            // YouTube decides by the network's country whether there's a Podcasts page at all: where there isn't
            // (Egypt, #18) it answers "Requested entity was not found", signed in or not. The rest of the page still shows.
            val home = async { runCatching { c.ytm.podcastsHome() } }
            val h = home.await()
            val yours = shows.await()
            val later = saved.await()
            if (h.isFailure && yours.isEmpty() && later.isEmpty() && h.exceptionOrNull() !is com.prism.music.data.innertube.InnerTubeException) throw h.exceptionOrNull()!!
            PodcastsHome(yours, later, h.getOrDefault(emptyList()))
        }
    }
    val resume by c.episodes.inProgress.collectAsState()
    val onSong: (SongItem) -> Unit = { playEpisode(c, it.song, "Podcasts") }
    // Search right here: shows and episodes only, as you type (a moment after the last key).
    var query by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    val found by androidx.compose.runtime.produceState<PodcastSearch?>(null, query) {
        val q = query.trim()
        if (q.length < 2) { value = null; return@produceState }
        value = PodcastSearch(q, null, null)
        kotlinx.coroutines.delay(350)
        coroutineScope {
            val shows = async { runCatching { c.ytm.search(q, com.prism.music.data.innertube.SearchFilter.PODCASTS).items.filterIsInstance<PodcastItem>() }.getOrNull() }
            val eps = async { runCatching { c.ytm.search(q, com.prism.music.data.innertube.SearchFilter.EPISODES).items.filterIsInstance<SongItem>().map { it.song } }.getOrNull() }
            value = PodcastSearch(q, shows.await() ?: emptyList(), eps.await() ?: emptyList())
        }
    }

    SubPage("Podcasts", bottomPadding) {
        item(key = "search") {
            com.prism.music.ui.components.SearchField(query, "Search shows and episodes", { query = it }, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
        found?.let { f ->
            when {
                f.shows == null || f.episodes == null -> item(key = "searching") { LoadingState() }
                f.shows.isEmpty() && f.episodes.isEmpty() -> item(key = "none") {
                    EmptyState(PrismIcons.Podcasts, "Nothing found", "No shows or episodes for \"${f.query}\". Try the show's name, or a host.")
                }
                else -> {
                    if (f.shows.isNotEmpty()) item(key = "found-shows") {
                        SectionHeader("Shows")
                        ItemCarousel(f.shows, { nav.open(it, onSong) })
                    }
                    if (f.episodes.isNotEmpty()) {
                        item(key = "found-eps-h") { SectionHeader("Episodes") }
                        items(f.episodes.take(30), key = { "fe:" + it.id }) { song ->
                            EpisodeRow(Episode(song, date = null, description = null), showShow = true) { playEpisode(c, song, "Podcast search") }
                        }
                    }
                }
            }
            return@SubPage
        }
        if (resume.isNotEmpty()) {
            item(key = "resume-h") { SectionHeader("Continue listening") }
            items(resume.take(5), key = { "r:" + it.song.id }) { e ->
                EpisodeRow(
                    Episode(e.song, date = null, description = null, progress = e.fraction),
                    showShow = true,
                    leftMs = e.leftMs,
                ) { playEpisode(c, e.song, "Continue listening") }
            }
        }
        when (val st = load.state) {
            Load.Loading -> item { LoadingState() }
            is Load.Err -> item { ErrorState(st.message) { load.reload(false) } }
            is Load.Ok -> {
                val home = st.value
                if (home.yourShows.isNotEmpty()) item(key = "yours") {
                    SectionHeader("Your shows")
                    ItemCarousel(home.yourShows, { nav.open(it, onSong) })
                }
                if (home.saved.isNotEmpty()) {
                    item(key = "saved-h") { SectionHeader("Episodes for later", onMore = { nav.go(Routes.playlist("SE")) }) }
                    items(home.saved.take(5), key = { "sv:" + it.id }) { song ->
                        EpisodeRow(Episode(song, date = null, description = null), showShow = true) { playEpisode(c, song, "Episodes for later") }
                    }
                }
                if (!signedIn && resume.isEmpty()) item(key = "hint") {
                    Text(
                        "Sign in to see the shows you follow on YouTube Music. Shows you open here can be shared or played without an account.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                home.shelves.forEach { shelf -> shelfItems(shelf) { nav.open(it, onSong) } }
                if (home.shelves.isEmpty() && home.yourShows.isEmpty()) item {
                    EmptyState(PrismIcons.Podcasts, "No Podcasts page here", "Prism Music's Podcasts page isn't offered in your country. Search can still find shows and episodes: search, then pick Podcasts or Episodes.")
                }
            }
        }
    }
}

/** Plays one episode on its own (no music radio follows); it resumes where it was left. */
fun playEpisode(c: com.prism.music.AppContainer, song: Song, from: String) {
    c.player.playQueue(listOf(song), 0, QueueSource(QueueKind.OTHER, from))
}

/** A show's page: its cover, host and description, then its episodes, newest first, loading more as you go. */
@Composable
fun PodcastScreen(id: String, bottomPadding: Dp) {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val load = rememberLoad("podcast:$id") { c.ytm.podcast(id) }
    val current by c.player.currentSong.collectAsState()
    val played by c.episodes.played.collectAsState()
    val resume by c.episodes.inProgress.collectAsState()
    // Pages loaded past the first, and the token for the next.
    val more = remember(id) { mutableStateListOf<Episode>() }
    var next by remember(id) { mutableStateOf<String?>(null) }
    var nextReady by remember(id) { mutableStateOf(false) }
    var loadingMore by remember(id) { mutableStateOf(false) }
    var saved by remember(id) { mutableStateOf<Boolean?>(null) }
    var sharing by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    val page = (load.state as? Load.Ok<PodcastPage>)?.value
    if (page != null && !nextReady) { next = page.continuation; saved = page.savedToLibrary; nextReady = true }
    val show = page?.let { PodcastItem(it.id, it.title, it.author?.name ?: "", it.thumbnail) }

    SubPage(page?.title ?: "Podcast", bottomPadding, actions = {
        if (show != null) RoundAction(PrismIcons.Share, "Share") { sharing = true }
    }) {
        when (val st = load.state) {
            Load.Loading -> item { LoadingState() }
            is Load.Err -> item { ErrorState(st.message) { load.reload(false) } }
            is Load.Ok -> {
                val p = st.value
                val episodes = (p.episodes + more).distinctBy { it.song.id }
                item(key = "header") {
                    val ui = LocalUi.current
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Artwork(p.thumbnail, Modifier.size(132.dp), ui.art, size = 544, placeholderIcon = PrismIcons.Podcasts)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                p.author?.let { a ->
                                    Text(
                                        a.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 4.dp).then(if (a.id != null) Modifier.clickable { nav.go(Routes.artist(a.id)) } else Modifier),
                                    )
                                }
                                Text("Podcast", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            val latest = episodes.firstOrNull { it.song.id !in played } ?: episodes.firstOrNull()
                            Row(
                                Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.onSurface)
                                    .clickable(enabled = latest != null) { latest?.let { playEpisode(c, it.song, p.title) } }
                                    .padding(horizontal = 18.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(PrismIcons.Play, null, tint = MaterialTheme.colorScheme.surface)
                                Spacer(Modifier.width(6.dp))
                                Text("Latest episode", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
                            }
                            if (s.isLoggedIn && saved != null) {
                                val on = saved == true
                                RoundAction(if (on) PrismIcons.BookmarkFilled else PrismIcons.Bookmark, if (on) "Remove from library" else "Save to library") {
                                    saved = !on
                                    scope.launch {
                                        runCatching { c.ytm.setLibrarySaved(p.playlistId, !on) }.onFailure {
                                            saved = on
                                            c.player.reportError("Couldn't update your library. Check your connection.")
                                        }
                                    }
                                }
                            }
                        }
                        // Some shows have none on YouTube (JRE's page showed nothing at all): said plainly.
                        Text(
                            p.description ?: "YouTube has no description for this show.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 14.dp).then(if (p.description != null) Modifier.clickable { expanded = !expanded } else Modifier),
                        )
                    }
                }
                item(key = "eps-h") { SectionHeader("Episodes") }
                items(episodes, key = { "e:" + it.song.id }) { e ->
                    val local = resume.firstOrNull { it.song.id == e.song.id }
                    EpisodeRow(
                        e.copy(
                            progress = local?.fraction ?: e.progress,
                            played = e.played || e.song.id in played,
                        ),
                        playing = current?.id == e.song.id,
                        leftMs = local?.leftMs,
                        about = ShowAbout(p.title, p.description),
                    ) { playEpisode(c, e.song, p.title) }
                }
                if (next != null && show != null) item(key = "more") {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        if (loadingMore) LoadingState()
                        else PrismTextButton(onClick = {
                            val token = next ?: return@PrismTextButton
                            loadingMore = true
                            scope.launch {
                                runCatching { c.ytm.podcastEpisodes(token, show) }
                                    .onSuccess { (eps, nxt) -> more += eps; next = nxt?.takeIf { eps.isNotEmpty() } }
                                    .onFailure { c.player.reportError("Couldn't load more episodes") }
                                loadingMore = false
                            }
                        }) { Text("More episodes") }
                    }
                }
            }
        }
    }
    if (sharing && show != null) com.prism.music.ui.components.ShareSheet(show.shareTarget()) { sharing = false }
}

/**
 * One episode: its picture, title, the date and length (or what's left), a bar for how far in it is, and the start
 * of its description. A played one is dimmed with a tick.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EpisodeRow(
    e: Episode,
    showShow: Boolean = false,
    playing: Boolean = false,
    leftMs: Long? = null,
    /** The show it's from (its name and description), for the details sheet. */
    about: ShowAbout? = null,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val ui = LocalUi.current
    var menu by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    val song = e.song
    val dim = e.played && !playing
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = { menu = true })
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box {
            Artwork(song.thumbnail, Modifier.size(width = 104.dp, height = 58.dp), ui.shape(8.dp), size = 480, placeholderIcon = PrismIcons.Podcasts)
            if (playing) Box(Modifier.size(width = 104.dp, height = 58.dp).clip(ui.shape(8.dp)).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                PlayingBars(Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                color = when { playing -> scheme.primary; dim -> scheme.onSurfaceVariant; else -> scheme.onSurface },
            )
            val length = when {
                e.played -> "Played"
                leftMs != null && leftMs > 0 -> "${formatLength(leftMs / 1000)} left"
                song.durationSec > 0 -> formatLength(song.durationSec.toLong())
                else -> null
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                if (e.played) Icon(PrismIcons.CheckCircle, null, Modifier.size(14.dp).padding(end = 3.dp), tint = scheme.primary)
                Text(
                    listOfNotNull(if (showShow) song.artistText.takeIf { song.artists.isNotEmpty() } else null, e.date, length).joinToString(" • "),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                )
            }
            if (e.progress > 0.01f && !e.played) com.prism.music.ui.components.PrismProgressBar({ e.progress }, Modifier.padding(top = 6.dp).fillMaxWidth(0.6f))
            e.description?.let {
                // Tapping the start of the description opens all of it.
                Text(
                    it.replace(Regex("\\s+"), " "), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant.copy(alpha = 0.85f),
                    modifier = Modifier.padding(top = 4.dp).clickable(onClickLabel = "Episode details") { details = true },
                )
            }
        }
        IconButton(onClick = { menu = true }) { Icon(PrismIcons.More, "More", tint = scheme.onSurfaceVariant.copy(alpha = 0.8f)) }
    }
    if (menu) SongActionsSheet(song, extra = listOf(com.prism.music.ui.components.SheetItem(PrismIcons.Info, "Episode details") { details = true })) { menu = false }
    if (details) EpisodeDetailsSheet(e, about, leftMs) { details = false }
}

/** A show's name and description, as its page has them. */
data class ShowAbout(val title: String, val description: String?)

/**
 * Everything about an episode (H6): its whole description (links open in the browser), date and length, then the show
 * it's from. YouTube gives some shows no description at all; that's said rather than left blank.
 */
@Composable
private fun EpisodeDetailsSheet(e: Episode, about: ShowAbout?, leftMs: Long?, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val song = e.song
    com.prism.music.ui.components.ActionSheet(song.thumbnail, onDismiss) { _ ->
        com.prism.music.ui.components.ActionHeader(
            song.thumbnail, song.title,
            listOfNotNull(
                e.date,
                song.durationSec.takeIf { it > 0 }?.let { formatLength(it.toLong()) },
                leftMs?.takeIf { it > 0 && !e.played }?.let { "${formatLength(it / 1000)} left" },
                "Played".takeIf { e.played },
            ).joinToString(" • ").ifEmpty { null },
            shape = LocalUi.current.shape(8.dp), placeholderIcon = PrismIcons.Podcasts,
        )
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Text(
                e.description?.let { withLinks(it, scheme.primary) } ?: androidx.compose.ui.text.AnnotatedString("YouTube has no description for this episode."),
                style = MaterialTheme.typography.bodyMedium,
                color = if (e.description == null) scheme.onSurfaceVariant else scheme.onSurface,
            )
            val showName = about?.title ?: song.artistText.takeIf { song.artists.isNotEmpty() }
            if (showName != null) {
                Text(
                    "ABOUT THE SHOW", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 22.dp, bottom = 6.dp),
                )
                Text(showName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                // Only on a show's own page do we have its description; elsewhere the show's name is enough.
                if (about != null) Text(
                    about.description?.let { withLinks(it, scheme.primary) } ?: androidx.compose.ui.text.AnnotatedString("YouTube has no description for this show."),
                    style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

private val URL_IN_TEXT = Regex("""https?://[^\s<>"')\]]+""")

/** [text] with every web address in it tappable (opened in the browser). */
private fun withLinks(text: String, color: androidx.compose.ui.graphics.Color): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        var at = 0
        for (m in URL_IN_TEXT.findAll(text)) {
            append(text.substring(at, m.range.first))
            val url = m.value.trimEnd('.', ',', ';', ':', '!', '?')
            withLink(
                androidx.compose.ui.text.LinkAnnotation.Url(
                    url, androidx.compose.ui.text.TextLinkStyles(androidx.compose.ui.text.SpanStyle(color = color)),
                ),
            ) { append(url) }
            at = m.range.first + url.length
        }
        append(text.substring(at))
    }

/** "1 hr 12 min", "47 min", "40 sec". */
private fun formatLength(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return when {
        h > 0 && m > 0 -> "$h hr $m min"
        h > 0 -> "$h hr"
        m > 0 -> "$m min"
        else -> "$sec sec"
    }
}
