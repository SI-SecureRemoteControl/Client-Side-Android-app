package ba.unsa.etf.si.secureremotecontrol.data.util
import android.content.Context
import ba.unsa.etf.si.secureremotecontrol.data.models.LogEntry
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object JsonLogger {
    private const val FILE_NAME = "log.json"
    private val gson = Gson()

    fun log(
        context: Context,
        level: String,
        tag: String,
        message: String,
        metadata: Map<String, String>? = null
    ) {
        synchronized(this) {
            val logEntry = LogEntry(
                timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
                level = level,
                tag = tag,
                message = message,
                metadata = metadata
            )
            val logFile = File(context.filesDir, FILE_NAME)
            val logs = if (logFile.exists() && logFile.readText().isNotBlank()) {
                val type = object : TypeToken<MutableList<LogEntry>>() {}.type
                gson.fromJson<MutableList<LogEntry>>(logFile.readText(), type)
            } else {
                mutableListOf()
            }
            logs.add(logEntry)
            logFile.writeText(gson.toJson(logs))
        }
    }

    fun readLogs(context: Context): List<LogEntry> {
        val logFile = File(context.filesDir, FILE_NAME)
        if (!logFile.exists()) return emptyList()
        val type = object : TypeToken<List<LogEntry>>() {}.type
        return gson.fromJson(logFile.readText(), type)
    }

    fun logScreenShareStart(context: Context) {
        log(context, "INFO", "ScreenSharing", "Screen sharing started")
    }

    fun logScreenShareEnd(context: Context) {
        log(context, "INFO", "ScreenSharing", "Screen sharing stopped")
    }

    fun logClick(context: Context, x: Float, y: Float) {
        log(context, "INPUT", "Click", "User clicked at ($x, $y)", mapOf("x" to "$x", "y" to "$y"))
    }

    fun logKeyPress(context: Context, key: String) {
        log(context, "INPUT", "Keyboard", "Key pressed: $key", mapOf("key" to key))
    }

    fun logFileUpload(context: Context, filename: String, size: Long) {
        log(context, "FILE", "Upload", "File uploaded: $filename", mapOf("filename" to filename, "size" to "$size"))
    }
}