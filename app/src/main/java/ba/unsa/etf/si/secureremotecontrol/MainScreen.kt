package ba.unsa.etf.si.secureremotecontrol

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ba.unsa.etf.si.secureremotecontrol.presentation.main.MainViewModel
import ba.unsa.etf.si.secureremotecontrol.presentation.main.SessionState
import android.provider.Settings
import android.content.Intent
import android.util.Log

@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel(),
    onDeregister: () -> Unit,
    onStartScreenCapture: (callback: (resultCode: Int, data: Intent) -> Unit) -> Unit
) {
    val sessionState by viewModel.sessionState.collectAsState()
    var buttonEnabled by remember { mutableStateOf(true) }

    val context = LocalContext.current

    when (sessionState) {
        is SessionState.Rejected -> {
            Toast.makeText(context, "Session request rejected.", Toast.LENGTH_LONG).show()
            viewModel.resetSessionState()
        }
        is SessionState.Accepted -> {
            var showDialog by remember { mutableStateOf(true) }

            if (showDialog) {
                AlertDialog(
                    onDismissRequest = { showDialog = false },
                    title = { Text("Confirm Session") },
                    text = { Text("Do you want to confirm the session?") },
                    confirmButton = {
                        Button(onClick = {
                            showDialog = false
                            viewModel.sendSessionFinalConfirmation(true) // User accepted
                            onStartScreenCapture { resultCode, data ->
                                val fromId = Settings.Secure.getString(
                                    context.contentResolver,
                                    Settings.Secure.ANDROID_ID
                                )
                                Log.d("MainScreen", "MainScreen je problem: $resultCode, data: $data, fromId: $fromId")
                                viewModel.startStreaming(resultCode, data, fromId)
                                //resultCode = 0
                                //data.replaceExtras(null)
                            }
                        }) {
                            Text("Yes")
                        }
                    },
                    dismissButton = {
                        Button(onClick = {
                            showDialog = false
                            viewModel.sendSessionFinalConfirmation(false) // User rejected

                        }) {
                            Text("No")
                        }
                    }
                )
            }
        }
        else -> {}
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
                .height(180.dp).graphicsLayer(
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

        Spacer(modifier = Modifier.height(64.dp))

        if (sessionState is SessionState.Connected) {
            Text("Connected", style = MaterialTheme.typography.bodyLarge)

            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = { viewModel.disconnectSession() }) {
                Text("Disconnect")
            }
        } else {
            Button(
                onClick = {
                    buttonEnabled = false
                    viewModel.requestSession()
                },
                enabled = buttonEnabled
            ) {
                Text("Request Session")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { viewModel.stopObservingMessages(); onDeregister() },
                enabled = buttonEnabled
            ) {
                Text("Deregister Device")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        when (sessionState) {
            is SessionState.Idle -> {
                buttonEnabled = true
            }
            is SessionState.Requesting -> Text("Requesting session...")
            is SessionState.Timeout -> {
                Text("Session request timed out.")
                buttonEnabled = true
            }
            is SessionState.Accepted -> Text("Session accepted!")
            is SessionState.Waiting -> Text("Waiting for response...")
            is SessionState.Rejected -> {
                Text("Session rejected.")
                buttonEnabled = true
            }
            is SessionState.Error -> {
                val errorMessage = (sessionState as SessionState.Error).message
                Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
                buttonEnabled = true
                viewModel.resetSessionState()
            }
            else -> {}
        }
    }
}