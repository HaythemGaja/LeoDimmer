package com.universal.tvdimmer

import android.content.Context
import java.io.File

/**
 * The last crash, in one row on the settings screen -- but only if it came from
 * the build that is running now.
 *
 * The trace is stored next to the version code that produced it. That tag
 * matters: `adb install -r` replaces the code but keeps the app's files
 * directory, so without it a trace from an older install is shown as if it just
 * happened. That is exactly what happened on the Mi Box -- v2.0 displayed
 *
 *   NoClassDefFoundError: Failed resolution of: Landroidx/leanback/...
 *
 * left behind by a hand-built 1.x APK whose Leanback jar had not been
 * downloaded. v2.0 contains no Leanback at all (the shipped dex has no such
 * string), so the line was a ghost, and it made a working build look broken.
 * A stale trace is now deleted on start instead of displayed.
 */
object CrashLog {

    private const val FILE_NAME = "crash.log"

    private var installed = false

    fun install(context: Context) {
        val app = context.applicationContext
        // A trace from another build says nothing about this one.
        if (!isCurrentBuild(app)) clear(app)
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                File(app.filesDir, FILE_NAME)
                    .writeText("${versionCode(app)}\n${error.stackTraceToString()}")
            } catch (_: Throwable) {
                // Reporting must never be the reason a crash is worse.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** First line of a crash from *this* build, kept short enough for a row. */
    fun lastCrash(context: Context): String? = try {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists() || !isCurrentBuild(context)) {
            null
        } else {
            // Line 1 is the version tag, line 2 the trace's first line.
            file.readLines().drop(1).firstOrNull { it.isNotBlank() }?.let { shorten(it) }
        }
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

    // ---- Internals ----

    /** True when a stored trace belongs to the build that is running. */
    private fun isCurrentBuild(context: Context): Boolean = try {
        val file = File(context.filesDir, FILE_NAME)
        file.exists() &&
            file.readLines().firstOrNull()?.trim() == versionCode(context).toString()
    } catch (_: Exception) {
        false
    }

    /**
     * "java.lang.NoClassDefFoundError: Failed resolution of: ..." is too wide
     * for one TV row, and the package prefix tells the user nothing.
     */
    private fun shorten(line: String): String {
        val trimmed = line
            .removePrefix("java.lang.")
            .removePrefix("android.view.")
            .removePrefix("android.os.")
            .trim()
        return if (trimmed.length <= 84) trimmed else trimmed.take(83) + "…"
    }

    @Suppress("DEPRECATION")
    private fun versionCode(context: Context): Int = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionCode
    } catch (_: Exception) {
        0
    }
}
