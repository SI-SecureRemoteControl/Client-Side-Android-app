
/*package ba.unsa.etf.si.secureremotecontrol

import NotificationPermissionHandler
import android.Manifest // Required for MANAGE_EXTERNAL_STORAGE if targeting below R for legacy
import android.app.Activity
import android.content.ActivityNotFoundException
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
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
    // It's good practice to use the Activity context for starting activities for result
    val activity = LocalContext.current as? Activity
        ?: throw IllegalStateException("MainScreen must be hosted in an Activity context for permission requests")

    val notificationPermissionHandler = remember { NotificationPermissionHandler(context) }
    var buttonEnabled by remember { mutableStateOf(true) }

    // Launcher for All Files Access permission result
    val requestAllFilesAccessLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ -> // The result isn't directly useful, we just re-check the permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                Toast.makeText(context, "All Files Access granted. You can now request a session.", Toast.LENGTH_LONG).show()
                // Optionally, you could try to re-trigger the action that was pending
                // For now, user will have to click "Request Session" again.
            } else {
                Toast.makeText(context, "All Files Access permission is required for full functionality.", Toast.LENGTH_LONG).show()
            }
        }
    }

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

                    viewModel.requestDirectoryAccess() // This triggers FileShareUiEvent.RequestDirectoryPicker
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
                // Before requesting directory access, ensure All Files Access if needed by your app logic
                // If your requestDirectoryAccess() relies on MANAGE_EXTERNAL_STORAGE
                // then the check should be here too or earlier.
                // For now, assuming it's primarily for SAF.
                viewModel.requestDirectoryAccess()
            }) {
                Text("Change File Sharing Directory")
            }
        } else {
            Button(
                onClick = {
                    // 1. Check for All Files Access (Android 11 / API 30+)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        if (!Environment.isExternalStorageManager()) {
                            Toast.makeText(context, "This app requires All Files Access. Please grant it in the next screen.", Toast.LENGTH_LONG).show()
                            try {
                                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                intent.data = Uri.parse("package:${activity.packageName}")
                                requestAllFilesAccessLauncher.launch(intent)
                            } catch (e: ActivityNotFoundException) {
                                Log.e("MainScreen", "Device doesn't support ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION. Trying fallback.", e)
                                // Fallback for devices that might not handle the package-specific URI well
                                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                requestAllFilesAccessLauncher.launch(intent)
                            }
                            return@Button // Stop further execution until permission is handled
                        }
                    } else {
                        // For Android 10 (API 29) and below, you'd rely on READ/WRITE_EXTERNAL_STORAGE
                        // and request them at runtime if not granted.
                        // This example focuses on MANAGE_EXTERNAL_STORAGE for API 30+.
                        // You might need to add legacy permission checks here.
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                            // TODO: Implement runtime request for WRITE_EXTERNAL_STORAGE for API < 30
                            // For simplicity, this example assumes it's granted or not strictly needed
                            // for the "all files access" scenario on older versions.
                            // If it IS needed, you'd use a different launcher for this.
                            Toast.makeText(context, "Legacy storage permission needed for older Android. (Not implemented in this example)", Toast.LENGTH_LONG).show()
                            // return@Button
                        }
                    }

                    // 2. Notification Permission
                    if (!notificationPermissionHandler.isNotificationPermissionGranted()) {
                        Toast.makeText(
                            context,
                            "Notifications are not allowed. Please enable them in settings.",
                            Toast.LENGTH_LONG
                        ).show()
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        }
                        context.startActivity(intent)
                        return@Button
                    }

                    // 3. Accessibility Service
                    val serviceClassName = "ba.unsa.etf.si.secureremotecontrol.service.RemoteControlAccessibilityService"
                    if (!AccessibilityUtils.isAccessibilityServiceEnabled(context, serviceClassName)) {
                        Toast.makeText(
                            context,
                            "Accessibility service is not enabled. Please enable it in settings.",
                            Toast.LENGTH_LONG
                        ).show()
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        context.startActivity(intent)
                        return@Button
                    }

                    // All permissions/settings seem OK, proceed
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

        Spacer(modifier = Modifier.height(16.dp))

        // Status text
        when (val currentState = sessionState) { // Renamed for clarity in when
            is SessionState.Idle -> {
                buttonEnabled = true
            }
            is SessionState.Requesting -> Text("Requesting session...")
            is SessionState.Timeout -> {
                Text("Session request timed out.")
                buttonEnabled = true
            }
            is SessionState.Accepted -> Text("Session accepted! Waiting for confirmation...") // Updated text
            is SessionState.Waiting -> Text("Waiting for response...")
            is SessionState.Rejected -> {
                Text("Session rejected.")
                buttonEnabled = true
            }
            is SessionState.Error -> {
                LaunchedEffect(currentState.message) { // Use currentState
                    Toast.makeText(context, currentState.message, Toast.LENGTH_LONG).show()
                    buttonEnabled = true
                    viewModel.resetSessionState()
                }
            }
            else -> {}
        }
    }
}*/

