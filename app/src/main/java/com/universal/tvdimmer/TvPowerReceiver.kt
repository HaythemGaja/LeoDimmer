package com.universal.tvdimmer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Screen on/off and shutdown hooks for the TV power link. Registered by
 * [DimmerService], so it only exists while the dimmer is running.
 *
 * Runs on the main thread; both callbacks only fire an intent or a reflection
 * attempt, so there is nothing to move off it.
 */
class TvPowerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SCREEN_OFF ->
                DimmerPrefs.setLastScreenOff(context, System.currentTimeMillis())

            Intent.ACTION_SCREEN_ON -> {
                if (!DimmerPrefs.isTvPowerOnEnabled(context)) return
                val lastOff = DimmerPrefs.getLastScreenOff(context)
                if (lastOff <= 0L) return
                val away = System.currentTimeMillis() - lastOff
                // A long gap means the box really sat in standby; a short one
                // is just a screensaver between two clicks and must not steal
                // the TV input from whatever else the user is watching.
                if (away >= TvPowerLink.standbyGapMillis()) {
                    TvPowerLink.wakeTv(context, "woke after $away ms off")
                }
            }

            Intent.ACTION_SHUTDOWN -> {
                if (DimmerPrefs.isTvPowerOffEnabled(context)) TvPowerLink.standbyTv(context)
            }
        }
    }
}
