package me.weishu.kernelsu.ui.util

import androidx.compose.runtime.mutableStateOf
import me.weishu.kernelsu.ksuApp
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Boot diagnostics that survive the app being unusable.
 *
 * The manager twice came up as a white window with no crash and no usable log line, and every
 * round of "fix and hope" cost a build plus a device install. This writes what the app actually
 * decided (state, page scale, ui mode, theme, whether pages composed, any uncaught exception)
 * into `Android/data/<package>/files/xec-boot.log`, which `adb pull` can read without root even
 * when the UI is blank.
 */
object BootTrace {
    private const val FILE_NAME = "xec-boot.log"

    /** Set by the main screen once its pages have composed at least once. */
    val pagesDrawn = mutableStateOf(false)

    /** Last kernel probe result, written from a worker thread for the safe page to print. */
    @Volatile
    var lastProbe: String = "(not probed yet)"

    private fun file(): File? = runCatching {
        ksuApp.getExternalFilesDir(null)?.let { File(it, FILE_NAME) }
    }.getOrNull()

    private fun stamp(): String =
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    fun log(message: String) {
        runCatching { file()?.appendText("${stamp()} $message\n") }
    }

    fun stacktrace(error: Throwable): String {
        val writer = StringWriter()
        PrintWriter(writer).use { error.printStackTrace(it) }
        return writer.toString()
    }

    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            log("FATAL on ${thread.name}: ${stacktrace(error)}")
            previous?.uncaughtException(thread, error)
        }
    }
}
