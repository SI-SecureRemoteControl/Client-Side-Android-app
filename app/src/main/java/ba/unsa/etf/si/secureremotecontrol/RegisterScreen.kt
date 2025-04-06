package ba.unsa.etf.si.secureremotecontrol.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ba.unsa.etf.si.secureremotecontrol.presentation.device.DeviceViewModel
import ba.unsa.etf.si.secureremotecontrol.presentation.device.DeviceState

@Composable
fun RegisterScreen() {
    val context = LocalContext.current
    val deviceViewModel: DeviceViewModel = hiltViewModel()
    val deviceState by deviceViewModel.deviceState.collectAsState()

    var name by remember { mutableStateOf("") }
    var registrationKey by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var showError by remember { mutableStateOf(false) }
    var successMessage by remember { mutableStateOf("") }

    LaunchedEffect(deviceState) {
        when (deviceState) {
            is DeviceState.Loading -> isLoading = true
            is DeviceState.Registered -> {
                isLoading = false
                successMessage = "Device registered successfully"
                showError = false
            }
            is DeviceState.Error -> {
                isLoading = false
                showError = true
                successMessage = (deviceState as DeviceState.Error).message
            }
            else -> Unit
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Device Registration",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Device Name") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = registrationKey,
            onValueChange = { registrationKey = it },
            label = { Text("Registration key") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))


        if (successMessage.isNotEmpty()) {
            Text(
                text = successMessage,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                isLoading = true
                showError = false
                successMessage = ""
                deviceViewModel.registerDevice(
                    name = name,
                    registrationKey = registrationKey,
                    deregistrationKey = "deregistrationKey" // Replace with actual deregistration key
                )
            },
            enabled = !isLoading && name.isNotBlank() && registrationKey.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(24.dp)
                )
            } else {
                Text("Register Device")
            }
        }
    }
}