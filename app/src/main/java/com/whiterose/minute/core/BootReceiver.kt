package com.whiterose.minute.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.whiterose.minute.data.SettingsStore

/** Brings the pulse back after a restart, an app update, or a change to the clock. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val store = SettingsStore.get(context)
        val prefs = store.current

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, ACTION_QUICKBOOT, ACTION_QUICKBOOT_HTC -> {
                if (!prefs.enabled) return
                if (prefs.startOnBoot) {
                    // Arm the alarm here as well as in the service: if the service start is
                    // refused for any reason, the alarm chain alone still keeps the pulse.
                    PulseScheduler.schedule(context)
                    PulseService.start(context)
                } else {
                    store.setEnabled(false)
                }
            }

            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (!prefs.enabled) return
                PulseScheduler.schedule(context)
                PulseService.start(context)
            }

            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                // Alarms are anchored to wall-clock minutes, so re-anchor to the new clock.
                if (prefs.enabled) PulseScheduler.schedule(context)
            }
        }
    }

    private companion object {
        const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
        const val ACTION_QUICKBOOT_HTC = "com.htc.intent.action.QUICKBOOT_POWERON"
    }
}
