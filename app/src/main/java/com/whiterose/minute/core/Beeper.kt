package com.whiterose.minute.core

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.whiterose.minute.data.Prefs
import kotlin.math.PI
import kotlin.math.sin

/**
 * The audible half of an alert: a short synthesised beep, shaped by the same pattern, length
 * and strength settings as the vibration.
 *
 * The tone is generated rather than shipped as an asset so that Pattern and Length can be
 * honoured exactly — a "Double" beep lands on the same edges as a "Double" buzz — and so the
 * APK carries no audio file.
 *
 * Deliberately does NOT take audio focus: this is a one-second-long chirp once a minute, and
 * pausing whatever the user is listening to would be far more disruptive than mixing over it.
 */
object Beeper {

    private const val TAG = "WhiteRose"
    private const val SAMPLE_RATE = 44_100

    /**
     * 4096 Hz, the Casio digital-watch beep. Not an arbitrary choice: those watches divide
     * their 32.768 kHz quartz crystal by 8, which lands exactly here.
     */
    private const val TONE_HZ = 4_096.0

    /**
     * A piezo buzzer is driven by a square wave, so it is bright and thin rather than round
     * like a sine. Adding the odd harmonics reproduces that without the aliasing a real
     * square wave would fold back at this pitch.
     */
    private val HARMONICS = doubleArrayOf(1.0, 1.0 / 3.0, 1.0 / 5.0)
    private val HARMONIC_NORM = HARMONICS.sum()

    /**
     * Barely there: the Casio beep is a gated tone whose abrupt edges are what make it read
     * as a "click". Just enough ramp to avoid a speaker pop.
     */
    private const val RAMP_MS = 1.0

    /** Index 0 unused, so this lines up with the 1..5 strength levels. */
    private val GAINS = doubleArrayOf(0.0, 0.14, 0.28, 0.48, 0.72, 1.0)

    private val handler = Handler(Looper.getMainLooper())

    private var current: AudioTrack? = null
    private var wakeLock: PowerManager.WakeLock? = null

    fun isAvailable(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_OUTPUT)

    /**
     * True when a beep would not actually be heard — either the watch has no speaker at all,
     * or the stream it would play on is turned down to zero. The UI warns on this so "Beep"
     * never looks silently broken.
     */
    fun isInaudible(context: Context, prefs: Prefs): Boolean {
        if (!isAvailable(context)) return true
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        // Must match the usage chosen in build(), or the warning watches the wrong slider.
        val stream =
            if (prefs.bypassDnd) AudioManager.STREAM_ALARM else AudioManager.STREAM_NOTIFICATION
        return audio.getStreamVolume(stream) == 0
    }

    @Synchronized
    fun beep(context: Context, prefs: Prefs) {
        if (!isAvailable(context)) return
        val pcm = synthesise(prefs)
        if (pcm.isEmpty()) return

        stopCurrent()

        val track = try {
            build(pcm, prefs.bypassDnd)
        } catch (e: Exception) {
            // Bad sample rate, no free audio session, hardware busy — never take the app down
            // for a beep; the vibration half of "Both" must still happen.
            Log.w(TAG, "could not open audio track", e)
            null
        } ?: return

        val durationMs = pcm.size * 1_000L / SAMPLE_RATE
        // Audio playback normally keeps the CPU up on its own, but the alarm that triggered
        // this only holds a wake lock for the length of onReceive. Cover the tail explicitly
        // so the beep cannot be cut short by the watch suspending mid-tone.
        acquireWakeLock(context, durationMs + 1_500L)

        try {
            track.play()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "could not start playback", e)
            release(track)
            releaseWakeLock()
            return
        }

        current = track
        handler.postDelayed({
            synchronized(this) {
                release(track)
                // A newer beep may have started while this one was finishing; only drop the
                // wake lock when nothing is left playing, or we would cut that one short.
                if (current === track) {
                    current = null
                    releaseWakeLock()
                }
            }
        }, durationMs + 250L)
    }

    private fun build(pcm: ShortArray, bypassDnd: Boolean): AudioTrack {
        val usage =
            if (bypassDnd) AudioAttributes.USAGE_ALARM
            else AudioAttributes.USAGE_NOTIFICATION
        val attributes = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        // MODE_STATIC plays the whole buffer, so pad the clip itself rather than just asking
        // for a bigger buffer — otherwise the tail would be whatever the buffer came with.
        val minBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(0)
        val samples =
            if (pcm.size * 2 >= minBytes) pcm else pcm.copyOf((minBytes + 1) / 2)

        return AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(samples.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
            .apply { write(samples, 0, samples.size) }
    }

    /** Renders the alert's on/off segments into 16-bit mono PCM. */
    private fun synthesise(prefs: Prefs): ShortArray {
        val segments = prefs.segmentsMs()
        if (segments.isEmpty()) return ShortArray(0)

        val counts = IntArray(segments.size) { (SAMPLE_RATE * segments[it] / 1_000L).toInt() }
        val out = ShortArray(counts.sum())
        val gain = GAINS[prefs.strength.coerceIn(1, Prefs.MAX_STRENGTH)]
        val ramp = (SAMPLE_RATE * RAMP_MS / 1_000.0).toInt().coerceAtLeast(1)

        var index = 0
        for (segment in segments.indices) {
            val length = counts[segment]
            if (segment % 2 != 0) { // odd slots are the silent gaps
                index += length
                continue
            }
            val edge = ramp.coerceAtMost(length / 2)
            for (sample in 0 until length) {
                val envelope = when {
                    edge <= 0 -> 1.0
                    sample < edge -> sample.toDouble() / edge
                    sample >= length - edge -> (length - sample).toDouble() / edge
                    else -> 1.0
                }
                val phase = 2.0 * PI * TONE_HZ * sample / SAMPLE_RATE
                var wave = 0.0
                for (h in HARMONICS.indices) {
                    wave += HARMONICS[h] * sin(phase * (2 * h + 1)) // 1st, 3rd, 5th
                }
                val value = wave / HARMONIC_NORM * envelope * gain
                out[index++] = (value * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out
    }

    private fun stopCurrent() {
        current?.let {
            current = null
            release(it)
        }
    }

    private fun release(track: AudioTrack) {
        try {
            if (track.state == AudioTrack.STATE_INITIALIZED &&
                track.playState != AudioTrack.PLAYSTATE_STOPPED
            ) {
                track.stop()
            }
        } catch (_: IllegalStateException) {
            // Already stopped; nothing to do.
        }
        try {
            track.release()
        } catch (_: Exception) {
            // Releasing twice is harmless.
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock(context: Context, timeoutMs: Long) {
        if (wakeLock?.isHeld == true) return
        val power = context.getSystemService(PowerManager::class.java) ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WhiteRose:beep").apply {
            setReferenceCounted(false)
            // Timed: even if the release below is somehow missed, this cannot leak.
            acquire(timeoutMs)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}
