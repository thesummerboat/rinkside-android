package se.jonasp.rinkside.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.jonasp.rinkside.engine.StreamFeed.Outcome
import se.jonasp.rinkside.engine.StreamJson.BASKETBALL
import se.jonasp.rinkside.engine.StreamJson.overview
import se.jonasp.rinkside.engine.StreamJson.scorer
import se.jonasp.rinkside.engine.TestData.T0
import se.jonasp.rinkside.engine.TestData.tik

/**
 * The stream and the overlays through one tracker: a goal is announced once whichever sees it first, the replay burst of a connect is history,
 * a disallowed goal is announced once. Time is a variable of the test rig; the "device clock" and the "Worker clock" are told apart where it matters.
 */
class StreamFeedTest {
    private val game = tik
    private val tikId = "shl-x0ws1h01an"

    /** A service with one tracker, a feed and a list of what would have been posted. */
    private class Rig(val game: WatchedGame, var now: Long, private val overlaySkewMs: Long = 0L, tracker: GameTracker = GameTracker()) {
        val tracker = tracker
        val feed = StreamFeed(tracker)
        val posted = ArrayList<GameEvent>()

        fun advance(ms: Long) {
            now += ms
        }

        /** One reading of the overlay; [overlaySkewMs] is how far the Worker's clock is from the device's. */
        fun overlay(
            home: Int?,
            away: Int?,
            state: GameState = GameState.LIVE,
            period: Int = 1,
            clock: String? = null,
            decision: String? = null,
        ) {
            val sec = clock?.let { GameClock.elapsed(period, it) }
            val obs = Observation(game.id, state, home, away, period, decision, false, now + overlaySkewMs, sec)
            posted.addAll(tracker.process(listOf(game), listOf(obs), now).map { feed.enrich(it) })
        }

        fun open() = feed.open(now)

        fun send(data: String, id: String? = null): StreamFeed.Result {
            val result = feed.onEvent(SseEvent(id, null, data), listOf(game), now)
            posted.addAll(result.events)
            return result
        }

        /** A connection as the stream makes it: it opens, a burst of other sports' messages arrives, then it goes quiet. After this the stream is live. */
        fun connect() {
            open()
            advance(50)
            send(BASKETBALL)
            advance(3_000)
        }
    }

    private fun rig(overlaySkewMs: Long = 0L) = Rig(game, T0 + 20 * 60_000L, overlaySkewMs)

    private fun goal(h: Int, a: Int, side: Side) = GameEvent.Goal(game, h, a, side)

    // ---- one cue per score line, whichever source sees it first ----

    @Test
    fun aGoalFromTheStreamAndThenTheOverlayIsAnnouncedOnce() {
        val r = rig()
        r.overlay(0, 0, period = 1, clock = "03:00") // the baseline: the first reading announces nothing
        r.connect()
        val result = r.send(overview(1, 0, period = 1, clock = "07:12"), "100")
        assertEquals(Outcome.APPLIED, result.outcome)
        assertEquals(listOf<GameEvent>(goal(1, 0, Side.HOME)), r.posted)
        // the overlay catches up 30 s later and finds the goal already announced
        r.advance(30_000)
        r.overlay(1, 0, period = 1, clock = "07:50")
        r.advance(15_000)
        r.overlay(1, 0, period = 1, clock = "08:20")
        assertEquals(listOf<GameEvent>(goal(1, 0, Side.HOME)), r.posted)
    }

    @Test
    fun aGoalFromTheOverlayAndThenTheStreamIsAnnouncedOnce() {
        val r = rig()
        r.overlay(0, 0, period = 1, clock = "03:00")
        r.connect() // the stream is up but slower this time
        r.overlay(1, 0, period = 1, clock = "07:15")
        assertEquals(listOf<GameEvent>(goal(1, 0, Side.HOME)), r.posted)
        r.advance(8_000)
        val result = r.send(overview(1, 0, period = 1, clock = "07:12"), "100")
        assertEquals(Outcome.APPLIED, result.outcome)
        assertTrue(result.events.isEmpty())
        assertEquals(listOf<GameEvent>(goal(1, 0, Side.HOME)), r.posted)
    }

