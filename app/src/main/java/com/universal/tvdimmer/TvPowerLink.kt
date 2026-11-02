package com.universal.tvdimmer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.lang.reflect.Proxy

/**
 * Best-effort "turn the TV on/off with the box" link.
 *
 * Everything here goes through HDMI-CEC, which Android only exposes to
 * privileged apps (`android.permission.HDMI_CEC` is signature|privileged).
 * A sideloaded app cannot hold that permission, so every call is reflective
 * and every failure is swallowed: on a retail box the calls usually throw
 * SecurityException, and the fallback below is what actually does the work.
 *
 * Power ON (works on most boxes):
 *   1. Try HDMI-CEC One Touch Play, which asks the TV to wake and switch to
 *      this input.
 *   2. If that is not permitted, launch the invisible [WakeActivity]. Any
 *      activity launch makes the box drive HDMI output again, which most TVs
 *      treat as a wake signal. This is only allowed from the background when
 *      the app holds "Display over other apps", so it is skipped otherwise.
 *
 * Power OFF: there is no public API for a source device to send CEC standby,
 * so this is attempted only where the platform happens to expose a TV client
 * and otherwise does nothing. In practice the TV's own "auto power off when
 * the source goes away" CEC setting is what turns the picture off; the README
 * says so rather than pretending otherwise.
 */
object TvPowerLink {

    private const val TAG = "LeoDimmer"
    private const val HDMI_SERVICE = "hdmi_control"

    /** Long enough for the box to re-establish HDMI output, short enough that
     *  the invisible activity never becomes noticeable. */
    private const val WAKE_ACTIVITY_MILLIS = 1_500L

    /** Screen-on after at least this much screen-off time counts as "the box
     *  was in standby" rather than a screensaver blip. */
    private const val STANDBY_GAP_MILLIS = 5 * 60 * 1000L

    fun register(context: Context): BroadcastReceiver? = try {
        val receiver = TvPowerReceiver()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SHUTDOWN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        receiver
    } catch (e: Exception) {
        Log.w(TAG, "Could not register TV power receiver", e)
        null
    }

    fun unregister(context: Context, receiver: BroadcastReceiver) {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // Already unregistered.
        }
    }

    /** Called on boot and when the box wakes from a long standby. */
    fun wakeTv(context: Context, reason: String) {
        val cec = tryCecOneTouchPlay(context)
        Log.i(TAG, "TV power on ($reason): CEC one-touch-play requested=$cec")
        if (!cec) startWakeActivity(context)
    }

    /** Called on shutdown, when the box is going away for good. */
    fun standbyTv(context: Context) {
        val cec = tryCecStandby(context)
        Log.i(TAG, "TV power off: CEC standby requested=$cec")
    }

    fun standbyGapMillis(): Long = STANDBY_GAP_MILLIS

    fun wakeActivityMillis(): Long = WAKE_ACTIVITY_MILLIS

    // ---- HDMI-CEC (all reflective: the API is not usable without the
    //      privileged HDMI_CEC permission, and reflection keeps the app
    //      honest about that instead of crashing on the missing grant). ----

    private fun tryCecOneTouchPlay(context: Context): Boolean {
        return try {
            val manager = hdmiManager(context) ?: return false
            val client = manager.javaClass
                .getMethod("getPlaybackClient")
                .invoke(manager) ?: return false
            val callbackType = Class.forName(
                "android.hardware.hdmi.HdmiControlManager\$ResultCallback"
            )
            val callback = Proxy.newProxyInstance(
                callbackType.classLoader,
                arrayOf(callbackType)
            ) { _, _, _ -> null }
            client.javaClass
                .getMethod("oneTouchPlay", callbackType)
                .invoke(client, callback)
            true
        } catch (e: Exception) {
            Log.d(TAG, "HDMI-CEC one-touch-play unavailable: ${e.javaClass.simpleName}")
            false
        } catch (e: LinkageError) {
            false
        }
    }

    private fun tryCecStandby(context: Context): Boolean {
        return try {
            val manager = hdmiManager(context) ?: return false
            val tvClient = manager.javaClass
                .getMethod("getTvClient")
                .invoke(manager) ?: return false
            tvClient.javaClass
                .getMethod("setStandbyMode", java.lang.Boolean.TYPE)
                .invoke(tvClient, true)
            true
        } catch (e: Exception) {
            Log.d(TAG, "HDMI-CEC standby unavailable: ${e.javaClass.simpleName}")
            false
        } catch (e: LinkageError) {
            false
        }
    }

    private fun hdmiManager(context: Context): Any? = try {
        context.getSystemService(HDMI_SERVICE)
    } catch (e: Exception) {
        null
    }

    private fun startWakeActivity(context: Context) {
        if (!Settings.canDrawOverlays(context)) {
            Log.i(TAG, "Overlay permission missing: background wake activity not allowed")
            return
        }
        try {
            context.startActivity(
                Intent(context, WakeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not start wake activity", e)
        }
    }
}
