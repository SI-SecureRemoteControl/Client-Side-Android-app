package ba.unsa.etf.si.secureremotecontrol.presentation.verification // Package name as needed

import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
// import androidx.lifecycle.viewmodel.compose.viewModel // NO LONGER NEEDED for standard viewModel()
import androidx.hilt.navigation.compose.hiltViewModel // <<< --- ADD THIS IMPORT
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import ba.unsa.etf.si.secureremotecontrol.presentation.verification.VerificationViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeregistrationScreen(
    navController: NavController,
    onDeregisterSuccess: () -> Unit = {}
) {
    val viewModel: VerificationViewModel = hiltViewModel()
    val context = LocalContext.current

    val deviceId = remember {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown_device_id"
    }

    val deregistrationKey = viewModel.deregistrationKey
    val deregistrationMessage = viewModel.deregistrationServerMessage
    val isDeregistrationSuccess = viewModel.isDeregistrationSuccessful
    val isDeregistrationLoading = viewModel.isDeregistrationLoading

    LaunchedEffect(key1 = isDeregistrationSuccess) {
        if (isDeregistrationSuccess == true) {
            Toast.makeText(context, "Deregistration successful!", Toast.LENGTH_SHORT).show()
            delay(2000)
            onDeregisterSuccess()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Device Deregistration") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Enter Deregistration key", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = deregistrationKey,
                onValueChange = { viewModel.updateDeregistrationKey(it) },
                label = { Text("Deregistration key") },
                singleLine = true,
                isError = isDeregistrationSuccess == false && deregistrationMessage != null,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { viewModel.deregisterDevice(deviceId) },
                enabled = deregistrationKey.isNotBlank() && isDeregistrationLoading.not(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Confirm Deregistration")
            }
        }

    }
}
