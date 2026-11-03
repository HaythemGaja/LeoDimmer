package com.universal.tvdimmer

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.view.KeyEvent

/**
 * LeoDimmer v2.0 -- one small store for everything the app shares.
 *
 * The whole app is built around ONE idea: a TV remote has no spare buttons, so
 * the dimmer is three states cycled by a single key.
 *
 *   Day   -> bright picture
 *   Night -> the picture dimmed
 *   Sleep -> the lamp: one opaque colour, dimmed by scaling the colour itself,
 *            which fades out when the sleep timer runs out
 *
 * Each state keeps its own value, so switching modes never means re-tuning:
 * Day and Night have their own brightness, Sleep has its own glow, colour and
 * auto-off timer.
 *
 * Brightness is a percent: 100 = no overlay at all, 0 = blackout (audio-only).
 * The prefs file keeps its original name and key strings so upgrading from
 * 1.x keeps the user's existing values.
 */
object Settings {
    const val PREFS_NAME = "tv_dimmer_prefs"

    // What is currently drawn.
    const val KEY_BRIGHTNESS = "brightness_percent"
    const val KEY_PRE_BLACKOUT = "pre_blackout_brightness"
    const val KEY_PRE_AMBIENT = "pre_ambient_brightness"
    const val KEY_AMBIENT_ACTIVE = "ambient_active"
    const val KEY_SERVICE_ENABLED = "service_enabled"

    // The three modes.
    const val KEY_MOOD = "mood"
    const val KEY_DAY_BRIGHTNESS = "day_brightness_percent"
    const val KEY_NIGHT_BRIGHTNESS = "night_brightness_percent"

    // Sleep light.
    const val KEY_AMBIENT_LEVEL = "ambient_level_percent"
    const val KEY_AMBIENT_COLOR_INDEX = "ambient_color_index"
    const val KEY_SLEEP_TIMER_MINUTES = "sleep_timer_minutes"
    const val KEY_SLEEP_TIMER_END = "sleep_timer_end_millis"

    // Extras carried over from 1.x (kept working, no longer required).
    const val KEY_FADE_ENABLED = "fade_enabled"
    const val KEY_SCHEDULE_ENABLED = "schedule_enabled"
    const val KEY_SCHEDULE_DIM_HOUR = "schedule_dim_hour"
    const val KEY_SCHEDULE_RESTORE_HOUR = "schedule_restore_hour"
    const val KEY_SCHEDULE_PRE_BRIGHTNESS = "schedule_pre_brightness"
    const val KEY_SCHEDULE_STATE = "schedule_state"
    const val KEY_IDLE_MINUTES = "idle_minutes"
    const val KEY_LAST_ACTIVITY_MS = "last_activity_ms"
    const val KEY_TV_POWER_ON = "tv_power_on_enabled"
    const val KEY_TV_POWER_OFF = "tv_power_off_enabled"
    const val KEY_LAST_SCREEN_OFF = "last_screen_off_millis"

    // Remote keys.
    const val KEY_MODE_KEY = "key_mode"
    const val KEY_BRIGHTER = "key_brighter"
    const val KEY_DIMMER = "key_dimmer"
    const val KEY_TOGGLE = "key_toggle_blackout"
    const val KEY_AMBIENT = "key_toggle_ambient"

    // Key learning, done by the accessibility service (see KeyService).
    const val KEY_LEARN_TARGET = "learn_target"
    const val KEY_LEARNED = "learned_key"
    const val KEY_KEYS_READY_AT = "keys_ready_at"

    /** Sentinel for "this key is not mapped". Never treated as a real key. */
    const val UNSET = -1

    // ---- Modes ----

    const val MOOD_DAY = 0
    const val MOOD_NIGHT = 1
    const val MOOD_SLEEP = 2

    val MOODS = listOf(MOOD_DAY, MOOD_NIGHT, MOOD_SLEEP)

    /** Day: barely dimmed, still a "normal TV" picture. */
    val DAY_BRIGHTNESS_OPTIONS = listOf(100, 90, 80, 70)

    /** Night: dark room, dimmer than Day but never fully dark. */
    val NIGHT_BRIGHTNESS_OPTIONS = listOf(10, 15, 20, 25, 30, 40, 50)

    /**
     * Lamp level. The lamp is drawn opaque, so the top of the range is a real
     * full-brightness panel rather than a wash over the picture.
     */
    const val AMBIENT_MIN = 5
    const val AMBIENT_MAX = 100
    const val AMBIENT_STEP = 5
    const val DEFAULT_AMBIENT_LEVEL = 20

    /**
     * Lamp colours, not washes. Each one is drawn opaque at full panel
     * brightness and dimmed by scaling its own channels (see [ambientLampColor]),
     * so the light on the wall is this colour and nothing from the picture.
     */
    val AMBIENT_COLORS = listOf(
        "Warm white" to 0xFFFFB46B.toInt(),
        "Candle" to 0xFFFF8A3D.toInt(),
        "Amber" to 0xFFFFA000.toInt(),
        "Sunset" to 0xFFFF6A3D.toInt(),
        "Rose" to 0xFFFF5470.toInt(),
        "Violet" to 0xFF9B6BFF.toInt(),
        "Ice" to 0xFFCFE3FF.toInt(),
        "Moonlight" to 0xFF6F9BFF.toInt()
    )

