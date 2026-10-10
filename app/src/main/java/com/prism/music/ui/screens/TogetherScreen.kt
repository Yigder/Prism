package com.prism.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.prism.music.data.model.Song
import com.prism.music.playback.together.Msg
import com.prism.music.playback.together.NearbySession
import com.prism.music.playback.together.Together
import com.prism.music.playback.together.TogetherState
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.components.Eyebrow
import com.prism.music.ui.components.SubPage
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalUi
import kotlinx.coroutines.launch

/**
 * Listen Together: host a session on this phone or join one nearby. Everything happens between
 * the phones on the same Wi-Fi (or one's hotspot); nothing goes through a server.
 */
@Composable
fun TogetherScreen(bottomPadding: Dp) {
    val c = LocalContainer.current
    val state by c.together.state.collectAsState()
    SubPage("Listen together", bottomPadding, horizontalPadding = 16.dp, spacing = 18.dp, subtitle = "With phones on the same Wi-Fi") {
        when (val s = state) {
            TogetherState.Idle -> item("idle") { Idle() }
            is TogetherState.Joining -> item("joining") { Joining(s.hostName) }
            is TogetherState.Hosting -> item("hosting") { Hosting(s) }
            is TogetherState.Guest -> item("guest") { GuestView(s) }
        }
        item("how") { HowItWorks() }
    }
}

@Composable
private fun Idle() {
    val c = LocalContainer.current
    val nearby by c.together.nearby.collectAsState()
    val scope = rememberCoroutineScope()
    var joinTarget by remember { mutableStateOf<NearbySession?>(null) }
    var manual by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Looks for sessions while this page is open.
    DisposableEffect(Unit) {
        c.together.startDiscovery()
        onDispose { c.together.stopDiscovery() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        BigCard(
            Icons.Rounded.WifiTethering, "Start a session",
            "Friends on this Wi-Fi join with a code. They can listen along on their phones or add songs to your queue.",
            "Start", onClick = { c.together.host() },
        )
        Group("Sessions nearby") {
            if (nearby.isEmpty()) Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Looking on this network…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            nearby.forEach { n ->
                Item(n.name, "Tap to join with their code", onClick = { joinTarget = n }, icon = Icons.Rounded.Groups)
            }
            Item("Join with an address", "If a session doesn't show up here", onClick = { manual = true })
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 8.dp)) }
    }
    joinTarget?.let { n ->
        JoinDialog(n.name, askAddress = false, onDismiss = { joinTarget = null }) { _, code ->
            joinTarget = null
            scope.launch { error = c.together.join(n.host, n.port, code, n.name) }
        }
    }
    if (manual) JoinDialog("a session", askAddress = true, onDismiss = { manual = false }) { address, code ->
        manual = false
        val host = address.substringBefore(':').trim()
        val port = address.substringAfter(':', "").trim().toIntOrNull() ?: Together.PORT
        scope.launch { error = c.together.join(host, port, code, host) }
    }
}

