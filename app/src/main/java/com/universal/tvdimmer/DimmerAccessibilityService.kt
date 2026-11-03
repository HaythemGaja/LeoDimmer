package com.universal.tvdimmer

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Global interception engine (nightd-style native D-pad handling, applied to
 * whatever remote buttons the user mapped).
 *
 * Reads the raw keycodes from "tv_dimmer_prefs" on every key event, so a
 * remap in the settings screen takes effect immediately without restarting
 * this service. It intercepts only:
 *   1. ANY key while blackout is active -> exits blackout (audio-only mode
 *      ends on the first press; that press is consumed so it does not also
 *      fire in the app underneath the black screen).
 *   2. Hold (repeat events) -> long-press action, once per hold.
 *   3. Two presses within 400 ms -> double-press action.
 *   4. The four user-mapped keys -> BRIGHTER / DIMMER / TOGGLE_BLACKOUT /
 *      TOGGLE_AMBIENT (sleep light).
 * Everything else is passed through untouched (return false). Ambient mode
 * deliberately does NOT swallow unmapped keys: it is a viewing state, not a
 * black screen, so normal navigation keeps working while the light is on.
 *
 * v1.4 long-press actions (hold the mapped key):
 *   Increase held -> full bright (100%)
 *   Decrease held -> blackout
 *   Toggle held   -> sleep light on/off
 *   Sleep light held -> full bright (100%)
 * v1.4 double-press actions (press twice quickly):
 *   Increase twice -> 75% preset
 *   Decrease twice -> 25% preset
 *   Toggle twice   -> full bright (100%)
 *   Sleep light twice -> 50% preset (leaves sleep-light mode)
 * Any key down also resets the idle timer, so idle blackout only fires
 * after genuine inactivity.
 *
 * Safety: if the overlay permission is gone, mapped keys are NOT swallowed
 * -- a dimmer that eats remote buttons without dimming anything would be
 * worse than no dimmer. The one exception is blackout exit, which still
 * clears the stored blackout state so a later relaunch does not start black.
 *
 * Key events only reach here when this service is enabled in Accessibility
 * settings and the device honours flagRequestFilterKeyEvents (see
 * res/xml/dimmer_accessibility_config.xml). HOME / POWER are handled by the
 * system before any accessibility service and can never be intercepted.
 */
class DimmerAccessibilityService : AccessibilityService() {

    private val lastDownMs = mutableMapOf<Int, Long>()
    private val longHandled = mutableSetOf<Int>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Keep the overlay alive even when the settings UI is closed.
        if (Settings.canDrawOverlays(this)) startDimmerService(null, null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No event handling needed: this service exists for key filtering.
    }

