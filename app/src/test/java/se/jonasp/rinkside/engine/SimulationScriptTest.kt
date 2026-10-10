package se.jonasp.rinkside.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class SimulationScriptTest {
    @Test
    fun theBuiltInGameProducesTheExpectedNotifications() {
        val base = 1_791_648_000_000L
        val game = SimulationScript.game(base)
        val tracker = GameTracker()
        val perStep = (0 until SimulationScript.STEP_COUNT).map { SimulationScript.runStep(tracker, game, it, base) }
        assertEquals(
            listOf(
                emptyList<GameEvent>(), // not started: the first reading only initialises
                listOf<GameEvent>(GameEvent.MatchStart(game)),
                listOf<GameEvent>(GameEvent.Goal(game, 1, 0, Side.HOME)),
                listOf<GameEvent>(GameEvent.Goal(game, 2, 0, Side.HOME)),
                listOf<GameEvent>(GameEvent.GoalDisallowed(game, 1, 0, Side.HOME)),
                listOf<GameEvent>(GameEvent.Goal(game, 1, 1, Side.AWAY)),
                emptyList<GameEvent>(), // overtime starts: nothing to say
                listOf<GameEvent>(GameEvent.Goal(game, 2, 1, Side.HOME), GameEvent.MatchEnd(game, 2, 1, "OT")),
            ),
            perStep,
        )
    }

    @Test
    fun theSimulationIsOverTheRealParserNotAShortcut() {
        val base = 1_791_648_000_000L
        val text = SimulationScript.overlayJson(2, base)
        val obs = OverlayParser.parseShlToday(text, listOf(SimulationScript.game(base)))
        assertEquals(1, obs.size)
        assertEquals(GameState.LIVE, obs[0].state)
        assertEquals(1, obs[0].home)
    }
}
