package com.universal.tvdimmer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
 *   adb shell am startservice -n com.universal.tvdimmer/.DimmerService --es action "TOGGLE_AMBIENT"
 *   adb shell am startservice -n com.universal.tvdimmer/.DimmerService --es action "SET_AMBIENT_LEVEL" --ei value [5-60]
 *   adb shell am startservice -n com.universal.tvdimmer/.DimmerService --es action "SET_SLEEP_TIMER" --ei value [0-120]
 *   adb shell am startservice -n com.universal.tvdimmer/.DimmerService --es action "STOP"
 *
 * SET_PERCENT sets *brightness* (100 = full bright, 0 = blackout), matching
 * the brightness_percent stored in prefs. STOP turns the dimmer off and clears
 * the "restart at boot" flag. The internal actions TOGGLE_BLACKOUT /
 * EXIT_BLACKOUT / TOGGLE_AMBIENT are used by the accessibility service and the
 * settings screen.
 *
 * Anti-flicker rule: the overlay view is inflated exactly once and then only
 * mutated in memory (view.alpha). It is never removed and re-added, never
 * re-inflated, and WindowManager.updateViewLayout is never called for a
 * brightness step -- that is what used to flash a bright frame on the Xiaomi
 * compositor. Even at 100% brightness the view stays attached at alpha 0,
 * so the next dim step has no attach cost at all.
 *
 * Fail-safes (see README):
 *   - The service never resumes into blackout or ambient mode. If it is
 *     (re)started while either state is stored, it restores the level the user
 *     was at before entering that mode.
 *   - If blackout is entered while the accessibility service is NOT enabled,
 *     nothing can press "any key" to restore the picture, so blackout
 *     auto-restores after [BLACKOUT_GUARD_MILLIS].
 *
 * Sleep timer: while the sleep light is on and a duration is chosen, the light
 * fades out to blackout over [SLEEP_FADE_MILLIS] when the time is up. Any
 * brightness, ambient or blackout command during the fade cancels it (a key
 * press means the user is awake). The armed end time is absolute and cleared
 * by the same rules as ambient mode, so a restart cannot leave a stray timer.
 */
class DimmerService : Service() {

