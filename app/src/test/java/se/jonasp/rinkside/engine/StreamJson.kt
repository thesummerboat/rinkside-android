package se.jonasp.rinkside.engine

import org.json.JSONObject

/** Builders for the stream's JSON messages, in the shapes seen on the SHL stream on 2026-10-15 (Timrå - HV71, game uuid x0ws1h01an). Synthetic values. */
object StreamJson {
    const val UUID = "x0ws1h01an"

    /** `{"gameOverview":{...}}`. [edit] may change the object before it is written. */
    fun overview(
        home: Int? = 1,
        away: Int? = 0,
        state: String = "Ongoing",
        period: Any? = 2,
        clock: String? = "18:05",
        uuid: String = UUID,
        homeId: String = "TIK",
        awayId: String = "HV71",
        edit: (JSONObject) -> Unit = {},
    ): String {
        val o = JSONObject()
        o.put("gameUuid", uuid)
        o.put("homeTeam", team("home", homeId, "Timrå IK", uuid))
        o.put("awayTeam", team("away", awayId, "HV71", uuid))
        if (home != null) o.put("homeGoals", home)
        if (away != null) o.put("awayGoals", away)
        o.put("state", state)
        val time = JSONObject()
        if (period != null) time.put("period", period)
        if (clock != null) time.put("periodTime", clock)
        o.put("time", time)
        edit(o)
        return JSONObject().put("gameOverview", o).toString()
    }

    private fun team(place: String, id: String, name: String, uuid: String): JSONObject =
        JSONObject().put("gameSourceId", "20261015-TIK-HV71").put("gameId", 23061).put("place", place)
            .put("score", 0) // stale, as on the real stream: the score is homeGoals/awayGoals
            .put("teamId", id).put("teamName", name).put("teamCode", "").put("gameUuid", uuid)

    /** `{"gameScorer":{...}}`: a goal by [side] ("home"/"away") that makes the score [home]-[away]. */
    fun scorer(
        home: Int = 1,
        away: Int = 0,
        side: String = "home",
        period: Int = 1,
        clock: String = "07:12",
        name: String = "A. Skater",
        uuid: String = UUID,
        edit: (JSONObject) -> Unit = {},
    ): String {
        val o = JSONObject()
        o.put("type", "goal")
        o.put("gameUuid", uuid)
        o.put("period", period)
        o.put("time", clock)
        o.put("eventId", 81)
        o.put("awayTeamValue", away)
        o.put("homeTeamValue", home)
        o.put("teamDetails", JSONObject().put("teamName", if (side == "home") "Timrå IK" else "HV71").put("teamCode", if (side == "home") "TIK" else "HV71").put("belongsTo", side))
        o.put("scorer", name)
        o.put("assist", org.json.JSONArray())
        o.put("goalStatus", "EQ")
        edit(o)
        return JSONObject().put("gameScorer", o).toString()
    }

    /** What the stream carries besides SHL games: a basketball game, as seen. */
    const val BASKETBALL = "{\"source\":\"genius-basketball-stream-reader\",\"gameId\":512344,\"homeScore\":41,\"awayScore\":38,\"period\":2}"
}
