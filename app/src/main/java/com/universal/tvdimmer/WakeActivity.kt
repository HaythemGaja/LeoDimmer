package com.universal.tvdimmer

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager

/**
 * Invisible, non-interactive one-shot activity used only by the TV power link.
 *
 * Launching an activity makes the box drive HDMI output again, which most TVs
 * treat as a CEC wake signal even when the CEC permission itself is not
 * granted. It draws nothing, takes no touches, and finishes itself.
 */
class WakeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        Handler(Looper.getMainLooper()).postDelayed(
            { finish() },
            TvPowerLink.wakeActivityMillis()
        )
    }
}
