package se.jonasp.rinkside

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import se.jonasp.rinkside.engine.GameTracker
import se.jonasp.rinkside.engine.Overlay
import se.jonasp.rinkside.engine.OverlayParser
import se.jonasp.rinkside.engine.WatchPlan
import se.jonasp.rinkside.engine.WatchedGame

/**
 * Foreground service that follows the games of the followed teams while they are on.
 *
 * Every 15 s it reads only the overlays its games need (nhl-today, shl-today, swe-today, shl-schedule-status), hands the answers to the
 * detection engine (se.jonasp.rinkside.engine) and posts what the engine reports. It stops itself when no followed game is in its window
 * (10 minutes before the start until the game is over, at most 4 hours after the start) and sets the alarm for the next game.
 */
class GameWatchService : Service() {

    companion object {
        @Volatile
        var running: Boolean = false
            private set

        private const val WATCHDOG_AFTER_MS = 5 * 60_000L
        private const val WATCHDOG_REFRESH_MS = 3 * 60_000L
        private const val WAKE_LOCK_MS = 10 * 60_000L
        private const val RETRY_AFTER_FAILED_START_MS = 60_000L

        /**
         * Starts the service when a followed game is in its window (and watching is not paused). Otherwise only the next alarm is set.
         * Returns true when the service was started.
         */
        fun startIfNeeded(ctx: Context): Boolean {
            val app = ctx.applicationContext
            val now = System.currentTimeMillis()
            if (Store.pausedUntil(app) > now) {
                Scheduler.scheduleNext(app)
                return false
            }
            val games = Store.snapshot(app)?.games ?: emptyList()
            val active = WatchPlan.activeGames(games, Store.memory(app), now, Store.leadMs(app, now))
            if (active.isEmpty()) {
                Scheduler.scheduleNext(app)
                return false
            }
            return try {
                app.startForegroundService(Intent(app, GameWatchService::class.java))
                true
            } catch (e: Exception) {
                // Android refused (a background start without an exemption): say so in the log and try again in a minute.
                Store.log(app, "Could not start the watch service: " + (e.message ?: e.javaClass.simpleName))
                Scheduler.scheduleRetry(app, RETRY_AFTER_FAILED_START_MS)
                false
            }
        }

        fun stop(ctx: Context) {
            val app = ctx.applicationContext
            app.stopService(Intent(app, GameWatchService::class.java))
        }
    }

    private class PollState {
        var nextAtMs: Long = 0L
        var failures: Int = 0
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var stopRequested = false

    @Volatile
    private var stopLatch = CountDownLatch(1)
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var shownKey: String = ""

    @Volatile
    private var shownTitle: String = ""

    @Volatile
    private var shownText: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must go to the foreground within seconds of startForegroundService(): do it first, decide everything else afterwards.
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        holdWakeLock()
        if (worker?.isAlive != true) {
            stopRequested = false
            stopLatch = CountDownLatch(1)
            val t = Thread({ loop() }, "rinkside-watch")
            worker = t
            t.start()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRequested = true
        stopLatch.countDown()
        releaseWakeLock()
        running = false
        super.onDestroy()
    }

    // ---- the loop (background thread) ----

    private fun loop() {
        val app = applicationContext
        val tracker = GameTracker(Store.memory(app))
        val states = HashMap<Overlay, PollState>()
        var lastWatchdog = 0L
        var crashes = 0
        Store.log(app, "Watching started")
        try {
            while (!stopRequested) {
                try {
                    val now = System.currentTimeMillis()
                    if (Store.pausedUntil(app) > now) {
                        Store.log(app, "Watching is paused")
                        break
                    }
                    val games = Store.snapshot(app)?.games ?: emptyList()
                    val active = WatchPlan.activeGames(games, tracker.memory(), now, Store.leadMs(app, now))
                    if (active.isEmpty()) break
                    showWatchNotification(active)
                    if (now - lastWatchdog >= WATCHDOG_REFRESH_MS) {
                        Scheduler.scheduleWatchdog(app, now + WATCHDOG_AFTER_MS)
                        holdWakeLock()
                        lastWatchdog = now
                    }
                    var nextWake = now + WatchPlan.POLL_MS
                    for (overlay in WatchPlan.overlaysFor(active, now)) {
                        val state = states.getOrPut(overlay) { PollState() }
                        if (now >= state.nextAtMs) pollOnce(overlay, state, games, tracker)
                        nextWake = minOf(nextWake, state.nextAtMs)
                    }
                    crashes = 0
                    sleepUntil(maxOf(nextWake, System.currentTimeMillis() + 1_000L))
                } catch (e: Exception) {
                    crashes++
                    Store.log(app, "Error in the watch loop: " + (e.message ?: e.javaClass.simpleName))
                    if (crashes >= 5) break
                    sleepUntil(System.currentTimeMillis() + WatchPlan.POLL_MS)
                }
            }
        } finally {
            Store.saveMemory(app, tracker.memory(), (Store.snapshot(app)?.games ?: emptyList()).map { it.id }.toSet())
            if (!stopRequested) finishAndStop()
        }
    }

