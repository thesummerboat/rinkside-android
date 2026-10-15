package se.jonasp.rinkside.engine

/**
 * Compares what the engine remembers about a game with a new reading and says what happened.
 * Readings come from the Worker's overlays (polled) and from the SHL live stream (pushed). Both go through [step], so a goal that one of
 * them announced is never announced again by the other.
 *
 * Rules (the tests in DetectorTest and StreamFeedTest pin each of them):
 *  - The first reading of a game only initialises the memory: no burst of notifications for old goals. The one exception is a game
 *    that is seen live at 0-0 in the first period within [START_GRACE_MS] of its scheduled start: that is the puck drop, announce it.
 *    Only an overlay reading can be the first one: the stream follows games the overlay has started (see [GameTracker.process]).
 *  - An overlay reading older than the one remembered, a reading that would move a game backwards (live -> scheduled) and anything after
 *    the end of a game are ignored (two Worker instances may answer a few seconds apart; the web app has the same rules).
 *  - A stream reading whose elapsed game time is earlier than the remembered one is a replayed or reordered message: ignored.
 *  - The overlay runs up to a minute behind the stream. For [OVERLAY_LAG_MS] after the stream spoke for a game, an overlay reading that
 *    would lower the score is that lag, not a disallowed goal: ignored. It still may raise the score (a goal the stream missed).
 *    The two sources are never ordered by comparing their clocks, only by game time and by "how long ago did the stream speak".
 *  - scheduled -> live is the match start. A score that goes up is one Goal per goal (more than [MAX_BURST_GOALS] at once is a missed
 *    stretch and becomes a single Goal with the new score). A score that goes down is a disallowed goal.
 *  - One cue per score line, like the web app: a Goal is announced once per game and score line, a disallowed goal once per drop
 *    ("2-1>1-1"). A goal that is taken back and scored again, or a score the other source already announced, stays silent.
 *  - Shootout scores are no goals: while a regular-season shootout is on, and when a game ends in a shootout, only the end is announced.
 *  - finished is the match end, with the final score and OT/SO. A game that was never seen live and is finished gets the end only.
 */
object Detector {
    const val START_GRACE_MS = 10 * 60_000L
    const val MAX_BURST_GOALS = 3

    /** The Worker reads a game every 30 s, the overlay is cached 15 s and polled every 15 s: a reading is at most about a minute behind. */
    const val OVERLAY_LAG_MS = 120_000L

    /** How many announced score lines and drops a game remembers (a hockey game has about a dozen goals). */
    const val MAX_CUES = 64

    class Step(val memory: GameMemory, val events: List<GameEvent>)

    fun step(game: WatchedGame, prev: GameMemory?, obs: Observation, nowMs: Long): Step {
        if (prev == null) {
            val first = GameMemory(obs.state, obs.home, obs.away, obs.readAtMs, obs.shootout, obs.readAtMs.takeIf { obs.fromStream }, obs.gameSec)
            return Step(first, initialEvents(game, obs, nowMs))
        }

        if (!obs.fromStream && obs.readAtMs < prev.readAtMs) return Step(prev, emptyList())
        if (prev.state == GameState.FINISHED) return Step(prev, emptyList())
        if (obs.state.rank < prev.state.rank) return Step(prev, emptyList())
        if (obs.fromStream) {
            if (obs.gameSec != null && prev.gameSec != null && obs.gameSec < prev.gameSec) return Step(prev, emptyList())
        } else if (overlayLags(prev, obs, nowMs)) {
            return Step(prev, emptyList())
        }

        val goals = LinkedHashSet(prev.goalLines)
        val drops = LinkedHashSet(prev.dropLines)
        val events = ArrayList<GameEvent>()
        when (obs.state) {
            GameState.SCHEDULED, GameState.POSTPONED -> Unit
            GameState.LIVE -> {
                val h = obs.home ?: return Step(prev, emptyList())
                val a = obs.away ?: return Step(prev, emptyList())
                if (prev.state != GameState.LIVE) events.add(GameEvent.MatchStart(game))
                if (!obs.shootout && !prev.shootout) {
                    events.addAll(scoreEvents(game, prev.home ?: 0, prev.away ?: 0, h, a, goals, drops))
                }
            }
            GameState.FINISHED -> {
                val h = obs.home ?: return Step(prev, emptyList())
                val a = obs.away ?: return Step(prev, emptyList())
                val shootoutGame = obs.decision == "SO" || obs.shootout || prev.shootout
                // Only a game that was followed live has goals to report; one that jumps from scheduled to finished gets just its end.
                if (prev.state == GameState.LIVE && !shootoutGame) {
                    events.addAll(scoreEvents(game, prev.home ?: 0, prev.away ?: 0, h, a, goals, drops))
                }
                events.add(GameEvent.MatchEnd(game, h, a, obs.decision))
            }
        }
        val next = GameMemory(
            state = obs.state,
            home = obs.home,
            away = obs.away,
            readAtMs = if (obs.fromStream) prev.readAtMs else obs.readAtMs,
            shootout = obs.shootout,
            streamAtMs = if (obs.fromStream) obs.readAtMs else prev.streamAtMs,
            gameSec = newest(prev.gameSec, obs.gameSec),
            goalLines = capped(goals),
            dropLines = capped(drops),
        )
        return Step(next, events)
    }

