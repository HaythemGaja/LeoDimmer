package com.universal.tvdimmer

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction

/**
 * Leanback settings screen + onboarding.
 *
 * The settings UI is a GuidedStep (the native Android TV settings pattern:
 * full-screen, D-pad navigable, 10-foot type). On first run all mappings are
 * unset and both permissions are shown as missing until granted.
 *
 * Onboarding: on first open the app asks for the permissions it needs,
 * one at a time, overlay first then accessibility (see
 * promptForMissingPermissions). Android does not allow a simple
 * Allow/Deny popup for either of these: both must be granted on a system
 * settings page, so the prompt opens that page. The settings rows remain
 * as a manual grant path. When the user returns, onResume notices the
 * state changed and rebuilds the screen so the rows flip to "Granted"
 * without relaunching.
 *
 * Note on the Mi Box: Xiaomi's overlay page is reached through the same
 * ACTION_MANAGE_OVERLAY_PERMISSION intent; there is no separate hidden page
 * to link to on stock Android TV, despite Xiaomi's extra app-info screens.
 */
class MainActivity : FragmentActivity() {

    companion object {
        private const val REQUEST_NOTIFICATIONS = 1
        private var crashLoggerInstalled = false
    }

    private var lastOverlayGranted: Boolean? = null
    private var lastAccessibilityEnabled: Boolean? = null

