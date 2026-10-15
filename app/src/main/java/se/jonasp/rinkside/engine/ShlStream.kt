package se.jonasp.rinkside.engine

import java.io.Reader
import org.json.JSONObject

// The SHL's public live stream (Server-Sent Events), as far as the app uses it. Pure Kotlin like the rest of this package: the connection
// itself is LiveStream.kt in the app package. The web app's reader (dist/shl-live.js, shlLiveParse) is the reference for the shapes.

/** One event as the wire format delivers it: the `id:` in force, the `event:` name if any, and the `data:` lines joined with a line feed. */
class SseEvent(val id: String?, val event: String?, val data: String)

/**
 * Reads lines from an event stream. A line ends with LF, CR or CRLF. A line longer than [maxLine] characters is cut at that length and
 * reported as truncated (the rest of it is skipped), so one huge message of another sport cannot fill the memory or stop the stream.
 * A last line that the connection ended in the middle of is dropped, as the SSE rules say.
 */
class SseLineReader(private val input: Reader, private val maxLine: Int = MAX_LINE) {
    class Line(val text: String, val truncated: Boolean)

    private var skipLf = false

    /** The next line, or null when the stream has ended. Blocks until a line is complete. */
    fun readLine(): Line? {
        val text = StringBuilder()
        var truncated = false
        while (true) {
            val c = input.read()
            if (c < 0) return null
            if (skipLf) {
                skipLf = false
                if (c == '\n'.code) continue
            }
            if (c == '\n'.code) return Line(text.toString(), truncated)
            if (c == '\r'.code) {
                skipLf = true
                return Line(text.toString(), truncated)
            }
            if (text.length < maxLine) text.append(c.toChar()) else truncated = true
        }
    }

    companion object {
        const val MAX_LINE = 64 * 1024
    }
}

/**
 * Turns lines into events (https://html.spec.whatwg.org/multipage/server-sent-events.html#event-stream-interpretation):
 * `data:` lines are joined with a line feed, a blank line ends the event, a line that starts with a colon is a comment, one space after the
 * colon of a field is dropped, a field without a colon has an empty value, `id:` stays in force for the following events, `retry:` is ignored.
 * An event with no `data:` is not delivered. An event that grew beyond [MAX_EVENT_CHARS], or had a truncated line, is discarded.
 */
class SseDecoder {
    private val data = StringBuilder()
    private var hasData = false
    private var event: String? = null
    private var lastId: String? = null
    private var broken = false
    private var atStart = true

    /** How many events were discarded for being too large. */
    var discarded: Int = 0
        private set

    /** Feeds one line (without its terminator); returns the event that this line completed, if any. */
    fun line(raw: String, truncated: Boolean = false): SseEvent? {
        var line = raw
        if (atStart) {
            atStart = false
            if (line.startsWith("﻿")) line = line.substring(1)
        }
        if (line.isEmpty()) return dispatch()
        if (line[0] == ':') return null
        if (truncated) broken = true
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "data" -> {
                hasData = true
                if (data.length + value.length + 1 > MAX_EVENT_CHARS) broken = true else data.append(value).append('\n')
            }
            "event" -> event = value.take(64)
            "id" -> if (!value.contains('\u0000') && value.length <= 64) lastId = value
        }
        return null
    }

    private fun dispatch(): SseEvent? {
        val out = if (hasData && !broken) {
            SseEvent(lastId?.ifEmpty { null }, event?.ifEmpty { null }, data.toString().removeSuffix("\n"))
        } else {
            if (broken) discarded++
            null
        }
        data.setLength(0)
        hasData = false
        broken = false
        event = null
        return out
    }

    companion object {
        const val MAX_EVENT_CHARS = 256 * 1024
    }
}

/** Who scored a goal, from a `gameScorer` message. [home] and [away] are the score after the goal, [gameSec] the elapsed game time of the whole game. */
data class ScorerInfo(val gameId: String, val home: Int, val away: Int, val side: Side, val gameSec: Int, val scorer: String) {
    val line: String get() = "$home-$away"
}

/** A message of the stream about one of the watched games. Everything else on the stream (other games, other sports) is not parsed at all. */
sealed class StreamMessage {
    abstract val game: WatchedGame

    /** A `gameOverview`: the state, the score and the clock of the game. Becomes an [Observation] for the detector. */
    data class Overview(override val game: WatchedGame, val observation: Observation) : StreamMessage()

    /** A `gameScorer` of type goal: who scored. Only used to put a name on the goal notification. */
    data class Scorer(override val game: WatchedGame, val info: ScorerInfo) : StreamMessage()
}

