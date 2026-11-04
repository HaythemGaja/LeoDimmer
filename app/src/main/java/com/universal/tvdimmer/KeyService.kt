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
 *  2. DRIVING. One key cycles Day -> Night -> Sleep -> Day, and a second key
 *     (two buttons if you map both directions) tunes the mode in use -- Day
 *     brightness, Night brightness or the Sleep glow -- so one pair of keys
 *     covers all three modes. Held in Sleep, the tune key steps the lamp
 *     colour instead. Any key exits blackout. The two optional 1.x mappings
 *     still work if they were set.
 *
 * The service only swallows keys it is responsible for: everything else is
 * returned untouched, so normal navigation is never broken. HOME and POWER
 * never reach an app on Android and can therefore not be mapped.
 */
class KeyService : AccessibilityService() {

    companion object {
        /**
         * The live service, so the settings screen can ask it for a global
         * action. AccessibilityService is the only way a sideloaded app can
         * open the system power dialog, and it has to be called on the bound
         * instance.
         */
        private var live: KeyService? = null

        /** Opens the box's power menu. False when the service is not running. */
        fun powerDialog(): Boolean {
            val service = live ?: return false
            return try {
                service.performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
            } catch (_: Exception) {
                false
            }
        }
    }

    private val lastDownMs = mutableMapOf<Int, Long>()
    private val longHandled = mutableSetOf<Int>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        live = this
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

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (live === this) live = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (live === this) live = null
        super.onDestroy()
    }

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
                OverlayService.send(this, OverlayService.ACTION_TUNE, 1)
                true
            }
            Settings.getKey(this, Settings.KEY_DIMMER) -> {
                OverlayService.send(this, OverlayService.ACTION_TUNE, -1)
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

    /**
     * Holding the tune key steps the lamp colour (Sleep only, ignored
     * elsewhere), so the colour needs no button of its own.
     */
    private fun dispatchLongPress(keyCode: Int) {
        when (keyCode) {
            Settings.getKey(this, Settings.KEY_BRIGHTER) ->
                OverlayService.send(this, OverlayService.ACTION_TUNE_COLOUR, 1)
            Settings.getKey(this, Settings.KEY_DIMMER) ->
                OverlayService.send(this, OverlayService.ACTION_TUNE_COLOUR, -1)
            Settings.getKey(this, Settings.KEY_TOGGLE) ->
                OverlayService.send(this, OverlayService.ACTION_TOGGLE_AMBIENT)
        }
    }

    /** A double press is the same tune, four steps at a time. */
    private fun dispatchDoublePress(keyCode: Int) {
        when (keyCode) {
            Settings.getKey(this, Settings.KEY_BRIGHTER) ->
                OverlayService.send(this, OverlayService.ACTION_TUNE, 4)
            Settings.getKey(this, Settings.KEY_DIMMER) ->
                OverlayService.send(this, OverlayService.ACTION_TUNE, -4)
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
