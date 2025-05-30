package ba.unsa.etf.si.secureremotecontrol.presentation.logs

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import ba.unsa.etf.si.secureremotecontrol.databinding.ActivityLogsBinding
import ba.unsa.etf.si.secureremotecontrol.data.models.LogEntry
import ba.unsa.etf.si.secureremotecontrol.data.util.JsonLogger
import com.google.gson.Gson
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class LogListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogsBinding
    private lateinit var adapter: LogEntryAdapter
    private lateinit var logs: MutableList<LogEntry>
    private val gson = Gson()
    private val logFileName = JsonLogger.FILE_NAME

    override fun onCreate(savedInstanceState: Bundle?) {

//if needed to delete old logs - usually not required
//        val logFile = File(filesDir, JsonLogger.FILE_NAME)
//        if (logFile.exists()) {
//            logFile.delete()
//            Toast.makeText(this, "Old logs deleted", Toast.LENGTH_SHORT).show()
//        }
        //only for the purpose of testing - REMOVE
        //JsonLogger.log(this, "DEBUG", "LogTest", "Test This is a blabla test log after reset")
        //JsonLogger.log(this, "DEBUG", "LogTest2", "Test This is a log after reset")

        super.onCreate(savedInstanceState)
        binding = ActivityLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Load existing logs via JsonLogger, sorting most recent first
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val rawLogs = JsonLogger.readLogs(this)
        // do not load logs that are IDENTICAL
        val uniqueLogs = rawLogs.distinctBy { Triple(it.level, it.tag, it.message) }
        logs = uniqueLogs
            .sortedByDescending { formatter.parse(it.timestamp) }
            .toMutableList()
        //loads only unique logs
        Toast.makeText(this, "Loaded ${logs.size} log(s)", Toast.LENGTH_SHORT).show()

        // Setup adapter
        adapter = LogEntryAdapter(
            logs,
            onDelete = { log -> deleteLog(log) },
            onSelectionChanged = { selected ->
                binding.buttonDeleteSelected.isEnabled = selected.isNotEmpty()
            }
        )

        // Use LinearLayoutManager with default (most recent first via sorted list)
        binding.recyclerLogs.layoutManager = LinearLayoutManager(this)
        binding.recyclerLogs.adapter = adapter

        binding.buttonDeleteAll.setOnClickListener { showDeleteAllConfirmation() }
        binding.buttonDeleteSelected.setOnClickListener {
            val deleted = adapter.deleteSelectedLogs()
            logs.removeAll(deleted)
            persistLogs()
            Toast.makeText(this, "${deleted.size} logs deleted", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteLog(log: LogEntry) {
        // Remove via adapter to update RecyclerView
        adapter.deleteLog(log)
        persistLogs()
        Toast.makeText(this, "1 log deleted", Toast.LENGTH_SHORT).show()
    }

    private fun showDeleteAllConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Delete All Logs")
            .setMessage("Are you sure you want to delete all logs?")
            .setPositiveButton("Yes") { _, _ ->
                logs.clear()
                adapter.notifyDataSetChanged()
                persistLogs()
                Toast.makeText(this, "All logs deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun persistLogs() {
        val file = File(filesDir, logFileName)
        file.writeText(gson.toJson(logs))
    }
}