/**
 * Parses the stream's JSON messages.
 *
 *   {"gameOverview":{"gameUuid":"emdl6a38ir","homeTeam":{"teamId":"SAIK","teamCode":"","gameUuid":"emdl6a38ir","score":0},"awayTeam":{...},
 *                    "homeGoals":1,"awayGoals":4,"state":"Ongoing","time":{"period":2,"periodTime":"18:05"}}}
 *   {"gameScorer":{"type":"goal","gameUuid":"blr9i7wd72","period":2,"time":"15:53","eventId":81,"awayTeamValue":0,"homeTeamValue":3,
 *                  "teamDetails":{"teamName":"IF Malmö Redhawks","teamCode":"MIF","belongsTo":"home"},"scorer":"Janne Kuokkanen","assist":[],...}}
 *
 * The score of an overview is `homeGoals`/`awayGoals`: the `score` inside homeTeam and awayTeam is stale (the web app's check says so, and the
 * fixture shows 0 there at 1-4). A message is accepted only when it is exactly what shlLiveParse accepts; anything else is skipped, never guessed at.
 */
object ShlStream {
    private const val MAX_PLAYOFF_PERIOD = 12
    private const val MAX_REGULAR_PERIOD = 5

    /** SHL feeds disagree about club codes; the same alias table as the web app's shl-teams.js. */
    private val ALIASES = mapOf("SKE" to "SAIK", "ORE" to "OHK")
    private val STATES = setOf("Ongoing", "PeriodBreak", "Overtime", "Shootout", "GameEnded", "NotStarted")

    /**
     * Parses one event's data. Returns null for everything that is not a valid overview or goal of one of [games] (SHL games with a game uuid):
     * other sports, other games, malformed or inconsistent messages. [nowMs] is the device time the message arrived, the reading time of the observation.
     */
    fun parse(data: String, games: List<WatchedGame>, nowMs: Long): StreamMessage? {
        if (data.length > SseDecoder.MAX_EVENT_CHARS) return null
        val overview = data.contains("\"gameOverview\"")
        if (!overview && !data.contains("\"gameScorer\"")) return null
        val byUuid = HashMap<String, WatchedGame>()
        for (g in games) if (g.league == "shl" && g.shlId != null) byUuid[g.shlId] = g
        // The stream carries every game of every sport it knows: do not build a JSON tree for a game nobody watches.
        if (byUuid.isEmpty() || byUuid.keys.none { data.contains(it) }) return null
        val root = Json.parseObject(data) ?: return null
        if (overview) Json.obj(root, "gameOverview")?.let { return parseOverview(it, byUuid, nowMs) }
        Json.obj(root, "gameScorer")?.let { return parseScorer(it, byUuid) }
        return null
    }

    private fun parseOverview(o: JSONObject, byUuid: Map<String, WatchedGame>, nowMs: Long): StreamMessage? {
        val uuid = Json.str(o, "gameUuid") ?: return null
        val game = byUuid[uuid] ?: return null
        val homeTeam = Json.obj(o, "homeTeam")
        val awayTeam = Json.obj(o, "awayTeam")
        if (!teamsMatch(game, homeTeam, awayTeam)) return null
        // A team that carries a game uuid of its own must carry this game's.
        for (team in listOf(homeTeam, awayTeam)) {
            val own = team?.let { Json.str(it, "gameUuid") }
            if (!own.isNullOrEmpty() && own != uuid) return null
        }
        val st = Json.str(o, "state") ?: return null
        if (st !in STATES) return null
        val home = Json.int(o.opt("homeGoals"), 0, 99) ?: return null // never turn a missing score into 0
        val away = Json.int(o.opt("awayGoals"), 0, 99) ?: return null
        val time = Json.obj(o, "time")
        val period = time?.let { periodOf(it.opt("period")) } ?: 0
        val clock = time?.let { Json.str(it, "periodTime") }
        if (st != "NotStarted") {
            if (period < 1 || period > (if (game.playoff) MAX_PLAYOFF_PERIOD else MAX_REGULAR_PERIOD)) return null
            if (GameClock.seconds(clock) == null) return null
        }
        if (st == "GameEnded" && home == away) return null // a final needs a decided score
        val state = when (st) {
            "NotStarted" -> GameState.SCHEDULED
            "GameEnded" -> GameState.FINISHED
            else -> GameState.LIVE
        }
        val observation = if (state == GameState.SCHEDULED) {
            Observation(game.id, state, null, null, 0, null, false, nowMs, null, fromStream = true)
        } else {
            val shootout = st == "Shootout" || (state == GameState.LIVE && !game.playoff && period == MAX_REGULAR_PERIOD)
            val decision = if (state == GameState.FINISHED) OverlayParser.deriveDecision(period, game.playoff) else null
            Observation(game.id, state, home, away, period, decision, shootout, nowMs, GameClock.elapsed(period, clock), fromStream = true)
        }
        return StreamMessage.Overview(game, observation)
    }

