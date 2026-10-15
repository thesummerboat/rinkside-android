package se.jonasp.rinkside.engine

// Pure Kotlin model of the detection engine. Nothing in this package may import android.* (the unit tests run on the plain JVM).

/** State of a game as the overlays report it. Postponed ranks with scheduled: a game never goes back down the ranking. */
enum class GameState(val rank: Int) {
    SCHEDULED(1),
    POSTPONED(1),
    LIVE(2),
    FINISHED(3),
}

enum class Side { HOME, AWAY }

/**
 * One followed game, as read from the web app's D.matches by the page script (assets/read-favourites.js).
 *
 * @param id         the web app's match id: nhl-2026020040, shl-yfdggwdkd6, ha-9001, et-1113403 (also the #match/<id> route)
 * @param league     the web app's league id: nhl, shl, ha, ettan
 * @param homeId     the web app's team id (SHL: shl-saik, NHL: det, HA: ha-aik, Ettan: et-119104)
 * @param homeName   short display name used in notifications
 * @param homeCode   team.code of the web app (NHL abbreviation, SHL club code); empty when unknown
 * @param nhlId      NHL game id (overlay nhl-today games[].id); only for league nhl
 * @param shlId      SHL game uuid (overlay shl-today games[].id); only for league shl
 * @param playoff    gameType 3 (decides what period 5 means: shootout in the regular season, overtime in the playoffs)
 */
data class WatchedGame(
    val id: String,
    val league: String,
    val startMs: Long,
    val homeId: String,
    val awayId: String,
    val homeName: String,
    val awayName: String,
    val homeCode: String = "",
    val awayCode: String = "",
    val nhlId: Long? = null,
    val shlId: String? = null,
    val playoff: Boolean = false,
)

/** What one overlay says about one followed game. home/away are null while the game has no score (scheduled, postponed). */
data class Observation(
    val gameId: String,
    val state: GameState,
    val home: Int?,
    val away: Int?,
    val period: Int,
    /** "OT", "SO" or null; only meaningful for a finished game. */
    val decision: String?,
    /** True while a regular-season shootout is being played: its scores are not goals. */
    val shootout: Boolean,
    /**
     * When the server read the game (overlay fetchedAt, or the entry's own "at"); used so an older reading never wins.
     * For a reading from the live stream ([fromStream]) it is the device time the message arrived: the two clocks are never compared.
     */
    val readAtMs: Long,
    /** Elapsed game time in seconds ((period - 1) * 1200 + the period clock), when the source gave a clock. Orders readings of one game across sources. */
    val gameSec: Int? = null,
    /** True for a reading from the SHL live stream, false for one from an overlay. */
    val fromStream: Boolean = false,
)

/**
 * What the engine remembers per game between two readings (persisted by the app so a service restart repeats nothing).
 *
 * @param readAtMs    the newest overlay reading that was applied (the Worker's clock)
 * @param streamAtMs  the device time of the newest stream reading that was applied; null while the stream has not spoken for this game
 * @param gameSec     the newest elapsed game time seen in either source
 * @param goalLines   the score lines ("2-1") a Goal was announced for: one cue per score line, whichever source saw it first
 * @param dropLines   the score drops ("2-1>1-1") a GoalDisallowed was announced for
 */
data class GameMemory(
    val state: GameState,
    val home: Int?,
    val away: Int?,
    val readAtMs: Long,
    val shootout: Boolean,
    val streamAtMs: Long? = null,
    val gameSec: Int? = null,
    val goalLines: Set<String> = emptySet(),
    val dropLines: Set<String> = emptySet(),
)

sealed class GameEvent {
    abstract val game: WatchedGame

    data class MatchStart(override val game: WatchedGame) : GameEvent()

    /**
     * A side scored; [home] and [away] are the new score. [scorer] and [gameSec] (elapsed game time) are filled in when the live stream
     * has told who scored; the overlays do not carry them.
     */
    data class Goal(
        override val game: WatchedGame,
        val home: Int,
        val away: Int,
        val side: Side,
        val scorer: String? = null,
        val gameSec: Int? = null,
    ) : GameEvent() {
        /** The score line this goal makes, the key of "one cue per score line". */
        val line: String get() = "$home-$away"
    }

    /** A side's score went down; [home] and [away] are the new score. */
    data class GoalDisallowed(override val game: WatchedGame, val home: Int, val away: Int, val side: Side) : GameEvent()

    data class MatchEnd(override val game: WatchedGame, val home: Int, val away: Int, val decision: String?) : GameEvent()
}

/** The overlays of the Worker (GET <base>/api/live/<path>.json) the app reads. */
enum class Overlay(val path: String) {
    NHL_TODAY("nhl-today"),
    SHL_TODAY("shl-today"),
    SWE_TODAY("swe-today"),
    SHL_SCHEDULE("shl-schedule-status"),
}
