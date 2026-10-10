package se.jonasp.rinkside

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fires 10 minutes before a followed game (and as the service's watchdog): starts the watch service when a game is on. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        GameWatchService.startIfNeeded(context.applicationContext)
    }
}
