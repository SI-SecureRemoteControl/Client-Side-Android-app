package ba.unsa.etf.si.secureremotecontrol.presentation.logs

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import ba.unsa.etf.si.secureremotecontrol.databinding.ActivityLogsBinding
import ba.unsa.etf.si.secureremotecontrol.data.models.LogEntry
import org.json.JSONArray
import java.io.File

class LogListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val logs = loadLogsFromFile()
        val adapter = LogEntryAdapter(logs)
        binding.recyclerLogs.adapter = adapter
    }

    private fun loadLogsFromFile(): List<LogEntry> {
        val logs = mutableListOf<LogEntry>()
        val file = File(filesDir, "session_logs.json")
        if (!file.exists()) return logs

        val jsonArray = JSONArray(file.readText())
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            logs.add(
                LogEntry(
                    timestamp = obj.getString("timestamp"),
                    level = obj.getString("type"),
                    tag = obj.getString("tag") ?: "N/A",
                    message = obj.getString("details"),
                    metadata = null
                )
            )
        }
        return logs
    }
}