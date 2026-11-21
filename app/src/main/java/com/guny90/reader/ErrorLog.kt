package com.guny90.reader

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Errors with their full details, one file each: local/book/logs/errors-<time>.log on the
 * device's storage, where a file manager or a computer over USB finds them. Writing there needs
 * the storage permission (all files access on Android 11+, see [canWriteShared]); without it,
 * logs go to the app's own folder instead. Safe to call from any thread.
 */
object ErrorLog {
    /** The log folder, relative to the device's storage. */
    private const val FOLDER = "local/book/logs"
    /** Older logs beyond this many are deleted. */
    private const val MAX_FILES = 50

    fun sharedDir(): File = File(Environment.getExternalStorageDirectory(), FOLDER)

    /** Whether logs can go to [FOLDER]; else they go to the app's own folder. */
    fun canWriteShared(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= 30 -> Environment.isExternalStorageManager()
        else -> context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Writes [error] with [what] was being done to a new log file and returns where it is, as a
     * file manager shows it (e.g. "local/book/logs/errors-2026-11-21_18-20-05.log"); null if no
     * file could be written. Never throws: logging must not cause a second failure.
     */
    @Synchronized
    fun write(context: Context, what: String, error: Throwable): String? {
        val now = Date()
        val name = "errors-" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US).format(now) + ".log"
        val text = try {
            report(context, what, error, now)
        } catch (e: Throwable) {
            "$what\n$error\n"
        }
        val shared = if (canWriteShared(context)) sharedDir() else null
        val own = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")
        for (dir in listOfNotNull(shared, own)) {
            try {
                dir.mkdirs()
                File(dir, name).writeText(text)
                dropOld(dir)
                return if (dir == shared) "$FOLDER/$name" else shownPath(context, dir) + "/" + name
            } catch (e: Throwable) {
                // try the next place
            }
        }
        return null
    }

    private fun report(context: Context, what: String, error: Throwable, time: Date): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        return "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(time)}  $what\n" +
            "Book $version, ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "Memory: ${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MB max\n\n" +
            trace
    }

    private fun dropOld(dir: File) {
        val logs = dir.listFiles { f -> f.name.startsWith("errors-") && f.name.endsWith(".log") } ?: return
        if (logs.size <= MAX_FILES) return
        logs.sortedBy { it.name }.take(logs.size - MAX_FILES).forEach { it.delete() }
    }

    /** [dir] relative to the device's storage if it's there (Android/data/...), else as is. */
    private fun shownPath(context: Context, dir: File): String {
        val root = Environment.getExternalStorageDirectory().absolutePath + "/"
        val path = dir.absolutePath
        return if (path.startsWith(root)) path.removePrefix(root) else path
    }

    /** A short line for the screen: the error's message, or its kind if it has none. */
    fun summary(error: Throwable): String = when (error) {
        is OutOfMemoryError -> "Not enough memory."
        else -> error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    }

    /**
     * Logs any crash before the app closes, so a failure that wasn't caught still leaves its
     * details behind. Call once, early.
     */
    fun catchCrashes(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is CrashLogger) return
        Thread.setDefaultUncaughtExceptionHandler(CrashLogger(app, previous))
    }

    private class CrashLogger(
        private val context: Context,
        private val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            write(context, "Crash (thread ${thread.name})", error)
            previous?.uncaughtException(thread, error)
        }
    }
}
