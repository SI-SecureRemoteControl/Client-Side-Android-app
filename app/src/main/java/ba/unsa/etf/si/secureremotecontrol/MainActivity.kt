/*package ba.unsa.etf.si.secureremotecontrol

import NotificationPermissionHandler
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ba.unsa.etf.si.secureremotecontrol.data.datastore.TokenDataStore
import ba.unsa.etf.si.secureremotecontrol.presentation.main.MainViewModel
import ba.unsa.etf.si.secureremotecontrol.presentation.verification.DeregistrationScreen
import ba.unsa.etf.si.secureremotecontrol.ui.theme.SecureRemoteControlTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import android.provider.Settings
import ba.unsa.etf.si.secureremotecontrol.data.webrtc.WebRTCManager
import ba.unsa.etf.si.secureremotecontrol.service.RemoteControlClickService
import ba.unsa.etf.si.secureremotecontrol.service.ScreenSharingService

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var tokenDataStore: TokenDataStore
    private lateinit var screenCaptureLauncher: ActivityResultLauncher<Intent>

    @Inject
    lateinit var webRTCManager: WebRTCManager // Inject WebRTCManager here
    private val SCREEN_CAPTURE_REQUEST_CODE = 1001
    private var onScreenCaptureResult: ((resultCode: Int, data: Intent) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val notificationPermissionHandler = NotificationPermissionHandler(this)
        notificationPermissionHandler.checkAndRequestNotificationPermission()
        // Initialize the ActivityResultLauncher
        screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val resultCode = result.resultCode
            val data = result.data

            Log.d("MainActivity", "Screen capture resultCode: $resultCode, data: $data")
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                /*onScreenCaptureResult?.invoke(result.resultCode, result.data!!)
                onScreenCaptureResult = null*/
                val fromId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                //webRTCManager.startScreenCapture(resultCode, data, fromId)
                //Log.d("MainActivity", "Screen capture started successfully.")
                val intent = ScreenSharingService.getStartIntent(this, resultCode, data, fromId)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(intent) // For API 26 and above
                } else {
                    startService(intent) // For API 24 and 25
                }

            } else {
                Log.e("MainActivity", "Screen capture permission denied or invalid data.")
            }
            val intent = Intent(this, RemoteControlClickService::class.java)
            startService(intent)
        }
        setContent {
            SecureRemoteControlTheme {
                val navController = rememberNavController()
                val viewModel: MainViewModel = hiltViewModel()

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
                            onDeregister = {
                                navController.navigate("deregister")
                            },
                            onStartScreenCapture = { callback ->
                                startScreenCapture(callback)
                            },
                            onStopScreenCapture = {
                                stopScreenCapture()
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

    private fun startScreenCapture(callback: (resultCode: Int, data: Intent) -> Unit) {
        val mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        onScreenCaptureResult = callback
        Log.d("MainActivity", "Starting screen capture with intent: $captureIntent")
        screenCaptureLauncher.launch(captureIntent)
    }

    private fun stopScreenCapture() {
        val intent = ScreenSharingService.getStopIntent(this)
        stopService(intent)
        Log.d("MainActivity", "Screen sharing stopped.")
    }



}*/

package ba.unsa.etf.si.secureremotecontrol

import NotificationPermissionHandler
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ba.unsa.etf.si.secureremotecontrol.data.datastore.TokenDataStore
import ba.unsa.etf.si.secureremotecontrol.presentation.main.FileShareUiEvent
import ba.unsa.etf.si.secureremotecontrol.presentation.main.MainViewModel
import ba.unsa.etf.si.secureremotecontrol.presentation.verification.DeregistrationScreen
import ba.unsa.etf.si.secureremotecontrol.ui.theme.SecureRemoteControlTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import android.provider.Settings
import ba.unsa.etf.si.secureremotecontrol.data.webrtc.WebRTCManager
import ba.unsa.etf.si.secureremotecontrol.service.RemoteControlClickService
import ba.unsa.etf.si.secureremotecontrol.service.ScreenSharingService

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var tokenDataStore: TokenDataStore

    private lateinit var screenCaptureLauncher: ActivityResultLauncher<Intent>
    private lateinit var directoryPickerLauncher: ActivityResultLauncher<Intent>

    @Inject
    lateinit var webRTCManager: WebRTCManager

    private var onScreenCaptureResult: ((resultCode: Int, data: Intent) -> Unit)? = null

    // Use the viewModels() delegate to get the same ViewModel instance that will be used in the Composable
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val notificationPermissionHandler = NotificationPermissionHandler(this)
        notificationPermissionHandler.checkAndRequestNotificationPermission()

        // Initialize the Screen Capture ActivityResultLauncher
        screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val resultCode = result.resultCode
            val data = result.data

            Log.d("MainActivity", "Screen capture resultCode: $resultCode, data: $data")
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                val fromId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                val intent = ScreenSharingService.getStartIntent(this, resultCode, data, fromId)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(intent) // For API 26 and above
                } else {
                    startService(intent) // For API 24 and 25
                }
            } else {
                Log.e("MainActivity", "Screen capture permission denied or invalid data.")
            }
            val intent = Intent(this, RemoteControlClickService::class.java)
            startService(intent)
        }

        // Initialize the Directory Picker ActivityResultLauncher
        directoryPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.also { directoryUri ->
                    Log.d("MainActivity", "SAF Directory selected: $directoryUri")

                    // Take persistent permissions
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    try {
                        contentResolver.takePersistableUriPermission(directoryUri, takeFlags)
                        Log.d("MainActivity", "Persistable URI permission granted for: $directoryUri")

                        // Save the URI in ViewModel
                        viewModel.setRootDirectoryUri(directoryUri)

                        // Show success toast
                        Toast.makeText(this, "Directory access granted", Toast.LENGTH_SHORT).show()
                    } catch (e: SecurityException) {
                        Log.e("MainActivity", "Failed to take persistable URI permission", e)
                        Toast.makeText(this, "Failed to access directory", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Log.w("MainActivity", "Directory selection was cancelled or failed.")
                Toast.makeText(this, "Directory selection cancelled", Toast.LENGTH_SHORT).show()
            }
        }

        // Observe file share UI events
        lifecycleScope.launch {
            viewModel.fileShareUiEvents.collect { event ->
                when (event) {
                    is FileShareUiEvent.RequestDirectoryPicker -> {
                        openDirectoryPicker()
                    }
                    is FileShareUiEvent.DirectorySelected -> {
                        // Handle directory selected event if needed
                        Log.d("MainActivity", "Directory selected: ${event.uri}")
                    }
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
                            viewModel = viewModel, // Pass the ViewModel instance
                            onDeregister = {
                                navController.navigate("deregister")
                            },
                            onStartScreenCapture = { callback ->
                                startScreenCapture(callback)
                            },
                            onStopScreenCapture = {
                                stopScreenCapture()
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

    private fun startScreenCapture(callback: (resultCode: Int, data: Intent) -> Unit) {
        val mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        onScreenCaptureResult = callback
        Log.d("MainActivity", "Starting screen capture with intent: $captureIntent")
        screenCaptureLauncher.launch(captureIntent)
    }

    private fun stopScreenCapture() {
        val intent = ScreenSharingService.getStopIntent(this)
        stopService(intent)
        Log.d("MainActivity", "Screen sharing stopped.")
    }

    private fun openDirectoryPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            // Optionally, specify initial URI if you have one
            // addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        try {
            directoryPickerLauncher.launch(intent)
            Log.d("MainActivity", "Directory picker launched")
        } catch (e: Exception) {
            Log.e("MainActivity", "Could not launch directory picker", e)
            Toast.makeText(this, "Failed to open directory picker", Toast.LENGTH_SHORT).show()
        }
    }
}


