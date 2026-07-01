package com.voicebot.app

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Installs a global crash handler that writes the last uncaught exception to a
 * file, so failures on the device can be read back inside the app (no adb needed).
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                crashFile(this).writeText(
                    "[$stamp] поток=${thread.name}\n$sw"
                )
            } catch (_: Exception) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        fun crashFile(app: Application): File = File(app.filesDir, "last_crash.txt")

        fun readLastCrash(context: android.content.Context): String? {
            val f = File(context.filesDir, "last_crash.txt")
            return if (f.exists()) f.readText() else null
        }

        fun clearCrash(context: android.content.Context) {
            File(context.filesDir, "last_crash.txt").delete()
        }
    }
}
