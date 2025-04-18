package ba.unsa.etf.si.secureremotecontrol.data.api

import ba.unsa.etf.si.secureremotecontrol.data.models.Device
import kotlinx.coroutines.flow.Flow
import okhttp3.WebSocket

interface WebSocketService {
    fun connectWebSocket(): WebSocket
    fun observeMessages(): Flow<String>
    fun sendRegistration(device: Device)
    fun sendFinalConformation(from: String, token: String, decision: Boolean)
    fun sendSessionRequest(from: String, token: String)
    fun disconnect()
    fun startHeartbeat(deviceId: String)
    fun stopHeartbeat()
    fun sendRawMessage(message: String)
    fun observeRtcMessages(): Flow<RtcMessage>
}

data class RtcMessage(
    val type: String,
    val fromId: String,
    val toId: String,
    val payload: Any
)