package ba.unsa.etf.si.secureremotecontrol

import NotificationPermissionHandler
import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ba.unsa.etf.si.secureremotecontrol.presentation.main.FileShareUiEvent
import ba.unsa.etf.si.secureremotecontrol.presentation.main.MainViewModel
import ba.unsa.etf.si.secureremotecontrol.presentation.main.SessionState
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
    val activity = LocalContext.current as? Activity
        ?: throw IllegalStateException("MainScreen must be hosted in an Activity context")

    val notificationPermissionHandler = remember { NotificationPermissionHandler(context) }
    var buttonEnabled by remember { mutableStateOf(true) }
    var showAllFilesAccessDialog by remember { mutableStateOf(false) }

    // Launcher for All Files Access permission result
    val requestAllFilesAccessLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                Toast.makeText(context, "All Files Access granted. Starting screen capture...", Toast.LENGTH_SHORT).show()

                // Now that we have file permission, start screen capture
                onStartScreenCapture { resultCode, data ->
                    val fromId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                    viewModel.startStreaming(resultCode, data, fromId)
                }
            } else {
                Toast.makeText(context, "All Files Access permission is required for file sharing.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Launcher for legacy WRITE_EXTERNAL_STORAGE permission
    val requestLegacyStoragePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Toast.makeText(context, "Storage permission granted. Starting screen capture...", Toast.LENGTH_SHORT).show()

            // Now that we have file permission, start screen capture
            onStartScreenCapture { resultCode, data ->
                val fromId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                viewModel.startStreaming(resultCode, data, fromId)
            }
        } else {
            Toast.makeText(context, "Storage permission is needed for file operations.", Toast.LENGTH_LONG).show()
        }
    }

    // For debugging - log current state
    LaunchedEffect(sessionState) {
        Log.d("MainScreen", "Current session state: $sessionState")
    }

    // Observe file share UI events from LiveData
    DisposableEffect(viewModel) {
        val observer = androidx.lifecycle.Observer<FileShareUiEvent> { event ->
            Log.d("MainScreen", "Received FileShareUiEvent: $event")
            when (event) {
                is FileShareUiEvent.RequestDirectoryPicker,
                is FileShareUiEvent.PermissionOrDirectoryNeeded -> {
                    // Instead of launching directory picker, show All Files Access dialog
                    showAllFilesAccessDialog = true
                }
                is FileShareUiEvent.DirectorySelected -> {
                    // This event comes after permission has been granted, start screen capture
                    Log.d("MainScreen", "Permission granted or directory selected, starting screen capture")
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

        viewModel.fileShareUiEvents.observeForever(observer)

        onDispose {
            viewModel.fileShareUiEvents.removeObserver(observer)
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

    // Dialog for All Files Access permission
    if (showAllFilesAccessDialog) {
        AlertDialog(
            onDismissRequest = { showAllFilesAccessDialog = false },
            title = { Text("File Access Required") },
            text = { Text("This app requires All Files Access permission for file browsing and sharing features.") },
            confirmButton = {
                Button(onClick = {
                    showAllFilesAccessDialog = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            intent.data = Uri.parse("package:${activity.packageName}")
                            requestAllFilesAccessLauncher.launch(intent)
                        } catch (e: ActivityNotFoundException) {
                            Log.e("MainScreen", "Device doesn't support ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION. Trying fallback.", e)
                            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            requestAllFilesAccessLauncher.launch(intent)
                        }
                    } else {
                        // On older Android, request WRITE_EXTERNAL_STORAGE
                        requestLegacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                }) {
                    Text(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "Grant All Files Access" else "Grant Storage Permission")
                }
            },
            dismissButton = {
                Button(onClick = {
                    showAllFilesAccessDialog = false
                    Log.d("MainScreen", "User declined to grant All Files Access")
                    Toast.makeText(context, "File sharing features will be limited without permission", Toast.LENGTH_LONG).show()
                }) {
                    Text("Later")
                }
            }
        )
    }

    // Dialog for session confirmation
    if (sessionState is SessionState.Accepted) {
        Log.d("MainScreen", "Showing session confirmation dialog")
        AlertDialog(
            onDismissRequest = { /* Cannot dismiss by clicking outside */ },
            title = { Text("Confirm Session") },
            text = { Text("Do you want to confirm this remote control session?") },
            confirmButton = {
                Button(onClick = {
                    viewModel.sendSessionFinalConfirmation(true)

                    // Check for All Files Access permission and request if needed
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                        Toast.makeText(context, "Session confirmed. Please grant All Files Access.", Toast.LENGTH_SHORT).show()
                        showAllFilesAccessDialog = true
                    } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        Toast.makeText(context, "Session confirmed. Please grant Storage Permission.", Toast.LENGTH_SHORT).show()
                        requestLegacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else {
                        // Permissions are good, start screen capture directly
                        Log.d("MainScreen", "Permissions are good. Starting screen capture after session confirmation.")
                        onStartScreenCapture { resultCode, data ->
                            val fromId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                            viewModel.startStreaming(resultCode, data, fromId)
                        }
                    }
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

            // Button to request All Files Access if not granted
            if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) ||
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)) {
                Button(onClick = { showAllFilesAccessDialog = true }) {
                    Text("Grant File Access Permission")
                }
            }
        } else {
            Button(
                onClick = {
                    // Permission checks before requesting session
                    var allPermissionsOk = true

                    // Check notification permission
                    if (!notificationPermissionHandler.isNotificationPermissionGranted()) {
                        Toast.makeText(context, "Notifications are not allowed. Please enable them in settings.", Toast.LENGTH_LONG).show()
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        }
                        context.startActivity(intent)
                        allPermissionsOk = false
                    }

                    // Check accessibility service
                    if (allPermissionsOk) {
                        val serviceClassName = "ba.unsa.etf.si.secureremotecontrol.service.RemoteControlAccessibilityService"
                        if (!AccessibilityUtils.isAccessibilityServiceEnabled(context, serviceClassName)) {
                            Toast.makeText(context, "Accessibility service is not enabled. Please enable it in settings.", Toast.LENGTH_LONG).show()
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            context.startActivity(intent)
                            allPermissionsOk = false
                        }
                    }

                    if (allPermissionsOk) {
                        buttonEnabled = false
                        viewModel.requestSession()
                    } else {
                        Log.d("MainScreen", "One or more pre-requisites for session not met.")
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
        when (val currentState = sessionState) {
            is SessionState.Idle -> buttonEnabled = true
            is SessionState.Requesting -> Text("Requesting session...")
            is SessionState.Timeout -> {
                Text("Session request timed out.")
                buttonEnabled = true
            }
            is SessionState.Accepted -> Text("Session accepted! Waiting for confirmation...")
            is SessionState.Waiting -> Text("Waiting for response...")
            is SessionState.Rejected -> {
                Text("Session rejected.")
                buttonEnabled = true
            }
            is SessionState.Error -> {
                LaunchedEffect(currentState.message) {
                    Toast.makeText(context, currentState.message, Toast.LENGTH_LONG).show()
                    buttonEnabled = true
                    viewModel.resetSessionState()
                }
            }
            else -> {}
        }
    }
}