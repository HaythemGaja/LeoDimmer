package com.universal.tvdimmer

import android.content.Context
import java.io.File

/**
 * The last crash, in one line, shown at the top of the settings screen.
 *
 * A dimmer runs on a TV with no console. If something throws while the overlay
 * is being created, the user sees the app vanish and has nothing to report; this
 * keeps the first line of the trace so the screen can show it and it can be
 * read out without adb.
 */
object CrashLog {

    private const val FILE_NAME = "crash.log"

    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                File(app.filesDir, FILE_NAME).writeText(error.stackTraceToString())
            } catch (_: Throwable) {
                // Reporting must never be the reason a crash is worse.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** First line of the last trace: the exception class and message. */
    fun firstLine(context: Context): String? = try {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) null
        else file.readLines().firstOrNull { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    fun clear(context: Context) {
        try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (_: Exception) {
            // Nothing to do.
        }
    }
}
