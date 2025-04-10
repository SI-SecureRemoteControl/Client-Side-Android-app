package ba.unsa.etf.si.secureremotecontrol.data.api

import ba.unsa.etf.si.secureremotecontrol.data.models.Device
import kotlinx.coroutines.flow.Flow
import okhttp3.WebSocket

interface WebSocketService {
    fun connectWebSocket(): WebSocket
    fun observeMessages(): Flow<String>
    fun sendRegistration(device: Device)
    fun disconnect()
    fun startHeartbeat(deviceId: String)
    fun stopHeartbeat()
}