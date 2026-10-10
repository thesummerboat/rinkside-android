package se.jonasp.rinkside

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Alarms are gone after a restart and after an update of the app: set the next one again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Scheduler.scheduleNext(context.applicationContext)
        }
    }
}
