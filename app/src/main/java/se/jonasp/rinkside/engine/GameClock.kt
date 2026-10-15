package se.jonasp.rinkside.engine

/**
 * Game time. The SHL sends the period and the time played in that period ("15:53" in period 2); the app orders readings of one game
 * and writes goal times with the elapsed time of the whole game: period 2, 15:53 is 35:53.
 */
object GameClock {
    /** Length of a period; a playoff overtime period is as long, a regular-season overtime is shorter and never reaches this. */
    const val PERIOD_SEC = 1200

    private val CLOCK = Regex("^\\d{1,2}:[0-5]\\d$")

    /** "mm:ss" or "m:ss" as seconds, null for anything else (the same clock shape the web app accepts). */
    fun seconds(clock: String?): Int? {
        if (clock == null || !CLOCK.matches(clock)) return null
        val colon = clock.indexOf(':')
        return clock.substring(0, colon).toInt() * 60 + clock.substring(colon + 1).toInt()
    }

    /** Elapsed game time in seconds, or null when the period or the clock is missing, or the clock is longer than a period. */
    fun elapsed(period: Int, clock: String?): Int? {
        if (period < 1) return null
        val inPeriod = seconds(clock) ?: return null
        if (inPeriod > PERIOD_SEC) return null
        return (period - 1) * PERIOD_SEC + inPeriod
    }

    /** 2153 -> "35:53", 3801 -> "63:21". */
    fun format(elapsedSec: Int): String {
        val sec = if (elapsedSec < 0) 0 else elapsedSec
        return (sec / 60).toString() + ":" + (sec % 60).toString().padStart(2, '0')
    }
}
