package com.prism.music.ui.player

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** 1.8.6 Canvas, Full screen: how long the controls stay after the last touch. */
internal const val CANVAS_CONTROLS_MS = 4_000L

/**
 * 1.8.6 Canvas, Full screen: whether the controls may fade away, leaving the title, the artist and a thin progress
 * line over the picture. Anything that says "someone is using them, or can't get them back by looking" keeps them up:
 * a paused song, a sheet, the lyrics or the queue, a drag on the volume, a screen reader, Reduce motion (nothing moves
 * behind them then), and the listener's own "Keep controls showing".
 */
internal fun canvasControlsMayFade(
    fullScreen: Boolean,
    hasCanvas: Boolean,
    keepControls: Boolean,
    playing: Boolean,
    covered: Boolean,
    panelOpen: Boolean,
    dragging: Boolean,
    screenReader: Boolean,
    reduceMotion: Boolean,
): Boolean = fullScreen && hasCanvas && !keepControls && playing && !covered && !panelOpen && !dragging && !screenReader && !reduceMotion

/**
 * The darkening over a full-screen canvas, top / middle / bottom, for how much of the controls shows ([controls] 0..1)
 * and whether a list (lyrics, queue) is read over it ([panel] 0..1). Lighter with the controls away, never so light
 * at the bottom that the kept title can't be read; a music video is busier than an animated cover, so it gets more.
 */
internal fun canvasScrim(controls: Float, panel: Float, video: Boolean): FloatArray {
    val c = controls.coerceIn(0f, 1f); val p = panel.coerceIn(0f, 1f)
    val more = if (video) 0.08f else 0f
    val top = 0.12f + (0.13f + more) * c
    val mid = 0.18f + (0.27f + more) * c
    val bottom = 0.6f + (0.15f + more) * c
    return floatArrayOf(top, mid, bottom).map { (it + (1f - it) * 0.45f * p).coerceIn(0f, 0.92f) }.toFloatArray()
}

/** True while a screen reader that explores by touch (TalkBack) is on. */
@Composable
internal fun rememberTouchExploration(): State<Boolean> {
    val context = LocalContext.current
    val am = remember { context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager }
    val on = remember { mutableStateOf(am?.isTouchExplorationEnabled == true) }
    DisposableEffect(am) {
        val l = AccessibilityManager.TouchExplorationStateChangeListener { on.value = it }
        am?.addTouchExplorationStateChangeListener(l)
        on.value = am?.isTouchExplorationEnabled == true
        onDispose { am?.removeTouchExplorationStateChangeListener(l) }
    }
    return on
}