    /** 0 = stay on until you turn it off. */
    val SLEEP_TIMER_OPTIONS = listOf(0, 10, 15, 20, 30, 45, 60, 90)

    val SCHEDULE_DIM_HOURS = listOf(19, 20, 21, 22, 23, 0, 1)
    val SCHEDULE_RESTORE_HOURS = listOf(5, 6, 7, 8, 9, 10)
    val IDLE_MINUTES_OPTIONS = listOf(0, 15, 30, 60, 120)

    /** One manual step on the brightness rows. */
    const val STEP_PERCENT = 10

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---- Mode ----

    fun getMood(context: Context): Int =
        prefs(context).getInt(KEY_MOOD, MOOD_DAY).coerceIn(MOOD_DAY, MOOD_SLEEP)

    fun setMood(context: Context, mood: Int) {
        prefs(context).edit().putInt(KEY_MOOD, mood.coerceIn(MOOD_DAY, MOOD_SLEEP)).apply()
    }

    /** Day -> Night -> Sleep -> Day. */
    fun nextMood(mood: Int): Int = (mood + 1) % MOODS.size

    fun moodName(mood: Int): String = when (mood) {
        MOOD_NIGHT -> "Night"
        MOOD_SLEEP -> "Sleep"
        else -> "Day"
    }

    fun getDayBrightness(context: Context): Int =
        prefs(context).getInt(KEY_DAY_BRIGHTNESS, 100).coerceIn(0, 100)

    fun setDayBrightness(context: Context, percent: Int) {
        prefs(context).edit().putInt(KEY_DAY_BRIGHTNESS, percent.coerceIn(0, 100)).apply()
    }

    fun getNightBrightness(context: Context): Int =
        prefs(context).getInt(KEY_NIGHT_BRIGHTNESS, 25).coerceIn(0, 100)

    fun setNightBrightness(context: Context, percent: Int) {
        prefs(context).edit().putInt(KEY_NIGHT_BRIGHTNESS, percent.coerceIn(0, 100)).apply()
    }

    // ---- What is drawn ----

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

    fun isAmbient(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AMBIENT_ACTIVE, false)

    fun setAmbientActive(context: Context, active: Boolean) {
        prefs(context).edit().putBoolean(KEY_AMBIENT_ACTIVE, active).apply()
    }

    fun getAmbientLevel(context: Context): Int =
        prefs(context).getInt(KEY_AMBIENT_LEVEL, DEFAULT_AMBIENT_LEVEL)
            .coerceIn(AMBIENT_MIN, AMBIENT_MAX)

    fun setAmbientLevel(context: Context, percent: Int) {
        prefs(context).edit()
            .putInt(KEY_AMBIENT_LEVEL, percent.coerceIn(AMBIENT_MIN, AMBIENT_MAX))
            .apply()
    }

    fun getPreAmbient(context: Context): Int =
        prefs(context).getInt(KEY_PRE_AMBIENT, 100).coerceIn(1, 100)

    fun setPreAmbient(context: Context, percent: Int) {
        prefs(context).edit().putInt(KEY_PRE_AMBIENT, percent.coerceIn(1, 100)).apply()
    }

    fun getAmbientColorIndex(context: Context): Int =
        prefs(context).getInt(KEY_AMBIENT_COLOR_INDEX, 0)
            .coerceIn(0, AMBIENT_COLORS.size - 1)

    fun setAmbientColorIndex(context: Context, index: Int) {
        prefs(context).edit()
            .putInt(KEY_AMBIENT_COLOR_INDEX, index.coerceIn(0, AMBIENT_COLORS.size - 1))
            .apply()
    }

    fun ambientColorName(context: Context): String =
        AMBIENT_COLORS[getAmbientColorIndex(context)].first

    fun ambientColorValue(context: Context): Int =
        AMBIENT_COLORS[getAmbientColorIndex(context)].second

    /** The lamp fill: the chosen colour scaled by the level, drawn fully opaque. */
    fun ambientLampColor(context: Context): Int =
        scaleColor(ambientColorValue(context), getAmbientLevel(context))

    /**
     * Dim a colour by scaling its channels. A lamp dims this way; fading a black
     * layer instead would only hide the picture behind it.
     */
    fun scaleColor(color: Int, percent: Int): Int {
        val factor = percent.coerceIn(0, 100) / 100f
        return Color.rgb(
            (Color.red(color) * factor).toInt(),
            (Color.green(color) * factor).toInt(),
            (Color.blue(color) * factor).toInt()
        )
    }

    // ---- Sleep timer ----

