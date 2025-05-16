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
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast // Required for Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels // Required for by viewModels()
import androidx.lifecycle.lifecycleScope // Required for lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ba.unsa.etf.si.secureremotecontrol.data.datastore.TokenDataStore
import ba.unsa.etf.si.secureremotecontrol.presentation.main.FileShareUiEvent // Required
import ba.unsa.etf.si.secureremotecontrol.presentation.main.MainViewModel
import ba.unsa.etf.si.secureremotecontrol.presentation.verification.DeregistrationScreen
import ba.unsa.etf.si.secureremotecontrol.service.RemoteControlClickService
import ba.unsa.etf.si.secureremotecontrol.service.ScreenSharingService
import ba.unsa.etf.si.secureremotecontrol.ui.theme.SecureRemoteControlTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest // Recommended for collecting SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch // Required for launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var tokenDataStore: TokenDataStore

    // Using by viewModels() to get the Activity-scoped ViewModel
    private val viewModel: MainViewModel by viewModels()

    private lateinit var screenCaptureLauncher: ActivityResultLauncher<Intent>
    private lateinit var directoryPickerLauncher: ActivityResultLauncher<Intent> // For SAF

    // Callback for screen capture result, to be passed to ViewModel
    private var onScreenCaptureResult: ((resultCode: Int, data: Intent?) -> Unit)? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val notificationPermissionHandler = NotificationPermissionHandler(this)
        notificationPermissionHandler.checkAndRequestNotificationPermission()

        // Initialize the Screen Capture ActivityResultLauncher
        screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val resultCode = result.resultCode
            val data = result.data // data can be null if resultCode is not RESULT_OK

            Log.d("MainActivity", "Screen capture resultCode: $resultCode, data: $data")

            // Pass the result back to the awaiting callback (which is viewModel.startStreaming via MainScreen)
            onScreenCaptureResult?.invoke(resultCode, data) // Pass data as nullable
            onScreenCaptureResult = null // Clear callback to prevent multiple invocations

            // The ViewModel (via MainScreen's callback) will now handle starting ScreenSharingService
            // So, we remove the direct service start from here.

            // Consider moving RemoteControlClickService start to when a session is truly active,
            // possibly initiated by the ViewModel or ScreenSharingService.
            // Starting it here means it starts regardless of screen capture success or session state.
            val clickServiceIntent = Intent(this, RemoteControlClickService::class.java)
            startService(clickServiceIntent)
            Log.d("MainActivity", "RemoteControlClickService started (or attempted).")
        }

        // Initialize the Directory Picker ActivityResultLauncher for SAF
        directoryPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.also { directoryUri ->
                    Log.d("MainActivity", "SAF Directory selected: $directoryUri")
                    try {
                        // Take persistent permissions for the selected directory
                        val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        contentResolver.takePersistableUriPermission(directoryUri, takeFlags)
                        Log.d("MainActivity", "Persistable URI permission granted for: $directoryUri")

                        // Inform the ViewModel about the selected SAF root directory
                        viewModel.setSafRootDirectoryUri(directoryUri) // Corrected method name

                        Toast.makeText(this, "Directory access granted via picker", Toast.LENGTH_SHORT).show()
                    } catch (e: SecurityException) {
                        Log.e("MainActivity", "Failed to take persistable URI permission", e)
                        Toast.makeText(this, "Failed to get persistent access to directory", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Log.w("MainActivity", "Directory selection was cancelled or failed.")
                Toast.makeText(this, "Directory selection cancelled", Toast.LENGTH_SHORT).show()
            }
        }

        // Observe file share UI events from the ViewModel
        lifecycleScope.launch {
            viewModel.fileShareUiEvents.collectLatest { event -> // Use collectLatest
                when (event) {
                    is FileShareUiEvent.RequestDirectoryPicker -> {
                        Log.d("MainActivity", "Received RequestDirectoryPicker event, launching picker.")
                        openDirectoryPicker()
                    }
                    is FileShareUiEvent.DirectorySelected -> {
                        // This event is primarily for MainScreen to react (e.g., start screen capture flow)
                        Log.d("MainActivity", "Observed DirectorySelected event in MainActivity: ${event.uri}. MainScreen should handle primary action.")
                    }
                    is FileShareUiEvent.PermissionOrDirectoryNeeded -> {
                        // This event is primarily for MainScreen to show a dialog.
                        Log.d("MainActivity", "Observed PermissionOrDirectoryNeeded event in MainActivity. MainScreen should handle.")
                    }
                }
            }
        }

        setContent {
            SecureRemoteControlTheme {
                val navController = rememberNavController()

                // Start observing RTC messages from WebRTCManager via ViewModel
                viewModel.startObservingRtcMessages(this) // Pass lifecycle owner

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
                        // MainViewModel instance is already available via 'viewModel' property
                        MainScreen(
                            viewModel = viewModel, // Pass the Activity-scoped ViewModel
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
        // Consider making this asynchronous if tokenDataStore.token.first() could block
        return runBlocking {
            val token = tokenDataStore.token.first()
            if (token.isNullOrEmpty()) "registration" else "main"
        }
    }

    private fun startScreenCapture(callback: (resultCode: Int, data: Intent) -> Unit) {
        val mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        //onScreenCaptureResult = callback // Store the callback
        Log.d("MainActivity", "Launching screen capture permission intent...")
        screenCaptureLauncher.launch(captureIntent)
    }

    private fun stopScreenCapture() {
        // This should stop the ScreenSharingService
        val intent = ScreenSharingService.getStopIntent(this)
        try {
            stopService(intent) // Use try-catch if service might not be running
            Log.d("MainActivity", "Screen sharing service stop requested.")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error stopping screen sharing service", e)
        }
    }

    private fun openDirectoryPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        // You can add EXTRA_INITIAL_URI if you want to suggest a starting directory
        // For example, to start at the root of external storage (though user can navigate away):
        // if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        //    val storageManager = getSystemService(Context.STORAGE_SERVICE) as StorageManager
        //    val primaryVolume = storageManager.primaryStorageVolume
        //    intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, primaryVolume.createOpenDocumentTreeIntent()?.getParcelableExtra<Uri>(DocumentsContract.EXTRA_INITIAL_URI))
        // }
        try {
            directoryPickerLauncher.launch(intent)
            Log.d("MainActivity", "SAF Directory picker launched.")
        } catch (e: ActivityNotFoundException) {
            Log.e("MainActivity", "Could not launch directory picker: No app can handle ACTION_OPEN_DOCUMENT_TREE.", e)
            Toast.makeText(this, "No application available to pick a directory.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "Could not launch directory picker due to other error.", e)
            Toast.makeText(this, "Failed to open directory picker.", Toast.LENGTH_SHORT).show()
        }
    }
}
