package ba.unsa.etf.si.secureremotecontrol.service

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okio.ByteString
import org.json.JSONObject // Use a JSON library
import java.util.concurrent.TimeUnit

class MyWebSocketClient {

    private var webSocket: WebSocket? = null
    private val client: OkHttpClient
    private val TAG = "WebSocketClient"
    private val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null
    private val PING_INTERVAL_MS = 60000L // 1 minute

    private val MAX_RETRY_ATTEMPTS = 5
    private val RETRY_DELAY_MS = 5000L // 5s

    private var retryCount = 0

    // Define listener interface (optional but good practice)
    interface WebSocketListenerCallback {
        fun onWebSocketOpen()
        fun onWebSocketMessage(text: String) // Can be JSON
        fun onWebSocketClosing(code: Int, reason: String)
        fun onWebSocketFailure(t: Throwable, response: Response?)
    }

    var listenerCallback: WebSocketListenerCallback? = null

    init {
        // Configure OkHttpClient - Removed automatic pingInterval
        // You CAN keep OkHttp's pingInterval for low-level checks if you want redundancy,
        // but the application heartbeat below is now the primary mechanism for triggering DB updates.
        client = OkHttpClient.Builder()
            // .pingInterval(1, TimeUnit.MINUTES) // Commented out or removed
            .build()
    }

    private fun retryConnection(url: String, deviceId: String) {
        if (retryCount < MAX_RETRY_ATTEMPTS) {
            retryCount++
            Log.d(TAG, "Retrying connection attempt #$retryCount")
            // Wait for a specified time before retrying
            clientScope.launch {
                delay(RETRY_DELAY_MS)
                connect(url, deviceId)
            }
        } else {
            Log.e(TAG, "Max retries reached. Connection failed.")
            listenerCallback?.onWebSocketFailure(Throwable("Max retries reached"), null)
        }
    }

    fun connect(url: String, deviceId: String) { // Pass deviceId for heartbeats
        if (webSocket != null) {
            Log.w(TAG, "Already connected or connecting.")
            return
        }
        Log.d(TAG, "Attempting to connect to: $url")
        val request = Request.Builder().url(url).build()
        // Pass deviceId to the listener or store it in the class if needed elsewhere
        webSocket = client.newWebSocket(request, SocketListener(deviceId, url))
    }

    fun sendMessage(message: String): Boolean {
        return webSocket?.send(message) ?: run {
            Log.e(TAG, "Cannot send message, WebSocket is not connected.")
            false
        }
    }

    // Function to send the application-level heartbeat
    private fun sendHeartbeatMessage(deviceId: String) {
        val heartbeatMsg = JSONObject()
        heartbeatMsg.put("type", "heartbeat")
        heartbeatMsg.put("deviceId", deviceId)
        heartbeatMsg.put("timestamp", System.currentTimeMillis())

        val sent = sendMessage(heartbeatMsg.toString())
        if (sent) {
            Log.d(TAG, "Sent heartbeat for device: $deviceId")
        } else {
            Log.w(TAG, "Failed to send heartbeat for device: $deviceId (WebSocket not ready?)")
        }
    }


    fun disconnect(code: Int, reason: String) {
        heartbeatJob?.cancel() // Stop sending heartbeats
        heartbeatJob = null
        webSocket?.close(code, reason)
        webSocket = null
        Log.d(TAG, "Disconnect requested.")
    }

    fun shutdown() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        webSocket?.cancel()
        webSocket = null
        clientScope.cancel() // Cancel coroutine scope
        // client.dispatcher.executorService.shutdown()
        Log.d(TAG, "WebSocket client shut down.")
    }


    private inner class SocketListener(
        private val deviceId: String,
        private val url: String
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket connection opened for device: $deviceId")
            listenerCallback?.onWebSocketOpen()

            // Start sending heartbeats ONLY after connection is open
            heartbeatJob?.cancel() // Cancel previous job if any
            heartbeatJob = clientScope.launch {
                while (isActive) {
                    sendHeartbeatMessage(deviceId)
                    delay(PING_INTERVAL_MS)
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "Received text message: $text")
            // Handle server messages if needed

            try {
                val data = JSONObject(text)

                when (data.getString("type")) {

                    "signal" -> {
                        val from = data.getString("from")
                        val payload = data.getJSONObject("payload")
                        Log.d(TAG, "Received signal from $from with payload: $payload")

                        listenerCallback?.onWebSocketMessage("Received signal from $from with payload: $payload")
                    }

                    "status" -> {
                        val deviceId = data.getString("deviceId")
                        val status = data.getString("status")
                        Log.d(TAG, "Device $deviceId status: $status")

                        listenerCallback?.onWebSocketMessage("Device $deviceId is $status")
                    }

                    "disconnect" -> {
                        val deviceId = data.getString("deviceId")
                        Log.d(TAG, "Device $deviceId has disconnected")

                        listenerCallback?.onWebSocketMessage("Device $deviceId has disconnected")
                    }

                    else -> {
                        Log.w(TAG, "Unknown message type: ${data.getString("type")}")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error parsing message: $e")
            }

        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            Log.d(TAG, "Received binary message: ${bytes.hex()}")
            // Handle binary message if needed
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closing: Code=$code, Reason=$reason")
            heartbeatJob?.cancel() // Stop sending heartbeats
            heartbeatJob = null
            webSocket.close(1000, null)
            this@MyWebSocketClient.webSocket = null
            listenerCallback?.onWebSocketClosing(code, reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket connection failure: ${t.message}", t)
            heartbeatJob?.cancel() // Stop sending heartbeats
            heartbeatJob = null
            this@MyWebSocketClient.webSocket = null
            listenerCallback?.onWebSocketFailure(t, response)

            retryConnection(url, deviceId)
        }
    }
}