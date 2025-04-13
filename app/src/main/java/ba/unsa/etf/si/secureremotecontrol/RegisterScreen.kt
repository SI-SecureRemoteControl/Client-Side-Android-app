package ba.unsa.etf.si.secureremotecontrol

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    var isLoading by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel.deviceState) {
        viewModel.deviceState.collect { state ->
            when (state) {
                is DeviceState.Registered -> {
                    isLoading = false
                    onRegistrationSuccess()
                }
                is DeviceState.Error -> {
                    isLoading = false
                }
                else -> Unit
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
        Image(
            painter = painterResource(id = R.mipmap.ic_launcher_foreground),
            contentDescription = stringResource(id = R.string.app_name),
            modifier = Modifier
                .height(180.dp)
                .graphicsLayer(
                    scaleX = 2.5f,
                    scaleY = 2.5f,
                    translationX = 10f
                )
        )

        Text(
            textAlign = TextAlign.Center,
            text = stringResource(id = R.string.title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Start by entering your registration key below",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = registrationKey,
            onValueChange = { registrationKey = it },
            label = { Text("Registration Key") },
            singleLine = true,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                isLoading = true
                viewModel.registerDevice(registrationKey)
            },
            enabled = !isLoading,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Text("Register")
            }
        }
    }
}