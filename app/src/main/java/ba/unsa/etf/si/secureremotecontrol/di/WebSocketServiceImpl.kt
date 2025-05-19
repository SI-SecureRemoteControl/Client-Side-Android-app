
package ba.unsa.etf.si.secureremotecontrol.data.websocket

import android.util.Log
import ba.unsa.etf.si.secureremotecontrol.data.api.WebSocketService
import ba.unsa.etf.si.secureremotecontrol.data.models.Device
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import ba.unsa.etf.si.secureremotecontrol.data.api.RtcMessage
import ba.unsa.etf.si.secureremotecontrol.data.fileShare.*
import com.google.gson.JsonObject

@Singleton
class WebSocketServiceImpl @Inject constructor(
    private val client: OkHttpClient,
    private val gson: Gson
) : WebSocketService {

    private val TAG = "WebSocketService"
    private var heartbeatJob: Job? = null
    private val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val HEARTBEAT_SEND_INTERVAL_MS = 25000L
    private val MAX_RETRY_ATTEMPTS = 5
    private val RETRY_DELAY_MS = 5000L // 5s
    private var retryCount = 0

    // Single WebSocket instance for all operations
    private var webSocket: WebSocket? = null
    private var isConnected = false

    // Message channel for broadcasting to all collectors
    private val messageChannel = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    override fun connectWebSocket(): WebSocket {
        if (isConnected && webSocket != null) {
            Log.d(TAG, "WebSocket is already connected.")
            return webSocket!!
        }

        Log.d(TAG, "Creating new WebSocket connection")
        val request = Request.Builder().url("wss://remote-control-gateway-production.up.railway.app/").build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connection opened successfully")
                isConnected = true
                retryCount = 0
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Message received: $text")
                // Emit to the shared flow for all collectors
                clientScope.launch {
                    messageChannel.emit(text)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket connection failed: ${t.message}")
                isConnected = false
                retryConnection()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $reason")
                isConnected = false
            }
        })

        return webSocket!!
    }

    override fun observeMessages(): Flow<String> {
        // Ensure we have a WebSocket connection
        if (!isConnected) {
            Log.d(TAG, "WebSocket not connected, connecting now")
            connectWebSocket()
        }

        Log.d(TAG, "Setting up message observation flow")
        // Return the shared message channel
        return messageChannel.asSharedFlow()
    }

    override fun sendRawMessage(message: String) {
        if (!isConnected) {
            Log.d(TAG, "WebSocket not connected, connecting now before sending message")
            connectWebSocket()
        }

        val success = webSocket?.send(message) ?: false
        Log.d(TAG, "Sent raw message, success: $success")
    }

    override fun sendRegistration(device: Device) {
        val message = gson.toJson(mapOf(
            "type" to "register",
            "deviceId" to device.deviceId,
            "registrationKey" to device.registrationKey,
            "model" to device.model,
            "osVersion" to device.osVersion
        ))
        sendRawMessage(message)
        startHeartbeat(device.deviceId)
    }

    override fun sendFinalConformation(from: String, token: String, decision: Boolean) {
        val message = gson.toJson(mapOf(
            "type" to "session_final_confirmation",
            "from" to from,
            "token" to token,
            "decision" to if (decision) "accepted" else "rejected"
        ))
        Log.d(TAG, "Final confirmation sent: $message")
        sendRawMessage(message)
    }

    override fun disconnect() {
        stopHeartbeat()
        webSocket?.close(1000, "Closing WebSocket")
        webSocket = null
        isConnected = false
    }

    fun sendDeregistration(device: Device) {
        val message = gson.toJson(mapOf(
            "type" to "deregister",
            "deviceId" to device.deviceId
        ))
        sendRawMessage(message)
        stopHeartbeat()
    }

    override fun startHeartbeat(deviceId: String) {
        heartbeatJob?.cancel()
        heartbeatJob = clientScope.launch {
            delay(500)
            while (isActive) {
                sendStatusHeartbeatMessage(deviceId)
                delay(HEARTBEAT_SEND_INTERVAL_MS)
            }
        }
    }
    override fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    override fun sendSessionRequest(from: String, token: String) {
        if (!isConnected) {
            Log.e(TAG, "Cannot send session request: WebSocket is not connected.")
            connectWebSocket()
        }
        val message = gson.toJson(mapOf(
            "type" to "session_request",
            "from" to from,
            "token" to token
        ))
        val success = webSocket?.send(message) ?: false
        Log.d(TAG, "Session request sent from $from, success: $success")
    }

    // FIXED: Only process known RTC message types and check for payload existence
    override fun observeRtcMessages(): Flow<RtcMessage> = observeMessages()
        .mapNotNull { message ->
            try {
                val jsonObject = JSONObject(message)
                val type = jsonObject.optString("type", "")

                // Only process RTC-specific message types
                if (type == "offer" || type == "answer" || type == "ice-candidate") {
                    val fromId = jsonObject.optString("fromId", "")
                    val toId = jsonObject.optString("toId", "")

                    // Check if payload exists before trying to access it
                    if (jsonObject.has("payload")) {
                        val payload = jsonObject.getJSONObject("payload")
                        RtcMessage(type, fromId, toId, payload)
                    } else {
                        Log.w(TAG, "RTC message is missing payload: $message")
                        null
                    }
                } else {
                    // Not an RTC message, skip it silently
                    null
                }
            } catch (e: Exception) {
                // Only log as debug for non-RTC messages to avoid log spam
                null
            }
        }

    private fun sendStatusHeartbeatMessage(deviceId: String) {
        val statusMsg = JSONObject().apply {
            put("type", "status")
            put("deviceId", deviceId)
            put("status", "active")
        }
        val sent = webSocket?.send(statusMsg.toString()) ?: false
        if (sent) {
            Log.d(TAG, "Sent status/heartbeat for device: $deviceId")
        } else {
            Log.w(TAG, "Failed to send status/heartbeat for device: $deviceId (WebSocket not ready?)")
        }
    }

    private fun retryConnection() {
        if (retryCount < MAX_RETRY_ATTEMPTS) {
            retryCount++
            Log.d(TAG, "Retrying connection #$retryCount after ${RETRY_DELAY_MS}ms")
            clientScope.launch {
                delay(RETRY_DELAY_MS)
                connectWebSocket()
            }
        } else {
            Log.e(TAG, "Max retry attempts reached ($MAX_RETRY_ATTEMPTS). Connection failed.")
            retryCount = 0
        }
    }

    override fun observeClickEvents(): Flow<Pair<Float, Float>> = observeMessages()
        .mapNotNull { message ->
            try {
                val jsonObject = JSONObject(message)
                if (jsonObject.getString("type") == "click") {
                    val payload = jsonObject.getJSONObject("payload")
                    val x = payload.getDouble("x").toFloat()
                    val y = payload.getDouble("y").toFloat()
                    Pair(x, y)
                } else null
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing click event", e)
                null
            }
        }

    // Sending messages from Android
    override fun sendRequestSessionFileshare(deviceId: String, sessionId: String) {
        val message = RequestSessionFileshareMessage(
            deviceId = deviceId,
            sessionId = sessionId
        )
        sendRawMessage(gson.toJson(message))
        Log.d(TAG, "Sent request_session_fileshare: ${gson.toJson(message)}")
    }

    override fun sendBrowseResponse(deviceId: String, sessionId: String, path: String, entries: List<FileEntry>) {
        val message = BrowseResponseMessage(
            deviceId = deviceId,
            sessionId = sessionId,
            path = path,
            entries = entries
        )
        sendRawMessage(gson.toJson(message))
        Log.d(TAG, "Sent browse_response: ${gson.toJson(message)}")
    }

    override fun sendUploadStatus(deviceId: String, sessionId: String, status: String, message: String?, path: String?, fileName: String) {
        val msg = UploadStatusMessage(
            deviceId = deviceId,
            sessionId = sessionId,
            status = status,
            message = message,
            path = path,
            fileName = fileName
        )
        sendRawMessage(gson.toJson(msg))
        Log.d(TAG, "Sent upload_status: ${gson.toJson(msg)}")
    }

    override fun sendDownloadResponse(deviceId: String, sessionId: String, downloadUrl: String) {
        val message = DownloadResponseMessage(
            deviceId = deviceId,
            sessionId = sessionId,
            downloadUrl = downloadUrl
        )
        sendRawMessage(gson.toJson(message))
        Log.d(TAG, "Sent download_response: ${gson.toJson(message)}")
    }

    // Observing messages for Android
    // Generic helper to parse messages of a specific type
    private inline fun <reified T> observeSpecificMessage(messageType: String): Flow<T> = observeMessages()
        .mapNotNull { jsonString ->
            try {
                // A quick check for type before full parsing
                if (!jsonString.contains("\"type\":\"$messageType\"")) {
                    return@mapNotNull null
                }

                val jsonObject = JSONObject(jsonString)
                if (jsonObject.optString("type", "") == messageType) {
                    gson.fromJson(jsonString, T::class.java)
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing $messageType message: $jsonString", e)
                null
            }
        }

    override fun observeDecisionFileShare(): Flow<DecisionFileshareMessage> =
        observeSpecificMessage("decision_fileshare")

    override fun observeBrowseRequest(): Flow<BrowseRequestMessage> =
        observeSpecificMessage("browse_request")

    override fun observeUploadFiles(): Flow<UploadFilesMessage> =
        observeSpecificMessage("upload_files")

    override fun observeDownloadRequest(): Flow<DownloadRequestMessage> =
        observeSpecificMessage("download_request")
}