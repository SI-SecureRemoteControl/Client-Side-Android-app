package ba.unsa.etf.si.secureremotecontrol.presentation.verification

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import ba.unsa.etf.si.secureremotecontrol.data.network.VerificationRequest
import ba.unsa.etf.si.secureremotecontrol.data.network.VerificationResponse
import ba.unsa.etf.si.secureremotecontrol.data.network.RetrofitClient
import kotlinx.coroutines.launch
import java.lang.Exception // Import Exception

@Composable
fun VerificationScreen(viewModel: VerificationViewModel = viewModel()) {
    val context = LocalContext.current
    // Dohvatanje jedinstvenog ID-a uređaja
    // NAPOMENA: ANDROID_ID može biti null ili se promijeniti kod factory reseta.
    // Za pouzdaniju identifikaciju razmotrite druge metode ako je potrebno.
    val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "nepoznat_id"

    Column(
        modifier = Modifier
            .fillMaxSize()
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

        // Prikaz poruke od servera ili lokalne greške
        viewModel.serverMessage?.let { message ->
            Text(
                text = message,
                color = if (message.contains("Uspješno", ignoreCase = true)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        // Prikaz loading indikatora dok čekamo odgovor
        if (viewModel.isLoading) {
            CircularProgressIndicator(modifier = Modifier.padding(bottom = 8.dp))
        }

        Button(
            onClick = { viewModel.sendVerification(deviceId) },
            enabled = !viewModel.isLoading, // Onemogući dugme tokom učitavanja
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Potvrdi Odjavu")
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text("ID Uređaja: $deviceId", style = MaterialTheme.typography.bodySmall)

    }
}