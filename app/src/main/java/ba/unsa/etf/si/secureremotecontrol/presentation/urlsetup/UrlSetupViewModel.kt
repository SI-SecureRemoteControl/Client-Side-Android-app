package ba.unsa.etf.si.secureremotecontrol.presentation.urlsetup

import android.widget.Toast
import androidx.compose.foundation.layout.*
// import androidx.compose.material.icons.Icons // Uklonjeno ako se Link ne koristi
// import androidx.compose.material.icons.filled.Link // Uklonjeno
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ba.unsa.etf.si.secureremotecontrol.data.datastore.TokenDataStore
import ba.unsa.etf.si.secureremotecontrol.data.util.RegistrationPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UrlSetupViewModel @Inject constructor(
    private val registrationPreferences: RegistrationPreferences,
    private val tokenDataStore: TokenDataStore
) : ViewModel() {

    var websocketUrlInput by mutableStateOf(TextFieldValue(registrationPreferences.webSocketUrl ?: "wss://"))

    fun updateWebsocketUrl(newValue: TextFieldValue) {
        websocketUrlInput = newValue
    }

    fun saveUrl(onSaved: (nextRoute: String) -> Unit, onError: (String) -> Unit) {
        val url = websocketUrlInput.text.trim()
        if (url.isBlank() || (!url.startsWith("ws://", ignoreCase = true) && !url.startsWith("wss://", ignoreCase = true))) {
            onError("Please enter a valid WebSocket URL (must start with ws:// or wss://)")
            return
        }
        registrationPreferences.webSocketUrl = url
        viewModelScope.launch {
            val token = tokenDataStore.token.first()
            val nextRoute = if (token.isNullOrEmpty()) "registration" else "main"
            onSaved(nextRoute)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UrlSetupScreen(
    viewModel: UrlSetupViewModel = hiltViewModel(),
    onUrlSet: (nextRoute: String) -> Unit
) {
    val context = LocalContext.current
    val urlInput = viewModel.websocketUrlInput

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Server Configuration") }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "WebSocket Server URL",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                "Enter the URL of your Secure Remote Control server. This is typically done once after installation.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = urlInput,
                onValueChange = { viewModel.updateWebsocketUrl(it) },
                label = { Text("WebSocket URL") },
                placeholder = { Text("wss://example.com/socket") },
                // leadingIcon = { Icon(Icons.Filled.Link, contentDescription = "URL Icon") }, // <<-- OVA LINIJA JE UKLONJENA
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    viewModel.saveUrl(
                        onSaved = { nextRoute ->
                            Toast.makeText(context, "WebSocket URL saved!", Toast.LENGTH_SHORT).show()
                            onUrlSet(nextRoute)
                        },
                        onError = { errorMessage ->
                            Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = urlInput.text.isNotBlank()
            ) {
                Text("Save and Continue")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "If you need to change this later, you might need to clear app data or reinstall the application.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}