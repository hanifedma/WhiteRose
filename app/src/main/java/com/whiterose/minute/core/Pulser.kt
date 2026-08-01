package com.whiterose.minute.core

import android.content.Context
import com.whiterose.minute.data.SettingsStore

/**
 * The single place a buzz can happen. Both the alarm and the in-service timer call [tick];
 * the minute claim below means whichever arrives first wins and the other is a no-op, so a
 * belt-and-braces setup can never double-buzz.
 */
object Pulser {

    private var lastMinute = -1L

    fun tick(context: Context) {
        val app = context.applicationContext
        val store = SettingsStore.get(app)
        val prefs = store.current

        if (!prefs.enabled) {
            PulseScheduler.cancel(app)
            return
        }

        val now = System.currentTimeMillis()
        if (claim(PulseScheduler.minuteIndex(now)) && !prefs.isQuietAt(now)) {
            Haptics.buzz(app, prefs)
            store.recordBuzz()
        }

        // Re-arm for the next minute, but re-read first: the user may have switched the app
        // off while this tick was in flight, and re-arming blindly would leave one orphan
        // alarm behind that the cancel on the way out has already passed by.
        if (store.current.enabled) PulseScheduler.schedule(app) else PulseScheduler.cancel(app)

        // Lets the service follow quiet-hour boundaries without running a timer of its own.
        PulseService.onTick()
    }

    @Synchronized
    private fun claim(minute: Long): Boolean {
        if (minute == lastMinute) return false
        lastMinute = minute
        return true
    }
}
