package com.whiterose.minute.core

import android.content.Context
import com.whiterose.minute.data.Prefs

/**
 * One entry point for "announce this minute", so the tick path, the Test button and the
 * live previews in Settings all produce exactly the same alert.
 */
object Alerter {

    fun fire(context: Context, prefs: Prefs) {
        // Started together rather than in sequence: both are asynchronous, so on "Both" the
        // buzz and the beep begin on the same edge instead of the sound trailing the wrist.
        if (prefs.alertMode.vibrates) Haptics.buzz(context, prefs)
        if (prefs.alertMode.beeps) Beeper.beep(context, prefs)
    }
}
