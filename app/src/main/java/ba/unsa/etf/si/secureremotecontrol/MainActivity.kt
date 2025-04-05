package ba.unsa.etf.si.secureremotecontrol

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import ba.unsa.etf.si.secureremotecontrol.ui.theme.SecureRemoteControlTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import androidx.work.*
import java.util.concurrent.TimeUnit

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var webRTCClient: WebRTCClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize WebRTC and send first heartbeat
        webRTCClient.initialize()
        webRTCClient.connect()
        startHeartbeatWorker()

        // Compose UI setup
        setContent {
            SecureRemoteControlTheme {
                MainScreen()
            }
        }
    }

    private fun startHeartbeatWorker() {
        val deviceId = "your_device_id_here"  // Get your device ID from SharedPreferences or another source
        Log.d("MainActivity", "Enqueuing heartbeat worker with deviceId: $deviceId")  // Add log here
        val workRequest = OneTimeWorkRequestBuilder<HeartbeatWorker>()
            .setInputData(workDataOf("deviceId" to deviceId))
            .setInitialDelay(0, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "heartbeat_chain",
            ExistingWorkPolicy.REPLACE, //.KEEP check
            workRequest
        )

        WorkManager.getInstance(applicationContext)
            .getWorkInfoByIdLiveData(workRequest.id)
            .observe(this) { info ->
                Log.d("MainActivity", "Worker state: ${info.state}")
            }

        Log.d("MainActivity", "Heartbeat worker enqueued.")  // Add log here
    }
}

@Composable
fun MainScreen() {
    Column {
        Text(text = "Welcome to Secure Remote Control!")
        Button(onClick = { startRemoteControl() }) {
            Text("Start Remote Control")
        }
    }
}

fun startRemoteControl() {
    Log.d("MainActivity", "Remote control started.")
    // This can later trigger WebRTC session logic, handled via injected WebRTCClient
}
