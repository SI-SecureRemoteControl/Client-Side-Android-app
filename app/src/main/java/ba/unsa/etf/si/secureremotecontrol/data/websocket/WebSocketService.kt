package ba.unsa.etf.si.secureremotecontrol.data.api

import ba.unsa.etf.si.secureremotecontrol.data.models.Device
import kotlinx.coroutines.flow.Flow
import okhttp3.WebSocket
import ba.unsa.etf.si.secureremotecontrol.data.fileShare.*

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
    fun observeClickEvents(): Flow<Pair<Float, Float>>

    // Sending messages from Android
    fun sendRequestSessionFileshare(deviceId: String, sessionId: String)
    fun sendBrowseResponse(deviceId: String, sessionId: String, path: String, entries: List<FileEntry>)
    fun sendUploadStatus(deviceId: String, sessionId: String, status: String, message: String?, path: String?, fileName: String)
    fun sendDownloadResponse(deviceId: String, sessionId: String, downloadUrl: String)

    // Observing messages for Android
    fun observeDecisionFileShare(): Flow<DecisionFileshareMessage>
    fun observeBrowseRequest(): Flow<BrowseRequestMessage>
    fun observeUploadFiles(): Flow<UploadFilesMessage>
    fun observeDownloadRequest(): Flow<DownloadRequestMessage>

}


data class RtcMessage(
    val type: String,
    val fromId: String,
    val toId: String,
    val payload: Any
)