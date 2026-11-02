package com.universal.tvdimmer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Persistent lifecycle (DimTV concept): after a reboot, restart the overlay
 * only if the user had previously started it. Never starts the overlay
 * uninvited on a fresh install.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val enabled = DimmerPrefs.prefs(context)
            .getBoolean(DimmerPrefs.KEY_SERVICE_ENABLED, false)
        if (!enabled) return
        val service = Intent(context, DimmerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(service)
        } else {
            context.startService(service)
        }
    }
}
