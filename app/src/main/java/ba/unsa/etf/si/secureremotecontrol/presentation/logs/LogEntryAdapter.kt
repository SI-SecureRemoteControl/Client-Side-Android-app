package ba.unsa.etf.si.secureremotecontrol.presentation.logs

import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ba.unsa.etf.si.secureremotecontrol.data.models.LogEntry
import ba.unsa.etf.si.secureremotecontrol.databinding.ItemLogEntryBinding

class LogEntryAdapter(
    private val logs: MutableList<LogEntry>,
    private val onDelete: (LogEntry) -> Unit,
    private val onSelectionChanged: (Set<LogEntry>) -> Unit
) : RecyclerView.Adapter<LogEntryAdapter.LogViewHolder>() {

    private val selectedLogs = mutableSetOf<LogEntry>()

    inner class LogViewHolder(val binding: ItemLogEntryBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemLogEntryBinding.inflate(inflater, parent, false)
        return LogViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        val log = logs[position]
        val binding = holder.binding
        Log.d("Adapter", "Binding log: ${log.message}")
        binding.textTimestamp.text = log.timestamp
        binding.textLevel.text = log.level
        binding.textTag.text = log.tag
        binding.textMessage.text = log.message

        // Change background if selected
        binding.root.setBackgroundColor(
            if (selectedLogs.contains(log)) 0x30FF0000 else 0x00000000
        )

        binding.buttonDeleteLog.setOnClickListener {
            onDelete(log)
        }

        binding.root.setOnClickListener {
            if (selectedLogs.contains(log)) {
                selectedLogs.remove(log)
            } else {
                selectedLogs.add(log)
            }
            notifyItemChanged(position)
            onSelectionChanged(selectedLogs)
        }
    }

    override fun getItemCount(): Int = logs.size

    fun deleteLog(log: LogEntry) {
        val index = logs.indexOf(log)
        if (index >= 0) {
            logs.removeAt(index)
            notifyItemRemoved(index)
        }
    }

    fun deleteSelectedLogs(): List<LogEntry> {
        val deleted = selectedLogs.toList()
        logs.removeAll(deleted)
        selectedLogs.clear()
        notifyDataSetChanged()
        return deleted
    }


    fun clearSelection() {
        selectedLogs.clear()
        notifyDataSetChanged()
    }

    fun getSelectedLogs(): Set<LogEntry> = selectedLogs.toSet()
}
