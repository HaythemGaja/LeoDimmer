package com.universal.tvdimmer

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent

/**
 * Single source of truth for everything the three components share.
 *
 * Raw integer keycodes are stored exactly as captured -- no key-name strings,
 * no remapping tables -- in SharedPreferences named "tv_dimmer_prefs", as
 * specified. Brightness is a percent: 100 = full brightness (no overlay),
 * 0 = blackout (solid black overlay, audio-only mode).
 */
object DimmerPrefs {
    const val PREFS_NAME = "tv_dimmer_prefs"

    const val KEY_BRIGHTER = "key_brighter"
    const val KEY_DIMMER = "key_dimmer"
    const val KEY_TOGGLE = "key_toggle_blackout"

    const val KEY_BRIGHTNESS = "brightness_percent"
    const val KEY_PRE_BLACKOUT = "pre_blackout_brightness"
    const val KEY_SERVICE_ENABLED = "service_enabled"

    /** One press steps the overlay by 10% (inside the specified 5-10% band). */
    const val STEP_PERCENT = 10

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
