package ba.unsa.etf.si.secureremotecontrol.ui.screens

import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ba.unsa.etf.si.secureremotecontrol.data.models.Device
import ba.unsa.etf.si.secureremotecontrol.data.models.DeviceStatus
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import ba.unsa.etf.si.secureremotecontrol.presentation.device.DeviceViewModel

@Composable
fun RegisterScreen() {
    val deviceViewModel: DeviceViewModel = hiltViewModel()  // Koristimo DeviceViewModel
    var name by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var osVersion by remember { mutableStateOf("") }


    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Register Device", style = MaterialTheme.typography.headlineMedium)

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Device Name") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("Device Model") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = osVersion,
            onValueChange = { osVersion = it },
            label = { Text("OS Version") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                deviceViewModel.registerDevice(name, model, osVersion)
            },
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text("Register")
        }
    }
}
