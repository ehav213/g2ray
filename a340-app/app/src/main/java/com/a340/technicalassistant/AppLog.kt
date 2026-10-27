package com.a340.technicalassistant

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AppLog(context: Context) {
    private val file = File(context.filesDir, "diagnostics/a340_app.log").apply {
        parentFile?.mkdirs()
        if (!exists()) createNewFile()
    }
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun i(event: String, detail: String = "") = write(
        "INFO",
        event,
        if (event == "APP_START") "version ${BuildConfig.VERSION_NAME}" else detail,
        null
    )

    @Synchronized
    fun e(event: String, error: Throwable? = null, detail: String = "") =
        write("ERROR", event, detail, error)

    @Synchronized
    fun tail(maxLines: Int = 120): String {
        if (!file.exists() || file.length() == 0L) return "No diagnostic events recorded yet."
        val lines = file.readLines()
        return lines.takeLast(maxLines.coerceAtLeast(1)).joinToString("\n")
    }

    @Synchronized
    fun clear() {
        file.writeText("")
        i("LOG_CLEARED")
    }

    fun path(): String = file.absolutePath

    private fun write(level: String, event: String, detail: String, error: Throwable?) {
        val line = buildString {
            append(stamp.format(Date()))
            append(" | ").append(level)
            append(" | ").append(event)
            if (detail.isNotBlank()) append(" | ").append(detail.replace('\n', ' '))
            if (error != null) {
                append(" | ").append(error.javaClass.simpleName)
                error.message?.let { append(": ").append(it.replace('\n', ' ')) }
            }
        }
        runCatching {
            if (file.length() > 1_000_000L) {
                val keep = file.readLines().takeLast(500)
                file.writeText(keep.joinToString("\n", postfix = if (keep.isEmpty()) "" else "\n"))
            }
            file.appendText(line + "\n")
        }
        if (level == "ERROR") Log.e("A340TA", line, error) else Log.i("A340TA", line)
    }
}