    @Test
    fun theSameStreamMessageTwiceChangesNothing() {
        val r = rig()
        r.overlay(0, 0, period = 1, clock = "03:00")
        r.connect()
        r.send(overview(1, 0, period = 1, clock = "07:12"), "100")
        r.send(overview(1, 0, period = 1, clock = "07:12"), "100")
        r.send(overview(1, 0, period = 1, clock = "07:12"), "100")
        assertEquals(1, r.posted.size)
    }

    @Test
    fun anOverlayThatLagsBehindTheStreamCannotTurnAGoalBack() {
        val r = rig()
        r.overlay(0, 0, period = 1, clock = "03:00")
        r.connect()
        r.send(overview(1, 0, period = 1, clock = "07:12"), "100")
        r.advance(10_000)
        r.send(overview(2, 0, period = 1, clock = "12:00"), "101")
        r.advance(5_000)
        // the Worker read the game before the second goal: 1-0. Not a disallowed goal, and not a reason to announce 2-0 again later.
        r.overlay(1, 0, period = 1, clock = "11:40")
        r.advance(15_000)
        r.overlay(1, 0, period = 1, clock = "11:40")
        r.advance(15_000)
        r.overlay(2, 0, period = 1, clock = "12:30")
        assertEquals(listOf<GameEvent>(goal(1, 0, Side.HOME), goal(2, 0, Side.HOME)), r.posted)
    }

    @Test
    fun anOverlayThatIsAheadOfTheStreamStillAnnouncesWhatTheStreamMissed() {
        val r = rig()
        r.overlay(0, 0, period = 1, clock = "03:00")
        r.connect()
        r.send(overview(1, 0, period = 1, clock = "07:12"), "100")
        r.advance(20_000)
        r.overlay(1, 1, period = 1, clock = "09:00") // the stream said nothing about this goal
        assertEquals(listOf<GameEvent>(goal(1, 0, Side.HOME), goal(1, 1, Side.AWAY)), r.posted)
    }

    @Test
    fun anOverlayDropLongAfterTheStreamLastSpokeIsBelieved() {
        val r = rig()
        r.overlay(0, 0, period = 1, clock = "03:00")
        r.connect()
        r.send(overview(1, 0, period = 1, clock = "07:12"), "100")
        // the stream goes quiet (or dies); two minutes later the overlay says 0-0 again: that is a disallowed goal, the overlay is the source now
        r.advance(Detector.OVERLAY_LAG_MS + 1_000)
        r.overlay(0, 0, period = 1, clock = "09:10")
        assertEquals(listOf<GameEvent>(goal(1, 0, Side.HOME), GameEvent.GoalDisallowed(game, 0, 0, Side.HOME)), r.posted)
    }

    @Test
    fun theOverlayAndTheStreamAreNeverOrderedByComparingTheirClocks() {
        // the Worker's clock is an hour behind the device's, and an hour ahead: neither blocks the overlay or the stream
        for (skew in listOf(-3_600_000L, 3_600_000L)) {
            val r = rig(overlaySkewMs = skew)
            r.overlay(0, 0, period = 1, clock = "03:00")
            r.connect()
            r.send(overview(1, 0, period = 1, clock = "07:12"), "100")
            r.advance(30_000)
            r.overlay(1, 0, period = 1, clock = "07:50")
            r.advance(15_000)
            r.overlay(2, 0, period = 1, clock = "12:00") // a goal that only the overlay saw
            r.advance(5_000)
            r.send(overview(2, 0, period = 1, clock = "12:05"), "101")
            r.send(overview(3, 0, period = 1, clock = "15:00"), "102")
            assertEquals("skew $skew", listOf<GameEvent>(goal(1, 0, Side.HOME), goal(2, 0, Side.HOME), goal(3, 0, Side.HOME)), r.posted)
        }
    }

    // ---- the replay burst ----

