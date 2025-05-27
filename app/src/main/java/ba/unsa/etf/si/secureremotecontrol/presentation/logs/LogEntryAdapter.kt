package ba.unsa.etf.si.secureremotecontrol.presentation.logs

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ba.unsa.etf.si.secureremotecontrol.data.models.LogEntry
import ba.unsa.etf.si.secureremotecontrol.databinding.ItemLogEntryBinding

class LogEntryAdapter(private val logs: List<LogEntry>) :
    RecyclerView.Adapter<LogEntryAdapter.LogViewHolder>() {

    inner class LogViewHolder(val binding: ItemLogEntryBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val binding = ItemLogEntryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return LogViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        val log = logs[position]
        holder.binding.apply {
            textTimestamp.text = log.timestamp
            textLevel.text = log.level
            textTag.text = log.tag
            textMessage.text = log.message
        }
    }

    override fun getItemCount(): Int = logs.size
}