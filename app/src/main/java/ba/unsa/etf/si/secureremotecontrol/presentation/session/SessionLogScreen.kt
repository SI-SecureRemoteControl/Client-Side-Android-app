package ba.unsa.etf.si.secureremotecontrol.presentation.session

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun SessionLogScreen(viewModel: SessionLogViewModel = viewModel()) {
    val logs by viewModel.logs.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadLogs()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        items(logs) { log ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Timestamp: ${log.timestamp}", style = MaterialTheme.typography.labelSmall)
                    Text("Level: ${log.level}", fontWeight = FontWeight.Bold)
                    Text("Tag: ${log.tag}")
                    Text("Message: ${log.message}")
                }
            }
        }
    }
}