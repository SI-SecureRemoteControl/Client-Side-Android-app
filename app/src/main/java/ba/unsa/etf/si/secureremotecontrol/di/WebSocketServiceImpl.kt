package ba.unsa.etf.si.secureremotecontrol.data.websocket

import android.util.Log
import ba.unsa.etf.si.secureremotecontrol.data.api.WebSocketService
import ba.unsa.etf.si.secureremotecontrol.data.models.Device
import com.google.gson.Gson
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebSocketServiceImpl @Inject constructor(
    private val client: OkHttpClient,
    private val gson: Gson
) : WebSocketService {
    private var webSocket: WebSocket? = null

    override fun connectWebSocket(): WebSocket {
        val request = Request.Builder()
            .url("wss://remote-control-gateway.onrender.com/")
            .build()

        return client.newWebSocket(request, createWebSocketListener())
            .also { webSocket = it }
    }

    override fun observeMessages(): Flow<String> = callbackFlow {
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                trySend(text)
            }
        }

        webSocket = client.newWebSocket(
            Request.Builder().url("wss://remote-control-gateway.onrender.com/").build(),
            listener
        )

        awaitClose {
            webSocket?.close(1000, "Flow closed")
        }
    }

    override fun sendRegistration(device: Device) {
        val message = gson.toJson(mapOf(
            "type" to "register",
            "deviceId" to device.deviceId,
            "registrationKey" to device.registrationKey,
            "name" to device.name,
//            "model" to device.model,
//            "osVersion" to device.osVersion,
//            "networkType" to device.networkType,
//            "ipAddress" to device.ipAddress,
//            "deregistrationKey" to device.deregistrationKey
        ))
        webSocket?.send(message)
    }

    override fun disconnect() {
        webSocket?.close(1000, "Disconnecting")
        webSocket = null
    }
}


private fun createWebSocketListener() = object : WebSocketListener() {
    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
        Log.d("WebSocket", "Connection opened")
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        Log.d("WebSocket", "Message received: $text")
        // Handle incoming messages
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        Log.d("WebSocket", "Connection closing: $code / $reason")
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        Log.d("WebSocket", "Connection closed: $code / $reason")
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
        Log.e("WebSocket", "Connection failed", t)
    }
}