package com.universal.tvdimmer

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent

/**
 * Single source of truth for everything the components share.
 *
 * Raw integer keycodes are stored exactly as captured -- no key-name strings,
 * no remapping tables -- in SharedPreferences named "tv_dimmer_prefs", as
 * specified. Brightness is a percent: 100 = full brightness (no overlay),
 * 0 = blackout (solid black overlay, audio-only mode).
 *
 * Ambient (sleep light) mode is a separate, warmer, dimmer state: brightness
 * is held at [getAmbientLevel] and the overlay is tinted warm instead of
 * black. It is never resumed after a restart (see DimmerService).
 *
 * The sleep timer is a duration ([getSleepTimerMinutes], 0 = off) plus the
 * absolute moment the armed timer ends ([getSleepTimerEnd]). The end time is
 * stored absolutely so a service restart cannot silently extend the timer.
 */
object DimmerPrefs {
    const val PREFS_NAME = "tv_dimmer_prefs"

    // Mapped remote buttons.
    const val KEY_BRIGHTER = "key_brighter"
    const val KEY_DIMMER = "key_dimmer"
    const val KEY_TOGGLE = "key_toggle_blackout"
    const val KEY_AMBIENT = "key_toggle_ambient"

    // State.
    const val KEY_BRIGHTNESS = "brightness_percent"
    const val KEY_PRE_BLACKOUT = "pre_blackout_brightness"
    const val KEY_SERVICE_ENABLED = "service_enabled"
    const val KEY_AMBIENT_ACTIVE = "ambient_active"
    const val KEY_AMBIENT_LEVEL = "ambient_level_percent"
    const val KEY_PRE_AMBIENT = "pre_ambient_brightness"

    // Sleep timer (arms only while the sleep light is on).
    const val KEY_SLEEP_TIMER_MINUTES = "sleep_timer_minutes"
    const val KEY_SLEEP_TIMER_END = "sleep_timer_end_millis"

    // TV power link (HDMI-CEC).
    const val KEY_TV_POWER_ON = "tv_power_on_enabled"
    const val KEY_TV_POWER_OFF = "tv_power_off_enabled"
    const val KEY_LAST_SCREEN_OFF = "last_screen_off_millis"

    // Merged from local v1.2 (v1.4): schedule, sleep-light colour, idle, fade.
    // Schedule, colour and idle default to v1.3 behaviour (schedule and
    // idle off, colour Warm brown); fade defaults ON (a 300 ms glide,
    // toggleable in settings) and is the only new behaviour on by default.
    const val KEY_SCHEDULE_ENABLED = "schedule_enabled"
    const val KEY_SCHEDULE_DIM_HOUR = "schedule_dim_hour"
    const val KEY_SCHEDULE_RESTORE_HOUR = "schedule_restore_hour"
    const val KEY_SCHEDULE_DIM_PERCENT = "schedule_dim_percent"
    const val KEY_SCHEDULE_STATE = "schedule_state" // 0 = none, 1 = dim applied
    const val KEY_SCHEDULE_PRE_BRIGHTNESS = "schedule_pre_brightness"
    const val KEY_AMBIENT_COLOR_INDEX = "ambient_color_index"
    const val KEY_IDLE_MINUTES = "idle_minutes"
    const val KEY_LAST_ACTIVITY_MS = "last_activity_ms"
    const val KEY_FADE_ENABLED = "fade_enabled"
    // While the key-capture dialog is open, the accessibility service
    // must not swallow the button being mapped (see KeyListeningDialog).
    const val KEY_CAPTURING = "capturing_key"

    fun isCapturingKey(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CAPTURING, false)

    fun setCapturingKey(context: Context, capturing: Boolean) {
        prefs(context).edit().putBoolean(KEY_CAPTURING, capturing).apply()
    }

    /** Sleep-light colours. Warm brown first = the v1.3 default, unchanged. */
    val AMBIENT_COLORS = listOf(
        "Warm brown" to 0xFF2A1200.toInt(),
        "Red" to 0xFFFF0000.toInt(),
        "Amber" to 0xFFFFAA00.toInt(),
        "Orange" to 0xFFFF6600.toInt(),
        "Green" to 0xFF00C853.toInt(),
        "Blue" to 0xFF0066FF.toInt(),
        "Violet" to 0xFFAA00FF.toInt(),
        "Pink" to 0xFFFF4081.toInt()
    )

    val SCHEDULE_DIM_HOURS = listOf(20, 21, 22, 23, 0)
    val SCHEDULE_RESTORE_HOURS = listOf(6, 7, 8, 9)
    val SCHEDULE_DIM_PERCENTS = listOf(10, 20, 30, 50)
    val IDLE_MINUTES_OPTIONS = listOf(0, 15, 30, 60)

    /** One press steps the overlay by 10% (inside the specified 5-10% band). */
    const val STEP_PERCENT = 10

    /** Sleep light range: dim enough to sleep by, bright enough to see by. */
    const val AMBIENT_MIN = 5
    const val AMBIENT_MAX = 60
    const val AMBIENT_STEP = 5
    const val DEFAULT_AMBIENT_LEVEL = 20

    /** Sleep-timer durations the settings row and ADB surface accept. 0 = off. */
    val SLEEP_TIMER_OPTIONS = intArrayOf(0, 10, 15, 20, 30, 45, 60)
    const val SLEEP_TIMER_MAX_MINUTES = 120

    /** Sentinel for "not mapped yet". Mappings start unset on purpose: the
     *  service must never swallow a common key (DPAD, volume) the user did
     *  not explicitly choose. */
    const val UNSET = -1

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getKey(context: Context, key: String): Int =
        prefs(context).getInt(key, UNSET)

