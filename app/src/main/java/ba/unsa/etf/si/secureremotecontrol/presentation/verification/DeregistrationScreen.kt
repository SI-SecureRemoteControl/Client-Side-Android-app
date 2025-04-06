package ba.unsa.etf.si.secureremotecontrol.presentation.verification // Preporučujem promjenu imena paketa/foldera

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
import ba.unsa.etf.si.secureremotecontrol.presentation.verification.VerificationViewModel // ViewModel ostaje isti

@OptIn(ExperimentalMaterial3Api::class)
@Composable
// PREIMENOVANO: Funkcija sada jasno opisuje svrhu ekrana
fun DeregistrationScreen(
    navController: NavController,
    // ViewModel instanca se i dalje koristi
    viewModel: VerificationViewModel = viewModel()
) {
    val context = LocalContext.current
    val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "nepoznat_id"

    // --- KORISTIMO STANJE ZA DEREGISTRACIJU ---
    val deregistrationMessage = viewModel.deregistrationServerMessage
    val isDeregistrationSuccess = viewModel.isDeregistrationSuccessful
    val isDeregistrationLoading = viewModel.isDeregistrationLoading
    // -------------------------------------------

    // Reaguj kada se poruka za DEREGISTRACIJU promijeni
    LaunchedEffect(key1 = deregistrationMessage, key2 = isDeregistrationSuccess) {
        if (deregistrationMessage != null && isDeregistrationSuccess == true) {
            // Nakon uspješne deregistracije, možda želite nazad ili na login?
            delay(2500) // Malo kraće čekanje za poruku
            // Primjer: Idi nazad na prethodni ekran
            navController.popBackStack()
            // Ili idi na specifičnu rutu, npr., login:
            // navController.navigate("login") {
            //     popUpTo(navController.graph.startDestinationId) { inclusive = true }
            //     launchSingleTop = true
            // }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // Ažuriran naslov
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
            // Ažuriran tekst
            Text("Unesite ključ za deregistraciju:", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(16.dp))

            // --- INPUT FIELD ZA DEREGISTRACIJSKI KLJUČ ---
            OutlinedTextField(
                value = viewModel.deregistrationKey, // Koristi 'deregistrationKey'
                onValueChange = { viewModel.updateDeregistrationKey(it) }, // Poziva 'updateDeregistrationKey'
                label = { Text("Ključ za deregistraciju") }, // Ažurirana labela
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            // --------------------------------------------
            Spacer(modifier = Modifier.height(16.dp))

            // Prikaz poruke za DEREGISTRACIJU (ako postoji)
            deregistrationMessage?.let { message ->
                Text(
                    text = message,
                    color = if (isDeregistrationSuccess == true)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // Prikaz indikatora učitavanja za DEREGISTRACIJU
            if (isDeregistrationLoading) {
                CircularProgressIndicator(modifier = Modifier.padding(bottom = 8.dp))
            }

            // --- DUGME ZA DEREGISTRACIJU ---
            Button(
                // POZIVA ISPRAVNU FUNKCIJU
                onClick = { viewModel.deregisterDevice(deviceId) },
                // Omogućeno ako NIJE učitavanje DEREGISTRACIJE i ako ključ NIJE prazan
                enabled = !isDeregistrationLoading && viewModel.deregistrationKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Ažuriran tekst dugmeta
                Text("Potvrdi Deregistraciju")
            }
            // -----------------------------

            Spacer(modifier = Modifier.height(24.dp))
            Text("ID Uređaja: $deviceId", style = MaterialTheme.typography.bodySmall)
        }
    }
}