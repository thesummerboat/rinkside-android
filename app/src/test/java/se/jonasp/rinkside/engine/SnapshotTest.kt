package se.jonasp.rinkside.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.jonasp.rinkside.engine.TestData.resource

class SnapshotTest {
    @Test
    fun pageScriptResultBecomesSnapshotWithOnlyWatchableGames() {
        val s = Snapshot.fromPageScript(resource("page-script-result.json"), 5_000L)
        assertNotNull(s)
        s!!
        assertEquals(listOf("saik", "det"), s.teams)
        assertEquals(listOf("shl", "nhl"), s.leagues)
        assertEquals("Skellefteå", s.teamNames["saik"])
        assertEquals(5_000L, s.savedAtMs)
        // the demo league row and the NHL row without an NHL id are dropped
        assertEquals(listOf("shl-xh68muyldy", "ha-9056", "nhl-2026020500"), s.games.map { it.id })
        val shl = s.games.first { it.id == "shl-xh68muyldy" }
        assertEquals("xh68muyldy", shl.shlId)
        assertEquals("SAIK", shl.homeCode)
        assertEquals("Rögle", shl.awayName)
        assertEquals(1_791_648_000_000L, shl.startMs)
        assertFalse(shl.playoff)
        val nhl = s.games.first { it.id == "nhl-2026020500" }
        assertEquals(2026020500L, nhl.nhlId)
        assertNull(nhl.shlId)
    }

    @Test
    fun gameTypeThreeMeansPlayoffs() {
        val text = resource("page-script-result.json").replace("\"shlId\": \"xh68muyldy\", \"gameType\": 2", "\"shlId\": \"xh68muyldy\", \"gameType\": 3")
        val s = Snapshot.fromPageScript(text, 1L)!!
        assertTrue(s.games.first { it.id == "shl-xh68muyldy" }.playoff)
    }

    @Test
    fun notReadyMeansNull() {
        assertNull(Snapshot.fromPageScript("{\"ready\":false}", 1L))
        assertNull(Snapshot.fromPageScript("{\"ready\":false,\"error\":\"x\"}", 1L))
        assertNull(Snapshot.fromPageScript(null, 1L))
        assertNull(Snapshot.fromPageScript("garbage", 1L))
    }

    @Test
    fun storedSnapshotRoundTrips() {
        val s = Snapshot.fromPageScript(resource("page-script-result.json"), 7_000L)!!
        val again = Snapshot.fromJson(s.toJson())!!
        assertEquals(s, again)
    }

    @Test
    fun anOddRecordNeverSpoilsTheRest() {
        val text = "{\"ready\":true,\"teams\":[\"a\",5,\"\"],\"leagues\":\"x\",\"games\":[1,\"x\",{\"id\":\"../evil\",\"league\":\"shl\"},{}]}"
        val s = Snapshot.fromPageScript(text, 1L)!!
        assertEquals(listOf("a"), s.teams)
        assertTrue(s.games.isEmpty())
    }

    @Test
    fun javascriptResultIsAQuotedStringThatIsUnwrapped() {
        val inner = "{\"ready\":true,\"teams\":[\"saik\"],\"games\":[]}"
        val wrapped = JSONObject.quote(inner) // what evaluateJavascript hands to its callback
        assertEquals(inner, unwrapJsResult(wrapped))
        assertNull(unwrapJsResult("null"))
        assertNull(unwrapJsResult(null))
        assertNull(unwrapJsResult("42"))
    }

    @Test
    fun memoryJsonKeepsTheStreamTimeTheGameTimeAndTheAnnouncedLines() {
        val map = mapOf(
            "a" to GameMemory(GameState.LIVE, 2, 1, 1000L, false, 5000L, 1920, setOf("1-0", "1-1", "2-1"), setOf("2-1>1-1")),
            "b" to GameMemory(GameState.FINISHED, 3, 2, 3000L, true),
        )
        val text = MemoryJson.write(map)
        assertEquals(map, MemoryJson.read(text))
        // a game without any of the four new facts is written as 0.1.3 wrote it
        val plain = JSONObject(text).getJSONObject("b")
        assertEquals(setOf("s", "h", "a", "r", "so"), plain.keys().asSequence().toSet())
    }

    @Test
    fun memoryWrittenByAnOlderVersionStillReads() {
        val old = "{\"g1\":{\"s\":\"LIVE\",\"h\":1,\"a\":0,\"r\":1000,\"so\":false}}"
        assertEquals(mapOf("g1" to GameMemory(GameState.LIVE, 1, 0, 1000L, false)), MemoryJson.read(old))
    }

    @Test
    fun oddAnnouncedLinesAreDropped() {
        val text = "{\"g1\":{\"s\":\"LIVE\",\"h\":1,\"a\":0,\"r\":1000,\"so\":false,\"gl\":[\"1-0\",\"x\",5,\"1-0>0-0\"],\"dl\":[\"1-0>0-0\",\"1-0\"],\"gs\":-4,\"sa\":\"soon\"}}"
        val m = MemoryJson.read(text).getValue("g1")
        assertEquals(setOf("1-0"), m.goalLines)
        assertEquals(setOf("1-0>0-0"), m.dropLines)
        assertNull(m.gameSec)
        assertNull(m.streamAtMs)
    }

    @Test
    fun memoryJsonRoundTripsAndPrunes() {
        val map = mapOf(
            "a" to GameMemory(GameState.LIVE, 1, 0, 1000L, false),
            "b" to GameMemory(GameState.SCHEDULED, null, null, 2000L, false),
            "c" to GameMemory(GameState.FINISHED, 4, 3, 3000L, true),
        )
        assertEquals(map, MemoryJson.read(MemoryJson.write(map)))
        val pruned = MemoryJson.prune(map, setOf("a"), 3000L + 4 * 24 * 3_600_000L, 3 * 24 * 3_600_000L)
        assertEquals(setOf("a"), pruned.keys)
        assertTrue(MemoryJson.read("garbage").isEmpty())
        assertTrue(MemoryJson.read("{\"x\":{\"s\":\"NOPE\"}}").isEmpty())
    }
}
