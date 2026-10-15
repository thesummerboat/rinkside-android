package se.jonasp.rinkside.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.jonasp.rinkside.engine.TestData.T0
import se.jonasp.rinkside.engine.TestData.finished
import se.jonasp.rinkside.engine.TestData.game
import se.jonasp.rinkside.engine.TestData.live
import se.jonasp.rinkside.engine.TestData.scheduled

class DetectorTest {
    private val g = game()

    private fun run(vararg steps: Observation, nowMs: Long = T0 + 60_000L): List<GameEvent> {
        val tracker = GameTracker()
        val all = ArrayList<GameEvent>()
        for (s in steps) all.addAll(tracker.process(listOf(g), listOf(s), nowMs))
        return all
    }

    @Test
    fun firstReadingOnlyInitialises() {
        // a game that is already 3-2 when the service first looks: no burst for old goals
        assertTrue(run(live(3, 2, T0 + 1000, period = 3)).isEmpty())
        // a finished game seen for the first time: nothing
        assertTrue(run(finished(4, 1, T0 + 1000)).isEmpty())
        // a scheduled game: nothing
        assertTrue(run(scheduled(T0 + 1000)).isEmpty())
    }

    @Test
    fun puckDropSeenFirstIsAnnouncedWithinTenMinutes() {
        val events = run(live(0, 0, T0 + 1000), nowMs = T0 + 3 * 60_000L)
        assertEquals(listOf<GameEvent>(GameEvent.MatchStart(g)), events)
        // ...but not when the first look comes long after the start
        assertTrue(run(live(0, 0, T0 + 1000), nowMs = T0 + 40 * 60_000L).isEmpty())
        // ...and not for 0-0 in a later period
        assertTrue(run(live(0, 0, T0 + 1000, period = 2), nowMs = T0 + 3 * 60_000L).isEmpty())
    }

    @Test
    fun startThenGoalsThenDisallowedThenEnd() {
        val events = run(
            scheduled(T0),
            live(0, 0, T0 + 1_000),
            live(1, 0, T0 + 2_000),
            live(2, 0, T0 + 3_000),
            live(1, 0, T0 + 4_000), // disallowed
            live(1, 1, T0 + 5_000, period = 3),
            finished(2, 1, T0 + 6_000, decision = "OT", period = 4),
        )
        assertEquals(
            listOf<GameEvent>(
                GameEvent.MatchStart(g),
                GameEvent.Goal(g, 1, 0, Side.HOME),
                GameEvent.Goal(g, 2, 0, Side.HOME),
                GameEvent.GoalDisallowed(g, 1, 0, Side.HOME),
                GameEvent.Goal(g, 1, 1, Side.AWAY),
                GameEvent.Goal(g, 2, 1, Side.HOME),
                GameEvent.MatchEnd(g, 2, 1, "OT"),
            ),
            events,
        )
    }

    @Test
    fun oneNotificationPerGoalWhenReadingsAreMissed() {
        val events = run(live(1, 0, T0 + 1_000, period = 1), live(2, 1, T0 + 2_000, period = 2))
        assertEquals(
            listOf<GameEvent>(GameEvent.Goal(g, 2, 0, Side.HOME), GameEvent.Goal(g, 2, 1, Side.AWAY)),
            events,
        )
    }

    @Test
    fun aLongMissedStretchIsOneGoalEvent() {
        val events = run(live(0, 0, T0 + 1_000), live(4, 2, T0 + 2_000, period = 3))
        assertEquals(listOf<GameEvent>(GameEvent.Goal(g, 4, 2, Side.HOME)), events.drop(0).filterIsInstance<GameEvent.Goal>())
    }

    @Test
    fun theSameReadingTwiceChangesNothing() {
        val events = run(scheduled(T0), live(1, 0, T0 + 1_000), live(1, 0, T0 + 1_000), live(1, 0, T0 + 2_000))
        assertEquals(2, events.size) // start + one goal
    }

