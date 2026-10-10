package se.jonasp.rinkside

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import se.jonasp.rinkside.engine.WatchPlan

/**
 * One alarm at a time wakes the app for the watch service: 10 minutes before the next followed game
 * (or two seconds from now when a game is on already). Exact alarms need USE_EXACT_ALARM / SCHEDULE_EXACT_ALARM;
 * without them the alarm falls back to an inexact one that Android may delay.
 */
object Scheduler {
    const val ACTION_ALARM = "se.jonasp.rinkside.action.WATCH_ALARM"
    private const val REQUEST_CODE = 4711
    private const val MIN_DELAY_MS = 2_000L

    /** Schedules the alarm for the next followed game (or cancels it when there is none). Returns the time, or null. */
    fun scheduleNext(ctx: Context): Long? {
        val app = ctx.applicationContext
        val now = System.currentTimeMillis()
        val snapshot = Store.snapshot(app)
        var trigger = WatchPlan.nextTriggerMs(snapshot?.games ?: emptyList(), Store.memory(app), now, Store.leadMs(app, now))
        val pausedUntil = Store.pausedUntil(app)
        if (trigger != null && pausedUntil > now) trigger = maxOf(trigger, pausedUntil)
        if (trigger == null) {
            cancel(app)
            Store.setNextAlarm(app, 0L)
            return null
        }
        val at = maxOf(trigger, now + MIN_DELAY_MS)
        setAlarm(app, at)
        Store.setNextAlarm(app, at)
        return at
    }

    /** A wake-up while the service runs, so a service killed by the system (or an OEM battery manager) comes back. */
    fun scheduleWatchdog(ctx: Context, atMs: Long) {
        val app = ctx.applicationContext
        setAlarm(app, atMs)
        Store.setNextAlarm(app, atMs)
    }

    /** Retry soon after a failed start of the service (never immediately: no loop). */
    fun scheduleRetry(ctx: Context, delayMs: Long) {
        val app = ctx.applicationContext
        val at = System.currentTimeMillis() + delayMs
        setAlarm(app, at)
        Store.setNextAlarm(app, at)
    }

    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pendingIntent(ctx))
    }

    fun canScheduleExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return false
        return am.canScheduleExactAlarms()
    }

    private fun setAlarm(ctx: Context, atMs: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(ctx)
        try {
            if (canScheduleExact(ctx)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        }
    }

    private fun pendingIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_ALARM)
        return PendingIntent.getBroadcast(ctx, REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
