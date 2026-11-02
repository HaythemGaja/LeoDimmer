package com.universal.tvdimmer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * The overlay owner (MidnightBeam-style click-through overlay + DimTV-style
 * persistent service lifecycle, combined).
 *
 * Headless ADB spec -- these exact commands work against this service:
 *   adb shell am startservice -n com.universal.tvdimmer/.DimmerService --es action "BRIGHTER"
 *   adb shell am startservice -n com.universal.tvdimmer/.DimmerService --es action "DIMMER"
 *   adb shell am startservice -n com.universal.tvdimmer/.DimmerService --es action "SET_PERCENT" --ei value [0-100]
 *
 * SET_PERCENT sets *brightness* (100 = full bright, 0 = blackout), matching
 * the brightness_percent stored in prefs. The internal actions
 * TOGGLE_BLACKOUT / EXIT_BLACKOUT are used by the accessibility service and
 * the settings screen; they are not part of the ADB spec.
 *
 * Anti-flicker rule: the overlay view is inflated exactly once and then only
 * mutated in memory (view.alpha). It is never removed and re-added, never
 * re-inflated, and WindowManager.updateViewLayout is never called for a
 * brightness step -- that is what used to flash a bright frame on the Xiaomi
 * compositor. Even at 100% brightness the view stays attached at alpha 0,
 * so the next dim step has no attach cost at all.
 */
class DimmerService : Service() {

    companion object {
        const val ACTION_BRIGHTER = "BRIGHTER"
        const val ACTION_DIMMER = "DIMMER"
        const val ACTION_SET_PERCENT = "SET_PERCENT"
        const val ACTION_TOGGLE_BLACKOUT = "TOGGLE_BLACKOUT"
        const val ACTION_EXIT_BLACKOUT = "EXIT_BLACKOUT"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "tv_dimmer"
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        ensureOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DimmerPrefs.prefs(this).edit().putBoolean(DimmerPrefs.KEY_SERVICE_ENABLED, true).apply()
        startAsForeground()
        ensureOverlay()

        val action = intent?.getStringExtra("action") ?: intent?.action
        val value = intent?.getIntExtra("value", -1) ?: -1
        when (action) {
            ACTION_BRIGHTER -> adjustBrightness(DimmerPrefs.STEP_PERCENT)
            ACTION_DIMMER -> adjustBrightness(-DimmerPrefs.STEP_PERCENT)
            ACTION_SET_PERCENT -> {
                if (value in 0..100) applyBrightness(value)
            }
            ACTION_TOGGLE_BLACKOUT -> toggleBlackout()
            ACTION_EXIT_BLACKOUT -> exitBlackout()
            else -> applyBrightness(DimmerPrefs.getBrightness(this))
        }
        // Refresh the ongoing notification text after any change above.
        startAsForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        overlayView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (_: IllegalArgumentException) {
                // Already removed; nothing to do.
            }
        }
        overlayView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- Overlay ----

    private fun ensureOverlay() {
        if (overlayView != null) return
        // Without SYSTEM_ALERT_WINDOW, addView throws. Stay alive without an
        // overlay so a later start command (after the user grants it) works.
        if (!Settings.canDrawOverlays(this)) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        val view = android.view.LayoutInflater.from(this)
            .inflate(R.layout.view_dimmer_overlay, null)
        view.alpha = DimmerPrefs.opacityFor(DimmerPrefs.getBrightness(this))
        try {
            windowManager?.addView(view, params)
            overlayView = view
        } catch (_: SecurityException) {
            // Overlay permission was revoked between the check and the add.
            overlayView = null
        } catch (_: WindowManager.BadTokenException) {
            overlayView = null
        }
    }

    // ---- Brightness state ----

    private fun applyBrightness(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        val previous = DimmerPrefs.getBrightness(this)
        // Remember the last non-black level so toggle / any-key exit restores it.
        if (previous > 0 && clamped == 0) DimmerPrefs.setPreBlackout(this, previous)
        DimmerPrefs.setBrightness(this, clamped)
        // In-memory mutation only -- no re-inflate, no updateViewLayout.
        overlayView?.alpha = DimmerPrefs.opacityFor(clamped)
    }

    private fun adjustBrightness(delta: Int) {
        // Leaving blackout with a step key restores through the same path as
        // any other step: from 0, +10 lands at 10 rather than jumping back
        // to the pre-blackout level. Toggle / any-key exit is the restore path.
        applyBrightness(DimmerPrefs.getBrightness(this) + delta)
    }

    private fun toggleBlackout() {
        if (DimmerPrefs.isBlackout(this)) {
            applyBrightness(DimmerPrefs.getPreBlackout(this))
        } else {
            applyBrightness(0)
        }
    }

    private fun exitBlackout() {
        if (DimmerPrefs.isBlackout(this)) {
            applyBrightness(DimmerPrefs.getPreBlackout(this))
        }
    }

    // ---- Foreground notification ----

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LeoDimmer",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps the LeoDimmer overlay running" }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun startAsForeground() {
        val brightness = DimmerPrefs.getBrightness(this)
        val text = if (brightness == 0) {
            "Blackout mode — press any remote button to restore the picture"
        } else {
            "Brightness $brightness%"
        }
        val openIntent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("LeoDimmer")
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
