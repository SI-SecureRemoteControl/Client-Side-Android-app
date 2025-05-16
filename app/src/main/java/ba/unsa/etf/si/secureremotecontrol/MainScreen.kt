/*package ba.unsa.etf.si.secureremotecontrol

import NotificationPermissionHandler
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
import ba.unsa.etf.si.secureremotecontrol.presentation.main.FileShareState
import ba.unsa.etf.si.secureremotecontrol.presentation.main.FileShareUiEvent
import ba.unsa.etf.si.secureremotecontrol.utils.AccessibilityUtils
*/

/*
@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel(),
    onDeregister: () -> Unit,
    onStartScreenCapture: (callback: (resultCode: Int, data: Intent) -> Unit) -> Unit,
    onStopScreenCapture: () -> Unit
) {
    val sessionState by viewModel.sessionState.collectAsState()
    val fileShareState by viewModel.fileShareState.collectAsState()
    var buttonEnabled by remember { mutableStateOf(true) }
    val context = LocalContext.current
    val notificationPermissionHandler = remember { NotificationPermissionHandler(context) }
    var rejectionMessageVisible by remember { mutableStateOf(false) }

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
                                viewModel.startStreaming(resultCode, data, fromId)
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
        Text(
            text = "Secure Remote Control",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(64.dp))

        if (sessionState is SessionState.Connected) {
            Text("Connected", style = MaterialTheme.typography.bodyLarge)

            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = {
                viewModel.disconnectSession() // Disconnect the session
                onStopScreenCapture() // Stop screen sharing
            }) {
                Text("Disconnect")
            }
        } else {
            Button(
                onClick = {
                    if (notificationPermissionHandler.isNotificationPermissionGranted()) {
                        val serviceClassName = "ba.unsa.etf.si.secureremotecontrol.service.RemoteControlAccessibilityService"
                        if (AccessibilityUtils.isAccessibilityServiceEnabled(context, serviceClassName)) {
                            buttonEnabled = false
                            viewModel.requestSession()
                        } else {
                            Toast.makeText(
                                context,
                                "Accessibility service is not enabled. Please enable it in settings.",
                                Toast.LENGTH_LONG
                            ).show()
                            // Open accessibility settings
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            context.startActivity(intent)
                        }
                    } else {
                        Toast.makeText(
                            context,
                            "Notifications are not allowed. Please enable them in settings.",
                            Toast.LENGTH_LONG
                        ).show()
                        // Open notification settings
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        }
                        context.startActivity(intent)
                    }
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

        Spacer(modifier = Modifier.height(16.dp))




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


*/

package ba.unsa.etf.si.secureremotecontrol

import NotificationPermissionHandler
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ba.unsa.etf.si.secureremotecontrol.presentation.main.MainViewModel
import ba.unsa.etf.si.secureremotecontrol.presentation.main.SessionState
import android.provider.Settings
import android.content.Intent
import android.util.Log
import ba.unsa.etf.si.secureremotecontrol.presentation.main.FileShareUiEvent
import ba.unsa.etf.si.secureremotecontrol.utils.AccessibilityUtils

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onDeregister: () -> Unit,
    onStartScreenCapture: (callback: (resultCode: Int, data: Intent) -> Unit) -> Unit,
    onStopScreenCapture: () -> Unit
) {
    val sessionState by viewModel.sessionState.collectAsState()
    val context = LocalContext.current
    val notificationPermissionHandler = remember { NotificationPermissionHandler(context) }
    var buttonEnabled by remember { mutableStateOf(true) }

    // For debugging - log current state
    LaunchedEffect(sessionState) {
        Log.d("MainScreen", "Current session state: $sessionState")
    }

    // Collect file share UI events
    LaunchedEffect(key1 = true) {
        viewModel.fileShareUiEvents.collect { event ->
            Log.d("MainScreen", "Received FileShareUiEvent: $event")
            when (event) {
                is FileShareUiEvent.RequestDirectoryPicker -> {
                    Log.d("MainScreen", "Requesting directory picker")
                    // This will be handled by MainActivity
                }
                is FileShareUiEvent.DirectorySelected -> {
                    Log.d("MainScreen", "Directory selected, starting screen capture")
                    // When directory is selected, start screen capture
                    onStartScreenCapture { resultCode, data ->
                        val fromId = Settings.Secure.getString(
                            context.contentResolver,
                            Settings.Secure.ANDROID_ID
                        )
                        viewModel.startStreaming(resultCode, data, fromId)
                    }
                }
            }
        }
    }

    // Handle session state changes
    LaunchedEffect(sessionState) {
        when (sessionState) {
            is SessionState.Rejected -> {
                Toast.makeText(context, "Session request rejected.", Toast.LENGTH_LONG).show()
                viewModel.resetSessionState()
            }
            else -> {}
        }
    }

    // Dialog for session confirmation
    if (sessionState is SessionState.Accepted) {
        Log.d("MainScreen", "Should show confirmation dialog now")

        AlertDialog(
            onDismissRequest = { /* Cannot dismiss by clicking outside */ },
            title = { Text("Confirm Session") },
            text = { Text("Do you want to confirm the session?") },
            confirmButton = {
                Button(onClick = {
                    Log.d("MainScreen", "User confirmed session, requesting directory access")
                    viewModel.sendSessionFinalConfirmation(true)

                    Toast.makeText(
                        context,
                        "Please select a directory for file sharing",
                        Toast.LENGTH_SHORT
                    ).show()

                    viewModel.requestDirectoryAccess()
                }) {
                    Text("Yes")
                }
            },
            dismissButton = {
                Button(onClick = {
                    Log.d("MainScreen", "User rejected session")
                    viewModel.sendSessionFinalConfirmation(false)
                }) {
                    Text("No")
                }
            }
        )
    }

    // Main screen content
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Secure Remote Control",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(64.dp))

        if (sessionState is SessionState.Connected || sessionState is SessionState.Streaming) {
            Text("Connected", style = MaterialTheme.typography.bodyLarge)

            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = {
                viewModel.disconnectSession()
                onStopScreenCapture()
            }) {
                Text("Disconnect")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(onClick = {
                viewModel.requestDirectoryAccess()
            }) {
                Text("Change File Sharing Directory")
            }
        } else {
            Button(
                onClick = {
                    if (notificationPermissionHandler.isNotificationPermissionGranted()) {
                        val serviceClassName = "ba.unsa.etf.si.secureremotecontrol.service.RemoteControlAccessibilityService"
                        if (AccessibilityUtils.isAccessibilityServiceEnabled(context, serviceClassName)) {
                            buttonEnabled = false
                            viewModel.requestSession()
                        } else {
                            Toast.makeText(
                                context,
                                "Accessibility service is not enabled. Please enable it in settings.",
                                Toast.LENGTH_LONG
                            ).show()
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            context.startActivity(intent)
                        }
                    } else {
                        Toast.makeText(
                            context,
                            "Notifications are not allowed. Please enable them in settings.",
                            Toast.LENGTH_LONG
                        ).show()
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        }
                        context.startActivity(intent)
                    }
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

        Spacer(modifier = Modifier.height(16.dp))

        // Status text
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
                LaunchedEffect(errorMessage) {
                    Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
                    buttonEnabled = true
                    viewModel.resetSessionState()
                }
            }
            else -> {}
        }
    }
}

