package se.jonasp.rinkside.engine

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.jonasp.rinkside.engine.TestData.resource

class OverlayParserTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    // ---- NHL ----

    @Test
    fun nhlLiveGameOfAFollowedTeam() {
        val obs = OverlayParser.parseNhlToday(resource("nhl-today.json"), listOf(TestData.nhl))
        assertEquals(1, obs.size) // the other four games of the day are not followed
        val o = obs[0]
        assertEquals("nhl-2026020500", o.gameId)
        assertEquals(GameState.LIVE, o.state)
        assertEquals(2, o.home)
        assertEquals(1, o.away)
        assertEquals(2, o.period)
        assertFalse(o.shootout)
        assertNull(o.decision)
        assertEquals(ms("2026-10-10T21:30:15Z"), o.readAtMs)
    }

    @Test
    fun nhlFinishedOvertimeShootoutScheduledAndPostponed() {
        fun game(id: Long, home: String, away: String) = TestData.nhl.copy(id = "nhl-$id", nhlId = id, homeCode = home, awayCode = away)
        val games = listOf(
            game(2026020502, "MTL", "OTT"), game(2026020503, "SEA", "VAN"), game(2026020504, "CGY", "EDM"), game(2026020505, "LAK", "SJS"),
        )
        val obs = OverlayParser.parseNhlToday(resource("nhl-today.json"), games).associateBy { it.gameId }
        assertEquals(4, obs.size)
        val ot = obs.getValue("nhl-2026020502")
        assertEquals(GameState.FINISHED, ot.state)
        assertEquals("OT", ot.decision)
        assertEquals(3, ot.home)
        assertEquals(2, ot.away)
        val so = obs.getValue("nhl-2026020505")
        assertEquals("SO", so.decision)
        val scheduled = obs.getValue("nhl-2026020503")
        assertEquals(GameState.SCHEDULED, scheduled.state)
        assertNull(scheduled.home)
        assertEquals(GameState.POSTPONED, obs.getValue("nhl-2026020504").state)
    }

    @Test
    fun nhlGameWithOtherTeamsUnderTheSameIdIsIgnored() {
        val wrong = TestData.nhl.copy(homeCode = "NYR", awayCode = "BOS")
        assertTrue(OverlayParser.parseNhlToday(resource("nhl-today.json"), listOf(wrong)).isEmpty())
    }

    @Test
    fun nhlPlayoffPeriodFiveIsOvertimeNotShootout() {
        val playoff = TestData.nhl.copy(playoff = true)
        val text = resource("nhl-today.json").replace("\"score\": [2, 1], \"period\": 2", "\"score\": [2, 2], \"period\": 5")
        val o = OverlayParser.parseNhlToday(text, listOf(playoff))[0]
        assertFalse(o.shootout)
        val regular = OverlayParser.parseNhlToday(text, listOf(TestData.nhl))[0]
        assertTrue(regular.shootout)
    }

    @Test
    fun nhlFinishedWithoutDecisionFieldIsDerivedFromThePeriod() {
        val text = resource("nhl-today.json").replace("\"period\": 4, \"clock\": \"\", \"intermission\": false, \"decision\": \"OT\"", "\"period\": 4, \"clock\": \"\", \"intermission\": false, \"decision\": null")
        val game = TestData.nhl.copy(id = "nhl-2026020502", nhlId = 2026020502L, homeCode = "MTL", awayCode = "OTT")
        assertEquals("OT", OverlayParser.parseNhlToday(text, listOf(game))[0].decision)
        assertEquals("SO", OverlayParser.deriveDecision(5, playoff = false))
        assertEquals("OT", OverlayParser.deriveDecision(5, playoff = true))
        assertNull(OverlayParser.deriveDecision(3, playoff = false))
    }

    @Test
    fun aLiveGameWithoutAScoreIsSkippedNeverTurnedIntoZero() {
        val text = resource("nhl-today.json").replace("\"score\": [2, 1]", "\"score\": null")
        assertTrue(OverlayParser.parseNhlToday(text, listOf(TestData.nhl)).isEmpty())
    }

    // ---- SHL ----

    @Test
    fun shlLiveNotStartedEndedAndShootout() {
        val games = listOf(
            TestData.shl,
            TestData.shl.copy(id = "shl-emdl6a38ir", shlId = "emdl6a38ir", awayCode = "FBK", awayId = "fbk"),
            TestData.shl.copy(id = "shl-h015c0se1o", shlId = "h015c0se1o", homeCode = "RBK", awayCode = "HV71"),
            TestData.shl.copy(id = "shl-pqcc7xg1m4", shlId = "pqcc7xg1m4", homeCode = "VLH", awayCode = "SAIK"),
            TestData.shl.copy(id = "shl-s3fwiszzmr", shlId = "s3fwiszzmr", homeCode = "IFB", awayCode = "RBK"),
        )
        val obs = OverlayParser.parseShlToday(resource("shl-today.json"), games).associateBy { it.gameId }
        assertEquals(5, obs.size)
        val live = obs.getValue("shl-xh68muyldy")
        assertEquals(GameState.LIVE, live.state)
        assertEquals(1, live.home)
        assertEquals(0, live.away)
        assertEquals(ms("2026-10-10T17:42:38Z"), live.readAtMs) // the entry's own reading time, not the overlay's
        assertEquals(false, obs.getValue("shl-emdl6a38ir").shootout)
        assertEquals(GameState.SCHEDULED, obs.getValue("shl-h015c0se1o").state)
        val ended = obs.getValue("shl-pqcc7xg1m4")
        assertEquals(GameState.FINISHED, ended.state)
        assertEquals("OT", ended.decision) // period 4
        assertEquals(2, ended.home)
        assertEquals(3, ended.away)
        assertTrue(obs.getValue("shl-s3fwiszzmr").shootout)
    }

    @Test
    fun shlScheduleStatusOnlyHelpsForFinishedGamesAndKnowsTheDecision() {
        val games = listOf(
            TestData.shl,
            TestData.shl.copy(id = "shl-emdl6a38ir", shlId = "emdl6a38ir", awayCode = "FBK"),
            TestData.shl.copy(id = "shl-pqcc7xg1m4", shlId = "pqcc7xg1m4", homeCode = "VLH", awayCode = "SAIK"),
        )
        val obs = OverlayParser.parseShlSchedule(resource("shl-schedule-status.json"), games).associateBy { it.gameId }
        assertEquals(2, obs.size) // the live row is not used
        assertEquals("OT", obs.getValue("shl-xh68muyldy").decision)
        assertNull(obs.getValue("shl-pqcc7xg1m4").decision)
        assertEquals(GameState.FINISHED, obs.getValue("shl-xh68muyldy").state)
    }

    @Test
    fun shlFinalWithATiedScoreIsRefused() {
        val text = resource("shl-today.json").replace("\"score\": [2, 3], \"period\": 4", "\"score\": [3, 3], \"period\": 4")
        val game = TestData.shl.copy(id = "shl-pqcc7xg1m4", shlId = "pqcc7xg1m4", homeCode = "VLH", awayCode = "SAIK")
        assertTrue(OverlayParser.parseShlToday(text, listOf(game)).isEmpty())
    }

    // ---- HockeyAllsvenskan and Hockeyettan ----

    @Test
    fun sweLiveFinishedAndScheduled() {
        val games = listOf(TestData.ha, TestData.ettan, TestData.ha.copy(id = "ha-9057", homeId = "ha-khc", awayId = "ha-aik"))
        val obs = OverlayParser.parseSweToday(resource("swe-today.json"), games).associateBy { it.gameId }
        assertEquals(3, obs.size)
        val live = obs.getValue("ha-9056")
        assertEquals(GameState.LIVE, live.state)
        assertEquals(1, live.home)
        assertEquals(1, live.away)
        assertEquals(ms("2026-10-10T18:20:00Z"), live.readAtMs)
        val done = obs.getValue("et-1113459")
        assertEquals(GameState.FINISHED, done.state)
        assertEquals("SO", done.decision)
        assertEquals(3, done.home)
        assertEquals(4, done.away)
        assertEquals(GameState.SCHEDULED, obs.getValue("ha-9057").state)
    }

    @Test
    fun sweEntryForOtherClubsUnderTheSameIdIsIgnored() {
        val wrong = TestData.ha.copy(homeId = "ha-xxx")
        assertTrue(OverlayParser.parseSweToday(resource("swe-today.json"), listOf(wrong)).isEmpty())
    }

    // ---- envelope ----

    @Test
    fun noOverlayYetAndGarbageGiveNothing() {
        val games = listOf(TestData.nhl, TestData.shl, TestData.ha)
        val garbage = listOf(resource("no-overlay-yet.json"), "", "not json", "[]", "{\"v\":1}", "{\"v\":2,\"kind\":\"nhl-today\",\"fetchedAt\":\"2026-10-10T21:30:15Z\",\"games\":[]}")
        for (text in garbage) {
            for (overlay in Overlay.values()) assertTrue("$overlay / $text", OverlayParser.parse(overlay, text, games).isEmpty())
        }
        assertTrue(OverlayParser.isNoOverlayYet(resource("no-overlay-yet.json")))
        assertFalse(OverlayParser.isNoOverlayYet("{\"error\":\"Not found\"}"))
        assertFalse(OverlayParser.isNoOverlayYet(null))
    }

    @Test
    fun anOverlayOfAnotherKindIsNotAccepted() {
        // swe-today text handed to the nhl parser: the kind does not match
        assertTrue(OverlayParser.parseNhlToday(resource("swe-today.json"), listOf(TestData.nhl)).isEmpty())
    }
}
