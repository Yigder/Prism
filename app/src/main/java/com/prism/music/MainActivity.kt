package com.prism.music

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.prism.music.data.model.Song
import com.prism.music.data.model.hiRes
import com.prism.music.download.SmartDownloadWorker
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Navigator
import com.prism.music.ui.Routes
import com.prism.music.ui.player.MiniPlayer
import com.prism.music.ui.player.NowPlayingScreen
import com.prism.music.ui.screens.ArtistScreen
import com.prism.music.ui.screens.BrowseScreen
import com.prism.music.ui.screens.CatalogueScreen
import com.prism.music.ui.screens.CollectionScreen
import com.prism.music.ui.screens.CollectionType
import com.prism.music.ui.screens.EqualizerScreen
import com.prism.music.ui.screens.HomeScreen
import com.prism.music.ui.screens.LibraryScreen
import com.prism.music.ui.screens.LoginScreen
import com.prism.music.ui.screens.ReplayScreen
import com.prism.music.ui.screens.SearchScreen
import com.prism.music.ui.screens.SettingsScreen
import com.prism.music.ui.screens.WelcomeScreen
import com.prism.music.ui.theme.GlassSurface
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalHazeState
import com.prism.music.ui.theme.PrismTheme
import dev.chrisbanes.haze.hazeSource
import com.prism.music.ui.theme.BackdropScreen
import com.prism.music.ui.theme.ScreenBackdrop
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private val deepLink = MutableStateFlow<Uri?>(null)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val c = container
        c.player.connect()
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        SmartDownloadWorker.schedule(this, c.settings.current.smartDownloads)
        // Tidies the smart-download queue (it only adds more if it's been a while).
        if (c.settings.current.smartDownloads) SmartDownloadWorker.runNow(this, force = false)
        if (c.settings.current.isLoggedIn) c.library.syncInBackground()
        deepLink.value = intent?.data
        setContent {
            CompositionLocalProvider(LocalContainer provides c) {
                PrismRoot(deepLink)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        deepLink.value = intent.data
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Home", Icons.Rounded.Home),
    Tab(Routes.SEARCH, "Search", Icons.Rounded.Search),
    Tab(Routes.LIBRARY, "Library", Icons.AutoMirrored.Rounded.LibraryBooks),
    Tab(Routes.REPLAY, "Replay", Icons.Rounded.AutoAwesome),
)

@Composable
private fun rememberArtworkColor(song: Song?): Color? {
    val context = LocalContext.current
    var color by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(song?.thumbnail) {
        val url = song?.thumbnail ?: return@LaunchedEffect
        color = withContext(Dispatchers.IO) {
            runCatching {
                val req = ImageRequest.Builder(context).data(hiRes(url, 160)).allowHardware(false).build()
                val bmp = (context.imageLoader.execute(req) as? SuccessResult)?.image?.toBitmap() ?: return@runCatching null
                val p = Palette.from(bmp).generate()
                (p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.dominantSwatch ?: p.mutedSwatch)?.rgb?.let { Color(it) }
            }.getOrNull()
        } ?: color
    }
    return color
}

@Composable
private fun PrismRoot(deepLink: MutableStateFlow<Uri?>) {
    val c = LocalContainer.current
    val settings by c.settings.flow.collectAsState()
    val song by c.player.currentSong.collectAsState()
    val artColor = rememberArtworkColor(song)
    val context = LocalContext.current

    PrismTheme(settings, artColor) {
        val navController = rememberNavController()
        var playerOpen by rememberSaveable { mutableStateOf(false) }
        val navigator = remember(navController) { Navigator(navController) { playerOpen = true } }
        val haze = rememberHazeState()
        val backStack by navController.currentBackStackEntryAsState()
        val route = backStack?.destination?.route
        val showChrome = route != Routes.LOGIN && route != Routes.WELCOME
        val bottomPadding = if (song != null) 156.dp else 92.dp
        // Decided once: the welcome screen is a real destination, so signing in
        // from it navigates inside a graph that already exists.
        val start = remember { if (!settings.onboarded && !settings.isLoggedIn) Routes.WELCOME else Routes.HOME }
        val goHome: () -> Unit = {
            navController.navigate(Routes.HOME) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }

        // The tab bar shrinks while you scroll down a page and grows back as you scroll up.
        var barCompact by remember { mutableStateOf(false) }
        val barScroll = remember {
            object : NestedScrollConnection {
                var acc = 0f
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    val dy = available.y
                    if (dy == 0f || abs(available.x) > abs(dy)) return Offset.Zero
                    acc = if ((acc < 0) == (dy < 0)) acc + dy else dy
                    if (acc < -28f) barCompact = true else if (acc > 28f) barCompact = false
                    return Offset.Zero
                }
            }
        }
        LaunchedEffect(route) { barCompact = false }
        val barT by animateFloatAsState(if (barCompact) 1f else 0f, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow), label = "bar")

        LaunchedEffect(Unit) {
            c.player.errors.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
        }
        LaunchedEffect(Unit) {
            deepLink.collect { uri -> if (uri != null) { handleDeepLink(uri, navigator, c); deepLink.value = null } }
        }

        CompositionLocalProvider(LocalNavigator provides navigator, LocalHazeState provides haze) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                Box(Modifier.fillMaxSize().nestedScroll(barScroll).hazeSource(haze)) {
                    NavHost(navController, startDestination = start) {
                        composable(Routes.WELCOME) {
                            WelcomeScreen(
                                onSignIn = { navController.navigate(Routes.LOGIN) },
                                onSkip = { c.settings.setOnboarded(true); goHome() },
                            )
                        }
                        composable(Routes.HOME) { ScreenBackdrop(BackdropScreen.HOME) { HomeScreen(bottomPadding) } }
                        composable(Routes.SEARCH) { ScreenBackdrop(BackdropScreen.SEARCH) { SearchScreen(bottomPadding) } }
                        composable(Routes.LIBRARY) { ScreenBackdrop(BackdropScreen.LIBRARY) { LibraryScreen(bottomPadding) } }
                        composable(Routes.REPLAY) { ScreenBackdrop(BackdropScreen.REPLAY) { ReplayScreen(bottomPadding) } }
                        composable(Routes.SETTINGS) { ScreenBackdrop(BackdropScreen.SETTINGS) { SettingsScreen(bottomPadding) } }
                        composable(Routes.BACKGROUNDS) { ScreenBackdrop(BackdropScreen.SETTINGS) { com.prism.music.ui.screens.BackgroundsScreen(bottomPadding) } }
                        composable("settings/{section}", listOf(navArgument("section") { type = NavType.StringType })) {
                            ScreenBackdrop(BackdropScreen.SETTINGS) { SettingsScreen(bottomPadding, com.prism.music.ui.screens.SettingsSection.of(it.arguments?.getString("section"))) }
                        }
                        composable(Routes.EQ) { EqualizerScreen(bottomPadding) }
                        composable(Routes.CATALOGUE) { CatalogueScreen(bottomPadding) }
                        composable(Routes.MOODS) { com.prism.music.ui.screens.MoodsScreen(bottomPadding) }
                        composable(Routes.DOWNLOADS) { com.prism.music.ui.screens.DownloadsScreen(bottomPadding) }
                        composable(Routes.LOGIN) {
                            LoginScreen(
                                onDone = goHome,
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable("album/{id}", listOf(navArgument("id") { type = NavType.StringType })) {
                            CollectionScreen(CollectionType.ALBUM, it.arguments?.getString("id")!!, null, bottomPadding)
                        }
                        composable("playlist/{id}", listOf(navArgument("id") { type = NavType.StringType })) {
                            CollectionScreen(CollectionType.PLAYLIST, it.arguments?.getString("id")!!, null, bottomPadding)
                        }
                        composable("liked?genre={genre}", listOf(navArgument("genre") { type = NavType.StringType; nullable = true; defaultValue = null })) {
                            CollectionScreen(CollectionType.LIKED, "liked", it.arguments?.getString("genre"), bottomPadding)
                        }
                        composable("artist/{id}", listOf(navArgument("id") { type = NavType.StringType })) {
                            ArtistScreen(it.arguments?.getString("id")!!, bottomPadding)
                        }
                        composable(
                            "browse/{id}?params={params}&title={title}",
                            listOf(
                                navArgument("id") { type = NavType.StringType },
                                navArgument("params") { type = NavType.StringType; defaultValue = "" },
                                navArgument("title") { type = NavType.StringType; defaultValue = "" },
                            ),
                        ) {
                            BrowseScreen(
                                it.arguments?.getString("id")!!,
                                it.arguments?.getString("params")?.takeIf { p -> p.isNotBlank() },
                                it.arguments?.getString("title") ?: "",
                                bottomPadding,
                            )
                        }
                    }
                }

                // Floating chrome: mini player + glass tab bar
                if (showChrome) Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    AnimatedVisibility(song != null && !playerOpen, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                        MiniPlayer(Modifier.padding(bottom = 8.dp)) { playerOpen = true }
                    }
                    TabBar(route, barT) { r ->
                        if (r == Routes.HOME) {
                            // Home always means the Home root, from anywhere (Settings included).
                            if (!navController.popBackStack(Routes.HOME, inclusive = false)) goHome()
                        } else {
                            navController.navigate(r) {
                                popUpTo(Routes.HOME)
                                launchSingleTop = true
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    playerOpen && song != null,
                    enter = slideInVertically(tween(420)) { it } + fadeIn(tween(200)),
                    exit = slideOutVertically(tween(360)) { it } + fadeOut(tween(300)),
                ) {
                    NowPlayingScreen { playerOpen = false }
                }
            }
        }
    }
}

/** @param compact 0 = full bar with labels, 1 = slim icon-only bar (while scrolling down). */
@Composable
private fun TabBar(route: String?, compact: Float, onSelect: (String) -> Unit) {
    val height = lerp(68.dp, 50.dp, compact)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    GlassSurface(Modifier.fillMaxWidth(1f - 0.30f * compact).height(height), shape = RoundedCornerShape(height / 2)) {
        Row(Modifier.fillMaxSize().padding(lerp(6.dp, 4.dp, compact)), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEach { tab ->
                val selected = route == tab.route
                val bg by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent, label = "tab",
                )
                val fg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(28.dp))
                        .background(bg)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(tab.route) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    Icon(tab.icon, tab.label, tint = fg, modifier = Modifier.size(24.dp))
                    if (compact < 0.98f) Text(
                        tab.label, color = fg, style = MaterialTheme.typography.labelSmall, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier
                            .height(lerp(16.dp, 0.dp, compact))
                            .graphicsLayer { alpha = (1f - compact * 1.6f).coerceIn(0f, 1f) },
                    )
                }
            }
        }
    }
    }
}

/** Opens music.youtube.com links shared into the app. */
private fun handleDeepLink(uri: Uri, nav: Navigator, c: AppContainer) {
    val v = uri.getQueryParameter("v")
    val list = uri.getQueryParameter("list")
    val path = uri.pathSegments
    when {
        v != null -> {
            nav.openPlayer()
            launchSingle(c, v)
        }
        list != null -> nav.go(Routes.playlist(list))
        path.firstOrNull() == "browse" && path.size > 1 -> {
            val id = path[1]
            if (id.startsWith("MPRE")) nav.go(Routes.album(id)) else nav.go(Routes.browse(id, null, ""))
        }
        path.firstOrNull() == "channel" && path.size > 1 -> nav.go(Routes.artist(path[1]))
    }
}

private fun launchSingle(c: AppContainer, videoId: String) = c.scope.launch {
    val r = runCatching { c.ytm.next(videoId) }.getOrNull()
    val song = r?.songs?.firstOrNull { it.id == videoId } ?: Song(videoId, "Loading…")
    withContext(Dispatchers.Main) { c.player.playSingle(song) }
}
