package se.jonasp.rinkside

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.util.concurrent.atomic.AtomicInteger
import se.jonasp.rinkside.engine.GameClock
import se.jonasp.rinkside.engine.GameEvent
import se.jonasp.rinkside.engine.Side
import se.jonasp.rinkside.engine.WatchedGame

/** Channels and notifications. Real events and the test buttons both come through [post], so a test shows exactly what a real one would. */
object Notifier {
    // Every match event pops up on the screen (heads-up, importance high). Android never lets an app raise the importance of a channel it already
    // created, so the channels that 0.1.x made with default importance get new ids (..._alert) and the old ones are deleted.
    const val CHANNEL_START = "start_alert"
    const val CHANNEL_GOAL = "goal"
    const val CHANNEL_DISALLOWED = "disallowed_alert"
    const val CHANNEL_END = "end_alert"
    private val OLD_CHANNELS = listOf("start", "disallowed", "end")
    const val CHANNEL_WATCH = "watch"
    const val WATCH_NOTIFICATION_ID = 1

    private val nextId = AtomicInteger((System.currentTimeMillis() / 1000).toInt() and 0x3fffffff)

    /** Only real web app match ids can be opened with #match/<id>; the simulation and sample games open the app itself. */
    private val OPENABLE_ID = Regex("^(nhl|shl|ha|et)-[A-Za-z0-9]{1,40}$")

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        for (old in OLD_CHANNELS) if (nm.getNotificationChannel(old) != null) nm.deleteNotificationChannel(old)
        fun channel(id: String, name: Int, desc: Int, importance: Int, quiet: Boolean) {
            val c = NotificationChannel(id, ctx.getString(name), importance)
            c.description = ctx.getString(desc)
            if (quiet) {
                c.setSound(null, null)
                c.enableVibration(false)
                c.setShowBadge(false)
            }
            nm.createNotificationChannel(c)
        }
        channel(CHANNEL_START, R.string.channel_start_name, R.string.channel_start_desc, NotificationManager.IMPORTANCE_HIGH, false)
        channel(CHANNEL_GOAL, R.string.channel_goal_name, R.string.channel_goal_desc, NotificationManager.IMPORTANCE_HIGH, false)
        channel(CHANNEL_DISALLOWED, R.string.channel_disallowed_name, R.string.channel_disallowed_desc, NotificationManager.IMPORTANCE_HIGH, false)
        channel(CHANNEL_END, R.string.channel_end_name, R.string.channel_end_desc, NotificationManager.IMPORTANCE_HIGH, false)
        channel(CHANNEL_WATCH, R.string.channel_watch_name, R.string.channel_watch_desc, NotificationManager.IMPORTANCE_LOW, true)
    }

    /** True when Android will actually show our notifications (Android 13+: POST_NOTIFICATIONS granted; any version: not switched off in settings). */
    fun canPost(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return true
        return nm.areNotificationsEnabled()
    }

    /** A notification that was posted, kept by the caller so that a goal can get the scorer's name afterwards (see [update]). */
    class Handle(val id: Int, val whenMs: Long)

    /** Posts the notification for [event]. Returns false when Android would not show it (the reason goes to the log). */
    fun post(ctx: Context, event: GameEvent): Boolean = postEvent(ctx, event) != null

    /** Like [post], but returns the posted notification, or null when Android would not show it. */
    fun postEvent(ctx: Context, event: GameEvent): Handle? {
        val app = ctx.applicationContext
        ensureChannels(app)
        if (!canPost(app)) {
            Store.log(app, "Notification blocked by Android (permission or settings): " + describe(event))
            return null
        }
        val nm = app.getSystemService(NotificationManager::class.java) ?: return null
        val handle = Handle(nextId.incrementAndGet(), System.currentTimeMillis())
        nm.notify(handle.id, build(app, event, handle, onlyAlertOnce = false))
        return handle
    }

    /**
     * Rewrites a notification that is still showing, for the goal whose scorer was not known when it was posted. It does not alert a second
     * time. Returns false when the notification is gone (dismissed, or never shown), in which case nothing is posted.
     */
    fun update(ctx: Context, handle: Handle, event: GameEvent): Boolean {
        val app = ctx.applicationContext
        val nm = app.getSystemService(NotificationManager::class.java) ?: return false
        if (nm.activeNotifications.none { it.id == handle.id }) return false
        nm.notify(handle.id, build(app, event, handle, onlyAlertOnce = true))
        return true
    }

    private fun build(app: Context, event: GameEvent, handle: Handle, onlyAlertOnce: Boolean): Notification {
        val content = content(app, event)
        return Notification.Builder(app, content.channel)
            .setSmallIcon(R.drawable.ic_stat_puck)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setCategory(Notification.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setWhen(handle.whenMs)
            .setOnlyAlertOnce(onlyAlertOnce)
            .setContentIntent(openIntent(app, event.game.id, handle.id))
            .build()
    }

    private class Content(val channel: String, val title: String, val text: String)

    private fun content(ctx: Context, event: GameEvent): Content {
        val g = event.game
        return when (event) {
            is GameEvent.MatchStart -> Content(
                CHANNEL_START,
                ctx.getString(R.string.notif_start_title, g.homeName, g.awayName),
                withScorer(ctx.getString(R.string.notif_start_text), event),
            )
            is GameEvent.Goal -> Content(
                CHANNEL_GOAL,
                ctx.getString(R.string.notif_goal_title, g.homeName, event.home, event.away, g.awayName),
                withScorer(ctx.getString(R.string.notif_goal_text, sideName(g, event.side)), event),
            )
            is GameEvent.GoalDisallowed -> Content(
                CHANNEL_DISALLOWED,
                ctx.getString(R.string.notif_disallowed_title, g.homeName, event.home, event.away, g.awayName),
                withScorer(ctx.getString(R.string.notif_disallowed_text, sideName(g, event.side)), event),
            )
            is GameEvent.MatchEnd -> {
                val suffix = when (event.decision) {
                    "OT" -> " (" + ctx.getString(R.string.decision_ot) + ")"
                    "SO" -> " (" + ctx.getString(R.string.decision_so) + ")"
                    else -> ""
                }
                val winner = if (event.home > event.away) g.homeName else g.awayName
                Content(
                    CHANNEL_END,
                    ctx.getString(R.string.notif_end_title, g.homeName, event.home, event.away, g.awayName) + suffix,
                    withScorer(ctx.getString(R.string.notif_end_text, winner), event),
                )
            }
        }
    }

    private fun sideName(g: WatchedGame, side: Side): String = if (side == Side.HOME) g.homeName else g.awayName

    /**
     * The scorer and the elapsed game time of a goal ("Janne Kuokkanen (35:53)"), when the live stream has told them. The overlays carry scores
     * only, so a goal that only an overlay has seen (the stream was down or slower) is shown without. Other events have no such line.
     */
    private fun scorerLine(event: GameEvent): String? {
        if (event !is GameEvent.Goal) return null
        val name = event.scorer ?: return null
        val at = event.gameSec?.let { " (" + GameClock.format(it) + ")" } ?: ""
        return name + at
    }

    private fun withScorer(text: String, event: GameEvent): String {
        val scorer = scorerLine(event)
        return if (scorer.isNullOrEmpty()) text else "$text · $scorer"
    }

    private fun describe(event: GameEvent): String = when (event) {
        is GameEvent.MatchStart -> "start ${event.game.homeName} - ${event.game.awayName}"
        is GameEvent.Goal -> "goal ${event.home}-${event.away} (${event.game.homeName} - ${event.game.awayName})"
        is GameEvent.GoalDisallowed -> "disallowed ${event.home}-${event.away} (${event.game.homeName} - ${event.game.awayName})"
        is GameEvent.MatchEnd -> "end ${event.home}-${event.away} ${event.decision ?: ""} (${event.game.homeName} - ${event.game.awayName})"
    }

    /** A line for the log of the settings screen. */
    fun logLine(event: GameEvent): String = describe(event)

    /** The ongoing notification of the watch service (silent, low importance). */
    fun buildWatch(ctx: Context, title: String, text: String): Notification {
        val builder = Notification.Builder(ctx, CHANNEL_WATCH)
            .setSmallIcon(R.drawable.ic_stat_puck)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(openIntent(ctx, "", 7))
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    /** Tapping a notification opens the app, on the match page when [gameId] is a real web app match id. */
    private fun openIntent(ctx: Context, gameId: String, requestCode: Int): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        if (OPENABLE_ID.matches(gameId)) intent.putExtra(MainActivity.EXTRA_MATCH_ID, gameId)
        return PendingIntent.getActivity(ctx, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
