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
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
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
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.MiniPlayerStyle
import com.prism.music.data.prefs.NavBarStyle
import com.prism.music.data.prefs.NavLabels
import com.prism.music.data.prefs.NavTab
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
import com.prism.music.ui.theme.BackdropScreen
import com.prism.music.ui.theme.GlassSurface
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalHazeState
import com.prism.music.ui.theme.PrismTheme
import com.prism.music.ui.theme.ScreenBackdrop
import dev.chrisbanes.haze.hazeSource
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
        // Picks up lossless files added while Prism is closed.
        com.prism.music.download.LosslessSyncWorker.schedule(this)
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

private data class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private fun NavTab.tab(): Tab = when (this) {
    NavTab.HOME -> Tab(Routes.HOME, label, Icons.Outlined.Home, Icons.Rounded.Home)
    NavTab.SEARCH -> Tab(Routes.SEARCH, label, Icons.Rounded.Search, Icons.Rounded.Search)
    NavTab.LIBRARY -> Tab(Routes.LIBRARY, label, Icons.Outlined.LibraryMusic, Icons.Rounded.LibraryMusic)
    NavTab.REPLAY -> Tab(Routes.REPLAY, label, Icons.Outlined.AutoAwesome, Icons.Rounded.AutoAwesome)
    NavTab.SETTINGS -> Tab(Routes.SETTINGS, label, Icons.Outlined.Settings, Icons.Rounded.Settings)
}

/** Heights of the floating chrome, so pages can leave room for it. */
private fun navHeight(style: NavBarStyle): Dp = when (style) {
    NavBarStyle.FLOATING -> 64.dp
    NavBarStyle.DOCKED -> 62.dp
    NavBarStyle.MINIMAL -> 54.dp
}