    /** What the stream replays on a connect for this game: its recent history, oldest first (a few ms apart). */
    private fun replayHistory(r: Rig) {
        val history = listOf(
            overview(0, 0, period = 1, clock = "00:03"),
            scorer(1, 0, "home", 1, "07:12", "A. Skater"),
            overview(1, 0, period = 1, clock = "07:12"),
            overview(1, 1, period = 2, clock = "03:40"),
            scorer(1, 1, "away", 2, "03:40", "B. Skater"),
            overview(2, 1, period = 2, clock = "12:00"),
            scorer(2, 1, "home", 2, "12:00", "C. Skater"),
        )
        for ((i, data) in history.withIndex()) {
            r.send(data, (27000 + i).toString())
            r.advance(5)
        }
    }

    @Test
    fun theReplayBurstOnConnectDoesNotAnnounceGoalsThatWereAlreadyAnnounced() {
        val r = rig()
        r.overlay(0, 0, period = 1, clock = "03:00")
        r.connect()
        r.send(overview(1, 0, period = 1, clock = "07:12"), "1")
        r.advance(20_000)
        r.send(overview(1, 1, period = 2, clock = "03:40"), "2")
        r.advance(20_000)
        r.send(overview(2, 1, period = 2, clock = "12:00"), "3")
        val before = r.posted.toList()
        assertEquals(listOf<GameEvent>(goal(1, 0, Side.HOME), goal(1, 1, Side.AWAY), goal(2, 1, Side.HOME)), before)

        // the connection drops and comes back: the burst replays the whole game, 0-0 and all
        r.advance(40_000)
        r.open()
        r.advance(100)
        replayHistory(r)
        assertEquals(before, r.posted) // no goal twice, and no "goal disallowed" for the old, lower scores in the burst
        assertTrue(r.feed.isReplaying())
        assertEquals(2, r.tracker.memoryOf(tikId)!!.home)
        assertEquals(1, r.tracker.memoryOf(tikId)!!.away)

        // the stream goes quiet, then speaks live: only the new goal is announced
        r.advance(3_000)
        assertEquals(Outcome.APPLIED, r.send(overview(2, 1, period = 2, clock = "12:30"), "30000").outcome)
        assertFalse(r.feed.isReplaying())
        r.advance(20_000)
        r.send(overview(3, 1, period = 2, clock = "15:53"), "30001")
        assertEquals(before + goal(3, 1, Side.HOME), r.posted)
    }

    @Test
    fun theBurstIsHistoryAlsoForAWatchThatStartsMidGame() {
        // The service starts in the second period. The stream replays 1-0, 1-1, 2-1; the overlay has not answered yet.
        val r = rig()
        r.connect()
        replayHistory(r)
        assertTrue(r.posted.isEmpty())
        assertNull(r.tracker.memoryOf(tikId)) // the stream does not start a game's memory
        // a live message before the overlay baseline: nothing to compare with, nothing announced, still no memory
        r.advance(3_000)
        assertEquals(Outcome.APPLIED, r.send(overview(2, 1, period = 2, clock = "12:30"), "30000").outcome)
        assertNull(r.tracker.memoryOf(tikId))
        assertTrue(r.posted.isEmpty())
        // the overlay's first reading is the baseline, silently; the next goal is the first announcement
        r.overlay(2, 1, period = 2, clock = "12:30")
        assertTrue(r.posted.isEmpty())
        r.advance(20_000)
        r.send(overview(3, 1, period = 2, clock = "15:53"), "30001")
        assertEquals(listOf<GameEvent>(goal(3, 1, Side.HOME)), r.posted)
    }

    @Test
    fun theBurstOfASecondConnectionRepeatsNothing() {
        val r = rig()
        r.overlay(0, 0, period = 1, clock = "03:00")
        r.connect()
        r.send(overview(1, 0, period = 1, clock = "07:12"), "1")
        r.send(overview(1, 1, period = 2, clock = "03:40"), "2")
        r.send(overview(2, 1, period = 2, clock = "12:00"), "3")
        val before = r.posted.toList()
        repeat(3) {
            r.advance(10_000)
            r.open()
            replayHistory(r)
            r.advance(3_000)
            r.send(overview(2, 1, period = 2, clock = "13:00"), (40000 + it).toString())
        }
        assertEquals(before, r.posted)
    }

