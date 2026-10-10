package se.jonasp.rinkside.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.jonasp.rinkside.engine.TestData.T0

class WatchPlanTest {
    private val min = 60_000L
    private val game = TestData.shl // starts at T0

    @Test
    fun windowOpensTenMinutesBeforeTheStart() {
        assertFalse(WatchPlan.isActive(game, null, T0 - 11 * min))
        assertTrue(WatchPlan.isActive(game, null, T0 - 10 * min))
        assertTrue(WatchPlan.isActive(game, null, T0 + 3 * 60 * min))
    }

    @Test
    fun aGameThatWasSeenLiveIsWatchedUntilFourHoursAfterTheStart() {
        val live = GameMemory(GameState.LIVE, 1, 0, T0, false)
        assertTrue(WatchPlan.isActive(game, live, T0 + 4 * 60 * min))
        assertFalse(WatchPlan.isActive(game, live, T0 + 4 * 60 * min + 1))
    }

    @Test
    fun aGameThatNeverStartedIsGivenUpAfterAnHour() {
        val scheduled = GameMemory(GameState.SCHEDULED, null, null, T0, false)
        assertTrue(WatchPlan.isActive(game, scheduled, T0 + 60 * min))
        assertFalse(WatchPlan.isActive(game, scheduled, T0 + 61 * min))
    }

    @Test
    fun aGameNobodyHasSeenYetIsStillWatchedMidGame() {
        // "Starta bevakning nu" in the second period: no memory, 70 minutes after the start
        assertTrue(WatchPlan.isActive(game, null, T0 + 70 * min))
    }

    @Test
    fun finishedAndPostponedGamesAreDone() {
        assertFalse(WatchPlan.isActive(game, GameMemory(GameState.FINISHED, 2, 1, T0, false), T0 + 90 * min))
        assertFalse(WatchPlan.isActive(game, GameMemory(GameState.POSTPONED, null, null, T0, false), T0 + 5 * min))
    }

    @Test
    fun unknownLeaguesAndGamesWithoutIdsAreNotWatchable() {
        assertFalse(WatchPlan.isWatchable(game.copy(league = "liiga")))
        assertFalse(WatchPlan.isWatchable(game.copy(shlId = null)))
        assertFalse(WatchPlan.isWatchable(TestData.nhl.copy(nhlId = null)))
        assertTrue(WatchPlan.isWatchable(TestData.ha))
    }

    @Test
    fun nextTriggerIsTheEarliestStartMinusTenMinutes() {
        val later = game.copy(id = "shl-later", startMs = T0 + 24 * 60 * min)
        val finished = game.copy(id = "shl-done", startMs = T0 + 60 * min)
        val memory = mapOf("shl-done" to GameMemory(GameState.FINISHED, 1, 0, T0, false))
        val now = T0 - 5 * 60 * min
        assertEquals(T0 - 10 * min, WatchPlan.nextTriggerMs(listOf(later, game, finished), memory, now))
        assertNull(WatchPlan.nextTriggerMs(emptyList(), emptyMap(), now))
        // a game that is over (four hours past its start) is not waited for
        assertNull(WatchPlan.nextTriggerMs(listOf(game), emptyMap(), T0 + 5 * 60 * min))
    }

    @Test
    fun nextTriggerIsNowWhileAGameIsOn() {
        val now = T0 + 20 * min
        assertEquals(now, WatchPlan.nextTriggerMs(listOf(game), emptyMap(), now))
    }

    @Test
    fun theServiceAndTheSchedulerAgree() {
        // whenever activeGames is empty, nextTrigger is either null or in the future: no stop/restart loop
        val games = listOf(game, TestData.nhl, TestData.ha, TestData.ettan)
        var t = T0 - 3 * 60 * min
        while (t < T0 + 40 * 60 * min) {
            val active = WatchPlan.activeGames(games, emptyMap(), t)
            val next = WatchPlan.nextTriggerMs(games, emptyMap(), t)
            if (active.isEmpty()) assertTrue("at $t", next == null || next > t) else assertEquals(t, next)
            t += 7 * min
        }
    }

    @Test
    fun forcedWatchingReachesGamesLaterToday() {
        val now = T0 - 5 * 60 * min
        assertFalse(WatchPlan.isActive(game, null, now))
        assertTrue(WatchPlan.isActive(game, null, now, WatchPlan.FORCE_LEAD_MS))
    }

    @Test
    fun onlyTheOverlaysTheActiveGamesNeedAreAskedFor() {
        assertEquals(setOf(Overlay.NHL_TODAY), WatchPlan.overlaysFor(listOf(TestData.nhl), T0))
        assertEquals(setOf(Overlay.SWE_TODAY), WatchPlan.overlaysFor(listOf(TestData.ha, TestData.ettan), T0))
        // SHL: shl-today, and the schedule status as a fallback once the game should have started
        assertEquals(setOf(Overlay.SHL_TODAY), WatchPlan.overlaysFor(listOf(game), T0 - 5 * min))
        assertEquals(setOf(Overlay.SHL_TODAY, Overlay.SHL_SCHEDULE), WatchPlan.overlaysFor(listOf(game), T0 + 5 * min))
        assertEquals(
            setOf(Overlay.NHL_TODAY, Overlay.SHL_TODAY, Overlay.SWE_TODAY),
            WatchPlan.overlaysFor(listOf(TestData.nhl, game, TestData.ha), T0 - 5 * min),
        )
        assertTrue(WatchPlan.overlaysFor(emptyList(), T0).isEmpty())
    }

    @Test
    fun pollingPaceAndBackoff() {
        assertEquals(15_000L, WatchPlan.pollIntervalMs(Overlay.NHL_TODAY))
        assertEquals(60_000L, WatchPlan.pollIntervalMs(Overlay.SHL_SCHEDULE))
        assertEquals(30_000L, WatchPlan.backoffMs(1))
        assertEquals(60_000L, WatchPlan.backoffMs(2))
        assertEquals(120_000L, WatchPlan.backoffMs(3))
        assertEquals(120_000L, WatchPlan.backoffMs(50))
    }
}
