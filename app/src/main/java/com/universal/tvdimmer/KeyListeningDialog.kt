package com.universal.tvdimmer

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.Window
import android.widget.TextView

/**
 * The key-capture dialog, exactly as specified.
 *
 * BACK cancels (and is therefore never itself mappable). Every other key
 * down is captured raw, reported to [onKeyCaptured], and the dialog closes.
 * HOME / POWER never reach a dialog on Android, so they cannot be mapped
 * this way -- that is a platform limit, not a bug in this dialog.
 */
class KeyListeningDialog(
    context: Context,
    private val actionLabel: String,
    private val onKeyCaptured: (keyCode: Int) -> Unit
) : Dialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_key_listening)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        findViewById<TextView>(R.id.txt_dialog_subtitle).text =
            "Press the physical remote button you want to use for:\n\"$actionLabel\""
        // The dialog content has no focusable view, and on a TV the
        // GuidedStep grid underneath can keep focus and swallow D-pad
        // keys before Dialog.onKeyDown ever runs. Give the decor view
        // focus so keys have a target, and capture in dispatchKeyEvent
        // below, which runs before view-focus dispatch regardless.
        window?.decorView?.isFocusableInTouchMode = true
        window?.decorView?.requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                dismiss()
                return true
            }
            onKeyCaptured(event.keyCode)
            dismiss()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            dismiss()
            return true
        }
        onKeyCaptured(keyCode)
        dismiss()
        return true
    }
}
