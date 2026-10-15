package se.jonasp.rinkside.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * What the page script (assets/read-favourites.js) reads from the web app: the followed teams and leagues and the followed teams' games.
 * Stored as JSON in SharedPreferences and read back by the service and the alarm scheduler.
 */
data class Snapshot(
    /** prefs.teams: the web app's team ids. */
    val teams: List<String>,
    /** prefs.leagues: the web app's league ids (shown in the settings screen only). */
    val leagues: List<String>,
    /** team id -> short display name, for the teams that exist in D.teams. */
    val teamNames: Map<String, String>,
    val games: List<WatchedGame>,
    /** When the page script ran (device clock). */
    val savedAtMs: Long,
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("teams", JSONArray(teams))
        o.put("leagues", JSONArray(leagues))
        val names = JSONObject()
        for ((k, v) in teamNames) names.put(k, v)
        o.put("teamNames", names)
        val arr = JSONArray()
        for (g in games) arr.put(gameToJson(g))
        o.put("games", arr)
        o.put("savedAt", savedAtMs)
        return o.toString()
    }

    companion object {
        const val MAX_GAMES = 60

        /** Reads what [toJson] wrote (also used for the page script's own output, which has the same shape). Null when it is not a snapshot. */
        fun fromJson(text: String?): Snapshot? {
            val o = Json.parseObject(text) ?: return null
            val teams = strings(Json.arr(o, "teams"), 200)
            val leagues = strings(Json.arr(o, "leagues"), 50)
            val names = LinkedHashMap<String, String>()
            Json.obj(o, "teamNames")?.let { n ->
                val keys = n.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = Json.str(n, k)
                    if (v != null && k.length <= 64 && v.length <= 80) names[k] = v
                }
            }
            val games = ArrayList<WatchedGame>()
            val arr = Json.arr(o, "games")
            if (arr != null) {
                for (i in 0 until minOf(arr.length(), MAX_GAMES)) {
                    val g = gameFromJson(arr.opt(i) as? JSONObject ?: continue) ?: continue
                    games.add(g)
                }
            }
            games.sortBy { it.startMs }
            val savedAt = Json.long(o.opt("savedAt")) ?: Json.long(o.opt("at")) ?: 0L
            return Snapshot(teams, leagues, names, games, savedAt)
        }

        /**
         * Parses the output of the page script. Null while the page is not ready (globals not defined yet) so the caller retries.
         * The result of `evaluateJavascript` is a JSON string literal; callers unwrap it first (see [unwrapJsResult]).
         */
        fun fromPageScript(text: String?, nowMs: Long): Snapshot? {
            val o = Json.parseObject(text) ?: return null
            if (Json.bool(o, "ready") != true) return null
            val s = fromJson(text) ?: return null
            return s.copy(savedAtMs = nowMs)
        }

        private fun strings(a: JSONArray?, max: Int): List<String> {
            val out = ArrayList<String>()
            if (a == null) return out
            for (i in 0 until minOf(a.length(), max)) {
                val s = a.opt(i) as? String ?: continue
                if (s.isNotEmpty() && s.length <= 64) out.add(s)
            }
            return out
        }

        private fun gameToJson(g: WatchedGame): JSONObject {
            val o = JSONObject()
            o.put("id", g.id)
            o.put("league", g.league)
            o.put("startMs", g.startMs)
            o.put("home", g.homeId)
            o.put("away", g.awayId)
            o.put("homeName", g.homeName)
            o.put("awayName", g.awayName)
            o.put("homeCode", g.homeCode)
            o.put("awayCode", g.awayCode)
            if (g.nhlId != null) o.put("nhlId", g.nhlId)
            if (g.shlId != null) o.put("shlId", g.shlId)
            o.put("playoff", g.playoff)
            return o
        }

        /** The same shape is produced by the page script (with gameType instead of playoff). A record that is not exactly right is skipped. */
        private fun gameFromJson(o: JSONObject): WatchedGame? {
            val id = Json.str(o, "id") ?: return null
            if (!SAFE_ID.matches(id)) return null
            val league = Json.str(o, "league") ?: return null
            if (league != "nhl" && league != "shl" && league != "ha" && league != "ettan") return null
            val startMs = Json.long(o.opt("startMs")) ?: return null
            val home = Json.str(o, "home") ?: return null
            val away = Json.str(o, "away") ?: return null
            if (!SAFE_ID.matches(home) || !SAFE_ID.matches(away)) return null
            val homeName = clean(Json.str(o, "homeName")).ifEmpty { home }
            val awayName = clean(Json.str(o, "awayName")).ifEmpty { away }
            val nhlId = Json.long(o.opt("nhlId"))
            val shlId = Json.str(o, "shlId")?.takeIf { SHL_ID.matches(it) }
            val playoff = Json.bool(o, "playoff") ?: (Json.int(o.opt("gameType"), 0, 9) == 3)
            if (league == "nhl" && nhlId == null) return null
            if (league == "shl" && shlId == null) return null
            return WatchedGame(
                id = id,
                league = league,
                startMs = startMs,
                homeId = home,
                awayId = away,
                homeName = homeName,
                awayName = awayName,
                homeCode = clean(Json.str(o, "homeCode")).take(8),
                awayCode = clean(Json.str(o, "awayCode")).take(8),
                nhlId = if (league == "nhl") nhlId else null,
                shlId = if (league == "shl") shlId else null,
                playoff = playoff,
            )
        }

        private val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
        private val SHL_ID = Regex("^[a-z0-9]{6,20}$")

        private fun clean(s: String?): String = (s ?: "").replace(Regex("[<>`\\u0000-\\u001f]"), "").trim().take(60)
    }
}

