package com.universal.tvdimmer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Persistent lifecycle (DimTV concept): after a reboot, restart the overlay
 * only if the user had previously started it. Never starts the overlay
 * uninvited on a fresh install.
 *
 * Also the "box turned on" trigger for the TV power link. The dimmer service
 * itself refuses to resume into blackout / ambient mode, so a reboot cannot
 * leave the TV dark.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        if (DimmerPrefs.isTvPowerOnEnabled(context)) {
            TvPowerLink.wakeTv(context, "boot completed")
        }

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
