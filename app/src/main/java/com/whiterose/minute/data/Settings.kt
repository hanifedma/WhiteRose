package com.whiterose.minute.data

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import com.whiterose.minute.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar

/** Shape of a single buzz. */
enum class BuzzPattern(@param:StringRes val labelRes: Int) {
    SINGLE(R.string.pattern_single),
    DOUBLE(R.string.pattern_double),
    TRIPLE(R.string.pattern_triple),
    LONG(R.string.pattern_long);

    fun next(): BuzzPattern = entries[(ordinal + 1) % entries.size]
}

/** How long each pulse in the pattern lasts. */
enum class BuzzLength(@param:StringRes val labelRes: Int, val scale: Float) {
    SHORT(R.string.length_short, 0.55f),
    MEDIUM(R.string.length_medium, 1.0f),
    LONG(R.string.length_long, 1.9f);

    fun next(): BuzzLength = entries[(ordinal + 1) % entries.size]
}

/** How the minute announces itself: on the wrist, out loud, or both. */
enum class AlertMode(@param:StringRes val labelRes: Int) {
    VIBRATE(R.string.mode_vibrate),
    BEEP(R.string.mode_beep),
    BOTH(R.string.mode_both);

    val vibrates: Boolean get() = this != BEEP
    val beeps: Boolean get() = this != VIBRATE

    fun next(): AlertMode = entries[(ordinal + 1) % entries.size]
}

fun formatHour(hour: Int): String = "%02d:00".format(hour.coerceIn(0, 23))

data class Prefs(
    val enabled: Boolean = false,
    val alertMode: AlertMode = AlertMode.VIBRATE,
    val strength: Int = 3,
    val pattern: BuzzPattern = BuzzPattern.DOUBLE,
    val length: BuzzLength = BuzzLength.MEDIUM,
    val quietEnabled: Boolean = false,
    val quietFromHour: Int = 23,
    val quietToHour: Int = 7,
    val startOnBoot: Boolean = true,
    val keepAwake: Boolean = false,
    val bypassDnd: Boolean = true,
    val buzzCount: Int = 0,
) {
    @get:StringRes
    val strengthLabelRes: Int
        get() = when (strength.coerceIn(1, MAX_STRENGTH)) {
            1 -> R.string.strength_1
            2 -> R.string.strength_2
            3 -> R.string.strength_3
            4 -> R.string.strength_4
            else -> R.string.strength_5
        }

    /** True while the clock sits inside the quiet window, which may wrap past midnight. */
    fun isQuietAt(timeMillis: Long): Boolean {
        if (!quietEnabled || quietFromHour == quietToHour) return false
        val hour = Calendar.getInstance().apply { timeInMillis = timeMillis }
            .get(Calendar.HOUR_OF_DAY)
        return if (quietFromHour < quietToHour) {
            hour in quietFromHour until quietToHour
        } else {
            hour >= quietFromHour || hour < quietToHour
        }
    }

    /**
     * The alert as alternating on/off durations in milliseconds, starting with an "on".
     *
     * Shared by the vibrator and the beeper so a Double pattern feels and sounds like the
     * same thing, and so "Both" lands the buzz and the beep on exactly the same edges.
     */
    fun segmentsMs(): LongArray {
        val on = (BASE_ON_MS * length.scale).toLong().coerceAtLeast(20L)
        val gap = (BASE_GAP_MS * length.scale).toLong().coerceIn(70L, 200L)
        return when (pattern) {
            BuzzPattern.SINGLE -> longArrayOf(on)
            BuzzPattern.DOUBLE -> longArrayOf(on, gap, on)
            BuzzPattern.TRIPLE -> longArrayOf(on, gap, on, gap, on)
            BuzzPattern.LONG -> longArrayOf(on * 4)
        }
    }

    companion object {
        const val MAX_STRENGTH = 5
        private const val BASE_ON_MS = 72.0
        private const val BASE_GAP_MS = 95.0
    }
}

/**
 * Every setting lives in SharedPreferences so the alarm receiver can read it synchronously
 * even when the process was just spun up from cold.
 */
class SettingsStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<Prefs> = _state.asStateFlow()

    val current: Prefs get() = _state.value

    fun setEnabled(value: Boolean) = edit { putBoolean(KEY_ENABLED, value) }

    fun setAlertMode(value: AlertMode) = edit { putString(KEY_ALERT_MODE, value.name) }

    fun setStrength(value: Int) = edit { putInt(KEY_STRENGTH, value.coerceIn(1, Prefs.MAX_STRENGTH)) }

    fun setPattern(value: BuzzPattern) = edit { putString(KEY_PATTERN, value.name) }

    fun setLength(value: BuzzLength) = edit { putString(KEY_LENGTH, value.name) }

    fun setQuietEnabled(value: Boolean) = edit { putBoolean(KEY_QUIET, value) }

    fun setQuietFrom(hour: Int) = edit { putInt(KEY_QUIET_FROM, hour.coerceIn(0, 23)) }

    fun setQuietTo(hour: Int) = edit { putInt(KEY_QUIET_TO, hour.coerceIn(0, 23)) }

    fun setStartOnBoot(value: Boolean) = edit { putBoolean(KEY_BOOT, value) }

    fun setKeepAwake(value: Boolean) = edit { putBoolean(KEY_KEEP_AWAKE, value) }

    fun setBypassDnd(value: Boolean) = edit { putBoolean(KEY_BYPASS_DND, value) }

    /**
     * Reads straight out of SharedPreferences rather than the cached [current], so a tick
     * delivered on the alarm thread cannot increment against a stale snapshot.
     */
    @Synchronized
    fun recordBuzz() = edit { putInt(KEY_COUNT, prefs.getInt(KEY_COUNT, 0) + 1) }

    fun resetCount() = edit { putInt(KEY_COUNT, 0) }

    private fun read() = Prefs(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        alertMode = enumOr(prefs.getString(KEY_ALERT_MODE, null), AlertMode.VIBRATE),
        strength = prefs.getInt(KEY_STRENGTH, 3),
        pattern = enumOr(prefs.getString(KEY_PATTERN, null), BuzzPattern.DOUBLE),
        length = enumOr(prefs.getString(KEY_LENGTH, null), BuzzLength.MEDIUM),
        quietEnabled = prefs.getBoolean(KEY_QUIET, false),
        quietFromHour = prefs.getInt(KEY_QUIET_FROM, 23),
        quietToHour = prefs.getInt(KEY_QUIET_TO, 7),
        startOnBoot = prefs.getBoolean(KEY_BOOT, true),
        keepAwake = prefs.getBoolean(KEY_KEEP_AWAKE, false),
        bypassDnd = prefs.getBoolean(KEY_BYPASS_DND, true),
        buzzCount = prefs.getInt(KEY_COUNT, 0),
    )

    /**
     * Writes, then republishes the state itself.
     *
     * Deliberately NOT driven by OnSharedPreferenceChangeListener: SharedPreferences keeps
     * listeners in a WeakHashMap, and R8 is free to inline away the field holding ours, at
     * which point the listener is collected at the next GC and every reader quietly goes
     * stale — including [Pulser], which would then keep buzzing after the user switched the
     * app off. This class is the only writer, so publishing here is both simpler and safe.
     */
    private inline fun edit(block: SharedPreferences.Editor.() -> Unit) {
        val editor = prefs.edit()
        editor.block()
        editor.apply()
        _state.value = read()
    }

    companion object {
        private const val NAME = "white_rose"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ALERT_MODE = "alert_mode"
        private const val KEY_STRENGTH = "strength"
        private const val KEY_PATTERN = "pattern"
        private const val KEY_LENGTH = "length"
        private const val KEY_QUIET = "quiet"
        private const val KEY_QUIET_FROM = "quiet_from"
        private const val KEY_QUIET_TO = "quiet_to"
        private const val KEY_BOOT = "start_on_boot"
        private const val KEY_KEEP_AWAKE = "keep_awake"
        private const val KEY_BYPASS_DND = "bypass_dnd"
        private const val KEY_COUNT = "buzz_count"

        @Volatile
        private var instance: SettingsStore? = null

        fun get(context: Context): SettingsStore =
            instance ?: synchronized(this) {
                instance ?: SettingsStore(context).also { instance = it }
            }

        private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
            name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback
    }
}