    @Test
    fun aGoalWhileTheConnectionWasDownIsStillAnnounced() {
        val r = rig()
        r.overlay(0, 0, period = 1, clock = "03:00")
        r.connect()
        r.send(overview(1, 0, period = 1, clock = "07:12"), "1")
        assertEquals(1, r.posted.size)
        // down; the goal happens; back: the burst has it (history, so not announced from there)...
        r.advance(40_000)
        r.open()
        r.advance(50)
        r.send(overview(1, 0, period = 1, clock = "07:12"), "100")
        r.send(overview(2, 0, period = 1, clock = "09:00"), "101")
        assertEquals(1, r.posted.size)
        // ...the first live message that follows carries the score, and the goal is announced then, once
        r.advance(3_000)
        r.send(overview(2, 0, period = 1, clock = "09:30"), "200")
        assertEquals(listOf<GameEvent>(goal(1, 0, Side.HOME), goal(2, 0, Side.HOME)), r.posted)
        r.advance(20_000)
        r.overlay(2, 0, period = 1, clock = "09:50")
        assertEquals(2, r.posted.size)
    }

    @Test
    fun aBurstThatStallsMidwayStillAnnouncesNothing() {
        // The network stalls for 3 s in the middle of the burst, so the rest of it is taken for live messages. The game time of the messages
        // (earlier than the remembered one) keeps them from doing harm: no disallowed goal for 1-1 after 2-1, no goal twice.
        val r = rig()
        r.overlay(0, 0, period = 1, clock = "03:00")
        r.connect()
        r.send(overview(1, 0, period = 1, clock = "07:12"), "1")
        r.send(overview(1, 1, period = 2, clock = "03:40"), "2")
        r.send(overview(2, 1, period = 2, clock = "12:00"), "3")
        val before = r.posted.toList()
        r.advance(40_000)
        r.open()
        r.advance(50)
        r.send(overview(0, 0, period = 1, clock = "00:03"))
        r.send(overview(1, 0, period = 1, clock = "07:12"))
        r.advance(3_000) // the stall: from here on messages count as live
        assertEquals(Outcome.APPLIED, r.send(overview(1, 1, period = 2, clock = "03:40")).outcome)
        r.send(overview(2, 1, period = 2, clock = "12:00"))
        assertEquals(before, r.posted)
        assertEquals(2, r.tracker.memoryOf(tikId)!!.home)
    }

    @Test
    fun theBurstEndsWhenTheStreamHasBeenQuietOrAfterFifteenSeconds() {
        val feed = StreamFeed(GameTracker())
        val games = listOf(game)
        fun event(at: Long) = feed.onEvent(SseEvent(null, null, BASKETBALL), games, at)

        feed.open(1_000L)
        assertTrue(feed.isReplaying())
        // the first message of a slow server (4 s after the connect) is still part of the burst
        event(5_000L)
        assertTrue(feed.isReplaying())
        event(6_000L)
        event(8_499L) // gaps under 2.5 s keep the burst going
        assertTrue(feed.isReplaying())
        event(8_499L + StreamFeed.REPLAY_QUIET_MS) // quiet for 2.5 s: this event is live
        assertFalse(feed.isReplaying())

        // a stream that never pauses is live after 15 s all the same
        feed.open(100_000L)
        var t = 100_000L
        while (t < 100_000L + StreamFeed.REPLAY_MAX_MS - 1_000L) {
            t += 1_000L
            event(t)
            assertTrue(feed.isReplaying())
        }
        event(100_000L + StreamFeed.REPLAY_MAX_MS)
        assertFalse(feed.isReplaying())

        // a device clock that steps back does not leave the feed in replay for ever
        feed.open(500_000L)
        event(400_000L)
        assertFalse(feed.isReplaying())
    }

