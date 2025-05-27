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

import androidx.compose.runtime.Composable // Placeholder for RegisterScreen
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ba.unsa.etf.si.secureremotecontrol.data.datastore.TokenDataStore
import ba.unsa.etf.si.secureremotecontrol.data.util.JsonLogger
import ba.unsa.etf.si.secureremotecontrol.data.util.RegistrationPreferences
import ba.unsa.etf.si.secureremotecontrol.presentation.main.FileShareUiEvent

import ba.unsa.etf.si.secureremotecontrol.presentation.main.MainViewModel
import ba.unsa.etf.si.secureremotecontrol.presentation.session.SessionLogScreen
import ba.unsa.etf.si.secureremotecontrol.presentation.urlsetup.UrlSetupScreen // Import new screen
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

    @Inject // Inject RegistrationPreferences
    lateinit var registrationPreferences: RegistrationPreferences

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

        screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val resultCode = result.resultCode
            val data = result.data

            Log.d("MainActivity", "Screen capture resultCode: $resultCode, data: $data")
            if (resultCode == Activity.RESULT_OK && data != null) {
                val fromId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                Log.d("MainActivity", "Screen capture granted for device $fromId")
                JsonLogger.logScreenShareStart(this)
                JsonLogger.log(this, "INFO", "ScreenCapture", "User granted screen sharing permission")

                onScreenCaptureResult?.invoke(resultCode, data)
                Log.d("MainActivity", "Screen capture callback was ${if (onScreenCaptureResult == null) "NULL" else "invoked"}")
                onScreenCaptureResult = null

                val clickIntent = Intent(this, RemoteControlClickService::class.java)
                startService(clickIntent)
                ba.unsa.etf.si.secureremotecontrol.data.util.JsonLogger.log(
                    this,
                    "INFO",
                    "MainActivity",
                    "Started RemoteControlClickService"
                )
                Log.d("MainActivity", "Started RemoteControlClickService")
            } else {
                Log.e("MainActivity", "Screen capture permission denied or invalid data.")
                JsonLogger.log(this, "WARN", "ScreenCapture", "User denied screen sharing permission or data null")
                Toast.makeText(this, "Screen sharing permission denied", Toast.LENGTH_SHORT).show()
            }
        }

        allFilesAccessLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    Toast.makeText(this, "All Files Access granted. Starting screen capture.", Toast.LENGTH_SHORT).show()
                    JsonLogger.log(this, "INFO", "Permissions", "All Files Access granted in launcher callback, starting screen capture")
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

        viewModel.stopScreenCaptureEvent.observe(this) {
            stopScreenCapture()
        }

        viewModel.fileShareUiEvents.observeForever { event ->
            when (event) {
                is FileShareUiEvent.RequestDirectoryPicker -> {
                    requestAllFilesAccess()
                }
                is FileShareUiEvent.DirectorySelected -> {
                    // Not used as much now
                }
                is FileShareUiEvent.PermissionOrDirectoryNeeded -> {
                    requestAllFilesAccess()
                }
            }
        }

        setContent {
            SecureRemoteControlTheme {
                val navController = rememberNavController()

                // Start observing RTC messages - but only if WebSocket URL is set.
                // The ViewModel's connectAndObserveMessages will use the URL from preferences.
                // If the URL isn't set, WebSocketServiceImpl will log an error and not connect.
                // We ensure the app flow forces URL setup first.
                if (!registrationPreferences.webSocketUrl.isNullOrEmpty()) {
                    viewModel.startObservingRtcMessages(this)
                }


                NavHost(
                    navController = navController,
                    startDestination = determineStartDestination()
                ) {
                    composable("urlSetup") { // New route for URL setup
                        UrlSetupScreen(
                            onUrlSet = { nextRoute ->
                                // After URL is set, RTC messages can be observed.
                                viewModel.startObservingRtcMessages(this@MainActivity)
                                navController.navigate(nextRoute) {
                                    popUpTo("urlSetup") { inclusive = true }
                                }
                            }
                        )
                    }

                    composable("registration") {
                        RegisterScreen( // Assuming this composable exists
                            onRegistrationSuccess = {
                                navController.navigate("main") {
                                    popUpTo("registration") { inclusive = true }
                                }
                            }
                        )
                    }

                    composable("main") {
                        MainScreen( // Assuming this composable exists
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
                                    popUpTo("main") { inclusive = true } // Corrected popUpTo logic
                                    popUpTo("deregister") {inclusive = true}
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
            val wsUrl = registrationPreferences.webSocketUrl
            if (wsUrl.isNullOrEmpty()) {
                "urlSetup" // Navigate to URL setup first if not set
            } else {
                val token = tokenDataStore.token.first()
                if (token.isNullOrEmpty()) "registration" else "main"
            }
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
        viewModel.fileShareUiEvents.removeObserver { /* observer */ }
        JsonLogger.log(this, "INFO", "MainActivity", "App destroyed and observer removed")
    }
}