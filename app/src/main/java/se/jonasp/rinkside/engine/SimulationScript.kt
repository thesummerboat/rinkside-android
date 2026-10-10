package se.jonasp.rinkside.engine

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/**
 * Built-in test data for "Simulera en match": a scripted SHL game as `shl-today` overlay snapshots, one per step.
 * The steps go through the real parser and the real engine (OverlayParser + GameTracker), so the simulated notifications
 * exercise exactly the code that real ones use. All data here is synthetic.
 *
 *   step 0  not started                -> (nothing: first reading)
 *   step 1  live 0-0, period 1          -> match start
 *   step 2  live 1-0                    -> goal
 *   step 3  live 2-0                    -> goal
 *   step 4  live 1-0                    -> goal disallowed
 *   step 5  live 1-1                    -> goal
 *   step 6  overtime 1-1                -> (nothing)
 *   step 7  game ended 2-1, overtime    -> goal, match end (OT)
 */
object SimulationScript {
    const val STEP_MS = 3_000L
    const val STEP_COUNT = 8
    const val GAME_ID = "sim-shl"
    private const val SHL_ID = "simulation001"

    fun game(startMs: Long): WatchedGame = WatchedGame(
        id = GAME_ID,
        league = "shl",
        startMs = startMs,
        homeId = "shl-saik",
        awayId = "shl-rbk",
        homeName = "Skellefteå",
        awayName = "Rögle",
        homeCode = "SAIK",
        awayCode = "RBK",
        shlId = SHL_ID,
    )

    private class Step(val ls: String, val score: IntArray?, val period: Int, val clock: String)

    private val steps = listOf(
        Step("NotStarted", null, 0, ""),
        Step("Ongoing", intArrayOf(0, 0), 1, "00:05"),
        Step("Ongoing", intArrayOf(1, 0), 1, "07:41"),
        Step("Ongoing", intArrayOf(2, 0), 2, "03:12"),
        Step("Ongoing", intArrayOf(1, 0), 2, "04:30"),
        Step("Ongoing", intArrayOf(1, 1), 3, "11:08"),
        Step("Overtime", intArrayOf(1, 1), 4, "01:20"),
        Step("GameEnded", intArrayOf(2, 1), 4, "02:03"),
    )

    /** The text of the `shl-today` overlay as the Worker would serve it at step [step] (reading time = base + step * STEP_MS). */
    fun overlayJson(step: Int, baseMs: Long): String {
        val s = steps[step]
        val at = isoSeconds(baseMs + step * STEP_MS)
        val e = JSONObject()
        e.put("id", SHL_ID)
        e.put("home", "SAIK")
        e.put("away", "RBK")
        e.put("start", isoSeconds(baseMs))
        e.put("ls", s.ls)
        if (s.score == null) {
            e.put("score", JSONObject.NULL)
        } else {
            e.put("score", JSONArray().put(s.score[0]).put(s.score[1]))
        }
        e.put("period", s.period)
        e.put("clock", s.clock)
        e.put("at", at)
        val o = JSONObject()
        o.put("v", 1)
        o.put("kind", "shl-today")
        o.put("source", "www.shl.se")
        o.put("fetchedAt", at)
        o.put("games", JSONArray().put(e))
        o.put("skipped", 0)
        return o.toString()
    }

    /** Runs one step through the parser and the tracker; call with increasing [step] on one [tracker]. */
    fun runStep(tracker: GameTracker, game: WatchedGame, step: Int, baseMs: Long): List<GameEvent> {
        val text = overlayJson(step, baseMs)
        val obs = OverlayParser.parseShlToday(text, listOf(game))
        return tracker.process(listOf(game), obs, baseMs + step * STEP_MS)
    }

    private fun isoSeconds(ms: Long): String = Instant.ofEpochSecond(ms / 1000).toString()
}
