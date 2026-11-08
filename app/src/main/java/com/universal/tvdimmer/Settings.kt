package com.universal.tvdimmer

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.view.KeyEvent
import kotlin.math.abs
import kotlin.random.Random

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

    // How the lamp looks.
    const val KEY_AMBIENT_GLOW = "ambient_glow"
    const val KEY_AMBIENT_CLOCK = "ambient_clock"

    // Sunset / sunrise.
    const val KEY_SUN_SCHEDULE = "sun_schedule_enabled"
    const val KEY_SUN_STATE = "sun_state"
    const val KEY_SUN_LAT = "sun_latitude"
    const val KEY_SUN_LON = "sun_longitude"

    // The local web remote.
    const val KEY_WEB_REMOTE = "web_remote_enabled"
    const val KEY_WEB_PIN = "web_remote_pin"

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

    // A repair note, shown on the settings screen after stored mappings
    // from an older build are deduplicated (see deduplicateKeys).
    const val KEY_DUP_REPAIR_NOTE = "duplicate_repair_note"

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

    /** How far the tune key can push a mode down before it stops. */
    const val DAY_MIN = 10
    const val NIGHT_MIN = 10

    /** Tune steps: Day is coarse, Night and the lamp are fine. */
    const val DAY_TUNE_STEP = 10
    const val NIGHT_TUNE_STEP = 5

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

    /**
     * The number the tune key changes. Each mode keeps its own, so the key
     * always tunes what is on screen -- and Day -> Night -> Day comes back to
     * the value you tuned, which stepping the overlay's raw brightness did not.
     */
    fun getMoodValue(context: Context, mood: Int): Int = when (mood) {
        MOOD_NIGHT -> getNightBrightness(context)
        MOOD_SLEEP -> getAmbientLevel(context)
        else -> getDayBrightness(context)
    }

    fun setMoodValue(context: Context, mood: Int, value: Int) {
        when (mood) {
            MOOD_NIGHT -> setNightBrightness(context, value.coerceIn(NIGHT_MIN, 100))
            MOOD_SLEEP -> setAmbientLevel(context, value)
            else -> setDayBrightness(context, value.coerceIn(DAY_MIN, 100))
        }
    }

    /** One tune press (positive = more). Returns the value the mode now has. */
    fun stepMoodValue(context: Context, mood: Int, steps: Int): Int {
        val step = when (mood) {
            MOOD_NIGHT -> NIGHT_TUNE_STEP
            MOOD_SLEEP -> AMBIENT_STEP
            else -> DAY_TUNE_STEP
        }
        setMoodValue(context, mood, getMoodValue(context, mood) + steps * step)
        return getMoodValue(context, mood)
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

    /** Next (or previous) lamp colour, wrapping. Returns the new name. */
    fun stepAmbientColor(context: Context, steps: Int): String {
        val count = AMBIENT_COLORS.size
        val index = ((getAmbientColorIndex(context) + steps) % count + count) % count
        setAmbientColorIndex(context, index)
        return AMBIENT_COLORS[index].first
    }

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

    // ---- Lamp look ----

    /** 0 = a flat panel of colour, 100 = a tight hot spot. */
    fun getAmbientGlow(context: Context): Int =
        prefs(context).getInt(KEY_AMBIENT_GLOW, 60).coerceIn(0, 100)

    fun setAmbientGlow(context: Context, percent: Int) {
        prefs(context).edit().putInt(KEY_AMBIENT_GLOW, percent.coerceIn(0, 100)).apply()
    }

    fun isClockShown(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AMBIENT_CLOCK, false)

    fun setClockShown(context: Context, shown: Boolean) {
        prefs(context).edit().putBoolean(KEY_AMBIENT_CLOCK, shown).apply()
    }

    // ---- Sunset / sunrise ----

    fun isSunScheduleEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SUN_SCHEDULE, false)

    fun setSunScheduleEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_SUN_SCHEDULE, enabled)
            .putInt(KEY_SUN_STATE, -1) // re-decide from scratch on the next tick
            .apply()
    }

    /** -1 = not decided yet, 0 = day, 1 = night. */
    fun getSunState(context: Context): Int = prefs(context).getInt(KEY_SUN_STATE, -1)

    fun setSunState(context: Context, state: Int) {
        prefs(context).edit().putInt(KEY_SUN_STATE, state).apply()
    }

    fun getSunLat(context: Context): Float = prefs(context).getFloat(KEY_SUN_LAT, 36.8f)

    fun setSunLat(context: Context, latitude: Float) {
        prefs(context).edit()
            .putFloat(KEY_SUN_LAT, latitude.coerceIn(-60f, 60f))
            .putInt(KEY_SUN_STATE, -1)
            .apply()
    }

    fun getSunLon(context: Context): Float = prefs(context).getFloat(KEY_SUN_LON, 10.2f)

    fun setSunLon(context: Context, longitude: Float) {
        prefs(context).edit()
            .putFloat(KEY_SUN_LON, longitude.coerceIn(-180f, 180f))
            .putInt(KEY_SUN_STATE, -1)
            .apply()
    }

    data class Place(val name: String, val latitude: Float, val longitude: Float)

    /** A short list, so the sun needs no keyboard and no location permission. */
    val PLACES = listOf(
        Place("Tunis", 36.8f, 10.2f),
        Place("Algiers", 36.8f, 3.1f),
        Place("Casablanca", 33.6f, -7.6f),
        Place("Cairo", 30.0f, 31.2f),
        Place("Istanbul", 41.0f, 29.0f),
        Place("Dubai", 25.2f, 55.3f),
        Place("Riyadh", 24.7f, 46.7f),
        Place("Paris", 48.9f, 2.4f),
        Place("London", 51.5f, -0.1f),
        Place("Berlin", 52.5f, 13.4f),
        Place("Madrid", 40.4f, -3.7f),
        Place("Rome", 41.9f, 12.5f),
        Place("Moscow", 55.8f, 37.6f),
        Place("Lagos", 6.5f, 3.4f),
        Place("Johannesburg", -26.2f, 28.0f),
        Place("New York", 40.7f, -74.0f),
        Place("Toronto", 43.7f, -79.4f),
        Place("Los Angeles", 34.1f, -118.2f),
        Place("Mexico City", 19.4f, -99.1f),
        Place("Sao Paulo", -23.6f, -46.6f),
        Place("Delhi", 28.6f, 77.2f),
        Place("Singapore", 1.4f, 103.8f),
        Place("Tokyo", 35.7f, 139.7f),
        Place("Sydney", -33.9f, 151.2f)
    )

    fun placeName(context: Context): String {
        val match = PLACES.firstOrNull { place ->
            abs(place.latitude - getSunLat(context)) < 0.6f &&
                abs(place.longitude - getSunLon(context)) < 0.6f
        }
        return match?.name ?: "Custom"
    }

    /** Index of the matching place, so the row can step through the list. */
    fun placeIndex(context: Context): Int {
        val index = PLACES.indexOfFirst { place ->
            abs(place.latitude - getSunLat(context)) < 0.6f &&
                abs(place.longitude - getSunLon(context)) < 0.6f
        }
        return if (index < 0) 0 else index
    }

    // ---- Web remote ----

    fun isWebRemoteEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WEB_REMOTE, false)

    fun setWebRemoteEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WEB_REMOTE, enabled).apply()
    }

    /** The PIN, created on first use. Four digits, never empty. */
    fun webPin(context: Context): String {
        val stored = prefs(context).getString(KEY_WEB_PIN, "") ?: ""
        if (stored.length == 4) return stored
        return newWebPin(context)
    }

    fun newWebPin(context: Context): String {
        val pin = (1000 + Random.nextInt(9000)).toString()
        prefs(context).edit().putString(KEY_WEB_PIN, pin).apply()
        return pin
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

    /** Short label for a key mapping, for toasts and repair notes. */
    fun keyTitle(key: String): String = when (key) {
        KEY_MODE_KEY -> "Mode key"
        KEY_BRIGHTER -> "Tune ▲ · more"
        KEY_DIMMER -> "Tune ▼ · less"
        KEY_TOGGLE -> "Blackout key"
        KEY_AMBIENT -> "Sleep light key"
        else -> key
    }

    /**
     * One button, one action. Learning a button for [key] takes it away from
     * any other action that had it, and returns what it was taken from. A
     * shared button used to fail silently with Mode winning: KeyService checks
     * the Mode key first, so a Tune button that was also the Mode button
     * cycled modes instead of tuning, and nothing said why. That can no
     * longer happen.
     */
    fun setKey(context: Context, key: String, keyCode: Int): List<String> {
        if (keyCode == UNSET) {
            prefs(context).edit().putInt(key, UNSET).apply()
            return emptyList()
        }
        val stolen = mutableListOf<String>()
        val editor = prefs(context).edit()
        for (other in listOf(KEY_MODE_KEY, KEY_BRIGHTER, KEY_DIMMER, KEY_TOGGLE, KEY_AMBIENT)) {
            if (other != key && prefs(context).getInt(other, UNSET) == keyCode) {
                editor.putInt(other, UNSET)
                stolen.add(keyTitle(other))
            }
        }
        editor.putInt(key, keyCode).apply()
        return stolen
    }

    /**
     * Repairs mappings stored by older builds, where one button could belong
     * to two actions at once. Keeps the first action in keep order and clears
     * the rest, returning one line per cleared mapping, e.g. "Mode key — same
     * button as Tune ▲ · more". Tune outranks Mode here on purpose: a button
     * the user presses expecting a tune must tune, not cycle modes.
     */
    fun deduplicateKeys(context: Context): List<String> {
        val keepOrder = listOf(KEY_BRIGHTER, KEY_DIMMER, KEY_MODE_KEY, KEY_TOGGLE, KEY_AMBIENT)
        val seen = mutableMapOf<Int, String>()
        val cleared = mutableListOf<String>()
        val editor = prefs(context).edit()
        for (key in keepOrder) {
            val code = prefs(context).getInt(key, UNSET)
            if (code == UNSET) continue
            val keeper = seen[code]
            if (keeper != null) {
                editor.putInt(key, UNSET)
                cleared.add("${keyTitle(key)} — same button as ${keyTitle(keeper)}")
            } else {
                seen[code] = key
            }
        }
        editor.apply()
        return cleared
    }

    fun getRepairNote(context: Context): String? =
        prefs(context).getString(KEY_DUP_REPAIR_NOTE, null)?.takeIf { it.isNotEmpty() }

    fun setRepairNote(context: Context, note: String) {
        prefs(context).edit().putString(KEY_DUP_REPAIR_NOTE, note).apply()
    }

    fun clearRepairNote(context: Context) {
        prefs(context).edit().remove(KEY_DUP_REPAIR_NOTE).apply()
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