    private fun initialEvents(game: WatchedGame, obs: Observation, nowMs: Long): List<GameEvent> {
        if (obs.state == GameState.LIVE && obs.home == 0 && obs.away == 0 && obs.period <= 1 && !obs.shootout &&
            nowMs - game.startMs <= START_GRACE_MS
        ) {
            return listOf(GameEvent.MatchStart(game))
        }
        return emptyList()
    }

    /** True for an overlay reading that is lower than the score the stream gave a moment ago: the overlay has not caught up yet. */
    private fun overlayLags(prev: GameMemory, obs: Observation, nowMs: Long): Boolean {
        val spokeAt = prev.streamAtMs ?: return false
        val age = nowMs - spokeAt
        if (age < 0 || age >= OVERLAY_LAG_MS) return false
        val ph = prev.home ?: return false
        val pa = prev.away ?: return false
        val h = obs.home ?: return false
        val a = obs.away ?: return false
        return h < ph || a < pa
    }

    private fun newest(a: Int?, b: Int?): Int? = if (a == null) b else if (b == null) a else maxOf(a, b)

    private fun capped(lines: LinkedHashSet<String>): Set<String> {
        while (lines.size > MAX_CUES) {
            val oldest = lines.iterator()
            oldest.next()
            oldest.remove()
        }
        return lines
    }

    /**
     * Events for a score that moved from (ph, pa) to (h, a): decreases first (disallowed goals), then increases, one Goal per goal.
     * [goals] and [drops] are the score lines and drops already announced; what is announced now is added to them, what is in them stays silent.
     */
    internal fun scoreEvents(
        game: WatchedGame,
        ph: Int,
        pa: Int,
        h: Int,
        a: Int,
        goals: MutableSet<String> = HashSet(),
        drops: MutableSet<String> = HashSet(),
    ): List<GameEvent> {
        val events = ArrayList<GameEvent>()
        var ch = ph
        var ca = pa
        if (h < ch) {
            val drop = "$ch-$ca>$h-$ca"
            ch = h
            if (drops.add(drop)) events.add(GameEvent.GoalDisallowed(game, ch, ca, Side.HOME))
        }
        if (a < ca) {
            val drop = "$ch-$ca>$ch-$a"
            ca = a
            if (drops.add(drop)) events.add(GameEvent.GoalDisallowed(game, ch, ca, Side.AWAY))
        }
        val up = (h - ch) + (a - ca)
        if (up > MAX_BURST_GOALS) {
            if (goals.add("$h-$a")) events.add(GameEvent.Goal(game, h, a, if (h - ch >= a - ca) Side.HOME else Side.AWAY))
            return events
        }
        while (ch < h) {
            ch++
            if (goals.add("$ch-$ca")) events.add(GameEvent.Goal(game, ch, ca, Side.HOME))
        }
        while (ca < a) {
            ca++
            if (goals.add("$ch-$ca")) events.add(GameEvent.Goal(game, ch, ca, Side.AWAY))
        }
        return events
    }
}

/**
 * Remembers one [GameMemory] per game and turns batches of readings into events. The Android service persists [memory] between runs;
 * the simulation uses a fresh tracker. Pure: no clock of its own (the caller passes `nowMs`).
 * The poll thread (overlays) and the stream thread both use one tracker, so every method is synchronized.
 */
class GameTracker(initial: Map<String, GameMemory> = emptyMap()) {
    private val mem = LinkedHashMap<String, GameMemory>(initial)

    /** A copy of what is remembered now. */
    @Synchronized
    fun memory(): Map<String, GameMemory> = LinkedHashMap(mem)

    @Synchronized
    fun memoryOf(gameId: String): GameMemory? = mem[gameId]

    @Synchronized
    fun process(games: List<WatchedGame>, observations: List<Observation>, nowMs: Long): List<GameEvent> {
        val byId = HashMap<String, WatchedGame>()
        for (g in games) byId[g.id] = g
        val events = ArrayList<GameEvent>()
        for (obs in observations) {
            val game = byId[obs.gameId] ?: continue
            val prev = mem[obs.gameId]
            // A game's memory starts with an overlay reading. The stream also replays what happened before the watch began; it must
            // not be the baseline, or the goals that followed it in the replay would be announced as new.
            if (prev == null && obs.fromStream) continue
            val step = Detector.step(game, prev, obs, nowMs)
            mem[obs.gameId] = step.memory
            events.addAll(step.events)
        }
        return events
    }
}
