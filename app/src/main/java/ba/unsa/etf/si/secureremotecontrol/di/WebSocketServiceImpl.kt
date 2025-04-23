package ba.unsa.etf.si.secureremotecontrol.data.websocket

import android.util.Log
import ba.unsa.etf.si.secureremotecontrol.data.api.WebSocketService
import ba.unsa.etf.si.secureremotecontrol.data.models.Device
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import ba.unsa.etf.si.secureremotecontrol.data.api.RtcMessage
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull

@Singleton
class WebSocketServiceImpl @Inject constructor(
    private val client: OkHttpClient,
    private val gson: Gson
) : WebSocketService {

    private var heartbeatJob: Job? = null
    private val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val HEARTBEAT_SEND_INTERVAL_MS = 25000L
    private val MAX_RETRY_ATTEMPTS = 5
    private val RETRY_DELAY_MS = 5000L // 5s
    private var retryCount = 0

    private var webSocket: WebSocket? = null
    private var isConnected = false

    override fun connectWebSocket(): WebSocket {
        if (isConnected) {
            Log.d("WebSocketService", "WebSocket is already connected.")
            return webSocket!!
        }

        val request = Request.Builder().url("wss://remote-control-gateway-production.up.railway.app/").build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                Log.d("WebSocketService", "WebSocket connected.")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                Log.e("WebSocketService", "WebSocket connection failed: ${t.message}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                Log.d("WebSocketService", "WebSocket closed: $reason")
            }
        })
        return webSocket!!
    }

    override fun observeMessages(): Flow<String> = callbackFlow {
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                trySend(text)
            }
        }

        webSocket = client.newWebSocket(
            Request.Builder().url("wss://remote-control-gateway-production.up.railway.app/").build(),
            listener
        )

        awaitClose {
            webSocket?.close(1000, "Flow closed")
        }
    }
    override fun sendRawMessage(message: String) {
        if (!isConnected) {
            Log.e("WebSocketService", "Cannot send message: WebSocket is not connected.")
            connectWebSocket()
        }
        webSocket?.send(message)
    }
    override fun sendRegistration(device: Device) {
        val message = gson.toJson(mapOf(
            "type" to "register",
            "deviceId" to device.deviceId,
            "registrationKey" to device.registrationKey,
            "model" to device.model,
            "osVersion" to device.osVersion
        ))
        webSocket?.send(message)
        startHeartbeat(device.deviceId)
    }

    override fun sendFinalConformation(from: String, token: String, decision: Boolean) {
        val message = gson.toJson(mapOf(
            "type" to "session_final_confirmation",
            "from" to from,
            "token" to token,
            "decision" to if (decision) "accepted" else "rejected"
        ))
        Log.d("WebSocket", "Final confirmation sent: $message")
        webSocket?.send(message)
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
        webSocket?.send(message)
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
            Log.e("WebSocketService", "Cannot send session request: WebSocket is not connected.")
            return
        }
        val message = gson.toJson(mapOf(
            "type" to "session_request",
            "from" to from,
            "token" to token
        ))
        webSocket?.send(message)
        Log.d("WebSocket", "Session request sent from $from")
    }
    override fun observeRtcMessages(): Flow<RtcMessage> = observeMessages()
        .map { message ->
            try {
                val jsonObject = JSONObject(message)
                val type = jsonObject.getString("type")
                val fromId = jsonObject.optString("fromId", "")
                val toId = jsonObject.optString("toId", "")
                val payload = jsonObject.getJSONObject("payload")

                RtcMessage(type, fromId, toId, payload)
            } catch (e: Exception) {
                Log.e("WebSocketService", "Error parsing RTC message", e)
                null
            }
        }
        .filterNotNull() // Filter out null values

    private fun sendStatusHeartbeatMessage(deviceId: String) {
        val statusMsg = JSONObject().apply {
            put("type", "status")
            put("deviceId", deviceId)
            put("status", "active")
        }
        val sent = webSocket?.send(statusMsg.toString()) ?: false
        if (sent) {
            Log.d("WebSocket", "Sent status/heartbeat for device: $deviceId")
        } else {
            Log.w("WebSocket", "Failed to send status/heartbeat for device: $deviceId (WebSocket not ready?)")
        }
    }

    private fun retryConnection(url: String, deviceId: String) {
        if (retryCount < MAX_RETRY_ATTEMPTS) {
            retryCount++
            Log.d("WebSocket", "Retrying connection #$retryCount for $deviceId after ${RETRY_DELAY_MS}ms")
            clientScope.launch {
                delay(RETRY_DELAY_MS)
                connectWebSocket()
            }
        } else {
            Log.e("WebSocket", "Max retry attempts reached ($MAX_RETRY_ATTEMPTS). Connection failed for $deviceId.")
            retryCount = 0
        }
    }

    private fun createWebSocketListener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            Log.d("WebSocket", "Connection opened")
            retryCount = 0
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d("WebSocket", "Message received: $text")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d("WebSocket", "Connection closing: $code / $reason")
            stopHeartbeat()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d("WebSocket", "Connection closed: $code / $reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
            Log.e("WebSocket", "Connection failed", t)
            stopHeartbeat()
            retryConnection("wss://remote-control-gateway-production.up.railway.app/", "deviceId") // Replace with actual URL and device ID
        }
    }

    fun observeClickEvents(): Flow<Pair<Float, Float>> = observeMessages()
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
                Log.e("WebSocket", "Error parsing click event", e)
                null
            }
        }

}