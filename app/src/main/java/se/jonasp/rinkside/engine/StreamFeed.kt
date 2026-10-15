package se.jonasp.rinkside.engine

/**
 * Feeds the SHL live stream into the detection engine. One instance per service run, shared by every connection of that run; the stream
 * thread calls [onEvent] for each event of the stream, the poll thread calls [enrich] for the goals its overlays found.
 *
 * What happens to a message of a watched game:
 *  - A `gameOverview` becomes an [Observation] (the stream's arrival time is its reading time) and goes through the same [GameTracker] and
 *    [Detector] as the overlays do. Whoever sees a score line first announces it; the other source then finds it announced.
 *  - A `gameScorer` is not a detector input. It is kept (by game and score line) so that the goal notification can name the scorer and the
 *    game time, whichever of the two messages arrives first. Its team values are not trusted for detection: the overview is what the web app
 *    scores from.
 *  - Nothing in the replay burst is fed to the detector. On every connect the stream first replays recent messages, old goals included.
 *    Those are history: re-announcing them is wrong and, read as live readings, their older scores would look like disallowed goals.
 *    The burst is whatever arrives from the connect until the stream has been quiet for [REPLAY_QUIET_MS] (at most [REPLAY_MAX_MS]).
 *    A goal that happened while the connection was down is still announced: by the overlay, or by the first live message that follows.
 *  - Messages of a game whose memory the overlay has not started, messages with an older sequence number than one already applied
 *    (per game, as the web app does) and messages with an earlier game time than the remembered one (see [Detector]) change nothing.
 */
class StreamFeed(private val tracker: GameTracker) {

    companion object {
        /** The burst ends when the stream has been silent this long. Generous: ending it early would let history through as live readings. */
        const val REPLAY_QUIET_MS = 2_500L

        /** A burst is over after this long whatever happens, so that a stream which never pauses cannot stay in replay for ever. */
        const val REPLAY_MAX_MS = 15_000L

        private const val MAX_SCORERS_PER_GAME = 40
        private val SEQUENCE = Regex("^\\d{1,18}$")
    }

    enum class Outcome {
        /** Not about a watched SHL game, or not a valid message: skipped before any JSON tree was built or after it did not check out. */
        NOT_RELEVANT,

        /** A message of a watched game that belongs to the replay burst: not fed to the detector. */
        REPLAYED,

        /** A message with an older sequence number than one already applied for that game. */
        OUT_OF_ORDER,

        /** An overview that went through the detector (which may still have found nothing new). */
        APPLIED,

        /** A goal scorer, kept for the notification. */
        SCORER,
    }

    /**
     * What one event of the stream led to. [events] are the notifications to post (goals already carry the scorer when it is known);
     * [scorer] is set for a [Outcome.SCORER], so that a goal that was announced a moment ago can be updated with the name;
     * [changed] says the memory has something new that is worth saving before the notifications are posted.
     */
    class Result(val outcome: Outcome, val events: List<GameEvent> = emptyList(), val scorer: ScorerInfo? = null, val changed: Boolean = false)

    private var replaying = false
    private var openedAtMs = 0L
    private var lastEventAtMs = 0L
    private var eventsSinceOpen = 0
    private val sequence = HashMap<String, Long>()
    private val scorers = HashMap<String, LinkedHashMap<String, ScorerInfo>>()

    /** A connection was opened: the replay burst starts, the sequence numbers of the old connection mean nothing any more. */
    @Synchronized
    fun open(nowMs: Long) {
        replaying = true
        openedAtMs = nowMs
        lastEventAtMs = nowMs
        eventsSinceOpen = 0
        sequence.clear()
    }

    /** True while events are still taken for the replay burst. */
    @Synchronized
    fun isReplaying(): Boolean = replaying

    @Synchronized
    fun onEvent(event: SseEvent, games: List<WatchedGame>, nowMs: Long): Result {
        val replay = inBurst(nowMs) // every event counts for the burst, also those of other sports
        val message = ShlStream.parse(event.data, games, nowMs) ?: return Result(Outcome.NOT_RELEVANT)
        return when (message) {
            is StreamMessage.Scorer -> {
                remember(message.info)
                Result(Outcome.SCORER, scorer = message.info)
            }
            is StreamMessage.Overview -> when {
                replay -> Result(Outcome.REPLAYED)
                !inSequence(event.id, message.game.id) -> Result(Outcome.OUT_OF_ORDER)
                else -> {
                    val before = tracker.memoryOf(message.game.id)
                    val events = tracker.process(listOf(message.game), listOf(message.observation), nowMs)
                    val after = tracker.memoryOf(message.game.id)
                    Result(Outcome.APPLIED, events.map { enrich(it) }, changed = differs(before, after))
                }
            }
        }
    }

    /** A goal with the scorer and the game time put on it when the stream has told them; any other event, or an unknown goal, as it is. */
    @Synchronized
    fun enrich(event: GameEvent): GameEvent {
        if (event !is GameEvent.Goal || event.scorer != null) return event
        val info = scorers[event.game.id]?.get(event.line) ?: return event
        if (info.side != event.side) return event
        return event.copy(scorer = info.scorer, gameSec = info.gameSec)
    }

    /** Whether the event that arrives now is part of the replay burst; ends the burst when the stream has gone quiet. Time may step backwards. */
    private fun inBurst(nowMs: Long): Boolean {
        if (replaying) {
            val quiet = eventsSinceOpen > 0 && nowMs - lastEventAtMs >= REPLAY_QUIET_MS
            val sinceOpen = nowMs - openedAtMs
            val over = sinceOpen < 0 || sinceOpen >= REPLAY_MAX_MS
            if (quiet || over) replaying = false
        }
        lastEventAtMs = nowMs
        eventsSinceOpen++
        return replaying
    }

    /**
     * The web app's sequence rule: a message whose number is older than one already applied for that game is ignored. Numbers that repeat are
     * let through (a stream that sends `id:` only now and then repeats the last one for the events in between); the detector copes with a repeat.
     */
    private fun inSequence(id: String?, gameId: String): Boolean {
        if (id == null || !SEQUENCE.matches(id)) return true
        val number = id.toLong()
        val last = sequence[gameId]
        if (last != null && number < last) return false
        sequence[gameId] = number
        return true
    }

    private fun remember(info: ScorerInfo) {
        val perGame = scorers.getOrPut(info.gameId) { LinkedHashMap() }
        perGame.remove(info.line)
        perGame[info.line] = info
        while (perGame.size > MAX_SCORERS_PER_GAME) {
            val oldest = perGame.keys.iterator()
            oldest.next()
            oldest.remove()
        }
    }

    /** Whether what [MemoryJson] has to remember about the game is different. The clock marks are not worth a write of their own. */
    private fun differs(a: GameMemory?, b: GameMemory?): Boolean {
        if (a == null || b == null) return a != b
        return a.state != b.state || a.home != b.home || a.away != b.away || a.goalLines != b.goalLines || a.dropLines != b.dropLines
    }
}