    // ---- disallowed goals ----

    @Test
    fun aDisallowedGoalIsAnnouncedOnceByWhicheverSourceSeesItFirst() {
        val r = rig()
        r.overlay(0, 0, period = 1, clock = "03:00")
        r.connect()
        r.send(overview(1, 0, period = 1, clock = "07:12"), "1")
        r.advance(20_000)
        r.send(overview(1, 1, period = 2, clock = "03:40"), "2")
        r.advance(20_000)
        r.send(overview(2, 1, period = 2, clock = "12:00"), "3")
        assertEquals(3, r.posted.size)

        r.advance(60_000) // video review; the game clock stands still at 12:00
        val drop = r.send(overview(1, 1, period = 2, clock = "12:00"), "4")
        assertEquals(listOf<GameEvent>(GameEvent.GoalDisallowed(game, 1, 1, Side.HOME)), drop.events)

        // every later message and reading of the same score finds it announced
        r.advance(5_000)
        r.send(overview(1, 1, period = 2, clock = "12:30"), "5")
        r.send(overview(1, 1, period = 2, clock = "12:30"), "5")
        r.advance(15_000)
        r.overlay(1, 1, period = 2, clock = "12:45")
        // the goal is scored again: the same score line, so no second cue; and if it is taken off again, that drop was announced already
        r.advance(30_000)
        r.send(overview(2, 1, period = 2, clock = "14:00"), "6")
        r.send(overview(1, 1, period = 2, clock = "14:20"), "7")
        r.advance(30_000)
        r.overlay(1, 1, period = 2, clock = "15:00")
        assertEquals(
            listOf<GameEvent>(goal(1, 0, Side.HOME), goal(1, 1, Side.AWAY), goal(2, 1, Side.HOME), GameEvent.GoalDisallowed(game, 1, 1, Side.HOME)),
            r.posted,
        )
    }

    @Test
    fun aDisallowedGoalFromTheOverlayIsNotAnnouncedAgainWhenTheStreamRepeatsIt() {
        val r = rig()
        r.overlay(2, 1, period = 2, clock = "12:00")
        r.connect()
        r.send(overview(2, 1, period = 2, clock = "12:05"), "1")
        r.advance(Detector.OVERLAY_LAG_MS + 1_000) // the stream was quiet; the overlay is the one that notices the drop
        r.overlay(1, 1, period = 2, clock = "13:00")
        r.send(overview(1, 1, period = 2, clock = "13:00"), "2")
        r.advance(15_000)
        r.overlay(1, 1, period = 2, clock = "13:20")
        assertEquals(listOf<GameEvent>(GameEvent.GoalDisallowed(game, 1, 1, Side.HOME)), r.posted)
    }

    // ---- ordering inside the stream ----

    @Test
    fun aMessageWithAnOlderSequenceNumberIsIgnored() {
        val r = rig()
        r.overlay(0, 0, period = 1, clock = "03:00")
        r.connect()
        r.send(overview(1, 0, period = 1, clock = "07:12"), "100")
        val late = r.send(overview(0, 0, period = 1, clock = "07:20"), "99")
        assertEquals(Outcome.OUT_OF_ORDER, late.outcome)
        assertEquals(1, r.posted.size)
        // numbers that repeat are let through, and so are messages without a number
        assertEquals(Outcome.APPLIED, r.send(overview(1, 0, period = 1, clock = "07:30"), "100").outcome)
        assertEquals(Outcome.APPLIED, r.send(overview(1, 0, period = 1, clock = "07:40"), null).outcome)
        assertEquals(Outcome.APPLIED, r.send(overview(1, 0, period = 1, clock = "07:50"), "not a number").outcome)
        // a new connection forgets the numbers
        r.advance(10_000)
        r.connect()
        assertEquals(Outcome.APPLIED, r.send(overview(1, 0, period = 1, clock = "08:00"), "5").outcome)
    }

