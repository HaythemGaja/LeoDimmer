package com.universal.tvdimmer

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * LeoDimmer v2.0 -- one screen, built for a D-pad.
 *
 * No fragments, no dialogs, no Leanback. Every previous failure came from that
 * stack: the GuidedStep theme crash, a dialog that never took focus so button
 * presses were never seen, and a long list of rows where one wrong tap looked
 * like a crash. Here the screen is plain views:
 *
 *   ▲ ▼  move between rows        ◀ ▶  change the value in place
 *   OK   switch a mode / learn a key
 *
 * Nothing lives behind a sub-screen, so there is nowhere for a press to get
 * lost and nothing to dismiss.
 *
 * The CHECK block at the top is deliberate: if the overlay permission or the
 * remote-keys service is missing, the row says so in red and OK takes you to
 * the right system page. A dimmer that silently does nothing is worse than one
 * that says why.
 */
class MainActivity : Activity() {

    private companion object {
        private val TEXT = 0xFFEDEDED.toInt()
        private val MUTED = 0xFF9A9A9A.toInt()
        private val ACCENT = 0xFFFFC857.toInt()
        private val GOOD = 0xFF7BD88F.toInt()
        private val BAD = 0xFFFF6B6B.toInt()
        private val BG = 0xFF0B0B0D.toInt()
        private const val REQUEST_NOTIFICATIONS = 1
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var column: LinearLayout
    private var learnPoll: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        window.setBackgroundDrawable(ColorDrawable(BG))

        // The dimmer lives in a foreground service. On 13+ the notification
        // needs a runtime grant; the service still runs without it.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATIONS
            )
        }

        val scroll = ScrollView(this).apply { isFillViewport = true }
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(56), dp(36), dp(56), dp(56))
        }
        scroll.addView(
            column,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        // Opening the app starts the dimmer, and re-syncs it with the settings.
        OverlayService.send(this, null)
        render()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        learnPoll = null
        super.onDestroy()
    }

    // ---- Screen ----

    private fun render() {
        val focused = currentFocus?.tag as? String
        column.removeAllViews()
        addHeader()
        addStatus()
        addModes()
        addTuning()
        addAutomation()
        addRemote()
        addFooter()
        val restore = focused?.let { column.findViewWithTag<View>(it) }
            ?: column.findViewWithTag<View>("mode_${Settings.getMood(this)}")
        column.post { restore?.requestFocus() }
    }

    private fun addHeader() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "LeoDimmer"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
            setTextColor(TEXT)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        val pill = TextView(this).apply {
            text = Settings.moodName(Settings.getMood(this@MainActivity)).uppercase()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(BG)
            setBackgroundColor(ACCENT)
            setPadding(dp(18), dp(7), dp(18), dp(7))
        }
        row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(pill)
        column.addView(row)
        column.addView(info("One remote button: Day → Night → Sleep.", MUTED, 17f))
        CrashLog.firstLine(this)?.let { column.addView(info("Last crash: $it", BAD, 15f)) }
        column.addView(spacer())
    }

    private fun addStatus() {
        section("CHECK")
        val overlayOk = Settings.canDrawOverlays(this)
        val keysOn = AccessibilityState.isEnabled(this)
        val keysReady = Settings.keysReadyAt(this) > 0L

        addRow(
            Row("status_overlay", "Screen dimming", {
                if (overlayOk) "Ready" else "Overlay permission missing — press OK"
            }, { if (overlayOk) GOOD else BAD }),
            { openOverlaySettings() }
        )

        addRow(
            Row("status_keys", "Remote keys", {
                when {
                    !keysOn -> "Off — press OK to turn on"
                    !keysReady -> "On — waiting for the box…"
                    else -> "On"
                }
            }, { if (keysOn) GOOD else BAD }),
            { openAccessibilitySettings() }
        )
        column.addView(spacer())
    }

    private fun addModes() {
        section("MODE   ·   OK SWITCHES")
        val current = Settings.getMood(this)
        for (mood in Settings.MOODS) {
            val selected = mood == current
            val row = Row(
                "mode_$mood",
                "${if (selected) "●" else "○"}  ${Settings.moodName(mood)}",
                {
                    when (mood) {
                        Settings.MOOD_DAY -> "brightness ${Settings.getDayBrightness(this)}%"
                        Settings.MOOD_NIGHT -> "brightness ${Settings.getNightBrightness(this)}%"
                        else -> "glow ${Settings.getAmbientLevel(this)}% · " +
                            "${Settings.ambientColorName(this)} · " +
                            Settings.timerLabel(Settings.getSleepTimerMinutes(this))
                    }
                },
                { if (selected) ACCENT else MUTED },
                selected
            )
            row.onOk = {
                OverlayService.send(this, OverlayService.ACTION_SET_MOOD, mood)
                render()
            }
            addRow(row)
        }
        column.addView(spacer())
    }

    private fun addTuning() {
        section("TUNE   ·   ◀ ▶ CHANGES")
        choiceRow(
            "tune_day", "Day brightness", Settings.DAY_BRIGHTNESS_OPTIONS,
            { Settings.getDayBrightness(this) },
            {
                Settings.setDayBrightness(this, it)
                if (Settings.getMood(this) == Settings.MOOD_DAY) applyCurrentMood()
            },
            { "$it%" }
        )
        choiceRow(
            "tune_night", "Night brightness", Settings.NIGHT_BRIGHTNESS_OPTIONS,
            { Settings.getNightBrightness(this) },
            {
                Settings.setNightBrightness(this, it)
                if (Settings.getMood(this) == Settings.MOOD_NIGHT) applyCurrentMood()
            },
            { "$it%" }
        )
        choiceRow(
            "tune_glow", "Sleep glow", (Settings.AMBIENT_MIN..Settings.AMBIENT_MAX)
                .step(Settings.AMBIENT_STEP).toList(),
            { Settings.getAmbientLevel(this) },
            {
                Settings.setAmbientLevel(this, it)
                if (Settings.isAmbient(this)) applyCurrentMood()
            },
            { "$it%" }
        )
        choiceRow(
            "tune_colour", "Sleep colour", Settings.AMBIENT_COLORS.indices.toList(),
            { Settings.getAmbientColorIndex(this) },
            {
                Settings.setAmbientColorIndex(this, it)
                if (Settings.isAmbient(this)) applyCurrentMood()
            },
            { Settings.AMBIENT_COLORS[it].first }
        )
        choiceRow(
            "tune_timer", "Sleep timer", Settings.SLEEP_TIMER_OPTIONS,
            { Settings.getSleepTimerMinutes(this) },
            {
                Settings.setSleepTimerMinutes(this, it)
                OverlayService.send(this, OverlayService.ACTION_SET_SLEEP_TIMER, it)
            },
            { Settings.timerLabel(it) }
        )
        choiceRow(
            "tune_fade", "Soft transitions", listOf(0, 1),
            { if (Settings.isFadeEnabled(this)) 1 else 0 },
            { Settings.setFadeEnabled(this, it == 1) },
            { if (it == 1) "On" else "Off" }
        )
        column.addView(spacer())
    }

    private fun addAutomation() {
        section("AUTOMATION")
        choiceRow(
            "auto_schedule", "Night schedule", listOf(0, 1),
            { if (Settings.isScheduleEnabled(this)) 1 else 0 },
            {
                Settings.setScheduleEnabled(this, it == 1)
                OverlayService.send(this, null)
            },
            { if (it == 1) "On" else "Off" }
        )
        choiceRow(
            "auto_dim", "Dim at", Settings.SCHEDULE_DIM_HOURS,
            { Settings.getScheduleDimHour(this) },
            { Settings.setScheduleDimHour(this, it); OverlayService.send(this, null) },
            { Settings.formatHour(it) }
        )
        choiceRow(
            "auto_restore", "Restore at", Settings.SCHEDULE_RESTORE_HOURS,
            { Settings.getScheduleRestoreHour(this) },
            { Settings.setScheduleRestoreHour(this, it); OverlayService.send(this, null) },
            { Settings.formatHour(it) }
        )
        choiceRow(
            "auto_idle", "Auto-dim when idle", Settings.IDLE_MINUTES_OPTIONS,
            { Settings.getIdleMinutes(this) },
            { Settings.setIdleMinutes(this, it); OverlayService.send(this, null) },
            { Settings.timerLabel(it) }
        )
        choiceRow(
            "auto_tv_on", "Wake TV with box", listOf(0, 1),
            { if (Settings.isTvPowerOnEnabled(this)) 1 else 0 },
            { Settings.setTvPowerOnEnabled(this, it == 1) },
            { if (it == 1) "On" else "Off" }
        )
        choiceRow(
            "auto_tv_off", "Sleep TV with box", listOf(0, 1),
            { if (Settings.isTvPowerOffEnabled(this)) 1 else 0 },
            { Settings.setTvPowerOffEnabled(this, it == 1) },
            { if (it == 1) "On" else "Off" }
        )
        column.addView(spacer())
    }

    private fun addRemote() {
        section("REMOTE   ·   ONE KEY IS ENOUGH")
        val learning = Settings.learnTarget(this)
        val modeRow = Row(
            "key_mode", "Mode key",
            {
                if (learning == Settings.KEY_MODE_KEY) "Press a remote button…"
                else Settings.keyLabel(this, Settings.KEY_MODE_KEY)
            },
            { if (learning.isNotEmpty()) ACCENT else MUTED }
        )
        modeRow.onOk = { startLearn(Settings.KEY_MODE_KEY) }
        addRow(modeRow)

        // 1.x mappings are kept, but only shown if this box already has them:
        // the whole point of 2.0 is that nobody needs a second button.
        val legacy = listOf(
            Settings.KEY_BRIGHTER to "Optional · brighter",
            Settings.KEY_DIMMER to "Optional · dimmer",
            Settings.KEY_TOGGLE to "Optional · blackout",
            Settings.KEY_AMBIENT to "Optional · sleep light"
        )
        for ((key, label) in legacy) {
            if (Settings.getKey(this, key) == Settings.UNSET) continue
            val row = Row("key_$key", label, { Settings.keyLabel(this, key) })
            row.onOk = {
                Settings.setKey(this, key, Settings.UNSET)
                render()
            }
            addRow(row)
        }

        if (Settings.anyKeyMapped(this)) {
            addRow(
                Row("keys_clear", "Forget all keys", { "Press OK" }),
                {
                    Settings.clearKeys(this)
                    render()
                    toast("Keys cleared")
                }
            )
        }

        addRow(
            Row("stop", "Turn dimmer off", { "Removes the overlay" }),
            {
                OverlayService.send(this, OverlayService.ACTION_STOP)
                toast("Dimmer off")
            }
        )
        column.addView(spacer())
    }

    private fun addFooter() {
        column.addView(info("◀ ▶ changes a value · OK selects · BACK leaves", MUTED, 15f))
        column.addView(info("LeoDimmer 2.0", MUTED, 14f))
    }

    // ---- Rows ----

    private inner class Row(
        rowTag: String,
        private val rowTitle: String,
        private val detailProvider: () -> String,
        private val detailColor: () -> Int = { MUTED },
        private val accentTitle: Boolean = false
    ) : LinearLayout(this@MainActivity) {

        var onLeft: (() -> Unit)? = null
        var onRight: (() -> Unit)? = null
        var onOk: (() -> Unit)? = null

        private val titleView = TextView(this@MainActivity)
        private val detailView = TextView(this@MainActivity)

        init {
            tag = rowTag
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            setPadding(dp(24), dp(15), dp(24), dp(15))
            background = this@MainActivity.getDrawable(R.drawable.row_background)

            titleView.text = rowTitle
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            titleView.setTextColor(if (accentTitle) ACCENT else TEXT)
            detailView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            detailView.gravity = Gravity.END

            addView(
                titleView,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                detailView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            setOnClickListener {
                onOk?.invoke()
                refresh()
            }
            refresh()
        }

        fun refresh() {
            detailView.text = detailProvider()
            detailView.setTextColor(detailColor())
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> onLeft?.let {
                    it.invoke()
                    refresh()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> onRight?.let {
                    it.invoke()
                    refresh()
                    return true
                }
            }
            return super.onKeyDown(keyCode, event)
        }
    }

    private fun addRow(row: Row, onOk: (() -> Unit)? = null) {
        if (onOk != null) row.onOk = onOk
        column.addView(row)
    }

    private fun choiceRow(
        tag: String,
        title: String,
        options: List<Int>,
        get: () -> Int,
        set: (Int) -> Unit,
        label: (Int) -> String
    ) {
        val row = Row(tag, title, { label(get()) })
        fun step(forward: Boolean) {
            val index = options.indexOf(get()).let { if (it < 0) 0 else it }
            val next = if (forward) {
                options[(index + 1) % options.size]
            } else {
                options[(index - 1 + options.size) % options.size]
            }
            set(next)
        }
        row.onLeft = { step(false) }
        row.onRight = { step(true) }
        addRow(row)
    }

    private fun section(text: String) {
        column.addView(info(text, ACCENT, 15f, bold = true))
    }

    private fun info(
        message: String,
        color: Int = MUTED,
        sizeSp: Float = 17f,
        bold: Boolean = false
    ): TextView = TextView(this).apply {
        text = message
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(24), dp(5), dp(24), dp(5))
    }

    private fun spacer(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(10)
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ---- Actions ----

    /** Re-apply the mode in use after one of its values changed. */
    private fun applyCurrentMood() {
        OverlayService.send(this, OverlayService.ACTION_SET_MOOD, Settings.getMood(this))
    }

    /**
     * Arm learning in the accessibility service, not here: the next button is
     * caught wherever the focus is, so there is no window that can miss it.
     */
    private fun startLearn(target: String) {
        if (!AccessibilityState.isEnabled(this)) {
            toast("Turn on Remote keys first")
            openAccessibilitySettings()
            return
        }
        Settings.armLearn(this, target)
        render()
        pollLearning()
    }

    private fun pollLearning() {
        handler.removeCallbacksAndMessages(null)
        val started = System.currentTimeMillis()
        val poll = object : Runnable {
            override fun run() {
                if (Settings.learnTarget(this@MainActivity).isEmpty()) {
                    learnPoll = null
                    render()
                    return
                }
                if (System.currentTimeMillis() - started > 20_000) {
                    Settings.clearLearn(this@MainActivity)
                    learnPoll = null
                    toast("No button arrived — check that Remote keys is on")
                    render()
                    return
                }
                handler.postDelayed(this, 400)
            }
        }
        learnPoll = poll
        handler.postDelayed(poll, 400)
    }

    /**
     * The per-app overlay page does not resolve on every TV build (that is why
     * the permission was impossible to find), so fall back until one of these
     * pages actually opens.
     */
    private fun openOverlaySettings() {
        val candidates = listOf(
            Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ),
            Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
        )
        if (!openFirst(candidates)) toast("Open Settings → Apps → Special access")
    }

    private fun openAccessibilitySettings() {
        val candidates = listOf(
            Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
        )
        if (!openFirst(candidates)) toast("Open Settings → Device → Accessibility")
    }

    private fun openFirst(intents: List<Intent>): Boolean =
        intents.any { intent ->
            if (intent.resolveActivity(packageManager) == null) return@any false
            try {
                startActivity(intent)
                true
            } catch (_: Exception) {
                false
            }
        }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
