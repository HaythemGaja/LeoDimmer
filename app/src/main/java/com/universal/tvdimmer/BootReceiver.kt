package com.universal.tvdimmer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After a reboot, restart the overlay only if the user had it running.
 *
 * The service never resumes into blackout or Sleep, so a reboot can never
 * leave the screen dark.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        if (Settings.isTvPowerOnEnabled(context)) {
            TvPowerLink.wakeTv(context, "boot completed")
        }

        if (Settings.isServiceEnabled(context)) {
            OverlayService.send(context, null)
        }
    }
}