    private fun pollOnce(overlay: Overlay, state: PollState, games: List<WatchedGame>, tracker: GameTracker) {
        val app = applicationContext
        val response = try {
            OverlayHttp.get(BuildConfig.BASE_URL, overlay.path)
        } catch (e: Exception) {
            fail(overlay, state, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
            return
        }
        val after = System.currentTimeMillis()
        when {
            response.code == 200 -> {
                val observations = OverlayParser.parse(overlay, response.body, games)
                val events = tracker.process(games, observations, after)
                // Save what was seen BEFORE posting: if the process dies in between, a notification is lost rather than repeated.
                Store.saveMemory(app, tracker.memory(), games.map { it.id }.toSet())
                for (event in events) {
                    Store.log(app, Notifier.logLine(event))
                    Notifier.post(app, event)
                }
                state.failures = 0
                state.nextAtMs = after + WatchPlan.pollIntervalMs(overlay)
                Store.setLastPoll(app, after, overlay.path + " ok, " + observations.size + " game(s)")
            }
            response.code == 404 && OverlayParser.isNoOverlayYet(response.body) -> {
                state.failures = 0
                state.nextAtMs = after + 60_000L
                Store.setLastPoll(app, after, overlay.path + " (no data yet)")
            }
            else -> fail(overlay, state, "HTTP " + response.code)
        }
    }

    private fun fail(overlay: Overlay, state: PollState, reason: String) {
        val app = applicationContext
        val now = System.currentTimeMillis()
        state.failures++
        state.nextAtMs = now + WatchPlan.backoffMs(state.failures)
        if (state.failures == 1 || state.failures % 10 == 0) {
            Store.log(app, overlay.path + " failed (" + state.failures + "): " + reason)
        }
        Store.setLastPoll(app, now, overlay.path + " failed: " + reason)
    }

    private fun sleepUntil(wakeAtMs: Long) {
        val ms = wakeAtMs - System.currentTimeMillis()
        if (ms > 0 && !stopRequested) {
            try {
                stopLatch.await(ms, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                stopRequested = true
            }
        }
    }

    private fun finishAndStop() {
        val app = applicationContext
        Store.log(app, "Watching stopped (no followed game is on)")
        Scheduler.scheduleNext(app)
        main.post {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // ---- notification and wake lock ----

    private fun enterForeground(): Boolean {
        // A service that is started again while it runs keeps showing what it showed (the loop only updates it when the games change).
        val title = shownTitle.ifEmpty { getString(R.string.app_name) }
        val text = shownText.ifEmpty { getString(R.string.watch_text_starting) }
        val notification = Notifier.buildWatch(this, title, text)
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Notifier.WATCH_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(Notifier.WATCH_NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            Store.log(applicationContext, "Could not enter the foreground: " + (e.message ?: e.javaClass.simpleName))
            false
        }
    }

    private fun showWatchNotification(active: List<WatchedGame>) {
        val key = active.joinToString(",") { it.id }
        if (key == shownKey) return
        shownKey = key
        shownText = active.joinToString(", ") { it.homeName + " – " + it.awayName }
        shownTitle = resources.getQuantityString(R.plurals.watch_title, active.size, active.size)
        val notification = Notifier.buildWatch(this, shownTitle, shownText)
        getSystemService(NotificationManager::class.java)?.notify(Notifier.WATCH_NOTIFICATION_ID, notification)
    }

    /**
     * The CPU must stay awake between two polls: with the screen off a sleeping device would otherwise stop the 15 s timer.
     * The lock is short (10 minutes) and renewed by the loop, so it cannot outlive a dead loop for long.
     */
    @Synchronized
    private fun holdWakeLock() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        val lock = wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rinkside:watch").also {
            it.setReferenceCounted(false)
            wakeLock = it
        }
        lock.acquire(WAKE_LOCK_MS)
    }

    @Synchronized
    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: RuntimeException) {
            // already released
        }
        wakeLock = null
    }
}