@Composable
private fun JoinDialog(name: String, askAddress: Boolean, onDismiss: () -> Unit, onJoin: (address: String, code: String) -> Unit) {
    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join $name") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (askAddress) OutlinedTextField(
                    address, { address = it.trim() }, label = { Text("Address on the host's screen") }, placeholder = { Text("192.168.1.20") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    code, { code = it.filter(Char::isDigit).take(4) }, label = { Text("Session code") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onJoin(address, code) }, enabled = code.length == 4 && (!askAddress || address.isNotBlank())) { Text("Join") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Joining(name: String) {
    Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(14.dp))
        Text("Joining $name…", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Hosting(s: TogetherState.Hosting) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val song by c.player.currentSong.collectAsState()
    val ui = LocalUi.current
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(ui.card).background(Brush.linearGradient(listOf(scheme.primaryContainer, scheme.tertiaryContainer))).padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Eyebrow("Session code", color = scheme.onPrimaryContainer.copy(alpha = 0.8f))
            Text(
                s.code.toCharArray().joinToString(" "), color = scheme.onPrimaryContainer,
                style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Black, letterSpacing = 0.08.em),
            )
            Text(
                "Friends open Listen together in Prism on the same Wi-Fi, tap your session and enter this code.",
                style = MaterialTheme.typography.bodySmall, color = scheme.onPrimaryContainer.copy(alpha = 0.85f), textAlign = TextAlign.Center,
            )
            s.address?.let {
                Text(
                    "Not showing up for them? They can join with " + if (s.port == Together.PORT) it else "$it:${s.port}",
                    style = MaterialTheme.typography.labelMedium, color = scheme.onPrimaryContainer.copy(alpha = 0.7f), textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        NowPlayingCard(song, if (song == null) "Play something and everyone hears it" else "Everyone in the session hears this")
        Group("Listening (${s.guests.size})") {
            if (s.guests.isEmpty()) Note("No one yet.")
            s.guests.forEach { g -> Item(g, null, onClick = {}, icon = Icons.Rounded.Headphones) }
            Toggle("Guests can control playback", "Play, pause and skip from their phones too. They can always add songs.", s.guestsControl) { c.together.setGuestsControl(it) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = { nav.go(Routes.SEARCH) }, Modifier.weight(1f)) {
                Icon(Icons.Rounded.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Find songs")
            }
            OutlinedButton(onClick = { c.together.leave() }, Modifier.weight(1f)) { Text("End session") }
        }
    }
}

@Composable
private fun GuestView(s: TogetherState.Guest) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val now = s.now
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        NowPlayingCard(now?.songs?.firstOrNull(), "${s.hostName}'s session" + (now?.guests?.size?.takeIf { it > 1 }?.let { " · $it listening" } ?: ""))
        if (now?.canControl == true) HostControls(now)
        Group("On this phone") {
            Toggle(
                "Play here too", "The same music plays on this phone, in step with ${s.hostName}'s. Off: just add songs to their queue.",
                s.listenHere,
            ) { c.together.setListenHere(it) }
            if (s.listenHere && !s.inSync) Item(
                "Out of step", "You paused or played something else here. Tap to catch up with ${s.hostName}.",
                onClick = { c.together.resync() }, icon = Icons.Rounded.Sync,
            )
        }
        val upcoming = now?.songs?.drop(1).orEmpty()
        if (upcoming.isNotEmpty()) Group("Up next") {
            upcoming.take(8).forEach { song -> QueueLine(song) }
            if (upcoming.size > 8) Note("…and ${upcoming.size - 8} more")
        }
        Note("Add songs from any song's ⋯ menu: they go to ${s.hostName}'s queue.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { nav.go(Routes.SEARCH) }, Modifier.weight(1f)) {
                Icon(Icons.Rounded.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add songs")
            }
            OutlinedButton(onClick = { c.together.leave() }, Modifier.weight(1f)) { Text("Leave") }
        }
    }
}

/** When the host allows it: their play, pause and skip. */
@Composable
private fun HostControls(now: Msg.State) {
    val c = LocalContainer.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { c.together.control("previous") }) { Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(30.dp)) }
        Spacer(Modifier.width(18.dp))
        Box(
            Modifier.size(58.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                .clickable { c.together.control(if (now.playing) "pause" else "play") },
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (now.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (now.playing) "Pause" else "Play", Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.width(18.dp))
        IconButton(onClick = { c.together.control("next") }) { Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(30.dp)) }
    }
}

@Composable
private fun NowPlayingCard(song: Song?, caption: String) {
    val ui = LocalUi.current
    Row(
        Modifier.fillMaxWidth().clip(ui.card).background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(song?.thumbnail, Modifier.size(72.dp), ui.art, size = 300)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Eyebrow(caption)
            Text(song?.title ?: "Nothing playing yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            song?.let { Text(it.artistText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun QueueLine(song: Song) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(song.thumbnail, Modifier.size(40.dp), LocalUi.current.smallArt, size = 120)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artistText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun BigCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, action: String, onClick: () -> Unit) {
    val ui = LocalUi.current
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(ui.card).background(Brush.linearGradient(listOf(scheme.primaryContainer, scheme.tertiaryContainer)))
            .clickable(onClick = onClick).padding(20.dp),
    ) {
        Icon(icon, null, Modifier.size(30.dp), tint = scheme.onPrimaryContainer)
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = scheme.onPrimaryContainer)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = scheme.onPrimaryContainer.copy(alpha = 0.85f))
        Spacer(Modifier.height(14.dp))
        Button(onClick = onClick) { Text(action) }
    }
}

@Composable
private fun HowItWorks() {
    Group("How it works") {
        Note("• Everyone needs Prism on the same Wi-Fi, or on one phone's hotspot.")
        Note("• Each phone streams the music itself, so guests use their own data or Wi-Fi.")
        Note("• Phones talk to each other directly. There's no Prism server, and nothing is shared once the session ends.")
        Note("• Some public and work Wi-Fi keeps phones apart; a hotspot works there.")
        Spacer(Modifier.height(6.dp))
    }
}
