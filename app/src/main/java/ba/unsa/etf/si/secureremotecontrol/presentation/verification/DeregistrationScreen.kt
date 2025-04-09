package ba.unsa.etf.si.secureremotecontrol.presentation.verification // Package name as needed

import android.provider.Settings
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
    navController: NavController
    // Remove viewModel parameter from here
) {
    // --- *** GET VIEWMODEL INSTANCE USING HILT *** ---
    val viewModel: VerificationViewModel = hiltViewModel()
    // --- *** ------------------------------------ *** ---

    val context = LocalContext.current
    // Consider making deviceId retrieval safer or providing a fallback if needed elsewhere
    val deviceId = remember { // Use remember to avoid retrieving it on every recomposition
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_device_id"
    }

    // --- KORISTIMO STANJE ZA DEREGISTRACIJU --- (State remains the same)
    val deregistrationKey = viewModel.deregistrationKey // Directly access state from viewModel
    val deregistrationMessage = viewModel.deregistrationServerMessage
    val isDeregistrationSuccess = viewModel.isDeregistrationSuccessful
    val isDeregistrationLoading = viewModel.isDeregistrationLoading
    // -------------------------------------------

    // Reaguj kada se poruka za DEREGISTRACIJU promijeni
    LaunchedEffect(key1 = isDeregistrationSuccess) { // Trigger only when success status changes definitively
        if (isDeregistrationSuccess == true) {
            // Optional: Display success message briefly before navigating
            delay(2000) // Keep a short delay for user feedback
            navController.popBackStack() // Go back after successful deregistration
            // Reset ViewModel state if needed after navigation (though Hilt might scope it correctly)
            // viewModel.resetDeregistrationState() // You might need to add this method to your ViewModel
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Deregistracija Uređaja") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "Nazad"
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
            Text("Unesite ključ za deregistraciju:", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = deregistrationKey, // Use the state variable
                onValueChange = { viewModel.updateDeregistrationKey(it) }, // Call ViewModel update function
                label = { Text("Ključ za deregistraciju") },
                singleLine = true,
                isError = isDeregistrationSuccess == false && deregistrationMessage != null, // Show error state on the field
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Display message (success or error)
            deregistrationMessage?.let { message ->
                Text(
                    text = message,
                    color = if (isDeregistrationSuccess == true)
                        MaterialTheme.colorScheme.primary // Or a specific success color
                    else
                        MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium, // Use appropriate style
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // Show loading indicator OR the button
            if (isDeregistrationLoading) {
                CircularProgressIndicator(modifier = Modifier.padding(bottom = 8.dp))
            } else {
                Button(
                    onClick = { viewModel.deregisterDevice(deviceId) },
                    enabled = deregistrationKey.isNotBlank(), // Enable only if key is not blank
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Potvrdi Deregistraciju")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                "ID Uređaja: $deviceId",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant // Softer color
            )
        }
    }
}