    @Test
    fun anOlderReadingNeverTurnsAGoalBack() {
        // two Worker instances answer out of order: the older 0-0 must not look like a disallowed goal
        val events = run(scheduled(T0), live(1, 0, T0 + 5_000), live(0, 0, T0 + 4_000), live(1, 0, T0 + 6_000))
        assertEquals(listOf<GameEvent>(GameEvent.MatchStart(g), GameEvent.Goal(g, 1, 0, Side.HOME)), events)
    }

    @Test
    fun aGameNeverGoesBackwards() {
        val events = run(scheduled(T0), live(0, 0, T0 + 1_000), scheduled(T0 + 2_000), live(0, 0, T0 + 3_000))
        assertEquals(listOf<GameEvent>(GameEvent.MatchStart(g)), events)
    }

    @Test
    fun nothingAfterTheEnd() {
        val events = run(live(1, 0, T0), finished(2, 0, T0 + 1_000), live(2, 0, T0 + 2_000), finished(3, 0, T0 + 3_000))
        assertEquals(listOf<GameEvent>(GameEvent.Goal(g, 2, 0, Side.HOME), GameEvent.MatchEnd(g, 2, 0, null)), events)
    }

    @Test
    fun finishedWithoutHavingSeenItLiveGivesOnlyTheEnd() {
        val events = run(scheduled(T0), finished(5, 3, T0 + 1_000))
        assertEquals(listOf<GameEvent>(GameEvent.MatchEnd(g, 5, 3, null)), events)
    }

    @Test
    fun shootoutScoresAreNoGoals() {
        val events = run(
            live(2, 2, T0, period = 4),
            live(2, 2, T0 + 1_000, period = 5, shootout = true),
            live(3, 2, T0 + 2_000, period = 5, shootout = true), // a shootout goal
            finished(3, 2, T0 + 3_000, decision = "SO", period = 5),
        )
        assertEquals(listOf<GameEvent>(GameEvent.MatchEnd(g, 3, 2, "SO")), events)
    }

    @Test
    fun theShootoutWinnerOnTheFinalReadingIsNoGoalEither() {
        // NHL: the score stays 2-2 through the shootout and the final reading is 3-2
        val events = run(live(2, 2, T0, period = 3), finished(3, 2, T0 + 1_000, decision = "SO", period = 5))
        assertEquals(listOf<GameEvent>(GameEvent.MatchEnd(g, 3, 2, "SO")), events)
    }

    @Test
    fun anOvertimeWinnerIsAGoalAndTheEnd() {
        val events = run(live(2, 2, T0, period = 4), finished(2, 3, T0 + 1_000, decision = "OT", period = 4))
        assertEquals(listOf<GameEvent>(GameEvent.Goal(g, 2, 3, Side.AWAY), GameEvent.MatchEnd(g, 2, 3, "OT")), events)
    }

    @Test
    fun aGoalIsAnnouncedOncePerScoreLine() {
        // one cue per score line, like the web app: a goal that is taken off and scored again does not blink twice
        val events = run(live(0, 0, T0), live(1, 0, T0 + 1_000), live(0, 0, T0 + 2_000), live(1, 0, T0 + 3_000))
        assertEquals(
            listOf<GameEvent>(GameEvent.Goal(g, 1, 0, Side.HOME), GameEvent.GoalDisallowed(g, 0, 0, Side.HOME)),
            events.filter { it !is GameEvent.MatchStart },
        )
    }

    @Test
    fun aDisallowedGoalIsAnnouncedOncePerDrop() {
        val events = run(
            live(0, 0, T0),
            live(1, 0, T0 + 1_000), // announced
            live(0, 0, T0 + 2_000), // disallowed
            live(1, 0, T0 + 3_000), // back: the line 1-0 was announced
            live(0, 0, T0 + 4_000), // the same drop again
            live(0, 1, T0 + 5_000), // a different line: announced
        )
        assertEquals(
            listOf<GameEvent>(GameEvent.Goal(g, 1, 0, Side.HOME), GameEvent.GoalDisallowed(g, 0, 0, Side.HOME), GameEvent.Goal(g, 0, 1, Side.AWAY)),
            events.filter { it !is GameEvent.MatchStart },
        )
        // a score that was already there when the watch began was never announced, so its first goal-back is news
        val late = run(live(1, 0, T0), live(0, 0, T0 + 1_000), live(1, 0, T0 + 2_000))
        assertEquals(listOf<GameEvent>(GameEvent.GoalDisallowed(g, 0, 0, Side.HOME), GameEvent.Goal(g, 1, 0, Side.HOME)), late)
    }

