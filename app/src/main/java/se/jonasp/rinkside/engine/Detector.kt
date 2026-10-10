package se.jonasp.rinkside.engine

/**
 * Compares what the engine remembers about a game with a new reading and says what happened.
 *
 * Rules (the tests in DetectorTest pin each of them):
 *  - The first reading of a game only initialises the memory: no burst of notifications for old goals. The one exception is a game
 *    that is seen live at 0-0 in the first period within [START_GRACE_MS] of its scheduled start: that is the puck drop, announce it.
 *  - A reading older than the one remembered, a reading that would move a game backwards (live -> scheduled) and anything after
 *    the end of a game are ignored (two Worker instances may answer a few seconds apart; the web app has the same rules).
 *  - scheduled -> live is the match start. A score that goes up is one Goal per goal (more than [MAX_BURST_GOALS] at once is a missed
 *    stretch and becomes a single Goal with the new score). A score that goes down is a disallowed goal.
 *  - Shootout scores are no goals: while a regular-season shootout is on, and when a game ends in a shootout, only the end is announced.
 *  - finished is the match end, with the final score and OT/SO. A game that was never seen live and is finished gets the end only.
 */
object Detector {
    const val START_GRACE_MS = 10 * 60_000L
    const val MAX_BURST_GOALS = 3

    class Step(val memory: GameMemory, val events: List<GameEvent>)

    fun step(game: WatchedGame, prev: GameMemory?, obs: Observation, nowMs: Long): Step {
        val next = GameMemory(obs.state, obs.home, obs.away, obs.readAtMs, obs.shootout)
        if (prev == null) return Step(next, initialEvents(game, obs, nowMs))

        if (obs.readAtMs < prev.readAtMs) return Step(prev, emptyList())
        if (prev.state == GameState.FINISHED) return Step(prev, emptyList())
        if (obs.state.rank < prev.state.rank) return Step(prev, emptyList())

        val events = ArrayList<GameEvent>()
        when (obs.state) {
            GameState.SCHEDULED, GameState.POSTPONED -> Unit
            GameState.LIVE -> {
                val h = obs.home ?: return Step(prev, emptyList())
                val a = obs.away ?: return Step(prev, emptyList())
                if (prev.state != GameState.LIVE) events.add(GameEvent.MatchStart(game))
                if (!obs.shootout && !prev.shootout) {
                    events.addAll(scoreEvents(game, prev.home ?: 0, prev.away ?: 0, h, a))
                }
            }
            GameState.FINISHED -> {
                val h = obs.home ?: return Step(prev, emptyList())
                val a = obs.away ?: return Step(prev, emptyList())
                val shootoutGame = obs.decision == "SO" || obs.shootout || prev.shootout
                // Only a game that was followed live has goals to report; one that jumps from scheduled to finished gets just its end.
                if (prev.state == GameState.LIVE && !shootoutGame) {
                    events.addAll(scoreEvents(game, prev.home ?: 0, prev.away ?: 0, h, a))
                }
                events.add(GameEvent.MatchEnd(game, h, a, obs.decision))
            }
        }
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

    /** Events for a score that moved from (ph, pa) to (h, a): decreases first (disallowed goals), then increases, one Goal per goal. */
    internal fun scoreEvents(game: WatchedGame, ph: Int, pa: Int, h: Int, a: Int): List<GameEvent> {
        val events = ArrayList<GameEvent>()
        var ch = ph
        var ca = pa
        if (h < ch) {
            ch = h
            events.add(GameEvent.GoalDisallowed(game, ch, ca, Side.HOME))
        }
        if (a < ca) {
            ca = a
            events.add(GameEvent.GoalDisallowed(game, ch, ca, Side.AWAY))
        }
        val up = (h - ch) + (a - ca)
        if (up > MAX_BURST_GOALS) {
            events.add(GameEvent.Goal(game, h, a, if (h - ch >= a - ca) Side.HOME else Side.AWAY))
            return events
        }
        while (ch < h) {
            ch++
            events.add(GameEvent.Goal(game, ch, ca, Side.HOME))
        }
        while (ca < a) {
            ca++
            events.add(GameEvent.Goal(game, ch, ca, Side.AWAY))
        }
        return events
    }
}

/**
 * Remembers one [GameMemory] per game and turns batches of readings into events. The Android service persists [memory] between runs;
 * the simulation uses a fresh tracker. Pure: no clock of its own (the caller passes `nowMs`).
 */
class GameTracker(initial: Map<String, GameMemory> = emptyMap()) {
    private val mem = LinkedHashMap<String, GameMemory>(initial)

    fun memory(): Map<String, GameMemory> = mem

    fun process(games: List<WatchedGame>, observations: List<Observation>, nowMs: Long): List<GameEvent> {
        val byId = HashMap<String, WatchedGame>()
        for (g in games) byId[g.id] = g
        val events = ArrayList<GameEvent>()
        for (obs in observations) {
            val game = byId[obs.gameId] ?: continue
            val step = Detector.step(game, mem[obs.gameId], obs, nowMs)
            mem[obs.gameId] = step.memory
            events.addAll(step.events)
        }
        return events
    }
}
