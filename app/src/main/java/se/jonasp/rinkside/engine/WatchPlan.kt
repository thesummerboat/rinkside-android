package se.jonasp.rinkside.engine

/**
 * Which followed games are worth watching right now, which overlays that needs, and when to wake up for the next game.
 * Pure functions of (games, memory, now): the service and the alarm scheduler use the same ones, so they cannot disagree
 * (a service that stops while the scheduler thinks a game is on would restart in a loop).
 */
object WatchPlan {
    /** Watching starts this long before the scheduled start (the Worker also starts reading games 10 minutes before). */
    const val LEAD_MS = 10 * 60_000L

    /** A game that was seen live is watched until this long after its start (a hockey game takes about 2.5 h, the rest is delays and overtime). */
    const val TAIL_MS = 4 * 3_600_000L

    /** A game that was last seen as scheduled and has not started this long after its start is given up (postponed). */
    const val GIVE_UP_MS = 60 * 60_000L

    /** "Starta bevakning nu" widens the lead to this, so a game later today can be tested. */
    const val FORCE_LEAD_MS = 12 * 3_600_000L

    const val POLL_MS = 15_000L
    const val SCHEDULE_POLL_MS = 60_000L

    /** Games that can be matched against an overlay at all. */
    fun isWatchable(game: WatchedGame): Boolean = when (game.league) {
        "nhl" -> game.nhlId != null
        "shl" -> game.shlId != null
        "ha", "ettan" -> true
        else -> false
    }

    fun isDone(mem: GameMemory?): Boolean = mem != null && (mem.state == GameState.FINISHED || mem.state == GameState.POSTPONED)

    fun isActive(game: WatchedGame, mem: GameMemory?, nowMs: Long, leadMs: Long = LEAD_MS): Boolean {
        if (!isWatchable(game)) return false
        if (isDone(mem)) return false
        if (nowMs < game.startMs - leadMs) return false
        val end = if (mem?.state == GameState.SCHEDULED) game.startMs + GIVE_UP_MS else game.startMs + TAIL_MS
        return nowMs <= end
    }

    fun activeGames(games: List<WatchedGame>, memory: Map<String, GameMemory>, nowMs: Long, leadMs: Long = LEAD_MS): List<WatchedGame> =
        games.filter { isActive(it, memory[it.id], nowMs, leadMs) }

    /**
     * When the app should wake up next: [nowMs] if a game is in its window already, otherwise the earliest "start minus lead" of a game
     * that has not been played, or null when there is nothing to wait for.
     */
    fun nextTriggerMs(games: List<WatchedGame>, memory: Map<String, GameMemory>, nowMs: Long, leadMs: Long = LEAD_MS): Long? {
        var best: Long? = null
        for (g in games) {
            if (!isWatchable(g)) continue
            val mem = memory[g.id]
            if (isActive(g, mem, nowMs, leadMs)) return nowMs
            if (isDone(mem)) continue
            val trigger = g.startMs - leadMs
            if (trigger > nowMs && (best == null || trigger < best)) best = trigger
        }
        return best
    }

    /** The overlays the active games need. shl-schedule-status is the SHL's fallback for the finished state, asked for once a game should have started. */
    fun overlaysFor(active: List<WatchedGame>, nowMs: Long): Set<Overlay> {
        val out = LinkedHashSet<Overlay>()
        for (g in active) {
            when (g.league) {
                "nhl" -> out.add(Overlay.NHL_TODAY)
                "shl" -> {
                    out.add(Overlay.SHL_TODAY)
                    if (nowMs >= g.startMs) out.add(Overlay.SHL_SCHEDULE)
                }
                "ha", "ettan" -> out.add(Overlay.SWE_TODAY)
            }
        }
        return out
    }

    fun pollIntervalMs(overlay: Overlay): Long = if (overlay == Overlay.SHL_SCHEDULE) SCHEDULE_POLL_MS else POLL_MS

    /** Back-off after consecutive failures: 30 s, 60 s, then 2 minutes at most (the normal pace is [POLL_MS]). */
    fun backoffMs(failures: Int): Long {
        if (failures <= 0) return POLL_MS
        val shift = minOf(failures, 4)
        return minOf(POLL_MS shl shift, 120_000L)
    }
}