    @Test
    fun theAwaySideHasOneCuePerLineAndPerDropToo() {
        val events = run(
            live(0, 0, T0),
            live(0, 1, T0 + 1_000), // announced
            live(0, 0, T0 + 2_000), // disallowed
            live(0, 1, T0 + 3_000), // back: the line 0-1 was announced
            live(0, 0, T0 + 4_000), // the same drop again
        )
        assertEquals(
            listOf<GameEvent>(GameEvent.Goal(g, 0, 1, Side.AWAY), GameEvent.GoalDisallowed(g, 0, 0, Side.AWAY)),
            events.filter { it !is GameEvent.MatchStart },
        )
    }

    @Test
    fun theAnnouncedLinesAreRememberedAcrossARestart() {
        val first = GameTracker()
        first.process(listOf(g), listOf(live(0, 0, T0)), T0 + 60_000L)
        first.process(listOf(g), listOf(live(1, 0, T0 + 1_000)), T0 + 61_000L)
        first.process(listOf(g), listOf(live(0, 0, T0 + 2_000)), T0 + 62_000L)
        val restored = GameTracker(MemoryJson.read(MemoryJson.write(first.memory())))
        assertEquals(setOf("1-0"), restored.memory().getValue("g1").goalLines)
        assertEquals(setOf("1-0>0-0"), restored.memory().getValue("g1").dropLines)
        assertTrue(restored.process(listOf(g), listOf(live(1, 0, T0 + 3_000)), T0 + 63_000L).isEmpty())
        assertEquals(1, restored.process(listOf(g), listOf(live(1, 1, T0 + 4_000)), T0 + 64_000L).size)
    }

    @Test
    fun aMissedStretchMarksOnlyItsFinalLine() {
        val tracker = GameTracker()
        tracker.process(listOf(g), listOf(live(0, 0, T0)), T0 + 60_000L)
        tracker.process(listOf(g), listOf(live(4, 2, T0 + 1_000, period = 3)), T0 + 61_000L)
        assertEquals(setOf("4-2"), tracker.memory().getValue("g1").goalLines)
    }

    @Test
    fun theMemoryKeepsOnlyTheLatestLines() {
        val tracker = GameTracker()
        tracker.process(listOf(g), listOf(live(0, 0, T0)), T0 + 60_000L)
        for (n in 1..(Detector.MAX_CUES + 20)) {
            val h = n / 2 + n % 2
            val a = n / 2
            tracker.process(listOf(g), listOf(live(minOf(h, 99), minOf(a, 99), T0 + n * 1_000L)), T0 + 60_000L + n * 1_000L)
        }
        assertTrue(tracker.memory().getValue("g1").goalLines.size <= Detector.MAX_CUES)
    }

    // ---- readings from the stream ----

    private fun stream(h: Int, a: Int, at: Long, gameSec: Int? = null, state: GameState = GameState.LIVE, period: Int = 1) =
        Observation("g1", state, h, a, period, null, false, at, gameSec, fromStream = true)

    private fun overlay(h: Int, a: Int, at: Long, gameSec: Int? = null) = Observation("g1", GameState.LIVE, h, a, 1, null, false, at, gameSec)

    @Test
    fun theStreamNeverStartsAGame() {
        val tracker = GameTracker()
        assertTrue(tracker.process(listOf(g), listOf(stream(1, 0, T0)), T0 + 60_000L).isEmpty())
        assertTrue(tracker.memory().isEmpty())
        // the overlay's first reading is the baseline, whatever the stream said before
        assertTrue(tracker.process(listOf(g), listOf(overlay(2, 0, T0)), T0 + 61_000L).isEmpty())
        assertEquals(1, tracker.process(listOf(g), listOf(stream(3, 0, T0 + 62_000L)), T0 + 62_000L).size)
    }

