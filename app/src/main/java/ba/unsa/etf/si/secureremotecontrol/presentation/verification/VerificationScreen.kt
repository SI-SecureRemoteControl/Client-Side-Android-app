package ba.unsa.etf.si.secureremotecontrol.presentation.verification

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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import kotlin.system.exitProcess

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerificationScreen(
    navController: NavController,
    viewModel: VerificationViewModel = viewModel()
) {
    val context = LocalContext.current
    val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "nepoznat_id"

    val serverMessage = viewModel.serverMessage
    val isSuccessful = viewModel.isVerificationSuccessful

    // Reaguj kada se poruka promijeni
    LaunchedEffect(serverMessage) {
        if (serverMessage != null && isSuccessful == true) {
            delay(3000)
            navController.navigate("home") {
                popUpTo(0)
            }
        }
    }


    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("") },
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
            Text("Unesite kod za odjavu:", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = viewModel.code,
                onValueChange = { viewModel.updateCode(it) },
                label = { Text("Kod") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Prikaz poruke (ako postoji)
            serverMessage?.let { message ->
                Text(
                    text = message,
                    color = if (isSuccessful == true)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            if (viewModel.isLoading) {
                CircularProgressIndicator(modifier = Modifier.padding(bottom = 8.dp))
            }

            Button(
                onClick = { viewModel.sendVerification(deviceId) },
                enabled = !viewModel.isLoading && viewModel.code.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Potvrdi Odjavu")
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text("ID Uređaja: $deviceId", style = MaterialTheme.typography.bodySmall)
        }
    }
}
