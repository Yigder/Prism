package com.prism.music.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.data.RankedCategories
import com.prism.music.ui.Load
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.components.ErrorState
import com.prism.music.ui.components.LoadingState
import com.prism.music.ui.components.MoodTile
import com.prism.music.ui.rememberLoad
import com.prism.music.ui.theme.LocalContainer

/** Every mood and genre YouTube Music offers, best fits for you first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodsScreen(bottomPadding: Dp) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val moods = rememberLoad("moods") { c.taste.rank(c.ytm.moodsAndGenres()) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Moods & genres") },
            navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
        )
        when (val s = moods.state) {
            Load.Loading -> LoadingState()
            is Load.Err -> ErrorState(s.message) { moods.reload(false) }
            is Load.Ok -> MoodGrid(s.value, bottomPadding)
        }
    }
}

@Composable
private fun MoodGrid(ranked: RankedCategories, bottomPadding: Dp) {
    val nav = LocalNavigator.current
    val c = LocalContainer.current
    LazyVerticalGrid(
        GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomPadding + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val groups = listOf("Picked for you" to ranked.picks.take(12)) + ranked.sections
        groups.forEach { (title, tiles) ->
            if (tiles.isEmpty()) return@forEach
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
            }
            items(tiles, key = { title + it.id + it.params }) { mood ->
                MoodTile(mood, Modifier.fillMaxWidth().height(84.dp)) { nav.open(mood) { c.player.playSingle(it.song) } }
            }
        }
    }
}
