package com.universal.tvdimmer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Screen on/off and shutdown hooks for the TV power link. Registered by
 * [OverlayService], so it only exists while the dimmer is running.
 */
class TvPowerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SCREEN_OFF ->
                Settings.setLastScreenOff(context, System.currentTimeMillis())

            Intent.ACTION_SCREEN_ON -> {
                if (!Settings.isTvPowerOnEnabled(context)) return
                val lastOff = Settings.getLastScreenOff(context)
                if (lastOff <= 0L) return
                val away = System.currentTimeMillis() - lastOff
                // A long gap means the box really sat in standby; a short one
                // is a screensaver between two clicks.
                if (away >= TvPowerLink.standbyGapMillis()) {
                    TvPowerLink.wakeTv(context, "woke after $away ms off")
                }
            }

            Intent.ACTION_SHUTDOWN -> {
                if (Settings.isTvPowerOffEnabled(context)) TvPowerLink.standbyTv(context)
            }
        }
    }
}