    /** Callback for the remote button being captured, if any (see startKeyCapture). */
    private var keyCapture: ((keyCode: Int) -> Unit)? = null
    private var keyDialog: KeyListeningDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashLogger()
        // A capture cannot survive an activity. If the app was killed while the
        // dialog was open, this flag stays set, and the accessibility service
        // would then pass every mapped button straight through for ever.
        DimmerPrefs.setCapturingKey(this, false)
        setContentView(R.layout.activity_main)
        lastOverlayGranted = Settings.canDrawOverlays(this)
        lastAccessibilityEnabled = AccessibilityState.isEnabled(this)
        if (lastOverlayGranted == true) {
            requestNotificationPermissionIfNeeded()
            startDimmerService(null, null)
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, DimmerSettingsFragment())
                .commit()
        }
        // Ask, don't just list rows: until v1.6 the app never prompted
        // for either permission on first open, so a new user could try
        // presets and key mapping with nothing granted and no idea why
        // nothing worked. Overlay first; on return with overlay granted
        // (which recreates this activity) the accessibility prompt follows.
        // Dismissing with Later does not re-prompt in the same session:
        // a new prompt only appears on a fresh activity creation.
        // Posted, not shown directly, so the dialog attaches to a window
        // that is already resumed (avoids a BadToken on some boxes).
        window?.decorView?.post { promptForMissingPermissions() }
    }

    private fun promptForMissingPermissions() {
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("LeoDimmer needs overlay permission")
                .setMessage(
                    "LeoDimmer dims the screen with an overlay above " +
                        "other apps. Without \"Display over other apps\" " +
                        "it cannot dim anything. Open settings and allow " +
                        "it for LeoDimmer."
                )
                .setPositiveButton("Open settings") { _, _ -> openOverlaySettings() }
                .setNegativeButton("Later", null)
                .setCancelable(true)
                .show()
            return
        }
        if (!AccessibilityState.isEnabled(this)) {
            AlertDialog.Builder(this)
                .setTitle("LeoDimmer needs the accessibility service")
                .setMessage(
                    "Global remote buttons only work through the " +
                        "accessibility service. Open Accessibility " +
                        "settings, find LeoDimmer, and turn it on. " +
                        "Without it, dimming from this settings screen " +
                        "still works, but remote buttons will not."
                )
                .setPositiveButton("Open settings") { _, _ -> openAccessibilitySettings() }
                .setNegativeButton("Later", null)
                .setCancelable(true)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        val overlayNow = Settings.canDrawOverlays(this)
        val accessibilityNow = AccessibilityState.isEnabled(this)
        if (lastOverlayGranted != null &&
            (overlayNow != lastOverlayGranted || accessibilityNow != lastAccessibilityEnabled)
        ) {
            lastOverlayGranted = overlayNow
            lastAccessibilityEnabled = accessibilityNow
            recreate()
        }
    }

    /**
     * The foreground-service notification is how the user learns which mode is
     * active; on Android 13+ it is hidden entirely until this is granted, so
     * ask for it once the overlay permission is in place (i.e. once the user
     * has actually committed to running the dimmer).
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    /**
     * Capture the next remote button in the activity itself.
     *
     * v1.6 captured inside a focusable Dialog. That works on an emulator but
     * not reliably on the Mi Box: when the dialog's window never takes focus
     * (the GuidedStep underneath can keep it on vendor TV builds) the presses
     * go to the activity instead, so no button was ever registered -- the
     * reported "does not register the button when pressed". The dialog is now
     * purely visual and not focusable, and the activity captures in
     * dispatchKeyEvent, so there is exactly one place a key can arrive.
     */
    fun startKeyCapture(actionLabel: String, prefsKey: String) {
        endKeyCapture()
        DimmerPrefs.setCapturingKey(this, true)
        keyCapture = { keyCode ->
            DimmerPrefs.setKey(this, prefsKey, keyCode)
            // Rebuild the GuidedStep so the new keycode shows in the row.
            recreate()
        }
        keyDialog = KeyListeningDialog(this, actionLabel).also { dialog ->
            dialog.setOnDismissListener { endKeyCapture() }
            dialog.show()
        }
    }

    fun endKeyCapture() {
        keyCapture = null
        DimmerPrefs.setCapturingKey(this, false)
        // Cleared before dismiss(): the dismiss listener calls back in here.
        val dialog = keyDialog
        keyDialog = null
        dialog?.dismiss()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val capture = keyCapture ?: return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_UP) return true
        if (event.action != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event)
        }
        val keyCode = event.keyCode
        endKeyCapture()
        // BACK cancels and is therefore never itself mappable.
        if (keyCode != KeyEvent.KEYCODE_BACK) capture(keyCode)
        return true
    }

    override fun onDestroy() {
        // Do not dismiss here: the window is already going away.
        keyCapture = null
        keyDialog = null
        DimmerPrefs.setCapturingKey(this, false)
        super.onDestroy()
    }

    fun isAccessibilityEnabled(): Boolean = AccessibilityState.isEnabled(this)

    /**
     * No ADB on the Mi Box, so a crash log the user can read is the only
     * evidence. Write any uncaught exception to crash.log (private app
     * storage, never sent anywhere), then let the normal crash proceed
     * so the user still sees "keeps stopping". The settings screen shows
     * the first line of that file on the next open.
     * The logger itself must never crash the app: every step is guarded.
     */
    private fun installCrashLogger() {
        try {
            if (crashLoggerInstalled) return
            crashLoggerInstalled = true
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    val writer = java.io.StringWriter()
                    throwable.printStackTrace(java.io.PrintWriter(writer))
                    openFileOutput("crash.log", MODE_PRIVATE).use {
                        it.write(writer.toString().toByteArray(Charsets.UTF_8))
                    }
                } catch (_: Exception) {
                    // Logging must not mask the original crash.
                }
                previous?.uncaughtException(thread, throwable)
            }
        } catch (_: Exception) {
            // No logger is better than a logger that crashes startup.
        }
    }

    fun lastCrashLine(): String? = try {
        val text = openFileInput("crash.log").bufferedReader().readText()
        text.lineSequence().firstOrNull { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    fun startDimmerService(action: String?, value: Int?) {
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

    /**
     * The Mi Box's Android 9 build does not always resolve the per-app
     * overlay page, which is why the overlay grant was impossible to find:
     * "Open settings" did nothing. Fall back down the chain instead of
     * leaving the user with no page at all -- the global overlay page, then
     * this app's own info page, where Xiaomi hides "Display over other apps"
     * under Special access / Permissions.
     */
    fun openOverlaySettings() {
        val candidates = listOf(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
        )
        candidates.any { startIfResolvable(it) }
    }

    private fun startIfResolvable(intent: Intent): Boolean {
        if (intent.resolveActivity(packageManager) == null) return false
        return try {
            startActivity(intent)
            true
        } catch (_: Exception) {
            // A vendor build can resolve but still refuse it; try the next one.
            false
        }
    }

    fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}

/**
 * The Leanback GuidedStep itself. Descriptions are rebuilt from prefs on
 * every creation, so after a remap the activity simply recreates and the
 * new keycode shows up here. That is deliberately simple: no live adapter
 * to keep in sync on a screen the user visits rarely.
 */
class DimmerSettingsFragment : GuidedStepSupportFragment() {

    companion object {
        private const val ID_BRIGHTER_KEY = 1L
        private const val ID_DIMMER_KEY = 2L
        private const val ID_TOGGLE_KEY = 3L
        private const val ID_AMBIENT_KEY = 4L
        private const val ID_TEST_BRIGHTER = 5L
        private const val ID_TEST_DIMMER = 6L
        private const val ID_TEST_TOGGLE = 7L
        private const val ID_TEST_AMBIENT = 8L
        private const val ID_AMBIENT_LEVEL = 9L
        private const val ID_PERM_OVERLAY = 10L
        private const val ID_PERM_ACCESSIBILITY = 11L
        private const val ID_TV_POWER_ON = 12L
        private const val ID_TV_POWER_OFF = 13L
        private const val ID_STOP = 14L
        private const val ID_RESET_KEYS = 15L
        private const val ID_SLEEP_TIMER = 16L
        // v1.4 merged rows (presets, sleep-light colour, schedule, idle, fade).
        private const val ID_PRESET_75 = 17L
        private const val ID_PRESET_50 = 18L
        private const val ID_PRESET_25 = 19L
        private const val ID_PRESET_BLACKOUT = 20L
        private const val ID_AMBIENT_COLOR = 21L
        private const val ID_SCHEDULE_TOGGLE = 22L
        private const val ID_SCHEDULE_DIM_HOUR = 23L
        private const val ID_SCHEDULE_RESTORE_HOUR = 24L
        private const val ID_SCHEDULE_DIM_PERCENT = 25L
        private const val ID_IDLE_TIMER = 26L
        private const val ID_FADE_TOGGLE = 27L
        // v1.9 moods: three states on one key (Day / Night / Sleep).
        private const val ID_MOOD_CYCLE = 28L
        private const val ID_DAY_BRIGHTNESS = 29L
        private const val ID_NIGHT_BRIGHTNESS = 30L
        private const val ID_MODE_KEY = 31L

        /** Sleep-light levels the settings row steps through. */
        private val AMBIENT_LEVELS = intArrayOf(10, 20, 30, 40, 50, 60)
    }

    private val host: MainActivity?
        get() = activity as? MainActivity

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        val ctx = requireContext()
        val brightness = DimmerPrefs.getBrightness(ctx)
        val overlay = if (Settings.canDrawOverlays(ctx)) "granted" else "MISSING"
        val accessibility =
            if (AccessibilityState.isEnabled(ctx)) "enabled" else "MISSING"
        val state = when {
            DimmerPrefs.isBlackout(ctx) -> "blackout (audio-only)"
            DimmerPrefs.isAmbient(ctx) -> "sleep light " +
                "${DimmerPrefs.ambientColorName(ctx)} ${DimmerPrefs.getAmbientLevel(ctx)}%"
            else -> "normal"
        }
        val schedule = if (DimmerPrefs.isScheduleEnabled(ctx)) {
            "Schedule: dim to ${DimmerPrefs.getScheduleDimPercent(ctx)}% at " +
                "${DimmerPrefs.formatHour(DimmerPrefs.getScheduleDimHour(ctx))}, " +
                "restore at ${DimmerPrefs.formatHour(DimmerPrefs.getScheduleRestoreHour(ctx))}\n"
        } else {
            "Schedule: off\n"
        }
        // Each line is built separately: concatenating an if-expression with +
        // used to bind the whole tail to one branch and drop the status lines.
        val crash = host?.lastCrashLine()
        val description = buildString {
            append(
                "Mode: ${DimmerPrefs.moodName(DimmerPrefs.getMood(ctx))} — " +
                    "brightness $brightness% — $state\n"
            )
            append(schedule)
            append("Idle blackout: ${DimmerPrefs.timerLabel(DimmerPrefs.getIdleMinutes(ctx))}\n")
            append("Smooth fade: ${if (DimmerPrefs.isFadeEnabled(ctx)) "on" else "off"}\n")
            append("Overlay permission: $overlay\n")
            append("Accessibility service: $accessibility\n")
            if (crash != null) {
                append("Last crash on this box: $crash\n")
            }
            append(
                "One remote button is enough: map the Mode key and press it to " +
                    "cycle Day → Night → Sleep. The other keys are optional."
            )
        }
        return GuidanceStylist.Guidance(
            "LeoDimmer",
            description,
            "Settings",
            null
        )
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        // A sleep light or blackout entered without the accessibility
        // service has no remote-button restore path (blackout auto-restores
        // in 1 min; the sleep light does not). Warn, don't block: the user
        // can still turn it off from this screen or the notification.
        val accessibilityMissing = !AccessibilityState.isEnabled(ctx)
        val restoreWarning = if (accessibilityMissing) {
            " — Accessibility is MISSING, so no remote button can turn it off; use this screen or the notification's Turn off action"
        } else {
            ""
        }
        fun action(id: Long, title: String, description: String) =
            GuidedAction.Builder(ctx).id(id).title(title).description(description).build()

        fun toggle(id: Long, title: String, description: String, checked: Boolean) =
            GuidedAction.Builder(ctx)
                .id(id)
                .title(title)
                .description(description)
                .checkSetId(GuidedAction.DEFAULT_CHECK_SET_ID)
                .checked(checked)
                .build()

        val mood = DimmerPrefs.getMood(ctx)
        val nextMood = DimmerPrefs.nextMood(mood)
        actions += action(
            ID_MOOD_CYCLE, "Mode: ${DimmerPrefs.moodName(mood)}",
            "Select to switch to ${DimmerPrefs.moodName(nextMood)}"
        )
        actions += action(
            ID_DAY_BRIGHTNESS, "Day mode brightness",
            "${DimmerPrefs.getDayBrightness(ctx)}% — select to step 100 / 90 / 80 / 70%"
        )
        actions += action(
            ID_NIGHT_BRIGHTNESS, "Night mode brightness",
            "${DimmerPrefs.getNightBrightness(ctx)}% — " +
                "select to step 10 / 15 / 20 / 25 / 30 / 40 / 50%"
        )
        // One button for everything: this is the only mapping a short remote
        // needs. Mapped first so it is the top row of the key section.
        actions += action(
            ID_MODE_KEY, "Mode key (Day / Night / Sleep)",
            DimmerPrefs.keyLabel(ctx, DimmerPrefs.KEY_MODE_KEY)
        )
        actions += action(
            ID_BRIGHTER_KEY, "Optional: increase brightness key",
            DimmerPrefs.keyLabel(ctx, DimmerPrefs.KEY_BRIGHTER)
        )
        actions += action(
            ID_DIMMER_KEY, "Optional: decrease brightness key",
            DimmerPrefs.keyLabel(ctx, DimmerPrefs.KEY_DIMMER)
        )
        actions += action(
            ID_TOGGLE_KEY, "Optional: toggle blackout key",
            DimmerPrefs.keyLabel(ctx, DimmerPrefs.KEY_TOGGLE)
        )
        actions += action(
            ID_AMBIENT_KEY, "Optional: sleep light key",
            DimmerPrefs.keyLabel(ctx, DimmerPrefs.KEY_AMBIENT)
        )
        actions += action(ID_TEST_BRIGHTER, "Test: brighter", "Step brightness up by 10% now")
        actions += action(ID_TEST_DIMMER, "Test: dimmer", "Step brightness down by 10% now")
        actions += action(ID_TEST_TOGGLE, "Test: toggle blackout", "Switch blackout on / off now")
        actions += action(
            ID_TEST_AMBIENT, "Test: sleep light",
            "Dim light for falling asleep — toggle on / off now$restoreWarning"
        )
        actions += action(
            ID_AMBIENT_LEVEL, "Sleep light level",
            "${DimmerPrefs.getAmbientLevel(ctx)}% — select to step 10 / 20 / … / 60%"
        )
        actions += action(
            ID_SLEEP_TIMER, "Sleep timer",
            sleepTimerLabel(DimmerPrefs.getSleepTimerMinutes(ctx))
        )
        actions += action(ID_PRESET_75, "Preset: 75% brightness", "Jump straight to 75%")
        actions += action(ID_PRESET_50, "Preset: 50% brightness", "Jump straight to 50%")
        actions += action(ID_PRESET_25, "Preset: 25% brightness", "Jump straight to 25%")
        actions += action(
            ID_PRESET_BLACKOUT, "Preset: blackout",
            "Jump straight to blackout$restoreWarning"
        )
        actions += action(
            ID_AMBIENT_COLOR, "Sleep light colour",
            "${DimmerPrefs.ambientColorName(ctx)} — select for next colour"
        )
        actions += action(
            ID_SCHEDULE_TOGGLE, "Night schedule",
            if (DimmerPrefs.isScheduleEnabled(ctx)) "On — select to turn off" else "Off — select to turn on"
        )
        actions += action(
            ID_SCHEDULE_DIM_HOUR, "Schedule: dim at",
            "${DimmerPrefs.formatHour(DimmerPrefs.getScheduleDimHour(ctx))} — select for next hour"
        )
        actions += action(
            ID_SCHEDULE_RESTORE_HOUR, "Schedule: restore at",
            "${DimmerPrefs.formatHour(DimmerPrefs.getScheduleRestoreHour(ctx))} — select for next hour"
        )
        actions += action(
            ID_SCHEDULE_DIM_PERCENT, "Schedule: dim to",
            "${DimmerPrefs.getScheduleDimPercent(ctx)}% — select for next level"
        )
        actions += action(
            ID_IDLE_TIMER, "Idle blackout timer",
            "${DimmerPrefs.timerLabel(DimmerPrefs.getIdleMinutes(ctx))} — " +
                "select for next value (any key resets it)"
        )
        actions += action(
            ID_FADE_TOGGLE, "Smooth fade",
            if (DimmerPrefs.isFadeEnabled(ctx)) "On — select to turn off" else "Off — select to turn on"
        )
        actions += action(
            ID_PERM_OVERLAY, "Overlay permission",
            if (Settings.canDrawOverlays(ctx)) {
                "Granted — nothing dims without it"
            } else {
                "Missing — select to open it: Settings → Apps → Special access → " +
                    "Display over other apps → LeoDimmer → Allow"
            }
        )
        actions += action(
            ID_PERM_ACCESSIBILITY, "Accessibility service",
            if (AccessibilityState.isEnabled(ctx)) "Enabled" else "Missing — select to open system settings"
        )
        actions += toggle(
            ID_TV_POWER_ON, "TV power on with box",
            "Ask the TV to wake when the box boots or wakes from standby (HDMI-CEC, with a fallback)",
            DimmerPrefs.isTvPowerOnEnabled(ctx)
        )
        actions += toggle(
            ID_TV_POWER_OFF, "TV power off with box",
            "Attempt HDMI-CEC standby when the box shuts down; most boxes block this — the TV's own CEC auto-off is the reliable route",
            DimmerPrefs.isTvPowerOffEnabled(ctx)
        )
        actions += action(
            ID_STOP, "Turn dimmer off",
            "Remove the overlay now and stop restarting it at boot"
        )
        actions += action(ID_RESET_KEYS, "Reset all key mappings", "Clear the four mapped buttons")
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        val ctx = requireContext()
        val activity = host ?: return
        val prefs = DimmerPrefs.prefs(ctx)
        fun cycleInt(key: String, values: List<Int>, current: Int) {
            val next = values[(values.indexOf(current) + 1).coerceAtLeast(0) % values.size]
            prefs.edit().putInt(key, next).apply()
            activity.startDimmerService(null, null)
            activity.recreate()
        }
        when (action.id) {
            ID_BRIGHTER_KEY -> showKeyDialog("Increase Brightness", DimmerPrefs.KEY_BRIGHTER)
            ID_DIMMER_KEY -> showKeyDialog("Decrease Brightness", DimmerPrefs.KEY_DIMMER)
            ID_TOGGLE_KEY -> showKeyDialog("Toggle Blackout", DimmerPrefs.KEY_TOGGLE)
            ID_AMBIENT_KEY -> showKeyDialog("Sleep Light", DimmerPrefs.KEY_AMBIENT)
            ID_TEST_BRIGHTER -> {
                activity.startDimmerService(DimmerService.ACTION_BRIGHTER, null)
                activity.recreate()
            }
            ID_TEST_DIMMER -> {
                activity.startDimmerService(DimmerService.ACTION_DIMMER, null)
                activity.recreate()
            }
            ID_TEST_TOGGLE -> {
                activity.startDimmerService(DimmerService.ACTION_TOGGLE_BLACKOUT, null)
                activity.recreate()
            }
            ID_TEST_AMBIENT -> {
                activity.startDimmerService(DimmerService.ACTION_TOGGLE_AMBIENT, null)
                activity.recreate()
            }
            ID_AMBIENT_LEVEL -> {
                val next = nextAmbientLevel(DimmerPrefs.getAmbientLevel(ctx))
                activity.startDimmerService(DimmerService.ACTION_SET_AMBIENT_LEVEL, next)
                activity.recreate()
            }
            ID_SLEEP_TIMER -> {
                val next = nextSleepTimer(DimmerPrefs.getSleepTimerMinutes(ctx))
                activity.startDimmerService(DimmerService.ACTION_SET_SLEEP_TIMER, next)
                activity.recreate()
            }
            ID_PRESET_75 -> {
                activity.startDimmerService(DimmerService.ACTION_SET_PERCENT, 75)
                activity.recreate()
            }
            ID_PRESET_50 -> {
                activity.startDimmerService(DimmerService.ACTION_SET_PERCENT, 50)
                activity.recreate()
            }
            ID_PRESET_25 -> {
                activity.startDimmerService(DimmerService.ACTION_SET_PERCENT, 25)
                activity.recreate()
            }
            ID_PRESET_BLACKOUT -> {
                activity.startDimmerService(DimmerService.ACTION_SET_PERCENT, 0)
                activity.recreate()
            }
            ID_AMBIENT_COLOR -> {
                val next = (DimmerPrefs.getAmbientColorIndex(ctx) + 1) %
                    DimmerPrefs.AMBIENT_COLORS.size
                prefs.edit().putInt(DimmerPrefs.KEY_AMBIENT_COLOR_INDEX, next).apply()
                // Refresh the live tint immediately if the light is on.
                if (DimmerPrefs.isAmbient(ctx)) {
                    activity.startDimmerService(
                        DimmerService.ACTION_SET_AMBIENT_LEVEL,
                        DimmerPrefs.getAmbientLevel(ctx)
                    )
                }
                activity.recreate()
            }
            ID_SCHEDULE_TOGGLE -> {
                val turningOn = !DimmerPrefs.isScheduleEnabled(ctx)
                val state = prefs.getInt(DimmerPrefs.KEY_SCHEDULE_STATE, 0)
                val edit = prefs.edit().putBoolean(
                    DimmerPrefs.KEY_SCHEDULE_ENABLED, turningOn
                )
                if (!turningOn && state == 1 &&
                    !DimmerPrefs.isAmbient(ctx) && !DimmerPrefs.isBlackout(ctx)
                ) {
                    // Turning the schedule off mid-window must restore
                    // the picture now, not leave the dim in place.
                    val restoreTo = prefs.getInt(
                        DimmerPrefs.KEY_SCHEDULE_PRE_BRIGHTNESS, 100
                    ).coerceIn(0, 100)
                    edit.putInt(DimmerPrefs.KEY_SCHEDULE_STATE, 0).apply()
                    activity.startDimmerService(DimmerService.ACTION_SET_PERCENT, restoreTo)
                } else {
                    if (!turningOn) edit.putInt(DimmerPrefs.KEY_SCHEDULE_STATE, 0)
                    edit.apply()
                    activity.startDimmerService(null, null)
                }
                activity.recreate()
            }
            ID_SCHEDULE_DIM_HOUR -> cycleInt(
                DimmerPrefs.KEY_SCHEDULE_DIM_HOUR,
                DimmerPrefs.SCHEDULE_DIM_HOURS,
                DimmerPrefs.getScheduleDimHour(ctx)
            )
            ID_SCHEDULE_RESTORE_HOUR -> cycleInt(
                DimmerPrefs.KEY_SCHEDULE_RESTORE_HOUR,
                DimmerPrefs.SCHEDULE_RESTORE_HOURS,
                DimmerPrefs.getScheduleRestoreHour(ctx)
            )
            ID_SCHEDULE_DIM_PERCENT -> cycleInt(
                DimmerPrefs.KEY_SCHEDULE_DIM_PERCENT,
                DimmerPrefs.SCHEDULE_DIM_PERCENTS,
                DimmerPrefs.getScheduleDimPercent(ctx)
            )
            ID_IDLE_TIMER -> cycleInt(
                DimmerPrefs.KEY_IDLE_MINUTES,
                DimmerPrefs.IDLE_MINUTES_OPTIONS,
                DimmerPrefs.getIdleMinutes(ctx)
            )
            ID_FADE_TOGGLE -> {
                prefs.edit().putBoolean(
                    DimmerPrefs.KEY_FADE_ENABLED,
                    !DimmerPrefs.isFadeEnabled(ctx)
                ).apply()
                activity.recreate()
            }
            ID_MOOD_CYCLE -> {
                val next = DimmerPrefs.nextMood(DimmerPrefs.getMood(ctx))
                activity.startDimmerService(DimmerService.ACTION_SET_MOOD, next)
                activity.recreate()
            }
            ID_DAY_BRIGHTNESS -> {
                val next = nextIn(
                    DimmerPrefs.DAY_BRIGHTNESS_OPTIONS,
                    DimmerPrefs.getDayBrightness(ctx)
                )
                prefs.edit().putInt(DimmerPrefs.KEY_DAY_BRIGHTNESS, next).apply()
                // Only re-apply if Day is the mode in use; otherwise the new
                // level simply waits for the next Day press.
                if (DimmerPrefs.getMood(ctx) == DimmerPrefs.MOOD_DAY) {
                    activity.startDimmerService(DimmerService.ACTION_SET_MOOD, DimmerPrefs.MOOD_DAY)
                }
                activity.recreate()
            }
            ID_NIGHT_BRIGHTNESS -> {
                val next = nextIn(
                    DimmerPrefs.NIGHT_BRIGHTNESS_OPTIONS,
                    DimmerPrefs.getNightBrightness(ctx)
                )
                prefs.edit().putInt(DimmerPrefs.KEY_NIGHT_BRIGHTNESS, next).apply()
                if (DimmerPrefs.getMood(ctx) == DimmerPrefs.MOOD_NIGHT) {
                    activity.startDimmerService(DimmerService.ACTION_SET_MOOD, DimmerPrefs.MOOD_NIGHT)
                }
                activity.recreate()
            }
            ID_MODE_KEY -> showKeyDialog("Cycle Day / Night / Sleep", DimmerPrefs.KEY_MODE_KEY)
            ID_PERM_OVERLAY -> activity.openOverlaySettings()
            ID_PERM_ACCESSIBILITY -> activity.openAccessibilitySettings()
            ID_TV_POWER_ON -> {
                DimmerPrefs.setTvPowerOnEnabled(ctx, !DimmerPrefs.isTvPowerOnEnabled(ctx))
                activity.recreate()
            }
            ID_TV_POWER_OFF -> {
                DimmerPrefs.setTvPowerOffEnabled(ctx, !DimmerPrefs.isTvPowerOffEnabled(ctx))
                activity.recreate()
            }
            ID_STOP -> {
                activity.startDimmerService(DimmerService.ACTION_STOP, null)
                activity.recreate()
            }
            ID_RESET_KEYS -> {
                DimmerPrefs.clearKeys(ctx)
                activity.recreate()
            }
            else -> Unit
        }
    }

    /** Step through a fixed option list, wrapping back to the first entry. */
    private fun nextIn(values: List<Int>, current: Int): Int =
        values[(values.indexOf(current) + 1) % values.size]

    private fun nextAmbientLevel(current: Int): Int =
        AMBIENT_LEVELS.firstOrNull { it > current } ?: AMBIENT_LEVELS.first()

    private fun nextSleepTimer(current: Int): Int {
        val options = DimmerPrefs.SLEEP_TIMER_OPTIONS
        return options.firstOrNull { it > current } ?: options.first()
    }

    private fun sleepTimerLabel(minutes: Int): String =
        if (minutes <= 0) {
            "Off — the sleep light stays on until you turn it off"
        } else {
            "$minutes min — the sleep light fades to black after $minutes min, " +
                "then any key restores a dim picture"
        }

    private fun showKeyDialog(actionLabel: String, prefsKey: String) {
        // The activity owns the capture: it is the window that keeps focus.
        host?.startKeyCapture(actionLabel, prefsKey)
    }
}