    fun setKey(context: Context, key: String, keyCode: Int) {
        prefs(context).edit().putInt(key, keyCode).apply()
    }

    fun clearKeys(context: Context) {
        prefs(context).edit()
            .putInt(KEY_BRIGHTER, UNSET)
            .putInt(KEY_DIMMER, UNSET)
            .putInt(KEY_TOGGLE, UNSET)
            .putInt(KEY_AMBIENT, UNSET)
            .apply()
    }

    fun getBrightness(context: Context): Int =
        prefs(context).getInt(KEY_BRIGHTNESS, 100).coerceIn(0, 100)

    fun setBrightness(context: Context, percent: Int) {
        prefs(context).edit().putInt(KEY_BRIGHTNESS, percent.coerceIn(0, 100)).apply()
    }

    fun isBlackout(context: Context): Boolean = getBrightness(context) == 0

    fun getPreBlackout(context: Context): Int =
        prefs(context).getInt(KEY_PRE_BLACKOUT, 100).coerceIn(1, 100)

    fun setPreBlackout(context: Context, percent: Int) {
        prefs(context).edit().putInt(KEY_PRE_BLACKOUT, percent.coerceIn(1, 100)).apply()
    }

    // ---- Ambient (sleep light) ----

    fun isAmbient(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AMBIENT_ACTIVE, false)

    fun setAmbientActive(context: Context, active: Boolean) {
        prefs(context).edit().putBoolean(KEY_AMBIENT_ACTIVE, active).apply()
    }

    fun getAmbientLevel(context: Context): Int =
        prefs(context).getInt(KEY_AMBIENT_LEVEL, DEFAULT_AMBIENT_LEVEL)
            .coerceIn(AMBIENT_MIN, AMBIENT_MAX)

    fun setAmbientLevel(context: Context, percent: Int) {
        prefs(context).edit().putInt(
            KEY_AMBIENT_LEVEL,
            percent.coerceIn(AMBIENT_MIN, AMBIENT_MAX)
        ).apply()
    }

    fun getPreAmbient(context: Context): Int =
        prefs(context).getInt(KEY_PRE_AMBIENT, 100).coerceIn(1, 100)

    fun setPreAmbient(context: Context, percent: Int) {
        prefs(context).edit().putInt(KEY_PRE_AMBIENT, percent.coerceIn(1, 100)).apply()
    }

    // ---- Sleep timer ----

    fun getSleepTimerMinutes(context: Context): Int =
        prefs(context).getInt(KEY_SLEEP_TIMER_MINUTES, 0)
            .coerceIn(0, SLEEP_TIMER_MAX_MINUTES)

    fun setSleepTimerMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(
            KEY_SLEEP_TIMER_MINUTES,
            minutes.coerceIn(0, SLEEP_TIMER_MAX_MINUTES)
        ).apply()
    }

    /** Epoch millis at which the armed timer fires; 0 = not armed. */
    fun getSleepTimerEnd(context: Context): Long =
        prefs(context).getLong(KEY_SLEEP_TIMER_END, 0L)

    fun setSleepTimerEnd(context: Context, atMillis: Long) {
        prefs(context).edit().putLong(KEY_SLEEP_TIMER_END, atMillis).apply()
    }

    // ---- TV power link ----

    fun isTvPowerOnEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TV_POWER_ON, false)

    fun setTvPowerOnEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_TV_POWER_ON, enabled).apply()
    }

    fun isTvPowerOffEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TV_POWER_OFF, false)

    fun setTvPowerOffEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_TV_POWER_OFF, enabled).apply()
    }

    fun getLastScreenOff(context: Context): Long =
        prefs(context).getLong(KEY_LAST_SCREEN_OFF, 0L)

    fun setLastScreenOff(context: Context, atMillis: Long) {
        prefs(context).edit().putLong(KEY_LAST_SCREEN_OFF, atMillis).apply()
    }

    // ---- Merged v1.4 helpers ----

    fun isScheduleEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SCHEDULE_ENABLED, false)

    fun getScheduleDimHour(context: Context): Int =
        prefs(context).getInt(KEY_SCHEDULE_DIM_HOUR, 22)

    fun getScheduleRestoreHour(context: Context): Int =
        prefs(context).getInt(KEY_SCHEDULE_RESTORE_HOUR, 7)

    fun getScheduleDimPercent(context: Context): Int =
        prefs(context).getInt(KEY_SCHEDULE_DIM_PERCENT, 20).coerceIn(0, 100)

    fun getAmbientColorIndex(context: Context): Int =
        prefs(context).getInt(KEY_AMBIENT_COLOR_INDEX, 0)
            .coerceIn(0, AMBIENT_COLORS.size - 1)

    fun ambientColorName(context: Context): String =
        AMBIENT_COLORS[getAmbientColorIndex(context)].first

    fun ambientColorValue(context: Context): Int =
        AMBIENT_COLORS[getAmbientColorIndex(context)].second

    fun getIdleMinutes(context: Context): Int =
        prefs(context).getInt(KEY_IDLE_MINUTES, 0)

    fun isFadeEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FADE_ENABLED, true)

    fun formatHour(hour: Int): String = "%02d:00".format(hour)

    fun timerLabel(minutes: Int): String =
        if (minutes == 0) "Off" else "$minutes min"

    /** Opacity of the black overlay for a brightness percent. */
    fun opacityFor(brightnessPercent: Int): Float =
        (100 - brightnessPercent.coerceIn(0, 100)) / 100f

    fun keyLabel(context: Context, key: String): String {
        val code = getKey(context, key)
        return if (code == UNSET) {
            "Not set — select to map a remote button"
        } else {
            "${KeyEvent.keyCodeToString(code)} (code $code)"
        }
    }
}
