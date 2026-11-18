package com.island.app.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashLogger {
    private const val TAG = "IslandCrash"
    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                writeLog(appContext, "Uncaught exception on thread ${thread.name}", throwable)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun recordNonFatal(context: Context, message: String, throwable: Throwable) {
        Log.e(TAG, message, throwable)
        runCatching {
            writeLog(context.applicationContext, message, throwable)
        }
    }

    fun readLastCrash(context: Context): String? {
        return runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) file.readText().takeIf { it.isNotBlank() } else null
        }.getOrNull()
    }

    fun clearLastCrash(context: Context) {
        runCatching {
            File(context.filesDir, FILE_NAME).delete()
        }
    }

    private fun writeLog(context: Context, header: String, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val content = "$timestamp — $header\n${sw.toString().take(4000)}"
        File(context.filesDir, FILE_NAME).writeText(content)
    }
}
