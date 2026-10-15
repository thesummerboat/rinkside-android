package se.jonasp.rinkside.engine

import org.json.JSONObject

/**
 * Turns the Worker's live overlays into [Observation]s of the followed games.
 *
 * Formats (all produced by cloudflare/overlay.mjs of the web repo, validated by dist/live-overlay.js):
 *   every overlay is {v:1, kind:<name>, source, fetchedAt:<ISO>, games:[...]}
 *   nhl-today            games[]: {id:<NHL game id>, home:"DET", away:"TOR", start, state:"scheduled|live|finished|postponed",
 *                                  raw, score:[h,a]|null, period, clock, intermission, decision:"OT"|"SO"|null}
 *   shl-today            games[]: {id:<SHL game uuid>, home:"SAIK", away:"RBK", start, ls:"NotStarted|Ongoing|PeriodBreak|Overtime|Shootout|GameEnded",
 *                                  score:[h,a]|null, period, clock:"mm:ss", at:<ISO, when the Worker read this game>}
 *   swe-today            games[]: {id:"ha-9001"|"et-1113403" (the web app's match id), league:"ha"|"ettan", home, away (the web app's team ids), start,
 *                                  state:"scheduled|live|finished", score, period, intermission, periods, so, decision:"OT"|"SO"|null, at}
 *   shl-schedule-status  games[]: {id:<SHL game uuid>, home, away, start, state:"scheduled|live|finished|unavailable", score, decision}
 *
 * An entry that is not exactly what the Worker promises is skipped (never guessed at), like the web app does.
 */
object OverlayParser {

    fun parse(overlay: Overlay, text: String?, games: List<WatchedGame>): List<Observation> = when (overlay) {
        Overlay.NHL_TODAY -> parseNhlToday(text, games)
        Overlay.SHL_TODAY -> parseShlToday(text, games)
        Overlay.SWE_TODAY -> parseSweToday(text, games)
        Overlay.SHL_SCHEDULE -> parseShlSchedule(text, games)
    }

    /** True for the Worker's {"error":"No overlay yet"} 404 body: nothing has been written yet, try again later. */
    fun isNoOverlayYet(text: String?): Boolean = Json.parseObject(text)?.let { Json.str(it, "error") == "No overlay yet" } ?: false

    private class Envelope(val json: JSONObject, val fetchedAtMs: Long)

    private fun envelope(text: String?, kind: String): Envelope? {
        val o = Json.parseObject(text) ?: return null
        if (Json.int(o.opt("v"), 1, 1) != 1) return null
        if (Json.str(o, "kind") != kind) return null
        val fetched = Json.iso(o.opt("fetchedAt")) ?: return null
        return Envelope(o, fetched)
    }

    /** A team code that the page script could read must match the overlay's; when we do not know it we cannot check and accept. */
    private fun codeMatches(known: String, seen: String?): Boolean {
        if (known.isEmpty()) return true
        return seen != null && known.equals(seen, ignoreCase = true)
    }

    // ---- NHL ----------------------------------------------------------------------------------------------------------------------------

    fun parseNhlToday(text: String?, games: List<WatchedGame>): List<Observation> {
        val env = envelope(text, "nhl-today") ?: return emptyList()
        val list = Json.arr(env.json, "games") ?: return emptyList()
        val byId = HashMap<Long, WatchedGame>()
        for (g in games) if (g.league == "nhl" && g.nhlId != null) byId[g.nhlId] = g
        val out = ArrayList<Observation>()
        for (i in 0 until list.length()) {
            val e = list.opt(i) as? JSONObject ?: continue
            val id = Json.long(e.opt("id")) ?: continue
            val game = byId[id] ?: continue
            if (!codeMatches(game.homeCode, Json.str(e, "home")) || !codeMatches(game.awayCode, Json.str(e, "away"))) continue
            val state = when (Json.str(e, "state")) {
                "scheduled" -> GameState.SCHEDULED
                "live" -> GameState.LIVE
                "finished" -> GameState.FINISHED
                "postponed" -> GameState.POSTPONED
                else -> continue
            }
            val period = Json.int(e.opt("period"), 0, 20) ?: continue
            var home: Int? = null
            var away: Int? = null
            if (state == GameState.LIVE || state == GameState.FINISHED) {
                val score = Json.goals(e.opt("score")) ?: continue // never turn a missing score into 0
                home = score.first
                away = score.second
            }
            val shootout = state == GameState.LIVE && !game.playoff && period >= 5
            var decision: String? = null
            if (state == GameState.FINISHED) {
                val given = Json.str(e, "decision")
                decision = if (given == "OT" || given == "SO") given else deriveDecision(period, game.playoff)
            }
            out.add(Observation(game.id, state, home, away, period, decision, shootout, env.fetchedAtMs))
        }
        return out
    }

    /** Regular season: a fifth period is the shootout, a fourth the overtime. Playoffs: every period after the third is overtime. */
    internal fun deriveDecision(period: Int, playoff: Boolean): String? = when {
        !playoff && period == 5 -> "SO"
        period >= 4 -> "OT"
        else -> null
    }

