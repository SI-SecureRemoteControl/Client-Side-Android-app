
package ba.unsa.etf.si.secureremotecontrol
import ba.unsa.etf.si.secureremotecontrol.presentation.session.SessionViewModel
import androidx.activity.viewModels
import NotificationPermissionHandler
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
//import androidx.activity.viewModels
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ba.unsa.etf.si.secureremotecontrol.data.datastore.TokenDataStore
import ba.unsa.etf.si.secureremotecontrol.data.util.JsonLogger
import ba.unsa.etf.si.secureremotecontrol.presentation.main.FileShareUiEvent
import ba.unsa.etf.si.secureremotecontrol.presentation.main.MainViewModel
import ba.unsa.etf.si.secureremotecontrol.presentation.session.SessionLogScreen
import ba.unsa.etf.si.secureremotecontrol.presentation.verification.DeregistrationScreen
import ba.unsa.etf.si.secureremotecontrol.service.RemoteControlClickService
import ba.unsa.etf.si.secureremotecontrol.service.ScreenSharingService
import ba.unsa.etf.si.secureremotecontrol.ui.theme.SecureRemoteControlTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var tokenDataStore: TokenDataStore

    private val viewModel: MainViewModel by viewModels()
    private val sessionViewModel: SessionViewModel by viewModels()
    private lateinit var screenCaptureLauncher: ActivityResultLauncher<Intent>
    private lateinit var allFilesAccessLauncher: ActivityResultLauncher<Intent>

    private var onScreenCaptureResult: ((resultCode: Int, data: Intent) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JsonLogger.log(this, "INFO", "MainActivity", "App started and onCreate invoked")
        sessionViewModel.onSessionStarted()
        JsonLogger.log(this, "INFO", "MainActivity", "SessionViewModel.onSessionStarted called")
        val notificationPermissionHandler = NotificationPermissionHandler(this)
        notificationPermissionHandler.checkAndRequestNotificationPermission()

        // Initialize screen capture launcher
        screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val resultCode = result.resultCode
            val data = result.data

            Log.d("MainActivity", "Screen capture resultCode: $resultCode, data: $data")
            if (resultCode == Activity.RESULT_OK && data != null) {
                // Get device ID for tracking
                val fromId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                Log.d("MainActivity", "Screen capture granted for device $fromId")
                JsonLogger.logScreenShareStart(this)
                JsonLogger.log(this, "INFO", "ScreenCapture", "User granted screen sharing permission")

                // FIRST: Pass result to the callback - this is critical for ViewModel's proper initialization
                // The callback ultimately calls viewModel.startStreaming which sets up WebRTC
                onScreenCaptureResult?.invoke(resultCode, data)

                // THEN: Add additional log to confirm callback was invoked
                Log.d("MainActivity", "Screen capture callback was ${if (onScreenCaptureResult == null) "NULL" else "invoked"}")
                onScreenCaptureResult = null

                // DO NOT start ScreenSharingService here - let viewModel.startStreaming handle it
                // This prevents duplicated service starts and ensures proper sequencing

                // Start the click service separately - this is fine
                val clickIntent = Intent(this, RemoteControlClickService::class.java)
                startService(clickIntent)
                ba.unsa.etf.si.secureremotecontrol.data.util.JsonLogger.log(
                    this,
                    "INFO",
                    "MainActivity",
                    "Started RemoteControlClickService"
                )
                val logs = ba.unsa.etf.si.secureremotecontrol.data.util.JsonLogger.readLogs(this)
                logs.forEach {
                    Log.d("LogEntry", "${it.timestamp} [${it.level}] ${it.tag}: ${it.message}")
                }
                Log.d("MainActivity", "Started RemoteControlClickService")
            } else {
                Log.e("MainActivity", "Screen capture permission denied or invalid data.")
                JsonLogger.log(this, "WARN", "ScreenCapture", "User denied screen sharing permission or data null")
                Toast.makeText(this, "Screen sharing permission denied", Toast.LENGTH_SHORT).show()
            }
        }

        // Initialize all files access launcher
        allFilesAccessLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    Toast.makeText(this, "All Files Access granted. Starting screen capture.", Toast.LENGTH_SHORT).show()
                    JsonLogger.log(this, "INFO", "Permissions", "All Files Access granted in launcher callback, starting screen capture")

                    // Now that we have file permission, start screen capture
                    startScreenCapture { resultCode, data ->
                        val fromId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                        viewModel.startStreaming(resultCode, data, fromId)
                    }
                } else {
                    Toast.makeText(this, "All Files Access permission is required for file sharing.", Toast.LENGTH_LONG).show()
                    JsonLogger.log(this, "WARN", "Permissions", "All Files Access NOT granted in launcher callback")
                }
            }
        }

        // Set up observer for file share events directly in onCreate
        viewModel.fileShareUiEvents.observeForever { event ->
            when (event) {
                is FileShareUiEvent.RequestDirectoryPicker -> {
                    // Instead of launching picker, launch all files access permission
                    requestAllFilesAccess()
                }
                is FileShareUiEvent.DirectorySelected -> {
                    // We won't use this event since we're not using the picker anymore
                }
                is FileShareUiEvent.PermissionOrDirectoryNeeded -> {
                    // This will request all files access
                    requestAllFilesAccess()
                }
            }
        }

        setContent {
            SecureRemoteControlTheme {
                val navController = rememberNavController()

                // Start observing RTC messages
                viewModel.startObservingRtcMessages(this)

                NavHost(
                    navController = navController,
                    startDestination = determineStartDestination()
                ) {
                    composable("registration") {
                        RegisterScreen(
                            onRegistrationSuccess = {
                                navController.navigate("main") {
                                    popUpTo("registration") { inclusive = true }
                                }
                            }
                        )
                    }

                    composable("main") {
                        MainScreen(
                            viewModel = viewModel,
                            onDeregister = {
                                navController.navigate("deregister")
                            },
                            onStartScreenCapture = { callback ->
                                startScreenCapture(callback)
                            },
                            onStopScreenCapture = {
                                stopScreenCapture()
                            },
                            onShowLogs = {
                                navController.navigate("sessionLog")
                            }
                        )
                    }

                    composable("deregister") {
                        DeregistrationScreen(
                            navController = navController,
                            onDeregisterSuccess = {
                                navController.navigate("registration") {
                                    popUpTo("deregister") { inclusive = true }
                                }
                            }
                        )
                    }

                    composable("sessionLog") {
                        SessionLogScreen()
                    }

                }
            }
        }
    }

    private fun determineStartDestination(): String {
        return runBlocking {
            val token = tokenDataStore.token.first()
            if (token.isNullOrEmpty()) "registration" else "main"
        }
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                allFilesAccessLauncher.launch(intent)
                JsonLogger.log(this, "INFO", "Permissions", "Launched app-specific All Files Access permission intent")
            } catch (e: Exception) {
                Log.e("MainActivity", "Could not launch All Files Access permission screen", e)
                JsonLogger.log(this, "ERROR", "Permissions", "Failed to launch app-specific All Files Access permission screen. Falling back to general.")
                // Fallback to general settings
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                allFilesAccessLauncher.launch(intent)
                JsonLogger.log(this, "INFO", "Permissions", "Launched general All Files Access permission intent")
            }
        } else {
            JsonLogger.log(this, "INFO", "Permissions", "SDK < R, skipping All Files Access and starting screen capture")
            startScreenCapture { resultCode, data ->
                val fromId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                viewModel.startStreaming(resultCode, data, fromId)
                JsonLogger.log(this, "INFO", "Permissions", "Started screen capture directly (SDK < R)")
            }
        }
    }

    private fun startScreenCapture(callback: (resultCode: Int, data: Intent) -> Unit) {
        val mediaProjectionManager = getSystemService(MediaProjectionManager::class.java) as MediaProjectionManager
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()

        // CRITICAL: Store the callback BEFORE launching the screen capture intent
        Log.d("MainActivity", "Setting onScreenCaptureResult callback and launching screen capture")
        onScreenCaptureResult = callback

        screenCaptureLauncher.launch(captureIntent)
    }

    private fun stopScreenCapture() {
        val intent = ScreenSharingService.getStopIntent(this)
        stopService(intent)
        Log.d("MainActivity", "Screen sharing stopped.")
        JsonLogger.logScreenShareEnd(this)
        JsonLogger.log(this, "INFO", "MainActivity", "User stopped screen sharing")
    }

    override fun onDestroy() {
        super.onDestroy()
        // Make sure to remove the observer to prevent memory leaks
        viewModel.fileShareUiEvents.removeObserver { /* observer */ }
        JsonLogger.log(this, "INFO", "MainActivity", "App destroyed and observer removed")
    }
}