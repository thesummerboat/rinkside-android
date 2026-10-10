package se.jonasp.rinkside.engine

/** Shared fixtures of the engine tests. Everything here is synthetic. */
object TestData {
    const val T0 = 1_791_648_000_000L // 2026-10-10T16:00:00Z

    fun resource(name: String): String {
        val stream = TestData::class.java.getResourceAsStream("/overlays/$name")
            ?: throw IllegalStateException("missing test resource $name")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    val nhl = WatchedGame(
        id = "nhl-2026020500", league = "nhl", startMs = 1_791_673_200_000L,
        homeId = "det", awayId = "tor", homeName = "Detroit", awayName = "Toronto", homeCode = "DET", awayCode = "TOR", nhlId = 2026020500L,
    )
    val shl = WatchedGame(
        id = "shl-xh68muyldy", league = "shl", startMs = T0,
        homeId = "saik", awayId = "rbk", homeName = "Skellefteå", awayName = "Rögle", homeCode = "SAIK", awayCode = "RBK", shlId = "xh68muyldy",
    )
    val ha = WatchedGame(
        id = "ha-9056", league = "ha", startMs = 1_791_651_600_000L,
        homeId = "ha-aik", awayId = "ha-lif", homeName = "AIK", awayName = "Leksand",
    )
    val ettan = WatchedGame(
        id = "et-1113459", league = "ettan", startMs = 1_791_640_800_000L,
        homeId = "et-119105", awayId = "et-119104", homeName = "Älvsbyn", awayName = "Borlänge",
    )

    /** A game for the detector tests that is not tied to a particular league's overlay. */
    fun game(id: String = "g1", startMs: Long = T0, playoff: Boolean = false) = WatchedGame(
        id = id, league = "shl", startMs = startMs,
        homeId = "h", awayId = "a", homeName = "Home", awayName = "Away", shlId = "abcdef12", playoff = playoff,
    )

    fun obs(
        state: GameState,
        home: Int? = null,
        away: Int? = null,
        readAt: Long = T0,
        period: Int = 1,
        decision: String? = null,
        shootout: Boolean = false,
        gameId: String = "g1",
    ) = Observation(gameId, state, home, away, period, decision, shootout, readAt)

    fun live(h: Int, a: Int, readAt: Long, period: Int = 1, shootout: Boolean = false) = obs(GameState.LIVE, h, a, readAt, period, null, shootout)

    fun finished(h: Int, a: Int, readAt: Long, decision: String? = null, period: Int = 3) = obs(GameState.FINISHED, h, a, readAt, period, decision)

    fun scheduled(readAt: Long) = obs(GameState.SCHEDULED, null, null, readAt, 0)
}
