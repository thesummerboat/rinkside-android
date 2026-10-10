package se.jonasp.rinkside

import android.content.Context
import se.jonasp.rinkside.engine.GameTracker
import se.jonasp.rinkside.engine.SimulationScript

/**
 * "Simulera en match": plays the scripted game of [SimulationScript] through the real parser and the real detection engine,
 * one step every 3 seconds, and posts the real notifications. Nothing is written to the app's memory of real games.
 */
object Simulator {
    @Volatile
    var running: Boolean = false
        private set

    /** [onProgress] is called from the simulation thread with (step starting at 1, steps in total, finished). */
    fun start(ctx: Context, onProgress: (Int, Int, Boolean) -> Unit) {
        if (running) return
        running = true
        val app = ctx.applicationContext
        val thread = Thread({
            try {
                val base = System.currentTimeMillis()
                val game = SimulationScript.game(base)
                val tracker = GameTracker()
                Store.log(app, "Simulation started")
                for (step in 0 until SimulationScript.STEP_COUNT) {
                    onProgress(step + 1, SimulationScript.STEP_COUNT, false)
                    val events = SimulationScript.runStep(tracker, game, step, base)
                    for (event in events) {
                        Store.log(app, "Simulation: " + Notifier.logLine(event))
                        Notifier.post(app, event)
                    }
                    if (step < SimulationScript.STEP_COUNT - 1) Thread.sleep(SimulationScript.STEP_MS)
                }
            } catch (e: Exception) {
                Store.log(app, "Simulation failed: " + (e.message ?: e.javaClass.simpleName))
            } finally {
                running = false
                onProgress(SimulationScript.STEP_COUNT, SimulationScript.STEP_COUNT, true)
            }
        }, "rinkside-simulation")
        thread.start()
    }
}