    companion object {
        const val ACTION_BRIGHTER = "BRIGHTER"
        const val ACTION_DIMMER = "DIMMER"
        const val ACTION_SET_PERCENT = "SET_PERCENT"
        const val ACTION_TOGGLE_BLACKOUT = "TOGGLE_BLACKOUT"
        const val ACTION_EXIT_BLACKOUT = "EXIT_BLACKOUT"
        const val ACTION_TOGGLE_AMBIENT = "TOGGLE_AMBIENT"
        const val ACTION_SET_AMBIENT_LEVEL = "SET_AMBIENT_LEVEL"
        const val ACTION_SET_SLEEP_TIMER = "SET_SLEEP_TIMER"
        const val ACTION_STOP = "STOP"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "tv_dimmer"
        private const val REQUEST_OPEN_APP = 0
        private const val REQUEST_TURN_OFF = 1

        /** Warm brown used by ambient (sleep light) mode instead of black. */
        private val COLOR_AMBIENT = 0xFF2A1200.toInt()

        /** How long a blackout may last when no accessibility service can
         *  intercept a remote button to restore the picture. */
        private const val BLACKOUT_GUARD_MILLIS = 60_000L

        /** Sleep timer: countdown refresh interval while a timer is armed. */
        private const val SLEEP_TICK_MILLIS = 60_000L

        /** Sleep timer: how long the light takes to fade from its level to black. */
        private const val SLEEP_FADE_MILLIS = 20_000L
        private const val SLEEP_FADE_STEPS = 40
        private const val SLEEP_FADE_STEP_MILLIS = SLEEP_FADE_MILLIS / SLEEP_FADE_STEPS
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var tintView: View? = null
    private var tvPowerReceiver: BroadcastReceiver? = null

    private val handler = Handler(Looper.getMainLooper())
    private val blackoutGuard = Runnable { guardBlackout() }
    private val sleepTick = Runnable { onSleepTick() }
    private val sleepFade = Runnable { onSleepFadeStep() }

    /** -1 = no fade running; >= 0 = index of the fade step in flight. */
    private var sleepFadeStep = -1
    private var fadeFromAlpha = 0f

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // Fail-safe first: never come back up black or tinted.
        restoreSafeState()
        ensureOverlay()
        tvPowerReceiver = TvPowerLink.register(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DimmerPrefs.prefs(this).edit().putBoolean(DimmerPrefs.KEY_SERVICE_ENABLED, true).apply()
        ensureOverlay()

        val action = intent?.getStringExtra("action") ?: intent?.action
        val value = intent?.getIntExtra("value", -1) ?: -1
        when (action) {
            ACTION_STOP -> {
                // The service may have been started with startForegroundService,
                // so post the notification before stopping to stay within the
                // 5 s startForeground() deadline.
                startAsForeground()
                stopDimmer()
                return START_NOT_STICKY
            }
            ACTION_BRIGHTER -> adjustBrightness(DimmerPrefs.STEP_PERCENT)
            ACTION_DIMMER -> adjustBrightness(-DimmerPrefs.STEP_PERCENT)
            ACTION_SET_PERCENT -> if (value in 0..100) {
                cancelSleepTimer()
                DimmerPrefs.setAmbientActive(this, false)
                applyBrightness(value)
            }
            ACTION_TOGGLE_BLACKOUT -> toggleBlackout()
            ACTION_EXIT_BLACKOUT -> exitBlackout()
            ACTION_TOGGLE_AMBIENT -> toggleAmbient()
            ACTION_SET_AMBIENT_LEVEL -> if (value in DimmerPrefs.AMBIENT_MIN..DimmerPrefs.AMBIENT_MAX) {
                DimmerPrefs.setAmbientLevel(this, value)
                if (DimmerPrefs.isAmbient(this)) applyBrightness(value)
            }
            ACTION_SET_SLEEP_TIMER -> {
                DimmerPrefs.setSleepTimerMinutes(this, value.coerceAtLeast(0))
                // A new duration applies from now to the light that is already on.
                if (DimmerPrefs.isAmbient(this)) armSleepTimer() else cancelSleepTimer()
            }
            else -> applyBrightness(DimmerPrefs.getBrightness(this))
        }
        startAsForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(blackoutGuard)
        handler.removeCallbacks(sleepTick)
        handler.removeCallbacks(sleepFade)
        sleepFadeStep = -1
        tvPowerReceiver?.let { TvPowerLink.unregister(this, it) }
        tvPowerReceiver = null
        overlayView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (_: IllegalArgumentException) {
                // Already removed; nothing to do.
            }
        }
        overlayView = null
        tintView = null
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
            tintView = view.findViewById(R.id.overlay_dim_view)
            applyTint()
        } catch (_: SecurityException) {
            // Overlay permission was revoked between the check and the add.
            overlayView = null
            tintView = null
        } catch (_: WindowManager.BadTokenException) {
            overlayView = null
            tintView = null
        }
    }

    /** Ambient mode is the same overlay with a warm tint instead of black. */
    private fun applyTint() {
        val color = if (DimmerPrefs.isAmbient(this)) COLOR_AMBIENT else Color.BLACK
        tintView?.setBackgroundColor(color)
    }

    /**
     * Never resume automatically into a state whose exit depends on a remote
     * button: a restart (crash, force stop, reboot) must come back to a
     * visible picture.
     */
    private fun restoreSafeState() {
        if (DimmerPrefs.isBlackout(this)) {
            DimmerPrefs.setBrightness(this, DimmerPrefs.getPreBlackout(this))
        }
        if (DimmerPrefs.isAmbient(this)) {
            DimmerPrefs.setAmbientActive(this, false)
            DimmerPrefs.setBrightness(this, DimmerPrefs.getPreAmbient(this))
        }
        // A timer can never survive a restart: it only arms from enterAmbient().
        DimmerPrefs.setSleepTimerEnd(this, 0L)
    }

    // ---- Brightness state ----

    private fun applyBrightness(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        val previous = DimmerPrefs.getBrightness(this)
        // Remember the last non-black level so toggle / any-key exit restores it.
        if (previous > 0 && clamped == 0) DimmerPrefs.setPreBlackout(this, previous)
        DimmerPrefs.setBrightness(this, clamped)
        // A brightness change is a user action: it ends a fade in progress.
        cancelSleepFade()
        // In-memory mutation only -- no re-inflate, no updateViewLayout.
        overlayView?.alpha = DimmerPrefs.opacityFor(clamped)
        applyTint()
        updateBlackoutGuard(clamped)
    }

    private fun adjustBrightness(delta: Int) {
        if (DimmerPrefs.isAmbient(this)) {
            // While the sleep light is on, the step keys sculpt the light
            // instead of leaving ambient mode.
            val next = (DimmerPrefs.getAmbientLevel(this) +
                if (delta > 0) DimmerPrefs.AMBIENT_STEP else -DimmerPrefs.AMBIENT_STEP)
                .coerceIn(DimmerPrefs.AMBIENT_MIN, DimmerPrefs.AMBIENT_MAX)
            DimmerPrefs.setAmbientLevel(this, next)
            applyBrightness(next)
            return
        }
        // Leaving blackout with a step key restores through the same path as
        // any other step: from 0, +10 lands at 10 rather than jumping back
        // to the pre-blackout level. Toggle / any-key exit is the restore path.
        applyBrightness(DimmerPrefs.getBrightness(this) + delta)
    }

    private fun toggleBlackout() {
        if (DimmerPrefs.isBlackout(this)) {
            applyBrightness(DimmerPrefs.getPreBlackout(this))
            return
        }
        if (DimmerPrefs.isAmbient(this)) {
            // Blackout from the sleep light returns to full brightness on exit,
            // not to the dim sleep level.
            cancelSleepTimer()
            DimmerPrefs.setAmbientActive(this, false)
            applyBrightness(DimmerPrefs.getPreAmbient(this))
        }
        applyBrightness(0)
    }

    private fun exitBlackout() {
        if (DimmerPrefs.isBlackout(this)) {
            applyBrightness(DimmerPrefs.getPreBlackout(this))
        }
    }

    // ---- Ambient (sleep light) ----

    private fun toggleAmbient() {
        if (DimmerPrefs.isAmbient(this)) exitAmbient() else enterAmbient()
    }

    private fun enterAmbient() {
        val current = DimmerPrefs.getBrightness(this)
        val restoreTo = if (current > 0) current else DimmerPrefs.getPreBlackout(this)
        DimmerPrefs.setPreAmbient(this, restoreTo)
        DimmerPrefs.setAmbientActive(this, true)
        applyBrightness(DimmerPrefs.getAmbientLevel(this))
        armSleepTimer()
    }

    private fun exitAmbient() {
        if (!DimmerPrefs.isAmbient(this)) return
        cancelSleepTimer()
        DimmerPrefs.setAmbientActive(this, false)
        applyBrightness(DimmerPrefs.getPreAmbient(this))
    }

    // ---- Sleep timer (sleep light only) ----

    private fun armSleepTimer() {
        handler.removeCallbacks(sleepTick)
        val minutes = DimmerPrefs.getSleepTimerMinutes(this)
        if (minutes <= 0) {
            DimmerPrefs.setSleepTimerEnd(this, 0L)
            return
        }
        DimmerPrefs.setSleepTimerEnd(
            this,
            System.currentTimeMillis() + minutes * 60_000L
        )
        scheduleSleepTick()
    }

    private fun cancelSleepTimer() {
        handler.removeCallbacks(sleepTick)
        cancelSleepFade()
        DimmerPrefs.setSleepTimerEnd(this, 0L)
    }

    private fun scheduleSleepTick() {
        handler.removeCallbacks(sleepTick)
        val end = DimmerPrefs.getSleepTimerEnd(this)
        if (end <= 0L) return
        val remaining = end - System.currentTimeMillis()
        if (remaining <= 0L) {
            startSleepFade()
            return
        }
        handler.postDelayed(sleepTick, remaining.coerceAtMost(SLEEP_TICK_MILLIS))
    }

    private fun onSleepTick() {
        if (!DimmerPrefs.isAmbient(this)) {
            cancelSleepTimer()
            return
        }
        scheduleSleepTick()
        // Refresh the notification so the countdown is visible.
        startAsForeground()
    }

    private fun startSleepFade() {
        handler.removeCallbacks(sleepTick)
        DimmerPrefs.setSleepTimerEnd(this, 0L)
        val view = overlayView
        if (view == null) {
            // No overlay to fade (permission revoked under us): leave ambient
            // cleanly instead of leaving an invisible blackout behind.
            DimmerPrefs.setAmbientActive(this, false)
            DimmerPrefs.setBrightness(this, DimmerPrefs.getPreAmbient(this))
            startAsForeground()
            return
        }
        fadeFromAlpha = view.alpha
        sleepFadeStep = 0
        handler.post(sleepFade)
    }

    private fun onSleepFadeStep() {
        val view = overlayView
        sleepFadeStep += 1
        val fraction = sleepFadeStep.toFloat() / SLEEP_FADE_STEPS
        if (view == null || fraction >= 1f) {
            finishSleep()
            return
        }
        view.alpha = fadeFromAlpha + (1f - fadeFromAlpha) * fraction
        handler.postDelayed(sleepFade, SLEEP_FADE_STEP_MILLIS)
    }

    private fun cancelSleepFade() {
        if (sleepFadeStep < 0) return
        handler.removeCallbacks(sleepFade)
        sleepFadeStep = -1
        // Put the overlay back where the stored brightness says it should be.
        overlayView?.alpha = DimmerPrefs.opacityFor(DimmerPrefs.getBrightness(this))
    }

    /**
     * The timer ran out: end ambient mode and land in blackout. The pre-blackout
     * level is the sleep level, so a key press brings back a dim picture rather
     * than a full-brightness flash in a dark room.
     */
    private fun finishSleep() {
        handler.removeCallbacks(sleepFade)
        sleepFadeStep = -1
        val level = DimmerPrefs.getAmbientLevel(this)
        DimmerPrefs.setAmbientActive(this, false)
        DimmerPrefs.setPreBlackout(this, level)
        applyBrightness(0)
        startAsForeground()
    }

    /** Whole minutes left on the armed timer, 0 when no timer is running. */
    private fun sleepTimerRemainingMinutes(): Int {
        val end = DimmerPrefs.getSleepTimerEnd(this)
        if (end <= 0L) return 0
        val remaining = end - System.currentTimeMillis()
        if (remaining <= 0L) return 0
        return ((remaining + 59_999L) / 60_000L).toInt()
    }

    // ---- Blackout fail-safe ----

    private fun updateBlackoutGuard(brightness: Int) {
        handler.removeCallbacks(blackoutGuard)
        if (brightness == 0 && !AccessibilityState.isEnabled(this)) {
            handler.postDelayed(blackoutGuard, BLACKOUT_GUARD_MILLIS)
        }
    }

    private fun guardBlackout() {
        if (!DimmerPrefs.isBlackout(this)) return
        // The accessibility service may have been enabled in the meantime;
        // then the user really can press any key and the blackout stays.
        if (AccessibilityState.isEnabled(this)) return
        applyBrightness(DimmerPrefs.getPreBlackout(this))
        startAsForeground()
    }

    // ---- Turn off ----

    private fun stopDimmer() {
        handler.removeCallbacks(blackoutGuard)
        cancelSleepTimer()
        DimmerPrefs.setAmbientActive(this, false)
        DimmerPrefs.setBrightness(this, 100)
        DimmerPrefs.prefs(this).edit()
            .putBoolean(DimmerPrefs.KEY_SERVICE_ENABLED, false)
            .apply()
        overlayView?.alpha = 0f
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
        val text = when {
            DimmerPrefs.isBlackout(this) ->
                if (AccessibilityState.isEnabled(this)) {
                    "Blackout mode — press any remote button to restore the picture"
                } else {
                    "Blackout mode — auto-restores in 1 min (Accessibility is off)"
                }
            DimmerPrefs.isAmbient(this) -> {
                val minutes = sleepTimerRemainingMinutes()
                if (minutes > 0) {
                    "Sleep light ${DimmerPrefs.getAmbientLevel(this)}% — fades out in $minutes min"
                } else {
                    "Sleep light ${DimmerPrefs.getAmbientLevel(this)}% (warm)"
                }
            }
            else -> "Brightness $brightness%"
        }
        val openIntent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, REQUEST_OPEN_APP, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val turnOff = PendingIntent.getService(
            this, REQUEST_TURN_OFF,
            Intent(this, DimmerService::class.java).putExtra("action", ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("LeoDimmer")
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Turn off", turnOff).build())
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
