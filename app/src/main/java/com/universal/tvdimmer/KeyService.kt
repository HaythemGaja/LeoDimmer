package com.universal.tvdimmer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * LeoDimmer v2.0 -- the remote.
 *
 * This service exists for two things, and both happen HERE rather than in a
 * window:
 *
 *  1. LEARNING. The screen only writes "learn this target" into prefs. The
 *     next button that arrives is stored, wherever the focus happens to be,
 *     and the press is consumed. That is the whole fix for "does not register
 *     the button when pressed": the old design listened inside a dialog, and a
 *     dialog that never takes focus on the Mi Box never sees a key. Here there
 *     is no window to lose focus, so a press cannot be missed.
 *
 *  2. DRIVING. One key cycles Day -> Night -> Sleep -> Day. Any key exits
 *     blackout. The four optional 1.x mappings still work if they were set.
 *
 * The service only swallows keys it is responsible for: everything else is
 * returned untouched, so normal navigation is never broken. HOME and POWER
 * never reach an app on Android and can therefore not be mapped.
 */
class KeyService : AccessibilityService() {

    private val lastDownMs = mutableMapOf<Int, Long>()
    private val longHandled = mutableSetOf<Int>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Ask for key filtering at runtime as well as in the XML: a few vendor
        // builds only honour the service-info flag.
        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.notificationTimeout = 100
            serviceInfo = info
        } catch (_: Exception) {
            // Not fatal: the XML already asks for the same thing.
        }
        // Lets the settings screen show "connected", not just "enabled".
        Settings.markKeysReady(this)
        if (Settings.canDrawOverlays(this)) OverlayService.send(this, null)
        toast("LeoDimmer remote is active")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Key filtering only; window content is never read.
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // ---- 1. Learning wins over everything else ----
        val target = Settings.learnTarget(this)
        if (target.isNotEmpty()) {
            if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return true
            val keyCode = event.keyCode
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                Settings.clearLearn(this)
                toast("Cancelled")
                return true
            }
            Settings.setKey(this, target, keyCode)
            Settings.finishLearn(this, keyCode)
            toast("Learned ${Settings.keyName(keyCode)}")
            // No service command here: the settings screen refreshes itself.
            return true
        }

        val keyCode = event.keyCode

        if (event.action == KeyEvent.ACTION_UP) {
            longHandled.remove(keyCode)
            return isMappedKey(keyCode)
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false

        // Keep the idle timer honest, but only once the dimmer was ever used.
        if (Settings.isServiceEnabled(this)) {
            OverlayService.send(this, OverlayService.ACTION_RESET_IDLE)
        }

        val canDraw = Settings.canDrawOverlays(this)

        // ---- 2. Any button leaves blackout ----
        if (Settings.isBlackout(this)) {
            OverlayService.send(this, OverlayService.ACTION_EXIT_BLACKOUT)
            return canDraw
        }
        // Without the overlay there is nothing to dim: never swallow a key.
        if (!canDraw) return false

        // ---- 3. The one key that matters ----
        val modeKey = Settings.getKey(this, Settings.KEY_MODE_KEY)
        if (modeKey != Settings.UNSET && keyCode == modeKey) {
            if (event.repeatCount == 0) {
                OverlayService.send(this, OverlayService.ACTION_CYCLE_MOOD)
            }
            return true
        }

        if (!isMappedKey(keyCode)) return false

        if (event.repeatCount > 0) {
            if (keyCode !in longHandled) {
                longHandled.add(keyCode)
                dispatchLongPress(keyCode)
            }
            return true
        }

        val now = event.eventTime
        val last = lastDownMs[keyCode] ?: 0L
        lastDownMs[keyCode] = now
        if (last != 0L && now - last < 400) {
            dispatchDoublePress(keyCode)
            return true
        }

        return when (keyCode) {
            Settings.getKey(this, Settings.KEY_BRIGHTER) -> {
                OverlayService.send(this, OverlayService.ACTION_BRIGHTER)
                true
            }
            Settings.getKey(this, Settings.KEY_DIMMER) -> {
                OverlayService.send(this, OverlayService.ACTION_DIMMER)
                true
            }
            Settings.getKey(this, Settings.KEY_TOGGLE) -> {
                OverlayService.send(this, OverlayService.ACTION_TOGGLE_BLACKOUT)
                true
            }
            Settings.getKey(this, Settings.KEY_AMBIENT) -> {
                OverlayService.send(this, OverlayService.ACTION_TOGGLE_AMBIENT)
                true
            }
            else -> false
        }
    }

    private fun dispatchLongPress(keyCode: Int) {
        when (keyCode) {
            Settings.getKey(this, Settings.KEY_BRIGHTER) ->
                OverlayService.send(this, OverlayService.ACTION_SET_PERCENT, 100)
            Settings.getKey(this, Settings.KEY_DIMMER) ->
                OverlayService.send(this, OverlayService.ACTION_SET_PERCENT, 0)
            Settings.getKey(this, Settings.KEY_TOGGLE) ->
                OverlayService.send(this, OverlayService.ACTION_TOGGLE_AMBIENT)
        }
    }

    private fun dispatchDoublePress(keyCode: Int) {
        when (keyCode) {
            Settings.getKey(this, Settings.KEY_BRIGHTER) ->
                OverlayService.send(this, OverlayService.ACTION_SET_PERCENT, 75)
            Settings.getKey(this, Settings.KEY_DIMMER) ->
                OverlayService.send(this, OverlayService.ACTION_SET_PERCENT, 25)
        }
    }

    private fun isMappedKey(keyCode: Int): Boolean {
        if (keyCode == Settings.UNSET) return false
        return keyCode == Settings.getKey(this, Settings.KEY_MODE_KEY) ||
            keyCode == Settings.getKey(this, Settings.KEY_BRIGHTER) ||
            keyCode == Settings.getKey(this, Settings.KEY_DIMMER) ||
            keyCode == Settings.getKey(this, Settings.KEY_TOGGLE) ||
            keyCode == Settings.getKey(this, Settings.KEY_AMBIENT)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
