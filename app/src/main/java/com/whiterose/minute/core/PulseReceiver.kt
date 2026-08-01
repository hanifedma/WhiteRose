package com.whiterose.minute.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.whiterose.minute.data.SettingsStore

/** Receives the minute alarm, buzzes, and arms the next one. */
class PulseReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PulseScheduler.ACTION_TICK) return

        Pulser.tick(context)

        // An exact alarm grants a brief window in which a foreground service may be started,
        // so this is where the service comes back if the system reclaimed the process.
        if (SettingsStore.get(context).current.enabled) {
            PulseService.ensureRunning(context.applicationContext)
        }
    }
}
