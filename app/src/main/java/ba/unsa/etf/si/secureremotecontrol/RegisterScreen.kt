package ba.unsa.etf.si.secureremotecontrol

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ba.unsa.etf.si.secureremotecontrol.presentation.device.DeviceState
import ba.unsa.etf.si.secureremotecontrol.presentation.device.DeviceViewModel

@Composable
fun RegisterScreen(
    viewModel: DeviceViewModel = hiltViewModel(),
    onRegistrationSuccess: () -> Unit
) {
    var registrationKey by remember { mutableStateOf("") }

    LaunchedEffect(viewModel.deviceState) {
        viewModel.deviceState.collect { state ->
            if (state is DeviceState.Registered) {
                onRegistrationSuccess()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Welcome to Secure Remote Control!",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Start by entering your registration key below",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = registrationKey,
            onValueChange = { registrationKey = it },
            label = { Text("Registration Key") },
            singleLine = true,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { viewModel.registerDevice(registrationKey) },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text("Register")
        }
    }
}