    override fun onInterrupt() {
        // Nothing to clean up.
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode

        // Key-up for a mapped key is consumed to match the consumed key-down
        // (an app receiving an UP with no DOWN is harmless, but swallowing
        // both keeps the pair clean). Key-up never triggers an action.
        if (event.action == KeyEvent.ACTION_UP) {
            longHandled.remove(keyCode)
            return isMappedKey(keyCode)
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false

        // Idle reset for ANY key, before any other decision -- but only if
        // the service was ever started, so a stray key press before setup
        // does not silently start the foreground service.
        if (DimmerPrefs.prefs(this).getBoolean(DimmerPrefs.KEY_SERVICE_ENABLED, false)) {
            startDimmerService(DimmerService.ACTION_RESET_IDLE, null)
        }

        // While the key-capture dialog is open, do not swallow the button
        // being mapped: return false so it reaches the dialog window.
        // The idle reset above is harmless and stays.
        if (DimmerPrefs.isCapturingKey(this)) return false

        val canDraw = Settings.canDrawOverlays(this)

        // Rule 1: any remote button exits blackout instantly.
        if (DimmerPrefs.isBlackout(this)) {
            startDimmerService(DimmerService.ACTION_EXIT_BLACKOUT, null)
            return canDraw
        }

        if (!canDraw || !isMappedKey(keyCode)) return false

        // Rule 2: hold -> long-press action, once per hold.
        if (event.repeatCount > 0) {
            if (keyCode !in longHandled) {
                longHandled.add(keyCode)
                dispatchLongPress(keyCode)
            }
            return true
        }

        // Rule 3: two presses within 400 ms -> double-press action.
        val now = event.eventTime
        val last = lastDownMs[keyCode] ?: 0L
        lastDownMs[keyCode] = now
        if (last != 0L && now - last < 400) {
            dispatchDoublePress(keyCode)
            return true
        }

        // Rule 4: mapped keys only. A key mapped to more than one action
        // resolves to the first match (brighter, dimmer, blackout, ambient).
        return when (keyCode) {
            DimmerPrefs.getKey(this, DimmerPrefs.KEY_BRIGHTER) -> {
                startDimmerService(DimmerService.ACTION_BRIGHTER, null)
                true
            }
            DimmerPrefs.getKey(this, DimmerPrefs.KEY_DIMMER) -> {
                startDimmerService(DimmerService.ACTION_DIMMER, null)
                true
            }
            DimmerPrefs.getKey(this, DimmerPrefs.KEY_TOGGLE) -> {
                startDimmerService(DimmerService.ACTION_TOGGLE_BLACKOUT, null)
                true
            }
            DimmerPrefs.getKey(this, DimmerPrefs.KEY_AMBIENT) -> {
                startDimmerService(DimmerService.ACTION_TOGGLE_AMBIENT, null)
                true
            }
            else -> false
        }
    }

    private fun dispatchLongPress(keyCode: Int) {
        when (keyCode) {
            DimmerPrefs.getKey(this, DimmerPrefs.KEY_BRIGHTER) ->
                startDimmerService(DimmerService.ACTION_SET_PERCENT, 100)
            DimmerPrefs.getKey(this, DimmerPrefs.KEY_DIMMER) ->
                startDimmerService(DimmerService.ACTION_SET_PERCENT, 0)
            DimmerPrefs.getKey(this, DimmerPrefs.KEY_TOGGLE) ->
                startDimmerService(DimmerService.ACTION_TOGGLE_AMBIENT, null)
            DimmerPrefs.getKey(this, DimmerPrefs.KEY_AMBIENT) ->
                startDimmerService(DimmerService.ACTION_SET_PERCENT, 100)
        }
    }

    private fun dispatchDoublePress(keyCode: Int) {
        when (keyCode) {
            DimmerPrefs.getKey(this, DimmerPrefs.KEY_BRIGHTER) ->
                startDimmerService(DimmerService.ACTION_SET_PERCENT, 75)
            DimmerPrefs.getKey(this, DimmerPrefs.KEY_DIMMER) ->
                startDimmerService(DimmerService.ACTION_SET_PERCENT, 25)
            DimmerPrefs.getKey(this, DimmerPrefs.KEY_TOGGLE) ->
                startDimmerService(DimmerService.ACTION_SET_PERCENT, 100)
            DimmerPrefs.getKey(this, DimmerPrefs.KEY_AMBIENT) ->
                startDimmerService(DimmerService.ACTION_SET_PERCENT, 50)
        }
    }

    private fun isMappedKey(keyCode: Int): Boolean {
        if (keyCode == DimmerPrefs.UNSET) return false
        return keyCode == DimmerPrefs.getKey(this, DimmerPrefs.KEY_BRIGHTER) ||
            keyCode == DimmerPrefs.getKey(this, DimmerPrefs.KEY_DIMMER) ||
            keyCode == DimmerPrefs.getKey(this, DimmerPrefs.KEY_TOGGLE) ||
            keyCode == DimmerPrefs.getKey(this, DimmerPrefs.KEY_AMBIENT)
    }

    private fun startDimmerService(action: String?, value: Int?) {
        val intent = Intent(this, DimmerService::class.java).apply {
            if (action != null) putExtra("action", action)
            if (value != null) putExtra("value", value)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
