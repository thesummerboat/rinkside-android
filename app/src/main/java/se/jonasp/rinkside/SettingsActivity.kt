package se.jonasp.rinkside

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import se.jonasp.rinkside.engine.GameEvent
import se.jonasp.rinkside.engine.GameState
import se.jonasp.rinkside.engine.Side
import se.jonasp.rinkside.engine.SimulationScript
import se.jonasp.rinkside.engine.WatchPlan
import se.jonasp.rinkside.engine.WatchedGame

/**
 * "Notiser": status of the permissions, the followed teams and games the app knows, and the buttons to test notifications.
 * Reached from the launcher shortcuts (long-press the app icon). Built in code, no layout files.
 */
class SettingsActivity : Activity() {

    companion object {
        private const val REQUEST_NOTIFICATIONS = 101
        private const val PAUSE_MS = 6 * 3_600_000L
        private const val FORCE_MS = 3 * 3_600_000L
    }

    private val ui = Handler(Looper.getMainLooper())
    private val refreshTick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 2_000L)
        }
    }

    private val bg = Color.parseColor("#0B1119")
    private val surface = Color.parseColor("#16202C")
    private val textColor = Color.parseColor("#E8EEF5")
    private val muted = Color.parseColor("#9FB0C3")
    private val accent = Color.parseColor("#4AA3FF")

    private lateinit var scroll: ScrollView
    private lateinit var testHeader: TextView
    private lateinit var notificationsLine: TextView
    private lateinit var notificationsButton: Button
    private lateinit var exactLine: TextView
    private lateinit var exactButton: Button
    private lateinit var batteryLine: TextView
    private lateinit var batteryButton: Button
    private lateinit var followedText: TextView
    private lateinit var gamesText: TextView
    private lateinit var watchText: TextView
    private lateinit var simulateButton: Button
    private lateinit var logText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.settings_title)

        val root = FrameLayout(this)
        root.setBackgroundColor(bg)
        scroll = ScrollView(this)
        scroll.isFillViewport = true
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsets.CONSUMED
            }
        }

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(dp(16), dp(16), dp(16), dp(32))
        scroll.addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        column.addView(text(getString(R.string.settings_title), 26f, textColor, bold = true))

        // Status
        column.addView(header(getString(R.string.section_status)))
        notificationsLine = text("", 15f, textColor)
        column.addView(notificationsLine)
        notificationsButton = button("") { onNotificationsButton() }
        column.addView(notificationsButton)
        exactLine = text("", 15f, textColor)
        column.addView(exactLine)
        exactButton = button(getString(R.string.btn_alarm_settings)) { onExactAlarmButton() }
        column.addView(exactButton)
        batteryLine = text("", 15f, textColor)
        column.addView(batteryLine)
        batteryButton = button("") { onBatteryButton() }
        column.addView(batteryButton)

        // Followed teams and games
        column.addView(header(getString(R.string.section_followed)))
        followedText = text("", 15f, textColor)
        column.addView(followedText)
        column.addView(header(getString(R.string.section_games)))
        gamesText = text("", 14f, textColor)
        column.addView(gamesText)

        // Watching
        column.addView(header(getString(R.string.section_watch)))
        watchText = text("", 15f, textColor)
        column.addView(watchText)
        column.addView(button(getString(R.string.btn_start_watch)) { onStartWatch() })
        column.addView(button(getString(R.string.btn_stop_watch)) { onStopWatch() })

        // Test
        testHeader = header(getString(R.string.section_test))
        column.addView(testHeader)
        column.addView(button(getString(R.string.btn_test_start)) { testNotification { GameEvent.MatchStart(it) } })
        column.addView(button(getString(R.string.btn_test_goal)) { testNotification { GameEvent.Goal(it, 2, 1, Side.HOME) } })
        column.addView(button(getString(R.string.btn_test_disallowed)) { testNotification { GameEvent.GoalDisallowed(it, 1, 1, Side.HOME) } })
        column.addView(button(getString(R.string.btn_test_end)) { testNotification { GameEvent.MatchEnd(it, 3, 2, "OT") } })
        simulateButton = button(getString(R.string.btn_simulate)) { onSimulate() }
        column.addView(simulateButton)

        // Log
        column.addView(header(getString(R.string.section_log)))
        logText = text("", 12f, muted)
        logText.typeface = Typeface.MONOSPACE
        column.addView(logText)

        Notifier.ensureChannels(this)
        refresh()

        if (intent?.getStringExtra("section") == "test") {
            scroll.post { scroll.scrollTo(0, testHeader.top) }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        ui.postDelayed(refreshTick, 2_000L)
    }

    override fun onPause() {
        ui.removeCallbacks(refreshTick)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getStringExtra("section") == "test") {
            scroll.post { scroll.scrollTo(0, testHeader.top) }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    // ---- status ----

    private fun refresh() {
        val now = System.currentTimeMillis()

        val canNotify = Notifier.canPost(this)
        notificationsLine.text = getString(if (canNotify) R.string.status_notifications_on else R.string.status_notifications_off)
        notificationsLine.setTextColor(if (canNotify) textColor else Color.parseColor("#FF8B8F"))
        notificationsButton.text = getString(if (canNotify) R.string.btn_notification_settings else R.string.btn_allow_notifications)

        val exact = Scheduler.canScheduleExact(this)
        exactLine.text = getString(if (exact) R.string.status_exact_on else R.string.status_exact_off)
        exactLine.setTextColor(if (exact) textColor else Color.parseColor("#F6BE5D"))
        exactButton.visibility = if (exact) View.GONE else View.VISIBLE

        val pm = getSystemService(PowerManager::class.java)
        val exempt = pm != null && pm.isIgnoringBatteryOptimizations(packageName)
        batteryLine.text = getString(if (exempt) R.string.status_battery_on else R.string.status_battery_off)
        batteryLine.setTextColor(if (exempt) textColor else Color.parseColor("#F6BE5D"))
        batteryButton.text = getString(if (exempt) R.string.btn_battery_settings else R.string.btn_battery_exempt)

        val snapshot = Store.snapshot(this)
        followedText.text = when {
            snapshot == null || snapshot.savedAtMs == 0L -> getString(R.string.followed_not_read)
            snapshot.teams.isEmpty() -> getString(R.string.followed_none)
            else -> snapshot.teams.joinToString(", ") { snapshot.teamNames[it] ?: it } +
                "\n" + getString(R.string.followed_updated, formatDateTime(snapshot.savedAtMs))
        }

        val memory = Store.memory(this)
        val games = (snapshot?.games ?: emptyList()).filter { WatchPlan.isWatchable(it) && !WatchPlan.isDone(memory[it.id]) && it.startMs + WatchPlan.TAIL_MS > now }
        gamesText.text = if (games.isEmpty()) {
            getString(R.string.games_none)
        } else {
            games.take(10).joinToString("\n") { gameLine(it, memory[it.id]?.state) }
        }

        val pausedUntil = Store.pausedUntil(this)
        val lines = ArrayList<String>()
        lines.add(
            when {
                GameWatchService.running -> getString(R.string.watch_running)
                pausedUntil > now -> getString(R.string.watch_paused, formatDateTime(pausedUntil))
                else -> getString(R.string.watch_idle)
            },
        )
        val next = Store.nextAlarm(this)
        lines.add(if (next > now) getString(R.string.watch_next_alarm, formatDateTime(next)) else getString(R.string.watch_no_alarm))
        val lastPoll = Store.lastPollAt(this)
        lines.add(
            if (lastPoll > 0L) {
                getString(R.string.watch_last_poll, formatDateTime(lastPoll) + "  " + Store.lastPollInfo(this))
            } else {
                getString(R.string.watch_never)
            },
        )
        watchText.text = lines.joinToString("\n")

        simulateButton.isEnabled = !Simulator.running

        val log = Store.logLines(this)
        logText.text = if (log.isEmpty()) getString(R.string.log_empty) else log.takeLast(25).reversed().joinToString("\n")
    }

    private fun gameLine(g: WatchedGame, state: GameState?): String {
        val mark = when (state) {
            GameState.LIVE -> " (live)"
            GameState.SCHEDULED -> ""
            else -> ""
        }
        return g.homeName + " – " + g.awayName + "  ·  " + formatDateTime(g.startMs) + "  ·  " + g.league.uppercase(Locale.ROOT) + mark
    }

    private fun formatDateTime(ms: Long): String = SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault()).format(Date(ms))

    // ---- buttons ----

    private fun onNotificationsButton() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            (!Store.notifAsked(this) || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
        ) {
            Store.setNotifAsked(this)
            Notifier.ensureChannels(this)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        } else {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            startSettings(intent)
        }
    }

    private fun onExactAlarmButton() {
        if (Build.VERSION.SDK_INT >= 31) {
            startSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }
    }

    private fun onBatteryButton() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm != null && pm.isIgnoringBatteryOptimizations(packageName)) {
            startSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } else {
            startSettings(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    /** Opens a system settings page; if this phone does not have that exact page, the app's own details page instead. */
    private fun startSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (e2: Exception) {
                // nothing more to try
            }
        }
    }

    private fun onStartWatch() {
        val now = System.currentTimeMillis()
        Store.setPausedUntil(this, 0L)
        Store.setForceUntil(this, now + FORCE_MS)
        val started = GameWatchService.startIfNeeded(this)
        if (started) {
            Store.log(this, "Watching started by the button")
        } else {
            Store.setForceUntil(this, 0L)
        }
        Toast.makeText(this, if (started) R.string.toast_watch_started else R.string.toast_nothing_to_watch, Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun onStopWatch() {
        Store.setPausedUntil(this, System.currentTimeMillis() + PAUSE_MS)
        Store.setForceUntil(this, 0L)
        GameWatchService.stop(this)
        Scheduler.scheduleNext(this)
        Store.log(this, "Watching paused by the button")
        Toast.makeText(this, R.string.toast_watch_stopped, Toast.LENGTH_SHORT).show()
        refresh()
    }

    /** The next followed game if the app knows one (a tap on the notification then opens its match page), otherwise a sample game. */
    private fun testGame(): WatchedGame {
        val now = System.currentTimeMillis()
        val next = Store.snapshot(this)?.games?.firstOrNull { it.startMs > now - 6 * 3_600_000L }
        return next ?: SimulationScript.game(now)
    }

    private fun testNotification(make: (WatchedGame) -> GameEvent) {
        if (!Notifier.canPost(this)) {
            Toast.makeText(this, R.string.toast_need_permission, Toast.LENGTH_LONG).show()
            onNotificationsButton()
            return
        }
        Notifier.post(this, make(testGame()))
    }

    private fun onSimulate() {
        if (!Notifier.canPost(this)) {
            Toast.makeText(this, R.string.toast_need_permission, Toast.LENGTH_LONG).show()
            onNotificationsButton()
            return
        }
        simulateButton.isEnabled = false
        Simulator.start(this) { step, total, finished ->
            runOnUiThread {
                if (finished) {
                    Toast.makeText(this, R.string.sim_done, Toast.LENGTH_SHORT).show()
                    simulateButton.text = getString(R.string.btn_simulate)
                    refresh()
                } else {
                    simulateButton.text = getString(R.string.sim_running, step, total)
                }
            }
        }
    }

    // ---- small view helpers ----

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun text(content: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView {
        val tv = TextView(this)
        tv.text = content
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        tv.setTextColor(color)
        if (bold) tv.setTypeface(tv.typeface, Typeface.BOLD)
        tv.setPadding(0, dp(4), 0, dp(4))
        return tv
    }

    private fun header(content: String): TextView {
        val tv = text(content.uppercase(Locale.getDefault()), 13f, accent, bold = true)
        tv.setPadding(0, dp(24), 0, dp(6))
        return tv
    }

    private fun button(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setTextColor(textColor)
        b.gravity = Gravity.CENTER
        val shape = GradientDrawable()
        shape.setColor(surface)
        shape.cornerRadius = dp(10).toFloat()
        shape.setStroke(dp(1), Color.parseColor("#2A3A4D"))
        b.background = shape
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(6)
        b.layoutParams = lp
        b.setOnClickListener { onClick() }
        return b
    }
}
