package ba.unsa.etf.si.secureremotecontrol.data.models

data class LogEntry(
    val timestamp: String,
    val level: String,
    val tag: String,
    val message: String
)