package com.whiterose.minute.core

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.whiterose.minute.data.BuzzPattern
import com.whiterose.minute.data.Prefs

/**
 * Turns a [Prefs] into an actual buzz. Nothing here touches audio focus, the screen or any
 * other app — a vibration effect is submitted to the system vibrator and that is all.
 */
object Haptics {

    /** Index 0 is unused so the array lines up with the 1..5 strength levels. */
    private val AMPLITUDES = intArrayOf(0, 48, 92, 140, 196, 255)

    private const val BASE_ON_MS = 72.0
    private const val BASE_GAP_MS = 95.0

    fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    fun hasAmplitudeControl(context: Context): Boolean =
        vibrator(context)?.hasAmplitudeControl() == true

    fun buzz(context: Context, prefs: Prefs) {
        val vibrator = vibrator(context) ?: return
        if (!vibrator.hasVibrator()) return
        val effect = effectFor(vibrator, prefs) ?: return
        play(vibrator, effect, prefs.bypassDnd)
    }

    private fun timings(prefs: Prefs): LongArray {
        val on = (BASE_ON_MS * prefs.length.scale).toLong().coerceAtLeast(20L)
        val gap = (BASE_GAP_MS * prefs.length.scale).toLong().coerceIn(70L, 200L)
        return when (prefs.pattern) {
            BuzzPattern.SINGLE -> longArrayOf(on)
            BuzzPattern.DOUBLE -> longArrayOf(on, gap, on)
            BuzzPattern.TRIPLE -> longArrayOf(on, gap, on, gap, on)
            BuzzPattern.LONG -> longArrayOf(on * 4)
        }
    }

    private fun effectFor(vibrator: Vibrator, prefs: Prefs): VibrationEffect? {
        val timings = timings(prefs)
        if (timings.isEmpty()) return null
        return if (vibrator.hasAmplitudeControl()) {
            val amplitude = AMPLITUDES[prefs.strength.coerceIn(1, Prefs.MAX_STRENGTH)]
            // Even slots are the pulses, odd slots are the silent gaps between them.
            val amplitudes = IntArray(timings.size) { if (it % 2 == 0) amplitude else 0 }
            VibrationEffect.createWaveform(timings, amplitudes, -1)
        } else {
            // Some motors are on/off only. Approximate strength with pulse length instead,
            // which is the closest thing to "stronger" such hardware can express.
            val stretch = 0.55 + 0.22 * prefs.strength.coerceIn(1, Prefs.MAX_STRENGTH)
            val onOff = LongArray(timings.size + 1)
            onOff[0] = 0 // createWaveform(timings, repeat) starts with an off period
            for (i in timings.indices) {
                onOff[i + 1] =
                    if (i % 2 == 0) (timings[i] * stretch).toLong().coerceAtLeast(15L)
                    else timings[i]
            }
            VibrationEffect.createWaveform(onOff, -1)
        }
    }

    private fun play(vibrator: Vibrator, effect: VibrationEffect, bypassDnd: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val usage =
                if (bypassDnd) VibrationAttributes.USAGE_ALARM
                else VibrationAttributes.USAGE_NOTIFICATION
            vibrator.vibrate(effect, VibrationAttributes.Builder().setUsage(usage).build())
        } else {
            val usage =
                if (bypassDnd) AudioAttributes.USAGE_ALARM
                else AudioAttributes.USAGE_NOTIFICATION
            @Suppress("DEPRECATION")
            vibrator.vibrate(
                effect,
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }
    }
}
