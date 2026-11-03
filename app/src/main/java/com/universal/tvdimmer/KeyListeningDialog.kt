package com.universal.tvdimmer

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import android.widget.TextView

/**
 * The key-capture dialog's visuals, and nothing else.
 *
 * It is deliberately NOT focusable and does not handle keys itself: on the
 * Mi Box a dialog window opened over a GuidedStep does not reliably take
 * focus, and a key that lands on the activity instead is never registered --
 * that was the reported "does not register the button when pressed". With
 * this window marked FLAG_NOT_FOCUSABLE, the settings activity keeps focus
 * and captures the press in MainActivity.dispatchKeyEvent, so a press cannot
 * be lost between two windows.
 *
 * BACK still cancels (handled by the activity, so it is never mappable).
 * HOME / POWER never reach an app on Android, so they cannot be mapped this
 * way -- that is a platform limit, not a bug in this dialog.
 */
class KeyListeningDialog(
    context: Context,
    private val actionLabel: String
) : Dialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_key_listening)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        findViewById<TextView>(R.id.txt_dialog_subtitle).text =
            "Press the physical remote button you want to use for:\n\"$actionLabel\""
        // Keep focus (and therefore every key) in the activity behind it.
        window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    }
}
