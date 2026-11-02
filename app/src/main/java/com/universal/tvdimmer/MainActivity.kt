package com.universal.tvdimmer

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction

/**
 * Leanback settings screen + onboarding.
 *
 * The settings UI is a GuidedStep (the native Android TV settings pattern:
 * full-screen, D-pad navigable, 10-foot type). On first run all three
 * mappings are unset and both permissions are shown as missing until granted.
 *
 * Onboarding: if SYSTEM_ALERT_WINDOW or the accessibility service is
 * missing, the corresponding row opens the system settings page directly
 * (ACTION_MANAGE_OVERLAY_PERMISSION for this app, ACTION_ACCESSIBILITY_SETTINGS
 * for the service). When the user returns, onResume notices the state changed
 * and rebuilds the screen so the rows flip to "Granted" without relaunching.
 *
 * Note on the Mi Box: Xiaomi's overlay page is reached through the same
 * ACTION_MANAGE_OVERLAY_PERMISSION intent; there is no separate hidden page
 * to link to on stock Android TV, despite Xiaomi's extra app-info screens.
 */
class MainActivity : FragmentActivity() {

    private var lastOverlayGranted: Boolean? = null
    private var lastAccessibilityEnabled: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        lastOverlayGranted = Settings.canDrawOverlays(this)
        lastAccessibilityEnabled = isAccessibilityEnabled()
        if (lastOverlayGranted == true) startDimmerService(null, null)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, DimmerSettingsFragment())
                .commit()
        }
    }

    override fun onResume() {
        super.onResume()
        val overlayNow = Settings.canDrawOverlays(this)
        val accessibilityNow = isAccessibilityEnabled()
        if (lastOverlayGranted != null &&
            (overlayNow != lastOverlayGranted || accessibilityNow != lastAccessibilityEnabled)
        ) {
            lastOverlayGranted = overlayNow
            lastAccessibilityEnabled = accessibilityNow
            recreate()
        }
    }

    fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val expected = ComponentName(this, DimmerAccessibilityService::class.java)
            .flattenToString()
        return enabled.split(':').contains(expected)
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

    fun openOverlaySettings() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
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
        private const val ID_TEST_BRIGHTER = 4L
        private const val ID_TEST_DIMMER = 5L
        private const val ID_TEST_TOGGLE = 6L
        private const val ID_PERM_OVERLAY = 7L
        private const val ID_PERM_ACCESSIBILITY = 8L
        private const val ID_RESET_KEYS = 9L
    }

    private val host: MainActivity?
        get() = activity as? MainActivity

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        val ctx = requireContext()
        val brightness = DimmerPrefs.getBrightness(ctx)
        val overlay = if (Settings.canDrawOverlays(ctx)) "granted" else "MISSING"
        val accessibility = if (host?.isAccessibilityEnabled() == true) "enabled" else "MISSING"
        val description = "Brightness $brightness% — " +
            if (brightness == 0) "blackout (audio-only). Press any button to restore.\n" else "\n" +
            "Overlay permission: $overlay\n" +
            "Accessibility service: $accessibility\n" +
            "Map three remote buttons below. Until you do, no key is intercepted."
        return GuidanceStylist.Guidance(
            "TV Dimmer",
            description,
            "Settings",
            null
        )
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        fun action(id: Long, title: String, description: String) =
            GuidedAction.Builder(ctx).id(id).title(title).description(description).build()

        actions += action(
            ID_BRIGHTER_KEY, "Increase brightness key",
            DimmerPrefs.keyLabel(ctx, DimmerPrefs.KEY_BRIGHTER)
        )
        actions += action(
            ID_DIMMER_KEY, "Decrease brightness key",
            DimmerPrefs.keyLabel(ctx, DimmerPrefs.KEY_DIMMER)
        )
        actions += action(
            ID_TOGGLE_KEY, "Toggle blackout key",
            DimmerPrefs.keyLabel(ctx, DimmerPrefs.KEY_TOGGLE)
        )
        actions += action(ID_TEST_BRIGHTER, "Test: brighter", "Step brightness up by 10% now")
        actions += action(ID_TEST_DIMMER, "Test: dimmer", "Step brightness down by 10% now")
        actions += action(ID_TEST_TOGGLE, "Test: toggle blackout", "Switch blackout on / off now")
        actions += action(
            ID_PERM_OVERLAY, "Overlay permission",
            if (Settings.canDrawOverlays(ctx)) "Granted" else "Missing — select to open system settings"
        )
        actions += action(
            ID_PERM_ACCESSIBILITY, "Accessibility service",
            if (host?.isAccessibilityEnabled() == true) "Enabled" else "Missing — select to open system settings"
        )
        actions += action(ID_RESET_KEYS, "Reset all key mappings", "Clear the three mapped buttons")
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        val ctx = requireContext()
        val activity = host ?: return
        when (action.id) {
            ID_BRIGHTER_KEY -> showKeyDialog("Increase Brightness", DimmerPrefs.KEY_BRIGHTER)
            ID_DIMMER_KEY -> showKeyDialog("Decrease Brightness", DimmerPrefs.KEY_DIMMER)
            ID_TOGGLE_KEY -> showKeyDialog("Toggle Blackout", DimmerPrefs.KEY_TOGGLE)
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
            ID_PERM_OVERLAY -> activity.openOverlaySettings()
            ID_PERM_ACCESSIBILITY -> activity.openAccessibilitySettings()
            ID_RESET_KEYS -> {
                DimmerPrefs.clearKeys(ctx)
                activity.recreate()
            }
            else -> Unit
        }
    }

    private fun showKeyDialog(actionLabel: String, prefsKey: String) {
        val ctx = requireContext()
        val activity = host ?: return
        KeyListeningDialog(ctx, actionLabel) { keyCode ->
            DimmerPrefs.setKey(ctx, prefsKey, keyCode)
            // Rebuild the GuidedStep so the new keycode shows in the row.
            activity.recreate()
        }.show()
    }
}
