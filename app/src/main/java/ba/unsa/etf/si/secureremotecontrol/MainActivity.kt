package ba.unsa.etf.si.secureremotecontrol

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
                startForegroundService(intent) // Start the foreground service
            } else {
                Log.e("MainActivity", "Screen capture permission denied or invalid data.")
            }
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



}