private fun miniHeight(style: MiniPlayerStyle): Dp = when (style) {
    MiniPlayerStyle.CARD -> 64.dp
    MiniPlayerStyle.SLIM -> 54.dp
    MiniPlayerStyle.PILL -> 56.dp
}

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
        // Playback that should be watched (a music video picked from a list) brings the player up.
        LaunchedEffect(Unit) { c.player.showPlayer.collect { playerOpen = true } }
        val haze = rememberHazeState()
        val backStack by navController.currentBackStackEntryAsState()
        val route = backStack?.destination?.route
        val showChrome = route != Routes.LOGIN && route != Routes.WELCOME
        val tabs = settings.navTabs.map { it.tab() }

        // Room the pages leave at the bottom for the bar, the mini player and the system's own bar.
        val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val bottomPadding = navInset + navHeight(settings.navStyle) +
            (if (settings.navStyle == NavBarStyle.DOCKED) 0.dp else 16.dp) +
            (if (song != null) miniHeight(settings.miniPlayerStyle) + 8.dp else 0.dp)

        // Decided once: the tab Prism opens on is the root every other tab sits on, and the welcome
        // screen is a real destination, so signing in from it navigates inside a graph that already exists.
        val root = remember { (settings.startTab.takeIf { it in settings.navTabs } ?: settings.navTabs.first()).tab().route }
        val start = remember { if (!settings.onboarded && !settings.isLoggedIn) Routes.WELCOME else root }
        val goRoot: () -> Unit = {
            navController.navigate(root) {
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
        val barT by animateFloatAsState(
            if (barCompact && settings.navHideOnScroll) 1f else 0f,
            spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow), label = "bar",
        )

        LaunchedEffect(Unit) {
            c.player.errors.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
        }
        LaunchedEffect(Unit) {
            deepLink.collect { uri -> if (uri != null) { handleDeepLink(uri, navigator, c); deepLink.value = null } }
        }

        val selectTab: (String) -> Unit = { r ->
            if (r == root) {
                // The root tab always means its top, from anywhere (Settings included).
                if (!navController.popBackStack(root, inclusive = false)) goRoot()
            } else {
                navController.navigate(r) {
                    popUpTo(root)
                    launchSingleTop = true
                }
            }
        }

        CompositionLocalProvider(LocalNavigator provides navigator, LocalHazeState provides haze) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                Box(Modifier.fillMaxSize().nestedScroll(barScroll).hazeSource(haze)) {
                    NavHost(navController, startDestination = start) {
                        composable(Routes.WELCOME) {
                            WelcomeScreen(
                                onSignIn = { navController.navigate(Routes.LOGIN) },
                                onSkip = { c.settings.setOnboarded(true); goRoot() },
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
                        composable(Routes.EQ) { ScreenBackdrop(BackdropScreen.SETTINGS) { EqualizerScreen(bottomPadding) } }
                        composable(Routes.CATALOGUE) { ScreenBackdrop(BackdropScreen.HOME) { CatalogueScreen(bottomPadding) } }
                        composable(Routes.MOODS) { ScreenBackdrop(BackdropScreen.SEARCH) { com.prism.music.ui.screens.MoodsScreen(bottomPadding) } }
                        composable(Routes.IMPORT) { ScreenBackdrop(BackdropScreen.LIBRARY) { com.prism.music.ui.screens.ImportScreen(bottomPadding) } }
                        composable(Routes.DOWNLOADS) { CollectionScreen(CollectionType.DOWNLOADS, "downloads", null, bottomPadding) }
                        composable(Routes.LOGIN) {
                            LoginScreen(
                                onDone = goRoot,
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

                // Floating chrome: mini player + tab bar
                if (showChrome) {
                    val docked = settings.navStyle == NavBarStyle.DOCKED
                    Column(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .then(if (docked) Modifier else Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)),
                    ) {
                        AnimatedVisibility(song != null && !playerOpen, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                            MiniPlayer(
                                Modifier.padding(bottom = 8.dp).then(if (docked) Modifier.padding(horizontal = 10.dp) else Modifier),
                            ) { playerOpen = true }
                        }
                        TabBar(tabs, route, barT, settings, selectTab)
                    }
                }

                AnimatedVisibility(
                    playerOpen && song != null,
                    enter = slideInVertically(tween(if (settings.reduceMotion) 260 else 420)) { it } + fadeIn(tween(200)),
                    exit = slideOutVertically(tween(if (settings.reduceMotion) 240 else 360)) { it } + fadeOut(tween(300)),
                ) {
                    NowPlayingScreen { playerOpen = false }
                }
            }
        }
    }
}

private fun isSelected(tab: Tab, route: String?): Boolean =
    route == tab.route || (tab.route == Routes.SETTINGS && route?.startsWith("settings/") == true)

/** @param compact 0 = full bar, 1 = slim bar (while scrolling down). */
@Composable
private fun TabBar(tabs: List<Tab>, route: String?, compact: Float, settings: AppSettings, onSelect: (String) -> Unit) {
    when (settings.navStyle) {
        NavBarStyle.FLOATING -> {
            val height = lerp(64.dp, 50.dp, compact)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                GlassSurface(Modifier.fillMaxWidth(1f - 0.30f * compact).height(height), shape = RoundedCornerShape(height / 2)) {
                    Row(Modifier.fillMaxSize().padding(lerp(6.dp, 4.dp, compact)), verticalAlignment = Alignment.CenterVertically) {
                        tabs.forEach { tab -> TabItem(tab, isSelected(tab, route), settings.navLabels, compact, Modifier.weight(1f).fillMaxHeight()) { onSelect(tab.route) } }
                    }
                }
            }
        }
        NavBarStyle.DOCKED -> {
            GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp), elevation = 4.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().height(lerp(62.dp, 50.dp, compact)).padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tabs.forEach { tab -> TabItem(tab, isSelected(tab, route), settings.navLabels, compact, Modifier.weight(1f).fillMaxHeight()) { onSelect(tab.route) } }
                }
            }
        }
        NavBarStyle.MINIMAL -> {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                GlassSurface(Modifier.height(54.dp), shape = RoundedCornerShape(27.dp)) {
                    Row(Modifier.fillMaxHeight().padding(5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        tabs.forEach { tab -> MinimalTab(tab, isSelected(tab, route)) { onSelect(tab.route) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabItem(tab: Tab, selected: Boolean, labels: NavLabels, compact: Float, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val fg = if (selected) scheme.primary else scheme.onSurfaceVariant
    val indicator by animateColorAsState(if (selected) scheme.primary.copy(alpha = 0.16f) else Color.Transparent, label = "tab")
    val click = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = tab.label, onClick = onClick)
    val icon = if (selected) tab.selectedIcon else tab.icon
    when (labels) {
        NavLabels.ALWAYS -> Column(
            modifier.clip(RoundedCornerShape(28.dp)).background(indicator).then(click),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, tab.label, tint = fg, modifier = Modifier.size(24.dp))
            if (compact < 0.98f) Text(
                tab.label, color = fg, style = MaterialTheme.typography.labelSmall, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.height(lerp(16.dp, 0.dp, compact)).graphicsLayer { alpha = (1f - compact * 1.6f).coerceIn(0f, 1f) },
            )
        }
        // The picked tab grows into a capsule with its name beside the icon.
        NavLabels.SELECTED -> Box(modifier.then(click), contentAlignment = Alignment.Center) {
            Row(
                Modifier.height(40.dp).clip(CircleShape).background(indicator).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, tab.label, tint = fg, modifier = Modifier.size(22.dp))
                AnimatedVisibility(selected && compact < 0.5f, enter = expandHorizontally() + fadeIn(), exit = shrinkHorizontally() + fadeOut()) {
                    Row {
                        Spacer(Modifier.width(6.dp))
                        Text(tab.label, color = fg, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
        }
        NavLabels.NEVER -> Box(modifier.then(click), contentAlignment = Alignment.Center) {
            Box(Modifier.size(52.dp, 40.dp).clip(CircleShape).background(indicator), contentAlignment = Alignment.Center) {
                Icon(icon, tab.label, tint = fg, modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun MinimalTab(tab: Tab, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) scheme.onSurface else Color.Transparent, label = "minTab")
    Box(
        Modifier.size(54.dp, 44.dp).clip(CircleShape).background(bg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = tab.label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (selected) tab.selectedIcon else tab.icon, tab.label, tint = if (selected) scheme.surface else scheme.onSurfaceVariant, modifier = Modifier.size(23.dp))
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
