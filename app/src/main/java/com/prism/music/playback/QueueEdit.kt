package com.prism.music.playback

/** Moving several of the queue's songs at once (R11, #159), worked out apart from the player so it can be tested. */
object QueueEdit {
    /** The player's moves (from, to) in order, the queue's order after them, and where autoplay's suggestions start. */
    class Plan(val moves: List<Pair<Int, Int>>, val order: List<Int>, val autoplayStart: Int)

    /**
     * Moves the songs at [indices] (of a queue of [size]) one after another: each comes out, and [to] gives its new place
     * in a running copy of the queue's order (the songs' original indices). The songs moved are the listener's from then
     * on, so autoplay's suggestions ([autoplayStart], -1 for none) start again at the first of theirs that stayed.
     */
    fun plan(size: Int, indices: List<Int>, autoplayStart: Int, to: (MutableList<Int>, Int) -> Int): Plan {
        val list = MutableList(size) { it }
        val moves = ArrayList<Pair<Int, Int>>()
        for (s in indices) {
            val from = list.indexOf(s)
            if (from < 0) continue
            list.removeAt(from)
            val at = to(list, s).coerceIn(0, list.size)
            list.add(at, s)
            moves += from to at
        }
        val moved = indices.toSet()
        val auto = if (autoplayStart < 0) -1 else list.indexOfFirst { it >= autoplayStart && it !in moved }.let { if (it < 0) list.size else it }
        return Plan(moves, list, auto)
    }
}
