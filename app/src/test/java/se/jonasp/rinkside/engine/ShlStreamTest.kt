package se.jonasp.rinkside.engine

import java.io.StringReader
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.jonasp.rinkside.engine.TestData.T0
import se.jonasp.rinkside.engine.TestData.saikFbk
import se.jonasp.rinkside.engine.TestData.streamResource
import se.jonasp.rinkside.engine.TestData.tik

/** The stream's wire format (SseLineReader, SseDecoder) and its messages (ShlStream). Messages are shaped like the lines seen on 2026-10-15 and the web fixture. */
class ShlStreamTest {

    // ---- lines and events ----

    private fun events(text: String): List<SseEvent> {
        val lines = SseLineReader(StringReader(text))
        val decoder = SseDecoder()
        val out = ArrayList<SseEvent>()
        while (true) {
            val line = lines.readLine() ?: break
            decoder.line(line.text, line.truncated)?.let { out.add(it) }
        }
        return out
    }

    @Test
    fun linesEndWithLfCrOrCrlf() {
        val reader = SseLineReader(StringReader("a\nb\r\nc\rd\r\n\r\ne\n"))
        val seen = ArrayList<String>()
        while (true) seen.add((reader.readLine() ?: break).text)
        assertEquals(listOf("a", "b", "c", "d", "", "e"), seen)
    }

    @Test
    fun aLastLineThatTheConnectionEndedInTheMiddleOfIsDropped() {
        val reader = SseLineReader(StringReader("complete\npart"))
        assertEquals("complete", reader.readLine()!!.text)
        assertNull(reader.readLine())
    }

    @Test
    fun aHugeLineIsCutAndFlaggedNotBuffered() {
        val reader = SseLineReader(StringReader("data: " + "x".repeat(500) + "\nnext\n"), maxLine = 100)
        val big = reader.readLine()!!
        assertEquals(100, big.text.length)
        assertTrue(big.truncated)
        val next = reader.readLine()!!
        assertEquals("next", next.text)
        assertFalse(next.truncated)
    }

    @Test
    fun anEventWithATruncatedLineIsDiscardedAndTheNextOneIsFine() {
        val lines = SseLineReader(StringReader("data: " + "x".repeat(300) + "\n\ndata: ok\n\n"), maxLine = 100)
        val decoder = SseDecoder()
        val out = ArrayList<SseEvent>()
        while (true) {
            val line = lines.readLine() ?: break
            decoder.line(line.text, line.truncated)?.let { out.add(it) }
        }
        assertEquals(listOf("ok"), out.map { it.data })
        assertEquals(1, decoder.discarded)
    }

    @Test
    fun aSimpleEventHasItsIdAndData() {
        val e = events("id: 27002\ndata: {\"a\":1}\n\n").single()
        assertEquals("27002", e.id)
        assertNull(e.event)
        assertEquals("{\"a\":1}", e.data)
    }

    @Test
    fun severalDataLinesAreJoinedWithALineFeed() {
        val e = events("data: {\"gameOverview\":\ndata: {\"gameUuid\":\"x\"}}\n\n").single()
        assertEquals("{\"gameOverview\":\n{\"gameUuid\":\"x\"}}", e.data)
        assertEquals("x", JSONObject(e.data).getJSONObject("gameOverview").getString("gameUuid")) // still one JSON value
    }

    @Test
    fun commentsRetryAndUnknownFieldsAreNotEvents() {
        assertTrue(events(": keep-alive\n\nretry: 3000\n\nfoo: bar\n\n").isEmpty())
        // a blank line with no data in front of it delivers nothing
        assertTrue(events("event: ping\n\n").isEmpty())
    }

    @Test
    fun oneSpaceAfterTheColonIsDroppedAndAFieldWithoutAColonIsEmpty() {
        assertEquals(listOf(" two spaces", "x"), events("data:  two spaces\n\ndata:x\n\n").map { it.data })
        assertEquals(listOf(""), events("data\n\n").map { it.data })
    }

    @Test
    fun theIdStaysInForceUntilTheNextOne() {
        val all = events("id: 7\ndata: a\n\ndata: b\n\nid: 9\ndata: c\n\n")
        assertEquals(listOf("7", "7", "9"), all.map { it.id })
    }

    @Test
    fun aByteOrderMarkAtTheStartIsDropped() {
        assertEquals(listOf("a"), events("﻿data: a\n\n").map { it.data })
    }