    private fun parseScorer(o: JSONObject, byUuid: Map<String, WatchedGame>): StreamMessage? {
        if (Json.str(o, "type") != "goal") return null
        val game = byUuid[Json.str(o, "gameUuid") ?: return null] ?: return null
        val period = periodOf(o.opt("period")) ?: return null
        if (period < 1 || period > MAX_PLAYOFF_PERIOD) return null
        val gameSec = GameClock.elapsed(period, Json.str(o, "time")) ?: return null
        val home = Json.int(o.opt("homeTeamValue"), 0, 99) ?: return null
        val away = Json.int(o.opt("awayTeamValue"), 0, 99) ?: return null
        val details = Json.obj(o, "teamDetails") ?: return null
        val side = when (Json.str(details, "belongsTo")) {
            "home" -> Side.HOME
            "away" -> Side.AWAY
            else -> return null
        }
        if ((side == Side.HOME && home < 1) || (side == Side.AWAY && away < 1)) return null // the team that scored has a goal
        val known = if (side == Side.HOME) game.homeCode else game.awayCode
        val code = Json.str(details, "teamCode")?.trim()
        if (known.isNotEmpty() && !code.isNullOrEmpty() && (ALIASES[code] ?: code) != known) return null
        val scorer = clean(Json.str(o, "scorer"))
        if (scorer.isEmpty()) return null
        return StreamMessage.Scorer(game, ScorerInfo(game.id, home, away, side, gameSec, scorer))
    }

    /** The period as the web app reads it with Number(): a whole number, or digits in a string. */
    private fun periodOf(v: Any?): Int? {
        if (v is String) return if (v.length in 1..2 && v.all { it in '0'..'9' }) v.toInt() else null
        return Json.int(v, 0, 99)
    }

    /**
     * The web app's team check (shl-teams.js resolve): teamId, then teamCode, then the alias table; the result must be the club the app
     * knows for that side, and teamId and teamCode must not disagree. When the app does not know both club codes it cannot check and accepts.
     */
    private fun teamsMatch(game: WatchedGame, home: JSONObject?, away: JSONObject?): Boolean {
        if (game.homeCode.isEmpty() || game.awayCode.isEmpty()) return true
        val allowed = setOf(game.homeCode, game.awayCode)
        return resolve(home, allowed) == game.homeCode && resolve(away, allowed) == game.awayCode
    }

    private fun resolve(team: JSONObject?, allowed: Set<String>): String? {
        if (team == null) return null
        val given = listOf(Json.str(team, "teamId"), Json.str(team, "teamCode")).mapNotNull { v -> v?.trim()?.takeIf { it.isNotEmpty() } }
        val found = given.filter { it in allowed } + given.mapNotNull { ALIASES[it] }.filter { it in allowed }
        if (found.isEmpty()) return null
        return if (found.all { it == found[0] }) found[0] else null
    }

    private fun clean(s: String?): String = (s ?: "").replace(Regex("[<>`\\u0000-\\u001f]"), "").trim().take(60)
}

/** Constants and decisions of the stream connection that are worth testing without a network. */
object StreamPlan {
    const val URL = "https://game-broadcaster.s8y.se/live/game"
    const val CONNECT_TIMEOUT_MS = 10_000

    /**
     * No byte for this long means the connection is dead (a phone that changed network does not always get a reset). The overlay keeps
     * the goals coming meanwhile. The stream carries every sport it knows, so while a game is on it is rarely quiet for this long.
     */
    const val READ_TIMEOUT_MS = 90_000

    /** A connection that stayed up this long was a good one: the next failure starts the back-off from the beginning. */
    const val STABLE_AFTER_MS = 30_000L

    private val BACKOFF_MS = longArrayOf(2_000L, 5_000L, 10_000L, 30_000L, 60_000L)

    /** Wait before reconnecting after the [attempt]th failure in a row (0 is the first): 2 s, 5 s, 10 s, 30 s, then 60 s at most. */
    fun backoffMs(attempt: Int): Long = BACKOFF_MS[attempt.coerceIn(0, BACKOFF_MS.size - 1)]

    /** The games the stream is held open for: SHL games in their watch window. The stream says nothing about NHL or the lower Swedish leagues. */
    fun watchedGames(active: List<WatchedGame>): List<WatchedGame> = active.filter { it.league == "shl" && it.shlId != null }
}
