package se.jonasp.rinkside

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import se.jonasp.rinkside.engine.GameMemory
import se.jonasp.rinkside.engine.MemoryJson
import se.jonasp.rinkside.engine.Snapshot
import se.jonasp.rinkside.engine.WatchPlan

/** Everything the app remembers, in one private SharedPreferences file. Nothing leaves the device. */
object Store {
    private const val PREFS = "rinkside_state"
    private const val K_SNAPSHOT = "snapshot"
    private const val K_MEMORY = "memory"
    private const val K_LOG = "log"
    private const val K_PAUSED_UNTIL = "paused_until"
    private const val K_FORCE_UNTIL = "force_until"
    private const val K_NEXT_ALARM = "next_alarm"
    private const val K_LAST_POLL_AT = "last_poll_at"
    private const val K_LAST_POLL_INFO = "last_poll_info"
    private const val K_NOTIF_ASKED = "notif_asked"
    private const val MAX_LOG_LINES = 40

    private fun prefs(ctx: Context): SharedPreferences = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- favourites and schedule read from the web app ----

    fun snapshot(ctx: Context): Snapshot? = Snapshot.fromJson(prefs(ctx).getString(K_SNAPSHOT, null))

    fun saveSnapshot(ctx: Context, snapshot: Snapshot) {
        prefs(ctx).edit().putString(K_SNAPSHOT, snapshot.toJson()).apply()
    }

    // ---- what the detection engine remembers per game ----

    fun memory(ctx: Context): Map<String, GameMemory> = MemoryJson.read(prefs(ctx).getString(K_MEMORY, null))

    fun saveMemory(ctx: Context, memory: Map<String, GameMemory>, knownIds: Set<String>) {
        val pruned = MemoryJson.prune(memory, knownIds, System.currentTimeMillis())
        val text = MemoryJson.write(pruned)
        val p = prefs(ctx)
        if (text != p.getString(K_MEMORY, null)) p.edit().putString(K_MEMORY, text).apply()
    }

    // ---- watch control ----

    fun pausedUntil(ctx: Context): Long = prefs(ctx).getLong(K_PAUSED_UNTIL, 0L)

    fun setPausedUntil(ctx: Context, ms: Long) {
        prefs(ctx).edit().putLong(K_PAUSED_UNTIL, ms).apply()
    }

    fun forceUntil(ctx: Context): Long = prefs(ctx).getLong(K_FORCE_UNTIL, 0L)

    fun setForceUntil(ctx: Context, ms: Long) {
        prefs(ctx).edit().putLong(K_FORCE_UNTIL, ms).apply()
    }

    /** How long before the start a game counts as "on": normally 10 minutes, wider after "Starta bevakning nu". */
    fun leadMs(ctx: Context, nowMs: Long): Long = if (nowMs < forceUntil(ctx)) WatchPlan.FORCE_LEAD_MS else WatchPlan.LEAD_MS

    fun nextAlarm(ctx: Context): Long = prefs(ctx).getLong(K_NEXT_ALARM, 0L)

    fun setNextAlarm(ctx: Context, ms: Long) {
        prefs(ctx).edit().putLong(K_NEXT_ALARM, ms).apply()
    }

    fun lastPollAt(ctx: Context): Long = prefs(ctx).getLong(K_LAST_POLL_AT, 0L)

    fun lastPollInfo(ctx: Context): String = prefs(ctx).getString(K_LAST_POLL_INFO, "") ?: ""

    fun setLastPoll(ctx: Context, atMs: Long, info: String) {
        prefs(ctx).edit().putLong(K_LAST_POLL_AT, atMs).putString(K_LAST_POLL_INFO, info).apply()
    }

    fun notifAsked(ctx: Context): Boolean = prefs(ctx).getBoolean(K_NOTIF_ASKED, false)

    fun setNotifAsked(ctx: Context) {
        prefs(ctx).edit().putBoolean(K_NOTIF_ASKED, true).apply()
    }

    // ---- a short log for the settings screen (what was seen, what was posted, what failed) ----

    @Synchronized
    fun log(ctx: Context, line: String) {
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
        val p = prefs(ctx)
        val lines = (p.getString(K_LOG, "") ?: "").split('\n').filter { it.isNotEmpty() }.toMutableList()
        lines.add("$stamp  $line")
        while (lines.size > MAX_LOG_LINES) lines.removeAt(0)
        p.edit().putString(K_LOG, lines.joinToString("\n")).apply()
    }

    fun logLines(ctx: Context): List<String> = (prefs(ctx).getString(K_LOG, "") ?: "").split('\n').filter { it.isNotEmpty() }
}