    @Test
    fun aMessageWithAnEarlierGameTimeThanTheRememberedOneIsIgnored() {
        val r = rig()
        r.overlay(2, 1, period = 2, clock = "12:00")
        r.connect()
        // a stale message of the same game (an earlier time, a lower score) is no disallowed goal
        assertEquals(Outcome.APPLIED, r.send(overview(1, 1, period = 2, clock = "03:40"), "1").outcome)
        assertTrue(r.posted.isEmpty())
        assertEquals(2, r.tracker.memoryOf(tikId)!!.home)
    }

    // ---- start and end ----

    @Test
    fun startAndEndFromTheStreamAreAnnouncedOnceAlongsideTheOverlay() {
        val r = Rig(game, T0 - 5 * 60_000L)
        r.overlay(null, null, state = GameState.SCHEDULED, period = 0)
        r.advance(5 * 60_000L)
        r.connect()
        r.send(overview(0, 0, period = 1, clock = "00:02"), "1")
        r.overlay(0, 0, period = 1, clock = "00:05") // the overlay catches up: the start is not announced again
        r.advance(60_000)
        r.send(overview(1, 0, period = 1, clock = "07:12"), "2")
        r.advance(60_000)
        r.send(overview(1, 0, state = "GameEnded", period = 3, clock = "20:00"), "3")
        r.overlay(1, 0, state = GameState.FINISHED, period = 3, clock = "20:00")
        r.overlay(1, 0, state = GameState.FINISHED, period = 3, clock = "20:00", decision = null)
        assertEquals(
            listOf<GameEvent>(GameEvent.MatchStart(game), goal(1, 0, Side.HOME), GameEvent.MatchEnd(game, 1, 0, null)),
            r.posted,
        )
    }

    @Test
    fun anOvertimeEndFromTheStreamCarriesTheDecision() {
        val r = rig()
        r.overlay(1, 1, period = 3, clock = "20:00")
        r.connect()
        r.send(overview(2, 1, state = "GameEnded", period = 4, clock = "02:03"), "1")
        assertEquals(listOf<GameEvent>(goal(2, 1, Side.HOME), GameEvent.MatchEnd(game, 2, 1, "OT")), r.posted)
    }

    // ---- what is not for us ----

    @Test
    fun otherSportsOtherGamesAndBrokenMessagesChangeNothing() {
        val r = rig()
        r.overlay(1, 0, period = 1, clock = "07:12")
        r.connect()
        val before = r.tracker.memory()
        assertEquals(Outcome.NOT_RELEVANT, r.send(BASKETBALL, "1").outcome)
        assertEquals(Outcome.NOT_RELEVANT, r.send(overview(5, 5, uuid = "blr9i7wd72"), "2").outcome)
        assertEquals(Outcome.NOT_RELEVANT, r.send("not json", "3").outcome)
        assertEquals(Outcome.NOT_RELEVANT, r.send("", "4").outcome)
        assertEquals(Outcome.NOT_RELEVANT, r.send(overview(home = null), "5").outcome)
        assertTrue(r.posted.isEmpty())
        assertEquals(before, r.tracker.memory())
    }

    // ---- scorer and game time ----

    @Test
    fun aScorerThatArrivesBeforeTheScoreNamesTheGoal() {
        val r = rig()
        r.overlay(0, 0, period = 1, clock = "03:00")
        r.connect()
        val named = r.send(scorer(1, 0, "home", 2, "15:53", "Janne Kuokkanen"), "1")
        assertEquals(Outcome.SCORER, named.outcome)
        assertTrue(r.posted.isEmpty()) // a scorer message alone announces nothing
        r.send(overview(1, 0, period = 2, clock = "15:53"), "2")
        val goal = r.posted.single() as GameEvent.Goal
        assertEquals("Janne Kuokkanen", goal.scorer)
        assertEquals(2153, goal.gameSec)
        assertEquals("35:53", GameClock.format(goal.gameSec!!))
    }