    @Test
    fun anOverlayWithinTheLagWindowAfterTheStreamCannotLowerTheScore() {
        val tracker = GameTracker()
        tracker.process(listOf(g), listOf(overlay(0, 0, T0)), T0 + 60_000L)
        assertEquals(1, tracker.process(listOf(g), listOf(stream(1, 0, T0 + 61_000L)), T0 + 61_000L).size)
        // the Worker's reading is older than the stream's message, whatever the clocks say
        assertTrue(tracker.process(listOf(g), listOf(overlay(0, 0, T0 + 5_000)), T0 + 70_000L).isEmpty())
        assertEquals(1, tracker.memory().getValue("g1").home)
        // ...but the same reading after the window is a disallowed goal
        val late = tracker.process(listOf(g), listOf(overlay(0, 0, T0 + 6_000)), T0 + 61_000L + Detector.OVERLAY_LAG_MS)
        assertEquals(listOf<GameEvent>(GameEvent.GoalDisallowed(g, 0, 0, Side.HOME)), late)
    }

    @Test
    fun aStreamReadingWithAnEarlierGameTimeIsStale() {
        val tracker = GameTracker()
        tracker.process(listOf(g), listOf(overlay(2, 0, T0, gameSec = 1500)), T0 + 60_000L)
        assertTrue(tracker.process(listOf(g), listOf(stream(1, 0, T0 + 61_000L, gameSec = 900)), T0 + 61_000L).isEmpty())
        assertEquals(2, tracker.memory().getValue("g1").home)
        // the same game time is not stale: the clock stands still during a review
        val drop = tracker.process(listOf(g), listOf(stream(1, 0, T0 + 62_000L, gameSec = 1500)), T0 + 62_000L)
        assertEquals(listOf<GameEvent>(GameEvent.GoalDisallowed(g, 1, 0, Side.HOME)), drop)
    }

    @Test
    fun theStreamAndTheOverlayKeepTheirOwnTimes() {
        val tracker = GameTracker()
        tracker.process(listOf(g), listOf(overlay(0, 0, T0)), T0 + 60_000L)
        tracker.process(listOf(g), listOf(stream(1, 0, T0 + 3_600_000L)), T0 + 3_600_000L) // a device clock an hour ahead of the Worker's
        val m = tracker.memory().getValue("g1")
        assertEquals(T0, m.readAtMs) // the overlay's time is not moved by the stream
        assertEquals(T0 + 3_600_000L, m.streamAtMs)
        // an overlay reading with the Worker's own (older) time is still read: the clocks are not compared
        assertEquals(1, tracker.process(listOf(g), listOf(overlay(1, 1, T0 + 1_000)), T0 + 3_700_000L).size)
    }

    @Test
    fun aScoreThatMovesBothWaysReportsTheCorrectionFirst() {
        val steps = Detector.scoreEvents(g, 2, 1, 1, 2)
        assertEquals(
            listOf<GameEvent>(GameEvent.GoalDisallowed(g, 1, 1, Side.HOME), GameEvent.Goal(g, 1, 2, Side.AWAY)),
            steps,
        )
    }

    @Test
    fun persistedMemoryStopsRepeatsAfterARestart() {
        val first = GameTracker()
        first.process(listOf(g), listOf(live(1, 0, T0)), T0 + 60_000L)
        val restored = GameTracker(MemoryJson.read(MemoryJson.write(first.memory())))
        // the same reading again after the restart: nothing new
        assertTrue(restored.process(listOf(g), listOf(live(1, 0, T0 + 15_000)), T0 + 75_000L).isEmpty())
        // a new goal after the restart is still announced
        assertEquals(1, restored.process(listOf(g), listOf(live(2, 0, T0 + 30_000)), T0 + 90_000L).size)
    }
}
