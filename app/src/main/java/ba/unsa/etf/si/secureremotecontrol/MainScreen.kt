package ba.unsa.etf.si.secureremotecontrol

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ba.unsa.etf.si.secureremotecontrol.presentation.main.MainViewModel
import ba.unsa.etf.si.secureremotecontrol.presentation.main.SessionState

@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel(),
    onDeregister: () -> Unit
) {
    var toDeviceId by remember { mutableStateOf("") }
    val sessionState by viewModel.sessionState.collectAsState()
    var buttonEnabled by remember { mutableStateOf(true) }

    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        OutlinedTextField(
            value = toDeviceId,
            onValueChange = { toDeviceId = it },
            label = { Text("Target Device ID") }
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                buttonEnabled = false
                viewModel.requestSession(toDeviceId)
            },
            enabled = buttonEnabled
        ) {
            Text("Request Session")
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (sessionState) {
            is SessionState.Requesting -> Text("Requesting session...")
            is SessionState.Timeout -> {
                Text("Session request timed out.")
                buttonEnabled = true
            }
            is SessionState.Accepted -> Text("Session accepted!")
            is SessionState.Rejected -> {
                Text("Session rejected.")
                buttonEnabled = true
            }
            is SessionState.Error -> {
                val errorMessage = (sessionState as SessionState.Error).message
                Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
                buttonEnabled = true
                viewModel.resetSessionState()
            }
            else -> {}
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = { onDeregister() }) {
            Text("Deregister Device")
        }
    }
}