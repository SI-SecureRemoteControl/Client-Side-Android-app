package ba.unsa.etf.si.secureremotecontrol // Or your presentation package

import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme // Import to check system theme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
// Remove direct Color imports if not needed for overrides
// import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ba.unsa.etf.si.secureremotecontrol.service.MyWebSocketClient
// Import your app's Compose theme (assuming it's in ui.theme package)
import ba.unsa.etf.si.secureremotecontrol.ui.theme.SecureRemoteControlTheme // Adjust import if needed

class MainActivity : ComponentActivity() {

    private lateinit var wsClient: MyWebSocketClient
    private val TAG = "MainActivity"
    private val WEBSOCKET_URL = "ws://192.168.1.15:8765" // Use WSS! Change as needed

    // State variables
    private val connectionStatus = mutableStateOf("Disconnected")
    private val lastMessage = mutableStateOf<String?>(null)
    private val isConnecting = mutableStateOf(false)
    private val deviceIdState = mutableStateOf("loading...")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate called")

        wsClient = MyWebSocketClient()
        setupWebSocketCallbacks()

        deviceIdState.value = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_device"
        Log.d(TAG, "Using Device ID: ${deviceIdState.value}")

        setContent {
            // Apply your app's Material Theme here.
            // This function likely detects system theme (light/dark) internally.
            SecureRemoteControlTheme { // Use your app's theme function
                // Surface now uses the theme's background color automatically
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background // Use theme background
                ) {
                    MainScreen(
                        status = connectionStatus.value,
                        lastMsg = lastMessage.value,
                        isConnecting = isConnecting.value,
                        deviceId = deviceIdState.value,
                        onConnect = ::connectWebSocket,
                        onDisconnect = ::disconnectWebSocket
                    )
                }
            }
        }
    }

    // --- setupWebSocketCallbacks, connectWebSocket, disconnectWebSocket, onDestroy remain the same ---
    private fun setupWebSocketCallbacks() {
        wsClient.listenerCallback = object : MyWebSocketClient.WebSocketListenerCallback {
            override fun onWebSocketOpen() {
                Log.i(TAG, "Callback: WebSocket Connected!")
                isConnecting.value = false
                connectionStatus.value = "Connected"
            }

            override fun onWebSocketMessage(text: String) {
                Log.d(TAG, "Callback: Message Received: $text")
                lastMessage.value = "Received: $text" // Update state
            }

            override fun onWebSocketClosing(code: Int, reason: String) {
                Log.w(TAG, "Callback: WebSocket Closing: $code / $reason")
                isConnecting.value = false
                connectionStatus.value = "Closing..."
            }

            override fun onWebSocketFailure(t: Throwable, response: okhttp3.Response?) {
                val errorMsg = t.message ?: "Unknown error"
                Log.e(TAG, "Callback: WebSocket Failure: $errorMsg", t)
                isConnecting.value = false
                connectionStatus.value = "Failed: $errorMsg" // Show specific error
            }
        }
    }

    private fun connectWebSocket() {
        if (connectionStatus.value != "Connected" && !isConnecting.value) {
            Log.d(TAG, "UI: Attempting WebSocket connection...")
            isConnecting.value = true
            connectionStatus.value = "Connecting..."
            lastMessage.value = null // Clear last message on new connection attempt
            wsClient.connect(WEBSOCKET_URL, deviceIdState.value)
        }
    }

    private fun disconnectWebSocket() {
        Log.d(TAG, "UI: Disconnecting WebSocket...")
        wsClient.disconnect(1000, "User Action")
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy called - shutting down WebSocket")
        wsClient.shutdown()
        super.onDestroy()
    }
}


// --- Updated MainScreen Composable using Theme Colors ---
@Composable
fun MainScreen(
    status: String,
    lastMsg: String?,
    isConnecting: Boolean,
    deviceId: String,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    // Use theme colors for text and components
    val statusColor = when {
        status == "Connected" -> MaterialTheme.colorScheme.primary
        status.startsWith("Failed:") -> MaterialTheme.colorScheme.error
        isConnecting -> MaterialTheme.colorScheme.secondary
        else -> LocalContentColor.current // Default text color
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "WebSocket Status: $status",
            style = MaterialTheme.typography.headlineSmall,
            color = statusColor // Use dynamic color based on status
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Device ID: $deviceId",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant // A slightly muted text color from theme
        )
        Spacer(modifier = Modifier.height(24.dp))

        // Conditional UI based on status
        when {
            isConnecting -> {
                CircularProgressIndicator(modifier = Modifier.padding(bottom = 8.dp))
                Text("Connecting...") // Text color will be default MaterialTheme.colorScheme.onSurface
            }
            status == "Connected" -> {
                Button(
                    onClick = onDisconnect,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    // Text color inside button uses theme's colorScheme.onError
                    Text("Disconnect")
                }
                Spacer(modifier = Modifier.height(16.dp))
                lastMsg?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        // Default text color (MaterialTheme.colorScheme.onSurface)
                    )
                }
            }
            else -> { // Disconnected, Failed, Closing, etc.
                Button(onClick = onConnect) {
                    // Text color inside button uses theme's colorScheme.onPrimary
                    Text("Connect WebSocket")
                }
                // Show last error message if status indicates failure
                if (status.startsWith("Failed:")) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        status, // Display the failure message
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error // Use error color explicitly
                    )
                }
            }
        }
    }
}