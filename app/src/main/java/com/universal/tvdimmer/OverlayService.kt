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
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import java.util.Calendar

/**
 * LeoDimmer v2.0 -- the overlay.
 *
 * One full-screen, non-touchable view sits above every app. Brightness only
 * changes that view's alpha in memory: it is created once, never re-inflated
 * (re-inflating is what used to flash a bright frame on the Mi Box), and never
 * re-added for a brightness step.
 *
 * The three modes are applied here, so the settings screen and the remote key
 * cannot disagree about what "Night" means:
 *
 *   Day   -> brightness = Day brightness, sleep light off, timer off
 *   Night -> brightness = Night brightness, sleep light off, timer off
 *   Sleep -> the lamp: an opaque fill of its colour, dimmed by scaling that
 *            colour rather than by fading a black layer, plus the sleep timer,
 *            which fades the light out when it runs out
 *
 * Fail-safes (unchanged from 1.x, because they are the reason a dimmer is
 * safe to leave running):
 *   - The service never resumes into blackout or Sleep. A restart always comes
 *     back to a visible picture.
 *   - Blackout entered while the key service is off auto-restores after a
 *     minute, so the screen can never be stuck black.
 *
 * Headless control (adb), on the owner's own box:
 *   adb shell am startservice -n com.universal.tvdimmer/.OverlayService --es action SET_MOOD --ei value 1
 *   adb shell am startservice -n com.universal.tvdimmer/.OverlayService --es action CYCLE_MOOD
 *   adb shell am startservice -n com.universal.tvdimmer/.OverlayService --es action SET_PERCENT --ei value 30
 *   adb shell am startservice -n com.universal.tvdimmer/.OverlayService --es action TOGGLE_BLACKOUT
 *   adb shell am startservice -n com.universal.tvdimmer/.OverlayService --es action STOP
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_SET_MOOD = "SET_MOOD"
        const val ACTION_CYCLE_MOOD = "CYCLE_MOOD"
        const val ACTION_SET_PERCENT = "SET_PERCENT"
        const val ACTION_BRIGHTER = "BRIGHTER"
        const val ACTION_DIMMER = "DIMMER"
        const val ACTION_TOGGLE_BLACKOUT = "TOGGLE_BLACKOUT"
        const val ACTION_EXIT_BLACKOUT = "EXIT_BLACKOUT"
        const val ACTION_TOGGLE_AMBIENT = "TOGGLE_AMBIENT"
        const val ACTION_SET_SLEEP_TIMER = "SET_SLEEP_TIMER"
        const val ACTION_RESET_IDLE = "RESET_IDLE"
        const val ACTION_STOP = "STOP"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "tv_dimmer"
        private const val REQUEST_OPEN_APP = 0
        private const val REQUEST_TURN_OFF = 1

        /** A blackout must never be able to trap the screen. */
        private const val BLACKOUT_GUARD_MILLIS = 60_000L
        private const val SLEEP_TICK_MILLIS = 60_000L
        private const val SLEEP_FADE_MILLIS = 20_000L
        private const val SLEEP_FADE_STEPS = 40
        private const val SLEEP_FADE_STEP_MILLIS = SLEEP_FADE_MILLIS / SLEEP_FADE_STEPS

        /** Start (or command) the overlay from anywhere in the app. */
        fun send(context: Context, action: String?, value: Int? = null) {
            val intent = Intent(context, OverlayService::class.java).apply {
                if (action != null) putExtra("action", action)
                if (value != null) putExtra("value", value)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private var windowManager: WindowManager? = null
    private var overlay: View? = null

    /** What the overlay is currently filled with (see [applyTint]). */
    private var fillColor = Color.BLACK
    private var osd: TextView? = null
    private var tvPowerReceiver: BroadcastReceiver? = null

    private val handler = Handler(Looper.getMainLooper())
    private val blackoutGuard = Runnable { guardBlackout() }
    private val sleepTick = Runnable { onSleepTick() }
    private val sleepFade = Runnable { onSleepFadeStep() }
    private val hideOsd = Runnable { osd?.alpha = 0f }
    private val generalFade = Runnable { onGeneralFadeStep() }

    private val scheduleRunnable = object : Runnable {
        override fun run() {
            checkSchedule()
            handler.postDelayed(this, 60_000)
        }
    }

    private val idleRunnable = Runnable {
        val idleMinutes = Settings.getIdleMinutes(this)
        if (idleMinutes > 0 && !Settings.isBlackout(this) &&
            System.currentTimeMillis() - lastActivityMs >= idleMinutes * 60_000L
        ) {
            if (Settings.isAmbient(this)) {
                cancelSleepTimer()
                Settings.setAmbientActive(this, false)
                Settings.setPreBlackout(this, Settings.getAmbientLevel(this))
            }
            applyBrightness(0, animate = true, showOsd = false)
            showOsd("Idle blackout — press any button")
        }
    }

    private var sleepFadeStep = -1

    /** The lamp colour the fade-out started from. */
    private var fadeFromColor = Color.BLACK

    private var generalFadeFromAlpha = 0f
    private var generalFadeTargetAlpha = 0f
    private var generalFadeFromColor = Color.BLACK
    private var generalFadeToColor = Color.BLACK
    private var generalFadeStep = -1
    private var generalFadeShowOsd = true
    private var lastActivityMs = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Foreground first, before any overlay work: a slow box must never
        // miss the startForegroundService deadline, which looks exactly like
        // a crash on the key press that started the service.
        startAsForeground()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        lastActivityMs = System.currentTimeMillis()
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
            startAsForeground()
            resetIdle()
            return START_STICKY
        }
        if (action != ACTION_STOP) Settings.setServiceEnabled(this, true)
        resetIdle()
        ensureOverlay()

        when (action) {
            ACTION_STOP -> {
                startAsForeground()
                stopDimmer()
                return START_NOT_STICKY
            }
            ACTION_SET_MOOD -> if (value in Settings.MOOD_DAY..Settings.MOOD_SLEEP) {
                applyMood(value)
            }
            ACTION_CYCLE_MOOD -> applyMood(Settings.nextMood(Settings.getMood(this)))
            ACTION_SET_PERCENT -> if (value in 0..100) {
                cancelSleepTimer()
                Settings.setAmbientActive(this, false)
                applyBrightness(value)
            }
            ACTION_BRIGHTER -> adjustBrightness(Settings.STEP_PERCENT)
            ACTION_DIMMER -> adjustBrightness(-Settings.STEP_PERCENT)
            ACTION_TOGGLE_BLACKOUT -> toggleBlackout()
            ACTION_EXIT_BLACKOUT -> exitBlackout()
            ACTION_TOGGLE_AMBIENT -> if (Settings.isAmbient(this)) exitAmbient() else enterAmbient()
            ACTION_SET_SLEEP_TIMER -> {
                Settings.setSleepTimerMinutes(this, value.coerceAtLeast(0))
                if (Settings.isAmbient(this)) armSleepTimer() else cancelSleepTimer()
            }
            else -> {
                // Plain start (app opened, boot, key service connected):
                // sync what is stored without a popup.
                checkSchedule(force = true)
                applyBrightness(Settings.getBrightness(this), animate = false, showOsd = false)
                scheduleIdle()
            }
        }
        startAsForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        sleepFadeStep = -1
        generalFadeStep = -1
        tvPowerReceiver?.let { TvPowerLink.unregister(this, it) }
        tvPowerReceiver = null
        listOf(overlay, osd).forEach { view ->
            view?.let {
                try {
                    windowManager?.removeView(it)
                } catch (_: IllegalArgumentException) {
                    // Already gone.
                }
            }
        }
        overlay = null
        osd = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- Overlay ----

    private fun ensureOverlay() {
        if (overlay != null) return
        if (!Settings.canDrawOverlays(this)) return
        try {
            val dim = View(this).apply { setBackgroundColor(Color.BLACK) }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            dim.alpha = if (Settings.isAmbient(this)) {
                1f
            } else {
                Settings.opacityFor(Settings.getBrightness(this))
            }
            windowManager?.addView(dim, params)
            overlay = dim
            applyTint()

            val text = TextView(this).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
                setTextColor(0xFFFFC857.toInt())
                setBackgroundColor(0xCC000000.toInt())
                gravity = Gravity.CENTER
                val pad = (28 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, pad / 2)
                alpha = 0f
            }
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
                y = (90 * resources.displayMetrics.density).toInt()
            }
            windowManager?.addView(text, osdParams)
            osd = text
        } catch (_: RuntimeException) {
            // Permission revoked between the check and addView, or a vendor
            // addView failure. Stay alive without an overlay: the next start
            // after the user grants it works, and nothing crashes.
            overlay = null
            osd = null
        }
    }

    /**
     * What the overlay is filled with. Day and Night paint black over the
     * picture; Sleep paints the lamp colour and lets the colour's own value do
     * the dimming, so the panel emits light instead of filtering it.
     */
    private fun applyTint() {
        fillColor = if (Settings.isAmbient(this)) {
            Settings.ambientLampColor(this)
        } else {
            Color.BLACK
        }
        overlay?.setBackgroundColor(fillColor)
    }

    private fun showOsd(message: String) {
        val view = osd ?: return
        view.text = message
        view.alpha = 1f
        handler.removeCallbacks(hideOsd)
        handler.postDelayed(hideOsd, 2000)
    }

    private fun showBrightnessOsd() {
        if (Settings.isBlackout(this)) return
        if (Settings.isAmbient(this)) {
            showOsd("Sleep light ${Settings.ambientColorName(this)} ${Settings.getAmbientLevel(this)}%")
        } else {
            showOsd("${Settings.moodName(Settings.getMood(this))} · ${Settings.getBrightness(this)}%")
        }
    }

    /** A restart must always come back to something visible. */
    private fun restoreSafeState() {
        if (Settings.isBlackout(this)) Settings.setBrightness(this, Settings.getPreBlackout(this))
        if (Settings.isAmbient(this)) {
            Settings.setAmbientActive(this, false)
            Settings.setBrightness(this, Settings.getPreAmbient(this))
        }
        Settings.setSleepTimerEnd(this, 0L)
    }

    // ---- Modes ----

    private fun applyMood(mood: Int) {
        Settings.setMood(this, mood)
        when (mood) {
            Settings.MOOD_SLEEP -> {
                if (Settings.isAmbient(this)) {
                    // Already lit: a changed glow or colour must show at once,
                    // and the timer restarts from now.
                    applyBrightness(Settings.getAmbientLevel(this), showOsd = false)
                    armSleepTimer()
                } else {
                    enterAmbient()
                }
            }
            Settings.MOOD_NIGHT -> {
                cancelSleepTimer()
                Settings.setAmbientActive(this, false)
                applyBrightness(Settings.getNightBrightness(this), showOsd = false)
                showOsd("Night · ${Settings.getNightBrightness(this)}%")
            }
            else -> {
                cancelSleepTimer()
                Settings.setAmbientActive(this, false)
                applyBrightness(Settings.getDayBrightness(this), showOsd = false)
                showOsd("Day · ${Settings.getDayBrightness(this)}%")
            }
        }
        startAsForeground()
    }

    private fun adjustBrightness(delta: Int) {
        if (Settings.isAmbient(this)) {
            val next = (Settings.getAmbientLevel(this) + delta).coerceIn(
                Settings.AMBIENT_MIN, Settings.AMBIENT_MAX
            )
            Settings.setAmbientLevel(this, next)
            applyBrightness(next)
            return
        }
        applyBrightness(Settings.getBrightness(this) + delta)
    }

    // ---- Brightness ----

    private fun applyBrightness(
        percent: Int,
        animate: Boolean = true,
        showOsd: Boolean = true
    ) {
        handler.removeCallbacks(generalFade)
        generalFadeStep = -1
        val clamped = percent.coerceIn(0, 100)
        val previous = Settings.getBrightness(this)
        val lamp = Settings.isAmbient(this)
        val oldAlpha = overlay?.alpha ?: Settings.opacityFor(previous)
        val oldFill = fillColor
        if (previous > 0 && clamped == 0 && !lamp) Settings.setPreBlackout(this, previous)
        Settings.setBrightness(this, clamped)
        cancelSleepFade()
        applyTint()
        updateBlackoutGuard(clamped)
        // The lamp is opaque: its level changes the colour, never the layer.
        val targetAlpha = if (lamp) 1f else Settings.opacityFor(clamped)
        val targetFill = fillColor
        if (animate && Settings.isFadeEnabled(this) && sleepFadeStep < 0 &&
            (oldAlpha != targetAlpha || oldFill != targetFill)
        ) {
            generalFadeFromAlpha = oldAlpha
            generalFadeTargetAlpha = targetAlpha
            generalFadeFromColor = oldFill
            generalFadeToColor = targetFill
            generalFadeStep = 0
            generalFadeShowOsd = showOsd
            overlay?.alpha = oldAlpha
            overlay?.setBackgroundColor(oldFill)
            handler.post(generalFade)
            return
        }
        overlay?.alpha = targetAlpha
        overlay?.setBackgroundColor(targetFill)
        if (showOsd) showBrightnessOsd()
    }

    /** Blend two fills, so an alpha fade and a colour fade use one animate. */
    private fun mixColor(from: Int, to: Int, fraction: Float): Int = Color.rgb(
        (Color.red(from) + (Color.red(to) - Color.red(from)) * fraction).toInt(),
        (Color.green(from) + (Color.green(to) - Color.green(from)) * fraction).toInt(),
        (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * fraction).toInt()
    )

    private fun onGeneralFadeStep() {
        generalFadeStep += 1
        val view = overlay
        if (view == null || generalFadeStep >= 6) {
            generalFadeStep = -1
            view?.alpha = generalFadeTargetAlpha
            view?.setBackgroundColor(generalFadeToColor)
            if (generalFadeShowOsd) showBrightnessOsd()
            return
        }
        val fraction = generalFadeStep.toFloat() / 6f
        view.alpha = generalFadeFromAlpha +
            (generalFadeTargetAlpha - generalFadeFromAlpha) * fraction
        view.setBackgroundColor(
            mixColor(generalFadeFromColor, generalFadeToColor, fraction)
        )
        handler.postDelayed(generalFade, 50)
    }

    private fun toggleBlackout() {
        if (Settings.isBlackout(this)) {
            applyBrightness(Settings.getPreBlackout(this))
            return
        }
        if (Settings.isAmbient(this)) {
            cancelSleepTimer()
            Settings.setAmbientActive(this, false)
            applyBrightness(Settings.getPreAmbient(this))
        }
        applyBrightness(0)
    }

    private fun exitBlackout() {
        if (Settings.isBlackout(this)) applyBrightness(Settings.getPreBlackout(this))
    }

    // ---- Sleep light ----

    private fun enterAmbient() {
        val current = Settings.getBrightness(this)
        Settings.setPreAmbient(this, if (current > 0) current else Settings.getPreBlackout(this))
        Settings.setAmbientActive(this, true)
        applyBrightness(Settings.getAmbientLevel(this), showOsd = false)
        showOsd("Sleep · ${Settings.ambientColorName(this)} ${Settings.getAmbientLevel(this)}%")
        armSleepTimer()
    }

    private fun exitAmbient() {
        if (!Settings.isAmbient(this)) return
        cancelSleepTimer()
        Settings.setAmbientActive(this, false)
        applyBrightness(Settings.getPreAmbient(this))
    }

    private fun armSleepTimer() {
        handler.removeCallbacks(sleepTick)
        val minutes = Settings.getSleepTimerMinutes(this)
        if (minutes <= 0) {
            Settings.setSleepTimerEnd(this, 0L)
            return
        }
        Settings.setSleepTimerEnd(this, System.currentTimeMillis() + minutes * 60_000L)
        scheduleSleepTick()
    }

    private fun cancelSleepTimer() {
        handler.removeCallbacks(sleepTick)
        cancelSleepFade()
        Settings.setSleepTimerEnd(this, 0L)
    }

    private fun scheduleSleepTick() {
        handler.removeCallbacks(sleepTick)
        val end = Settings.getSleepTimerEnd(this)
        if (end <= 0L) return
        val remaining = end - System.currentTimeMillis()
        if (remaining <= 0L) {
            startSleepFade()
            return
        }
        handler.postDelayed(sleepTick, remaining.coerceAtMost(SLEEP_TICK_MILLIS))
    }

    private fun onSleepTick() {
        if (!Settings.isAmbient(this)) {
            cancelSleepTimer()
            return
        }
        scheduleSleepTick()
        startAsForeground()
    }

    private fun startSleepFade() {
        handler.removeCallbacks(generalFade)
        generalFadeStep = -1
        handler.removeCallbacks(sleepTick)
        Settings.setSleepTimerEnd(this, 0L)
        val view = overlay
        if (view == null) {
            Settings.setAmbientActive(this, false)
            Settings.setBrightness(this, Settings.getPreAmbient(this))
            startAsForeground()
            return
        }
        // The lamp dims itself out: the fill loses its light, nothing else moves.
        view.alpha = 1f
        fadeFromColor = Settings.ambientLampColor(this)
        sleepFadeStep = 0
        handler.post(sleepFade)
    }

    private fun onSleepFadeStep() {
        val view = overlay
        sleepFadeStep += 1
        val fraction = sleepFadeStep.toFloat() / SLEEP_FADE_STEPS
        if (view == null || fraction >= 1f) {
            finishSleep()
            return
        }
        view.setBackgroundColor(
            Settings.scaleColor(fadeFromColor, ((1f - fraction) * 100f).toInt())
        )
        handler.postDelayed(sleepFade, SLEEP_FADE_STEP_MILLIS)
    }

    private fun cancelSleepFade() {
        if (sleepFadeStep < 0) return
        handler.removeCallbacks(sleepFade)
        sleepFadeStep = -1
        applyTint()
        overlay?.alpha = if (Settings.isAmbient(this)) {
            1f
        } else {
            Settings.opacityFor(Settings.getBrightness(this))
        }
    }

    /** The timer ran out: sleep light off, blackout, dim picture on wake. */
    private fun finishSleep() {
        handler.removeCallbacks(sleepFade)
        sleepFadeStep = -1
        val level = Settings.getAmbientLevel(this)
        Settings.setAmbientActive(this, false)
        Settings.setPreBlackout(this, level)
        applyBrightness(0, animate = false, showOsd = false)
        startAsForeground()
    }

    private fun sleepTimerRemainingMinutes(): Int {
        val end = Settings.getSleepTimerEnd(this)
        if (end <= 0L) return 0
        val remaining = end - System.currentTimeMillis()
        if (remaining <= 0L) return 0
        return ((remaining + 59_999L) / 60_000L).toInt()
    }

    // ---- Schedule + idle ----

    private fun checkSchedule(force: Boolean = false) {
        if (!Settings.isScheduleEnabled(this)) return
        if (Settings.isAmbient(this) || Settings.isBlackout(this)) return
        val now = Calendar.getInstance()
        val minutesNow = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val dimStart = Settings.getScheduleDimHour(this) * 60
        val restoreStart = Settings.getScheduleRestoreHour(this) * 60
        val inWindow = if (dimStart <= restoreStart) {
            minutesNow in dimStart until restoreStart
        } else {
            minutesNow >= dimStart || minutesNow < restoreStart
        }
        val state = Settings.prefs(this).getInt(Settings.KEY_SCHEDULE_STATE, 0)
        if (inWindow) {
            if (state != 1) {
                Settings.prefs(this).edit()
                    .putInt(Settings.KEY_SCHEDULE_PRE_BRIGHTNESS, Settings.getBrightness(this))
                    .putInt(Settings.KEY_SCHEDULE_STATE, 1)
                    .apply()
            }
            if (state != 1 || force) {
                applyBrightness(Settings.getNightBrightness(this), animate = true, showOsd = false)
            }
        } else if (state == 1) {
            val restoreTo = Settings.prefs(this)
                .getInt(Settings.KEY_SCHEDULE_PRE_BRIGHTNESS, 100)
                .coerceIn(0, 100)
            Settings.prefs(this).edit().putInt(Settings.KEY_SCHEDULE_STATE, 0).apply()
            applyBrightness(restoreTo, animate = true, showOsd = false)
        }
    }

    private fun resetIdle() {
        lastActivityMs = System.currentTimeMillis()
        Settings.prefs(this).edit()
            .putLong(Settings.KEY_LAST_ACTIVITY_MS, lastActivityMs).apply()
        scheduleIdle()
    }

    private fun scheduleIdle() {
        handler.removeCallbacks(idleRunnable)
        val idleMinutes = Settings.getIdleMinutes(this)
        if (idleMinutes > 0) handler.postDelayed(idleRunnable, idleMinutes * 60_000L)
    }

    // ---- Fail-safe + stop ----

    private fun updateBlackoutGuard(brightness: Int) {
        handler.removeCallbacks(blackoutGuard)
        if (brightness == 0 && !AccessibilityState.isEnabled(this)) {
            handler.postDelayed(blackoutGuard, BLACKOUT_GUARD_MILLIS)
        }
    }

    private fun guardBlackout() {
        if (!Settings.isBlackout(this)) return
        if (AccessibilityState.isEnabled(this)) return
        applyBrightness(Settings.getPreBlackout(this))
        startAsForeground()
    }

    private fun stopDimmer() {
        handler.removeCallbacks(blackoutGuard)
        handler.removeCallbacks(scheduleRunnable)
        handler.removeCallbacks(idleRunnable)
        handler.removeCallbacks(hideOsd)
        handler.removeCallbacks(generalFade)
        generalFadeStep = -1
        cancelSleepTimer()
        Settings.setAmbientActive(this, false)
        Settings.setBrightness(this, 100)
        Settings.setServiceEnabled(this, false)
        overlay?.alpha = 0f
        osd?.alpha = 0f
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- Notification ----

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "LeoDimmer", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps the LeoDimmer overlay running" }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun startAsForeground() {
        val text = when {
            Settings.isBlackout(this) ->
                if (AccessibilityState.isEnabled(this)) {
                    "Blackout — press any remote button to restore"
                } else {
                    "Blackout — restores itself in 1 min (keys are off)"
                }
            Settings.isAmbient(this) -> {
                val minutes = sleepTimerRemainingMinutes()
                val base = "Sleep light ${Settings.ambientColorName(this)} " +
                    "${Settings.getAmbientLevel(this)}%"
                if (minutes > 0) "$base — off in $minutes min" else base
            }
            else -> "${Settings.moodName(Settings.getMood(this))} · " +
                "${Settings.getBrightness(this)}%"
        }
        val open = PendingIntent.getActivity(
            this, REQUEST_OPEN_APP, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, REQUEST_TURN_OFF,
            Intent(this, OverlayService::class.java).putExtra("action", ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("LeoDimmer")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_dialog_info),
                    "Turn off", stop
                ).build()
            )
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
