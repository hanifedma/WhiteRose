package com.whiterose.minute.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Owns the one-minute cadence. Alarms are anchored to wall-clock minute boundaries, so a buzz
 * always lands on :00 rather than drifting away from the minute over the course of a day.
 */
object PulseScheduler {

    const val ACTION_TICK = "com.whiterose.minute.action.TICK"
    const val INTERVAL_MS = 60_000L

    private const val TAG = "WhiteRose"
    private const val REQUEST_CODE = 1001

    /** Timestamp of the next :00 second. */
    fun nextBoundary(now: Long = System.currentTimeMillis()): Long =
        (now / INTERVAL_MS + 1) * INTERVAL_MS

    fun millisUntilNext(now: Long = System.currentTimeMillis()): Long = nextBoundary(now) - now

    /**
     * Which minute a tick belongs to. The small offset absorbs an alarm that fires a hair
     * early, so it is still counted as the minute it was scheduled for.
     */
    fun minuteIndex(now: Long = System.currentTimeMillis()): Long = (now + 1_500L) / INTERVAL_MS

    fun schedule(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = nextBoundary()
        val intent = pendingIntent(context)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        try {
            if (exact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, intent)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, intent)
            }
        } catch (e: SecurityException) {
            // Exact alarms revoked while running: keep going with the inexact variant.
            Log.w(TAG, "exact alarm denied, falling back to inexact", e)
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, intent)
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context.applicationContext,
            REQUEST_CODE,
            Intent(context.applicationContext, PulseReceiver::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
