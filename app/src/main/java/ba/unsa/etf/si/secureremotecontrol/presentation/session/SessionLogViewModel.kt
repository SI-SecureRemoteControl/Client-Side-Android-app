package ba.unsa.etf.si.secureremotecontrol.presentation.session

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import ba.unsa.etf.si.secureremotecontrol.data.models.LogEntry
import ba.unsa.etf.si.secureremotecontrol.data.util.JsonLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SessionLogViewModel(application: Application) : AndroidViewModel(application) {
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs

    fun loadLogs() {
        _logs.value = JsonLogger.readLogs(getApplication())
    }
}