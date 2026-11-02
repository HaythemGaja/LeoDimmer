package com.universal.tvdimmer

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/**
 * "Is our accessibility service enabled?" -- asked in two places (the
 * settings screen and the blackout fail-safe), so it lives here once.
 *
 * Two sources, because one is not reliable everywhere:
 *   1. AccessibilityManager's enabled-service list (authoritative when the
 *      platform populates it).
 *   2. The raw ENABLED_ACCESSIBILITY_SERVICES setting, matching both the full
 *      component name and the short "pkg/.Service" form -- vendors differ in
 *      which one they store, and comparing only the full form produced a
 *      false "MISSING" on some boxes.
 */
object AccessibilityState {

    fun componentName(context: Context): ComponentName =
        ComponentName(context, DimmerAccessibilityService::class.java)

    fun isEnabled(context: Context): Boolean {
        val expected = componentName(context)

        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val enabled = manager?.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        if (enabled != null) {
            for (info in enabled) {
                val serviceInfo = info.resolveInfo?.serviceInfo ?: continue
                if (serviceInfo.packageName == expected.packageName &&
                    serviceInfo.name == expected.className
                ) {
                    return true
                }
            }
        }

        val raw = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val full = expected.flattenToString()
        val short = expected.flattenToShortString()
        return raw.split(':').any { it == full || it == short }
    }
}