    @Test
    fun aScorerThatArrivesAfterTheScoreIsHandedBackForTheNotificationToBeUpdated() {
        val r = rig()
        r.overlay(0, 0, period = 1, clock = "03:00")
        r.connect()
        r.send(overview(1, 0, period = 2, clock = "15:53"), "1")
        val first = r.posted.single() as GameEvent.Goal
        assertNull(first.scorer)
        val late = r.send(scorer(1, 0, "home", 2, "15:53", "Janne Kuokkanen"), "2")
        assertEquals(1, r.posted.size) // still one announcement
        assertEquals(ScorerInfo(tikId, 1, 0, Side.HOME, 2153, "Janne Kuokkanen"), late.scorer)
        // and the cache has it for anyone who asks later
        assertEquals("Janne Kuokkanen", (r.feed.enrich(first) as GameEvent.Goal).scorer)
    }

    @Test
    fun aGoalThatOnlyTheOverlaySawIsNamedWhenTheStreamKnowsTheScorer() {
        val r = rig()
        r.overlay(0, 0, period = 1, clock = "03:00")
        r.connect()
        r.send(scorer(1, 0, "home", 1, "07:12", "A. Skater"), "1")
        r.overlay(1, 0, period = 1, clock = "07:50")
        val goal = r.posted.single() as GameEvent.Goal
        assertEquals("A. Skater", goal.scorer)
        assertEquals(432, goal.gameSec)
        // a scorer for the other side of the same score line does not name it
        val other = rig()
        other.overlay(0, 0, period = 1, clock = "03:00")
        other.connect()
        other.send(scorer(1, 0, "away", 1, "07:12", "Wrong Side"), "1")
        other.overlay(1, 0, period = 1, clock = "07:50")
        assertNull((other.posted.single() as GameEvent.Goal).scorer)
    }

    @Test
    fun scorersFromTheReplayBurstAreKeptForTheNames() {
        val r = rig()
        r.overlay(0, 0, period = 1, clock = "03:00")
        r.connect()
        r.advance(40_000)
        r.open()
        replayHistory(r)
        // the replay named the goals; nothing was announced for it
        assertTrue(r.posted.isEmpty())
        assertNotNull(r.feed.enrich(goal(2, 1, Side.HOME)) as? GameEvent.Goal)
        assertEquals("C. Skater", (r.feed.enrich(goal(2, 1, Side.HOME)) as GameEvent.Goal).scorer)
    }

    // ---- persistence ----

    @Test
    fun whatWasAnnouncedSurvivesARestartOfTheService() {
        val r = rig()
        r.overlay(0, 0, period = 1, clock = "03:00")
        r.connect()
        r.send(overview(1, 0, period = 1, clock = "07:12"), "1")
        r.send(overview(1, 1, period = 2, clock = "03:40"), "2")
        r.send(overview(2, 1, period = 2, clock = "12:00"), "3")
        r.advance(60_000)
        r.send(overview(1, 1, period = 2, clock = "12:00"), "4") // disallowed
        assertEquals(4, r.posted.size)

        // the process dies; the memory comes back from the SharedPreferences text
        val restored = MemoryJson.read(MemoryJson.write(r.tracker.memory()))
        assertEquals(r.tracker.memory(), restored)
        val after = Rig(game, r.now + 5 * 60_000L, tracker = GameTracker(restored))
        after.connect()
        // the burst of the new connection, and an overlay that sees the goal come back: all silent, the lines were announced
        replayHistory(after)
        after.advance(3_000)
        after.send(overview(1, 1, period = 2, clock = "13:00"), "50000")
        after.overlay(2, 1, period = 2, clock = "14:00")
        after.send(overview(2, 1, period = 2, clock = "14:00"), "50001")
        after.send(overview(1, 1, period = 2, clock = "14:30"), "50002")
        assertTrue(after.posted.isEmpty())
        // a new goal is announced
        after.send(overview(2, 2, period = 3, clock = "02:00"), "50003")
        assertEquals(listOf<GameEvent>(goal(2, 2, Side.AWAY)), after.posted)
    }
}