    // ---- SHL ----------------------------------------------------------------------------------------------------------------------------

    fun parseShlToday(text: String?, games: List<WatchedGame>): List<Observation> {
        val env = envelope(text, "shl-today") ?: return emptyList()
        val list = Json.arr(env.json, "games") ?: return emptyList()
        val byId = HashMap<String, WatchedGame>()
        for (g in games) if (g.league == "shl" && g.shlId != null) byId[g.shlId] = g
        val out = ArrayList<Observation>()
        for (i in 0 until list.length()) {
            val e = list.opt(i) as? JSONObject ?: continue
            val id = Json.str(e, "id") ?: continue
            val game = byId[id] ?: continue
            if (!codeMatches(game.homeCode, Json.str(e, "home")) || !codeMatches(game.awayCode, Json.str(e, "away"))) continue
            val ls = Json.str(e, "ls") ?: continue
            val state = when (ls) {
                "NotStarted" -> GameState.SCHEDULED
                "GameEnded" -> GameState.FINISHED
                "Ongoing", "PeriodBreak", "Overtime", "Shootout" -> GameState.LIVE
                else -> continue
            }
            val readAt = Json.iso(e.opt("at")) ?: continue
            if (state == GameState.SCHEDULED) {
                out.add(Observation(game.id, state, null, null, 0, null, false, readAt))
                continue
            }
            val score = Json.goals(e.opt("score")) ?: continue
            val period = Json.int(e.opt("period"), 1, 12) ?: continue
            if (state == GameState.FINISHED && score.first == score.second) continue // a final needs a decided score
            val shootout = ls == "Shootout" || (state == GameState.LIVE && !game.playoff && period == 5)
            // The feed has no decision field: the web app derives it from the period (shl-live.js shlLiveParse); so do we.
            val decision = if (state == GameState.FINISHED) deriveDecision(period, game.playoff) else null
            val gameSec = GameClock.elapsed(period, Json.str(e, "clock"))
            out.add(Observation(game.id, state, score.first, score.second, period, decision, shootout, readAt, gameSec))
        }
        return out
    }

    /** shl-schedule-status only helps for a game that has finished (the web app uses it for nothing else): it knows OT and SO exactly. */
    fun parseShlSchedule(text: String?, games: List<WatchedGame>): List<Observation> {
        val env = envelope(text, "shl-schedule-status") ?: return emptyList()
        val list = Json.arr(env.json, "games") ?: return emptyList()
        val byId = HashMap<String, WatchedGame>()
        for (g in games) if (g.league == "shl" && g.shlId != null) byId[g.shlId] = g
        val out = ArrayList<Observation>()
        for (i in 0 until list.length()) {
            val e = list.opt(i) as? JSONObject ?: continue
            val id = Json.str(e, "id") ?: continue
            val game = byId[id] ?: continue
            if (Json.str(e, "state") != "finished") continue
            if (!codeMatches(game.homeCode, Json.str(e, "home")) || !codeMatches(game.awayCode, Json.str(e, "away"))) continue
            val score = Json.goals(e.opt("score")) ?: continue
            if (score.first == score.second) continue
            val given = Json.str(e, "decision")
            val decision = if (given == "OT" || given == "SO") given else null
            out.add(Observation(game.id, GameState.FINISHED, score.first, score.second, 0, decision, false, env.fetchedAtMs))
        }
        return out
    }

    // ---- HockeyAllsvenskan and Hockeyettan -----------------------------------------------------------------------------------------------

    fun parseSweToday(text: String?, games: List<WatchedGame>): List<Observation> {
        val env = envelope(text, "swe-today") ?: return emptyList()
        val list = Json.arr(env.json, "games") ?: return emptyList()
        val byId = HashMap<String, WatchedGame>()
        for (g in games) if (g.league == "ha" || g.league == "ettan") byId[g.id] = g
        val out = ArrayList<Observation>()
        for (i in 0 until list.length()) {
            val e = list.opt(i) as? JSONObject ?: continue
            val id = Json.str(e, "id") ?: continue
            val game = byId[id] ?: continue
            if (Json.str(e, "league") != game.league) continue
            if (Json.str(e, "home") != game.homeId || Json.str(e, "away") != game.awayId) continue // an id that does not belong to these clubs
            val state = when (Json.str(e, "state")) {
                "scheduled" -> GameState.SCHEDULED
                "live" -> GameState.LIVE
                "finished" -> GameState.FINISHED
                else -> continue
            }
            val readAt = Json.iso(e.opt("at")) ?: continue
            val period = Json.int(e.opt("period"), 0, 5) ?: continue
            if (state == GameState.SCHEDULED) {
                out.add(Observation(game.id, state, null, null, 0, null, false, readAt))
                continue
            }
            val score = Json.goals(e.opt("score")) ?: continue
            val shootout = state == GameState.LIVE && !game.playoff && period == 5
            var decision: String? = null
            if (state == GameState.FINISHED) {
                val given = Json.str(e, "decision")
                decision = if (given == "OT" || given == "SO") given else null
            }
            out.add(Observation(game.id, state, score.first, score.second, period, decision, shootout, readAt))
        }
        return out
    }
}