    fun getSleepTimerMinutes(context: Context): Int =
        prefs(context).getInt(KEY_SLEEP_TIMER_MINUTES, 30)

    fun setSleepTimerMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_SLEEP_TIMER_MINUTES, minutes.coerceAtLeast(0)).apply()
    }

    fun getSleepTimerEnd(context: Context): Long =
        prefs(context).getLong(KEY_SLEEP_TIMER_END, 0L)

    fun setSleepTimerEnd(context: Context, atMillis: Long) {
        prefs(context).edit().putLong(KEY_SLEEP_TIMER_END, atMillis).apply()
    }

    // ---- Extras ----

    fun isFadeEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FADE_ENABLED, true)

    fun setFadeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FADE_ENABLED, enabled).apply()
    }

    fun isScheduleEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SCHEDULE_ENABLED, false)

    fun setScheduleEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SCHEDULE_ENABLED, enabled).apply()
    }

    fun getScheduleDimHour(context: Context): Int =
        prefs(context).getInt(KEY_SCHEDULE_DIM_HOUR, 22)

    fun setScheduleDimHour(context: Context, hour: Int) {
        prefs(context).edit().putInt(KEY_SCHEDULE_DIM_HOUR, hour).apply()
    }

    fun getScheduleRestoreHour(context: Context): Int =
        prefs(context).getInt(KEY_SCHEDULE_RESTORE_HOUR, 7)

    fun setScheduleRestoreHour(context: Context, hour: Int) {
        prefs(context).edit().putInt(KEY_SCHEDULE_RESTORE_HOUR, hour).apply()
    }

    fun getIdleMinutes(context: Context): Int =
        prefs(context).getInt(KEY_IDLE_MINUTES, 0)

    fun setIdleMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_IDLE_MINUTES, minutes.coerceAtLeast(0)).apply()
    }

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

    fun setServiceEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply()
    }

    fun isServiceEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SERVICE_ENABLED, false)

    // ---- Keys ----

    /** Overlay permission, asked through our own name to keep the platform
     *  Settings class from shadowing this object at call sites. */
    fun canDrawOverlays(context: Context): Boolean =
        android.provider.Settings.canDrawOverlays(context)

    fun getKey(context: Context, key: String): Int = prefs(context).getInt(key, UNSET)

    fun setKey(context: Context, key: String, keyCode: Int) {
        prefs(context).edit().putInt(key, keyCode).apply()
    }

    fun clearKeys(context: Context) {
        prefs(context).edit()
            .putInt(KEY_MODE_KEY, UNSET)
            .putInt(KEY_BRIGHTER, UNSET)
            .putInt(KEY_DIMMER, UNSET)
            .putInt(KEY_TOGGLE, UNSET)
            .putInt(KEY_AMBIENT, UNSET)
            .apply()
    }

    fun anyKeyMapped(context: Context): Boolean =
        listOf(KEY_MODE_KEY, KEY_BRIGHTER, KEY_DIMMER, KEY_TOGGLE, KEY_AMBIENT)
            .any { getKey(context, it) != UNSET }

    /**
     * Arm learning: the accessibility service stores the next button it sees
     * into [target]. Nothing happens in a window, so a press cannot be lost to
     * window focus -- that was the bug that made the old dialog useless.
     */
    fun armLearn(context: Context, target: String) {
        prefs(context).edit()
            .putString(KEY_LEARN_TARGET, target)
            .putInt(KEY_LEARNED, UNSET)
            .apply()
    }

    fun learnTarget(context: Context): String =
        prefs(context).getString(KEY_LEARN_TARGET, "") ?: ""

    fun finishLearn(context: Context, keyCode: Int) {
        prefs(context).edit()
            .putString(KEY_LEARN_TARGET, "")
            .putInt(KEY_LEARNED, keyCode)
            .apply()
    }

    fun clearLearn(context: Context) {
        prefs(context).edit().putString(KEY_LEARN_TARGET, "").apply()
    }

    fun markKeysReady(context: Context) {
        prefs(context).edit()
            .putLong(KEY_KEYS_READY_AT, System.currentTimeMillis())
            .apply()
    }

    fun keysReadyAt(context: Context): Long =
        prefs(context).getLong(KEY_KEYS_READY_AT, 0L)

    // ---- Labels ----

    fun keyLabel(context: Context, key: String): String {
        val code = getKey(context, key)
        return if (code == UNSET) "Not set" else keyName(code)
    }

    fun keyName(code: Int): String = KeyEvent.keyCodeToString(code)
        .removePrefix("KEYCODE_")
        .replace('_', ' ')

    fun formatHour(hour: Int): String = "%02d:00".format(hour)

    fun timerLabel(minutes: Int): String = if (minutes <= 0) "Off" else "$minutes min"

    fun percentLabel(percent: Int): String = "$percent%"

    /** Opacity of the black overlay for a brightness percent. */
    fun opacityFor(brightnessPercent: Int): Float =
        (100 - brightnessPercent.coerceIn(0, 100)) / 100f
}
