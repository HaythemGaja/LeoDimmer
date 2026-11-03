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
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import java.util.Calendar

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
 * v1.4 additions (merged from local v1.2; v1.3 sleep light / timer / turn-off
 * / TV power behaviour is otherwise unchanged):
 *   --es action "SET_AMBIENT_COLOR" --es color "Red"  (Warm brown, Red, Amber,
 *       Orange, Green, Blue, Violet, Pink)
 *   --es action "RESET_IDLE"  (any remote key press; resets the idle timer)
 * Presets, schedule, idle timer, fade, and on-screen readout are driven
 * from the settings screen and the accessibility service, not new ADB verbs,
 * except that SET_PERCENT already covers presets (75 / 50 / 25 / 0).
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
        const val ACTION_SET_AMBIENT_COLOR = "SET_AMBIENT_COLOR"
        const val ACTION_RESET_IDLE = "RESET_IDLE"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "tv_dimmer"
        private const val REQUEST_OPEN_APP = 0
        private const val REQUEST_TURN_OFF = 1

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
    private var osdView: TextView? = null
    private var tvPowerReceiver: BroadcastReceiver? = null

    private val handler = Handler(Looper.getMainLooper())
    private val blackoutGuard = Runnable { guardBlackout() }
    private val sleepTick = Runnable { onSleepTick() }
    private val sleepFade = Runnable { onSleepFadeStep() }
    private val scheduleRunnable = object : Runnable {
        override fun run() {
            checkSchedule()
            handler.postDelayed(this, 60_000)
        }
    }
    private val idleRunnable = Runnable {
        val idleMinutes = DimmerPrefs.getIdleMinutes(this)
        if (idleMinutes > 0 && !DimmerPrefs.isBlackout(this) &&
            System.currentTimeMillis() - lastActivityMs >= idleMinutes * 60_000L
        ) {
            // If the sleep light is on, exit it first: otherwise the
            // overlay at alpha 1.0 would be a solid colour, not black.
            if (DimmerPrefs.isAmbient(this)) {
                cancelSleepTimer()
                DimmerPrefs.setAmbientActive(this, false)
                DimmerPrefs.setPreBlackout(this, DimmerPrefs.getAmbientLevel(this))
            }
            applyBrightness(0, animate = true, showOsd = false)
            showOsd("Idle blackout — press any button to restore")
        }
    }
    private val hideOsdRunnable = Runnable { osdView?.alpha = 0f }
    private val generalFade = Runnable { onGeneralFadeStep() }

    /** -1 = no fade running; >= 0 = index of the fade step in flight. */
    private var sleepFadeStep = -1
    private var fadeFromAlpha = 0f

    // General (v1.4) fade state: 6 steps x 50 ms = ~300 ms glide.
    private var generalFadeFromAlpha = 0f
    private var generalFadeTargetAlpha = 0f
    private var generalFadeStep = -1
    private var generalFadeShowOsd = true
    private var lastActivityMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Foreground immediately: the 5-second startForegroundService
        // deadline runs from the caller's start call, not from the end
        // of onStartCommand, and a slow box recreating the settings
        // screen on the same main thread can otherwise miss it and
        // look exactly like an app crash on the click that started it.
        // onStartCommand still refreshes the notification at its end.
        startAsForeground()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        lastActivityMs = System.currentTimeMillis()
        // Fail-safe first: never come back up black or tinted.
        restoreSafeState()
        ensureOverlay()
        tvPowerReceiver = TvPowerLink.register(this)
        handler.post(scheduleRunnable)
        scheduleIdle()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra("action") ?: intent?.action
        val value = intent?.getIntExtra("value", -1) ?: -1
        if (action == ACTION_RESET_IDLE) {
            // Must not itself mark the service as "ever started": the
            // accessibility service only sends it when that flag is set.
            // Foreground first, so a lone reset delivered via
            // startForegroundService cannot miss the foreground deadline.
            startAsForeground()
            resetIdle()
            return START_STICKY
        }
        if (action != ACTION_STOP) {
            DimmerPrefs.prefs(this).edit().putBoolean(DimmerPrefs.KEY_SERVICE_ENABLED, true).apply()
        }
        // Any service command is user activity for the idle timer.
        resetIdle()
        ensureOverlay()

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
            ACTION_SET_AMBIENT_COLOR -> {
                val name = intent?.getStringExtra("color") ?: ""
                val index = DimmerPrefs.AMBIENT_COLORS.indexOfFirst {
                    it.first.equals(name, ignoreCase = true)
                }
                if (index >= 0) {
                    DimmerPrefs.prefs(this).edit()
                        .putInt(DimmerPrefs.KEY_AMBIENT_COLOR_INDEX, index).apply()
                    applyTint()
                }
            }
            ACTION_SET_SLEEP_TIMER -> {
                DimmerPrefs.setSleepTimerMinutes(this, value.coerceAtLeast(0))
                // A new duration applies from now to the light that is already on.
                if (DimmerPrefs.isAmbient(this)) armSleepTimer() else cancelSleepTimer()
            }
            else -> {
                // Plain start (boot, settings open, accessibility connect):
                // sync the stored state silently, then let the schedule and
                // idle timers take over if they are configured.
                checkSchedule(force = true)
                applyBrightness(
                    DimmerPrefs.getBrightness(this),
                    animate = false,
                    showOsd = false
                )
                scheduleIdle()
            }
        }
        startAsForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(blackoutGuard)
        handler.removeCallbacks(sleepTick)
        handler.removeCallbacks(sleepFade)
        handler.removeCallbacks(scheduleRunnable)
        handler.removeCallbacks(idleRunnable)
        handler.removeCallbacks(hideOsdRunnable)
        handler.removeCallbacks(generalFade)
        sleepFadeStep = -1
        generalFadeStep = -1
        tvPowerReceiver?.let { TvPowerLink.unregister(this, it) }
        tvPowerReceiver = null
        listOf(overlayView, osdView).forEach { view ->
            view?.let {
                try {
                    windowManager?.removeView(it)
                } catch (_: IllegalArgumentException) {
                    // Already removed; nothing to do.
                }
            }
        }
        overlayView = null
        tintView = null
        osdView = null
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
            // v1.4 on-screen readout: a separate top layer, created once,
            // hidden at alpha 0 until a change shows it for 2 seconds.
            val osdParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (80 * resources.displayMetrics.density).toInt()
            }
            val osd = TextView(this).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                setTextColor(0xFFFFD166.toInt())
                setBackgroundColor(0xCC000000.toInt())
                gravity = Gravity.CENTER
                val pad = (24 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, pad / 2)
                alpha = 0f
            }
            windowManager?.addView(osd, osdParams)
            osdView = osd
        } catch (_: RuntimeException) {
            // Overlay permission revoked between check and add, or a
            // vendor-specific addView failure (BadToken, IllegalArgument,
            // InvalidDisplay, ...). Stay alive without an overlay rather
            // than killing the whole app process on a service start.
            overlayView = null
            tintView = null
            osdView = null
        }
    }

    /** Ambient mode is the same overlay with a tint instead of black. */
    private fun applyTint() {
        val color = if (DimmerPrefs.isAmbient(this)) {
            DimmerPrefs.ambientColorValue(this)
        } else {
            Color.BLACK
        }
        tintView?.setBackgroundColor(color)
    }

    private fun showOsd(text: String) {
        val osd = osdView ?: return
        osd.text = text
        osd.alpha = 1f
        handler.removeCallbacks(hideOsdRunnable)
        handler.postDelayed(hideOsdRunnable, 2000)
    }

    private fun showBrightnessOsd() {
        // Blackout is audio-only by definition: no yellow popup on a
        // black screen (worst at sleep time). The idle path shows its
        // own explicit message via showOsd() and is unaffected.
        if (DimmerPrefs.isBlackout(this)) return
        when {
            DimmerPrefs.isAmbient(this) -> showOsd(
                "Sleep light ${DimmerPrefs.ambientColorName(this)} " +
                    "${DimmerPrefs.getAmbientLevel(this)}%"
            )
            else -> showOsd("Brightness ${DimmerPrefs.getBrightness(this)}%")
        }
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

    /**
     * v1.4: with animate = true (default) and fade enabled, the overlay
     * alpha glides from its current value to the target over ~300 ms while
     * the stored brightness is already the target, so an interrupted fade
     * can never leave the stored state and the picture disagreeing. The
     * sleep fade is a separate, longer animation and is never mixed with
     * this one: a manual brightness change cancels it first.
     */
    private fun applyBrightness(
        percent: Int,
        animate: Boolean = true,
        showOsd: Boolean = true
    ) {
        handler.removeCallbacks(generalFade)
        generalFadeStep = -1
        val clamped = percent.coerceIn(0, 100)
        val previous = DimmerPrefs.getBrightness(this)
        val oldAlpha = overlayView?.alpha
            ?: DimmerPrefs.opacityFor(previous)
        // Remember the last non-black level so toggle / any-key exit restores it.
        if (previous > 0 && clamped == 0) DimmerPrefs.setPreBlackout(this, previous)
        DimmerPrefs.setBrightness(this, clamped)
        // A brightness change is a user action: it ends a fade in progress.
        cancelSleepFade()
        applyTint()
        updateBlackoutGuard(clamped)
        val targetAlpha = DimmerPrefs.opacityFor(clamped)
        if (animate && DimmerPrefs.isFadeEnabled(this) &&
            sleepFadeStep < 0 && oldAlpha != targetAlpha
        ) {
            generalFadeFromAlpha = oldAlpha
            generalFadeTargetAlpha = targetAlpha
            generalFadeStep = 0
            generalFadeShowOsd = showOsd
            overlayView?.alpha = oldAlpha
            handler.post(generalFade)
            return
        }
        // In-memory mutation only -- no re-inflate, no updateViewLayout.
        overlayView?.alpha = targetAlpha
        if (showOsd) showBrightnessOsd()
    }

    private fun onGeneralFadeStep() {
        generalFadeStep += 1
        val view = overlayView
        if (view == null || generalFadeStep >= 6) {
            generalFadeStep = -1
            view?.alpha = generalFadeTargetAlpha
            if (generalFadeShowOsd) showBrightnessOsd()
            return
        }
        val fraction = generalFadeStep.toFloat() / 6f
        view.alpha = generalFadeFromAlpha +
            (generalFadeTargetAlpha - generalFadeFromAlpha) * fraction
        handler.postDelayed(generalFade, 50)
    }

    // ---- v1.4: schedule + idle ----

    private fun checkSchedule(force: Boolean = false) {
        if (!DimmerPrefs.isScheduleEnabled(this)) return
        // The sleep light is a manual sleep mode: the schedule must not
        // fight it, and blackout must not be exited by a schedule change.
        // State is left untouched, so the next minute's check applies the
        // schedule once the light is off / the picture is back.
        if (DimmerPrefs.isAmbient(this) || DimmerPrefs.isBlackout(this)) return
        val now = Calendar.getInstance()
        val minutesNow = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val dimStart = DimmerPrefs.getScheduleDimHour(this) * 60
        val restoreStart = DimmerPrefs.getScheduleRestoreHour(this) * 60
        val inWindow = if (dimStart <= restoreStart) {
            minutesNow in dimStart until restoreStart
        } else {
            minutesNow >= dimStart || minutesNow < restoreStart
        }
        val state = DimmerPrefs.prefs(this).getInt(DimmerPrefs.KEY_SCHEDULE_STATE, 0)
        if (inWindow && (state != 1 || force)) {
            // Store the pre-schedule level only on entry, so a forced
            // re-apply (settings row changed mid-window) does not lose
            // the restore level, and a manual brightness change during
            // the window is not fought by the minute tick.
            if (state != 1) {
                DimmerPrefs.prefs(this).edit()
                    .putInt(
                        DimmerPrefs.KEY_SCHEDULE_PRE_BRIGHTNESS,
                        DimmerPrefs.getBrightness(this)
                    )
                    .putInt(DimmerPrefs.KEY_SCHEDULE_STATE, 1).apply()
            }
            applyBrightness(
                DimmerPrefs.getScheduleDimPercent(this),
                animate = true,
                showOsd = false
            )
        } else if (!inWindow && state == 1) {
            val restoreTo = DimmerPrefs.prefs(this)
                .getInt(DimmerPrefs.KEY_SCHEDULE_PRE_BRIGHTNESS, 100)
                .coerceIn(0, 100)
            DimmerPrefs.prefs(this).edit()
                .putInt(DimmerPrefs.KEY_SCHEDULE_STATE, 0).apply()
            applyBrightness(restoreTo, animate = true, showOsd = false)
        }
    }

    private fun resetIdle() {
        lastActivityMs = System.currentTimeMillis()
        DimmerPrefs.prefs(this).edit()
            .putLong(DimmerPrefs.KEY_LAST_ACTIVITY_MS, lastActivityMs).apply()
        scheduleIdle()
    }

    private fun scheduleIdle() {
        handler.removeCallbacks(idleRunnable)
        val idleMinutes = DimmerPrefs.getIdleMinutes(this)
        if (idleMinutes > 0) {
            handler.postDelayed(idleRunnable, idleMinutes * 60_000L)
        }
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
        handler.removeCallbacks(generalFade)
        generalFadeStep = -1
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
        // No popup at the end of a sleep fade: the user is meant to be asleep.
        applyBrightness(0, animate = false, showOsd = false)
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
        handler.removeCallbacks(scheduleRunnable)
        handler.removeCallbacks(idleRunnable)
        handler.removeCallbacks(hideOsdRunnable)
        handler.removeCallbacks(generalFade)
        generalFadeStep = -1
        cancelSleepTimer()
        DimmerPrefs.setAmbientActive(this, false)
        DimmerPrefs.setBrightness(this, 100)
        DimmerPrefs.prefs(this).edit()
            .putBoolean(DimmerPrefs.KEY_SERVICE_ENABLED, false)
            .apply()
        overlayView?.alpha = 0f
        osdView?.alpha = 0f
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
                val color = DimmerPrefs.ambientColorName(this)
                if (minutes > 0) {
                    "Sleep light $color ${DimmerPrefs.getAmbientLevel(this)}% — fades out in $minutes min"
                } else {
                    "Sleep light $color ${DimmerPrefs.getAmbientLevel(this)}%"
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
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_dialog_info),
                    "Turn off", turnOff
                ).build()
            )
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