    @Test
    fun theSyntheticTranscriptDecodesAndOnlyTheWatchedGameIsRelevant() {
        val all = events(streamResource("replay-burst.sse"))
        // basketball, overview, scorer, multi-line overview, other game's scorer, not json, broken overview
        assertEquals(7, all.size)
        assertEquals(listOf("27001", "27002", "27003", "27004", "27005", "27005", "27006"), all.map { it.id })
        val parsed = all.map { ShlStream.parse(it.data, listOf(tik), T0) }
        assertNull(parsed[0]) // basketball
        assertTrue(parsed[1] is StreamMessage.Overview)
        assertTrue(parsed[2] is StreamMessage.Scorer)
        assertTrue(parsed[3] is StreamMessage.Overview) // the one written over several data: lines
        assertNull(parsed[4]) // a goal of Malmö in a game nobody watches
        assertNull(parsed[5]) // not json
        assertNull(parsed[6]) // a score that is not a number
        val multi = (parsed[3] as StreamMessage.Overview).observation
        assertEquals(1, multi.home)
        assertEquals(1, multi.away)
        assertEquals(2, multi.period)
    }

    // ---- gameOverview ----

    private fun overview(
        home: Int? = 1,
        away: Int? = 0,
        state: String = "Ongoing",
        period: Any? = 2,
        clock: String? = "18:05",
        uuid: String = StreamJson.UUID,
        homeId: String = "TIK",
        awayId: String = "HV71",
        edit: (JSONObject) -> Unit = {},
    ): String = StreamJson.overview(home, away, state, period, clock, uuid, homeId, awayId, edit)

    private fun parseOverview(data: String, game: WatchedGame = tik, nowMs: Long = T0 + 5_000L): Observation? =
        (ShlStream.parse(data, listOf(game), nowMs) as? StreamMessage.Overview)?.observation

    @Test
    fun anOverviewWithAScoreChangeBecomesAnObservation() {
        val obs = parseOverview(overview(home = 2, away = 1, period = 2, clock = "15:53"))!!
        assertEquals("shl-x0ws1h01an", obs.gameId)
        assertEquals(GameState.LIVE, obs.state)
        assertEquals(2, obs.home)
        assertEquals(1, obs.away)
        assertEquals(2, obs.period)
        assertEquals(1200 + 15 * 60 + 53, obs.gameSec)
        assertTrue(obs.fromStream)
        assertEquals(T0 + 5_000L, obs.readAtMs) // the time the message arrived is the time of the reading
        assertFalse(obs.shootout)
        assertNull(obs.decision)
    }

    @Test
    fun theScoreIsHomeGoalsNotTheNestedScore() {
        // the web fixture: homeTeam.score and awayTeam.score say 0-0 while the game is 1-4
        val fixture = JSONObject(streamResource("shl-live-overview.json")).getJSONObject("overview")
        assertEquals(0, fixture.getJSONObject("homeTeam").getInt("score"))
        val obs = parseOverview(JSONObject().put("gameOverview", fixture).toString(), saikFbk)!!
        assertEquals(1, obs.home)
        assertEquals(4, obs.away)
        assertEquals(3, obs.period)
        assertEquals(2 * 1200 + 60 + 51, obs.gameSec)
    }

    @Test
    fun statesMapTheWayTheOverlaysMapThem() {
        assertEquals(GameState.LIVE, parseOverview(overview(state = "PeriodBreak", period = 1, clock = "20:00"))!!.state)
        assertEquals(GameState.LIVE, parseOverview(overview(state = "Overtime", period = 4, clock = "01:20"))!!.state)
        val shootout = parseOverview(overview(state = "Shootout", period = 5, clock = "00:00"))!!
        assertEquals(GameState.LIVE, shootout.state)
        assertTrue(shootout.shootout)
        val pre = parseOverview(overview(home = 0, away = 0, state = "NotStarted", period = null, clock = null))!!
        assertEquals(GameState.SCHEDULED, pre.state)
        assertNull(pre.home)
        val ended = parseOverview(overview(home = 3, away = 2, state = "GameEnded", period = 3, clock = "20:00"))!!
        assertEquals(GameState.FINISHED, ended.state)
        assertNull(ended.decision)
        assertEquals("OT", parseOverview(overview(home = 3, away = 2, state = "GameEnded", period = 4, clock = "02:03"))!!.decision)
        assertEquals("SO", parseOverview(overview(home = 3, away = 2, state = "GameEnded", period = 5, clock = "00:00"))!!.decision)
    }

    @Test
    fun periodFiveIsOvertimeInThePlayoffs() {
        val playoff = tik.copy(playoff = true)
        val obs = parseOverview(overview(period = 5, clock = "04:00"), playoff)!!
        assertFalse(obs.shootout)
        assertNull(parseOverview(overview(period = 13, clock = "04:00"), playoff))
        assertNull(parseOverview(overview(period = 6, clock = "04:00"), tik)) // the regular season has five
    }