/** What `WebView.evaluateJavascript` hands to its callback is the JSON encoding of the script's result; for a string result that is a quoted, escaped literal. */
fun unwrapJsResult(value: String?): String? {
    if (value == null || value == "null" || value.isEmpty()) return null
    return try {
        org.json.JSONTokener(value).nextValue() as? String
    } catch (e: Exception) {
        null
    }
}

/**
 * Persistence of [GameMemory] as one JSON object: {gameId: {s,h,a,r,so, sa,gs,gl,dl}}. The last four (stream time, game time, announced
 * score lines, announced drops) are left out when empty, so memory written by 0.1.3 and older reads back as it is.
 */
object MemoryJson {
    private val GOAL_LINE = Regex("^\\d{1,2}-\\d{1,2}$")
    private val DROP_LINE = Regex("^\\d{1,2}-\\d{1,2}>\\d{1,2}-\\d{1,2}$")

    fun write(map: Map<String, GameMemory>): String {
        val o = JSONObject()
        for ((id, m) in map) {
            val e = JSONObject()
            e.put("s", m.state.name)
            if (m.home != null) e.put("h", m.home)
            if (m.away != null) e.put("a", m.away)
            e.put("r", m.readAtMs)
            e.put("so", m.shootout)
            if (m.streamAtMs != null) e.put("sa", m.streamAtMs)
            if (m.gameSec != null) e.put("gs", m.gameSec)
            if (m.goalLines.isNotEmpty()) e.put("gl", JSONArray(m.goalLines.toList()))
            if (m.dropLines.isNotEmpty()) e.put("dl", JSONArray(m.dropLines.toList()))
            o.put(id, e)
        }
        return o.toString()
    }

    fun read(text: String?): Map<String, GameMemory> {
        val o = Json.parseObject(text) ?: return emptyMap()
        val out = LinkedHashMap<String, GameMemory>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val e = Json.obj(o, id) ?: continue
            val state = try {
                GameState.valueOf(Json.str(e, "s") ?: continue)
            } catch (ex: IllegalArgumentException) {
                continue
            }
            out[id] = GameMemory(
                state = state,
                home = Json.int(e.opt("h"), 0, 99),
                away = Json.int(e.opt("a"), 0, 99),
                readAtMs = Json.long(e.opt("r")) ?: 0L,
                shootout = Json.bool(e, "so") ?: false,
                streamAtMs = Json.long(e.opt("sa")),
                gameSec = Json.int(e.opt("gs"), 0, 100_000),
                goalLines = lines(Json.arr(e, "gl"), GOAL_LINE),
                dropLines = lines(Json.arr(e, "dl"), DROP_LINE),
            )
        }
        return out
    }

    private fun lines(a: JSONArray?, shape: Regex): Set<String> {
        val out = LinkedHashSet<String>()
        if (a == null) return out
        for (i in 0 until minOf(a.length(), Detector.MAX_CUES)) {
            val s = a.opt(i) as? String ?: continue
            if (shape.matches(s)) out.add(s)
        }
        return out
    }

    /** Keeps the memory small: entries of games that are not in the current snapshot and were last read more than [keepMs] ago go. */
    fun prune(map: Map<String, GameMemory>, knownIds: Set<String>, nowMs: Long, keepMs: Long = 3 * 24 * 3_600_000L): Map<String, GameMemory> =
        map.filter { (id, m) -> id in knownIds || nowMs - m.readAtMs <= keepMs }
}
