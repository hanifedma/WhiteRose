package com.whiterose.minute.core

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.whiterose.minute.R
import com.whiterose.minute.data.Prefs
import com.whiterose.minute.data.SettingsStore
import com.whiterose.minute.data.formatHour
import com.whiterose.minute.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the process alive and owns the silent ongoing notification Android requires for a
 * foreground service. The buzzing itself is driven by [PulseScheduler]; the in-service timer
 * here is a second, independent path to the same [Pulser] so a missed alarm still lands.
 */
class PulseService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var store: SettingsStore

    private var wakeLock: PowerManager.WakeLock? = null
    private var tickJob: Job? = null
    private var prefsJob: Job? = null
    private var lastQuiet: Boolean? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = SettingsStore.get(this)
        createChannel()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            store.setEnabled(false)
            PulseScheduler.cancel(this)
            stopSelf()
            return START_NOT_STICKY
        }

        // Must happen immediately after startForegroundService, before anything else.
        startForeground(
            NOTIFICATION_ID,
            buildNotification(store.current),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )

        if (!store.current.enabled) {
            stopSelf()
            return START_NOT_STICKY
        }

        PulseScheduler.schedule(this)
        syncTicker()
        observePrefs()
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        releaseWakeLock()
        scope.cancel()
        if (!store.current.enabled) PulseScheduler.cancel(this)
        super.onDestroy()
    }

    /**
     * A second timer that fires just after each wall-clock minute, used only when the user
     * asked for guaranteed timing.
     *
     * With the wake lock off it would be redundant: the exact alarm already delivers every
     * minute on its own, and a coroutine cannot run while the CPU is asleep anyway — so all
     * this loop would add is a duplicate tick and a duplicate alarm re-arm every 60 seconds,
     * forever. [Pulser] discards whichever of the two paths arrives second.
     */
    private fun syncTicker() {
        if (!store.current.keepAwake) {
            tickJob?.cancel()
            tickJob = null
            return
        }
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (isActive) {
                delay(PulseScheduler.millisUntilNext().coerceAtLeast(1L) + 30L)
                Pulser.tick(applicationContext)
            }
        }
    }

    /**
     * Quiet hours start and end without any setting changing, so the prefs collector never
     * sees it. Repaint the notification on the boundary instead of letting it lie.
     */
    private fun refreshQuietStatus() {
        val prefs = store.current
        if (!prefs.enabled) return
        val quiet = prefs.isQuietAt(System.currentTimeMillis())
        if (quiet == lastQuiet) return
        lastQuiet = quiet
        // Nothing is going to buzz until the window ends, so let the CPU sleep through it.
        applyWakeLock(prefs)
        notifyStatus(prefs)
    }

    private fun observePrefs() {
        if (prefsJob?.isActive == true) return
        prefsJob = scope.launch {
            store.state
                // The buzz counter changes every minute; ignore it so the notification and
                // the wake lock are only touched when something meaningful changes.
                .map { it.copy(buzzCount = 0) }
                .distinctUntilChanged()
                .collect { prefs ->
                    if (!prefs.enabled) {
                        PulseScheduler.cancel(applicationContext)
                        stopSelf()
                        return@collect
                    }
                    applyWakeLock(prefs)
                    syncTicker()
                    notifyStatus(prefs)
                }
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun applyWakeLock(prefs: Prefs) {
        // Quiet hours suppress every buzz, so holding the CPU awake through them would cost
        // battery all night and buy nothing.
        if (prefs.keepAwake && !prefs.isQuietAt(System.currentTimeMillis())) {
            if (wakeLock != null) return
            val power = getSystemService(PowerManager::class.java) ?: return
            // Deliberately untimed: the user opted into "guaranteed timing", and the lock is
            // released the moment that switch goes back off or the service stops.
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        } else {
            releaseWakeLock()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun notifyStatus(prefs: Prefs) {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(prefs))
    }

    private fun buildNotification(prefs: Prefs): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, PulseService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val text =
            if (prefs.isQuietAt(System.currentTimeMillis())) {
                getString(R.string.notif_quiet, formatHour(prefs.quietToHour))
            } else {
                // Strength now differs per output, so the mode is the useful thing to show.
                getString(R.string.notif_running, getString(prefs.alertMode.labelRes))
            }

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_notification),
                    getString(R.string.notif_stop),
                    stop,
                ).build()
            )
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = getString(R.string.channel_desc)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_STOP = "com.whiterose.minute.action.STOP"

        private const val TAG = "WhiteRose"
        private const val CHANNEL_ID = "pulse"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TAG = "WhiteRose:pulse"

        /** Cleared in onDestroy, so it never outlives the service it points at. */
        @Volatile
        private var instance: PulseService? = null

        val isRunning: Boolean get() = instance != null

        /**
         * Called by [Pulser] after every tick, whichever path delivered it, so the notification
         * and the wake lock still follow quiet-hour boundaries now that the in-service timer
         * only runs in guaranteed-timing mode.
         */
        fun onTick() {
            instance?.refreshQuietStatus()
        }

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, PulseService::class.java))
            } catch (e: Exception) {
                // Background start restrictions; the alarm chain keeps buzzing regardless.
                Log.w(TAG, "could not start foreground service", e)
            }
        }

        fun ensureRunning(context: Context) {
            if (!isRunning) start(context)
        }
    }
}