    @Test
    fun aClubAliasIsResolvedLikeTheWebApp() {
        val saik = saikFbk
        val data = overview(uuid = "emdl6a38ir", homeId = "SKE", awayId = "FBK") // play-by-play style code for Skellefteå
        assertNotNull(parseOverview(data, saik))
        val conflict = overview(uuid = "emdl6a38ir", homeId = "SAIK", awayId = "FBK") { o ->
            o.getJSONObject("homeTeam").put("teamCode", "FBK") // teamId and teamCode disagree
        }
        assertNull(parseOverview(conflict, saik))
    }

    @Test
    fun anOverviewOfAnotherGameOrWithTheWrongClubsIsRejected() {
        assertNull(parseOverview(overview(uuid = "blr9i7wd72"))) // not watched
        assertNull(parseOverview(overview(homeId = "MIF"))) // a game with this uuid but other clubs
        assertNull(parseOverview(overview(awayId = "TIK")))
        assertNull(parseOverview(overview { o -> o.getJSONObject("homeTeam").put("gameUuid", "other") })) // a team that belongs to another game
        // a club code the app does not know cannot be checked: accepted
        assertNotNull(parseOverview(overview(homeId = "MIF"), tik.copy(homeCode = "", awayCode = "")))
    }

    @Test
    fun malformedOverviewsAreRejectedNeverGuessedAt() {
        assertNull(parseOverview(overview(home = null))) // never turn a missing score into 0
        assertNull(parseOverview(overview(away = null)))
        assertNull(parseOverview(overview { it.put("homeGoals", "1") })) // a string is no score
        assertNull(parseOverview(overview { it.put("homeGoals", 1.5) }))
        assertNull(parseOverview(overview { it.put("awayGoals", -1) }))
        assertNull(parseOverview(overview { it.put("awayGoals", 100) }))
        assertNull(parseOverview(overview(clock = "18:99")))
        assertNull(parseOverview(overview(clock = "1805")))
        assertNull(parseOverview(overview(clock = null)))
        assertNull(parseOverview(overview(period = 0)))
        assertNull(parseOverview(overview(period = null)))
        assertNull(parseOverview(overview(period = 2.5)))
        assertNull(parseOverview(overview(state = "Halftime")))
        assertNull(parseOverview(overview { it.remove("state") }))
        assertNull(parseOverview(overview(home = 2, away = 2, state = "GameEnded", period = 3, clock = "20:00"))) // a final needs a decided score
    }

    @Test
    fun aPeriodAsDigitsInAStringIsReadLikeTheWebAppDoes() {
        assertEquals(2, parseOverview(overview(period = "2"))!!.period)
        assertNull(parseOverview(overview(period = "two")))
    }

    @Test
    fun anythingThatIsNotAnOverviewOrAGoalOfAWatchedGameIsNoMessage() {
        val games = listOf(tik)
        // another sport, as seen on the stream
        val basketball = "{\"source\":\"genius-basketball-stream-reader\",\"gameId\":512344,\"homeScore\":41,\"awayScore\":38}"
        assertNull(ShlStream.parse(basketball, games, T0))
        // not json, json of the wrong kind, empty
        for (bad in listOf("", "   ", "not json", "{", "[]", "null", "42", "\"gameOverview\"", "{\"gameOverview\":null}", "{\"gameOverview\":[]}", "{\"gameOverview\":\"x\"}")) {
            assertNull("for: $bad", ShlStream.parse(bad, games, T0))
        }
        // a perfectly good overview, but nothing is watched
        assertNull(ShlStream.parse(overview(), emptyList(), T0))
        assertNull(ShlStream.parse(overview(), listOf(TestData.nhl, TestData.ha), T0))
        assertNull(ShlStream.parse(overview(), listOf(tik.copy(league = "ha")), T0))
        assertNull(ShlStream.parse(overview(), listOf(tik.copy(shlId = null)), T0))
        // way too big
        assertNull(ShlStream.parse("{\"gameOverview\":\"" + "x".repeat(SseDecoder.MAX_EVENT_CHARS) + "\"}", games, T0))
    }

    // ---- gameScorer ----

    private fun scorer(edit: (JSONObject) -> Unit = {}): String =
        StreamJson.scorer(home = 3, away = 0, side = "home", period = 2, clock = "15:53", name = "Janne Kuokkanen", edit = edit)

    private fun parseScorer(data: String, game: WatchedGame = tik): ScorerInfo? = (ShlStream.parse(data, listOf(game), T0) as? StreamMessage.Scorer)?.info

    @Test
    fun aGoalMessageNamesTheScorerAndTheElapsedGameTime() {
        val info = parseScorer(scorer())!!
        assertEquals("shl-x0ws1h01an", info.gameId)
        assertEquals(3, info.home)
        assertEquals(0, info.away)
        assertEquals(Side.HOME, info.side)
        assertEquals("Janne Kuokkanen", info.scorer)
        assertEquals("3-0", info.line)
        assertEquals(2153, info.gameSec) // period 2, 15:53
        assertEquals("35:53", GameClock.format(info.gameSec))
    }

    @Test
    fun theGoalLineSeenOnTheStreamParses() {
        // the literal line of the transcript: Malmö at home, 3-0, period 2, 15:53, scorer Janne Kuokkanen
        val mif = WatchedGame(
            id = "shl-blr9i7wd72", league = "shl", startMs = T0, homeId = "shl-mif", awayId = "shl-lhc",
            homeName = "Malmö", awayName = "Luleå", homeCode = "MIF", awayCode = "LHC", shlId = "blr9i7wd72",
        )
        val data = events(streamResource("replay-burst.sse"))[4].data
        val info = (ShlStream.parse(data, listOf(mif), T0) as StreamMessage.Scorer).info
        assertEquals(ScorerInfo("shl-blr9i7wd72", 3, 0, Side.HOME, 2153, "Janne Kuokkanen"), info)
    }

    @Test
    fun anAwayGoalAndAnOvertimeGoal() {
        val away = parseScorer(scorer { o ->
            o.put("homeTeamValue", 1).put("awayTeamValue", 2)
            o.getJSONObject("teamDetails").put("belongsTo", "away").put("teamCode", "HV71")
        })!!
        assertEquals(Side.AWAY, away.side)
        assertEquals("1-2", away.line)
        val overtime = parseScorer(scorer { it.put("period", 4).put("time", "03:21") })!!
        assertEquals("63:21", GameClock.format(overtime.gameSec))
    }

    @Test
    fun aGoalMessageThatDoesNotCheckOutIsRejected() {
        assertNull(parseScorer(scorer { it.put("type", "penalty") }))
        assertNull(parseScorer(scorer { it.put("gameUuid", "blr9i7wd72") })) // another game
        assertNull(parseScorer(scorer { it.put("period", "shootout") })) // a shootout goal has no game time
        assertNull(parseScorer(scorer { it.put("time", "15:99") }))
        assertNull(parseScorer(scorer { it.remove("homeTeamValue") }))
        assertNull(parseScorer(scorer { it.put("awayTeamValue", "0") }))
        assertNull(parseScorer(scorer { it.remove("teamDetails") }))
        assertNull(parseScorer(scorer { it.getJSONObject("teamDetails").put("belongsTo", "both") }))
        assertNull(parseScorer(scorer { it.getJSONObject("teamDetails").put("teamCode", "MIF") })) // not Timrå
        assertNull(parseScorer(scorer { it.put("homeTeamValue", 0) })) // the home side scored but has no goal
        assertNull(parseScorer(scorer { it.remove("scorer") }))
        assertNull(parseScorer(scorer { it.put("scorer", "  ") }))
    }

    @Test
    fun theScorerNameIsCleaned() {
        assertEquals("Mikkel Øby-Olsen", parseScorer(scorer { it.put("scorer", "  Mikkel Øby-Olsen\u0007 ") })!!.scorer)
        assertEquals(60, parseScorer(scorer { it.put("scorer", "x".repeat(200)) })!!.scorer.length)
    }

    // ---- game clock and plan ----

    @Test
    fun gameClock() {
        assertEquals(953, GameClock.seconds("15:53"))
        assertEquals(65, GameClock.seconds("1:05"))
        assertNull(GameClock.seconds("15:5"))
        assertNull(GameClock.seconds(null))
        assertEquals(0, GameClock.elapsed(1, "00:00"))
        assertEquals(1200, GameClock.elapsed(2, "00:00"))
        assertEquals(3600, GameClock.elapsed(3, "20:00"))
        assertNull(GameClock.elapsed(0, "10:00"))
        assertNull(GameClock.elapsed(2, "25:00")) // longer than a period
        assertEquals("0:00", GameClock.format(0))
        assertEquals("59:59", GameClock.format(3599))
        assertEquals("60:05", GameClock.format(3605))
    }

    @Test
    fun backOffIsTwoFiveTenThirtyThenSixtySeconds() {
        assertEquals(listOf(2_000L, 5_000L, 10_000L, 30_000L, 60_000L, 60_000L, 60_000L), (0..6).map { StreamPlan.backoffMs(it) })
        assertEquals(2_000L, StreamPlan.backoffMs(-3))
        assertEquals(60_000L, StreamPlan.backoffMs(10_000))
    }

    @Test
    fun theStreamIsHeldOnlyForShlGamesWithAGameUuid() {
        val games = listOf(tik, TestData.nhl, TestData.ha, TestData.ettan, saikFbk, tik.copy(id = "shl-odd", shlId = null))
        assertEquals(listOf("shl-x0ws1h01an", "shl-emdl6a38ir"), StreamPlan.watchedGames(games).map { it.id })
        assertTrue(StreamPlan.watchedGames(listOf(TestData.nhl, TestData.ha)).isEmpty())
    }
}
