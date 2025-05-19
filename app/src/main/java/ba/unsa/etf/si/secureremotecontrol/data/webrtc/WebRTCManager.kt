package ba.unsa.etf.si.secureremotecontrol.data.webrtc

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import ba.unsa.etf.si.secureremotecontrol.data.api.WebSocketService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import org.webrtc.PeerConnection

@Singleton
class WebRTCManager @Inject constructor(
    private val webRTCService: WebRTCService,
    private val webSocketService: WebSocketService
) {
    private val TAG = "WebRTCManager"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    // Store pending offers that arrive before peer connection is ready
    private var pendingOffer: Pair<String, String>? = null
    private var connectionInitialized = false

    fun startScreenCapture(resultCode: Int, data: Intent, fromId: String) {
        if (data == null) {
            Log.e(TAG, "Invalid resultData. Cannot start screen capture.")
            return
        }

        try {
            // Reset connection state
            connectionInitialized = false

            // Ensure any existing capture is stopped first
            webRTCService.stopScreenCapture()

            // Start new capture
            webRTCService.startScreenCapture(resultCode, data, fromId)

            // Mark connection as initialized
            connectionInitialized = true
            Log.d(TAG, "Screen sharing initiated for user: $fromId")

            // If we have a pending offer, process it now
            processPendingOffer()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start screen capture", e)
            throw e  // Re-throw so caller knows about the failure
        }
    }

    private fun processPendingOffer() {
        pendingOffer?.let { (fromId, sdp) ->
            Log.d(TAG, "Processing pending offer from $fromId")
            mainHandler.post {
                webRTCService.handleRemoteSessionDescription("offer", sdp, fromId)
            }
            pendingOffer = null
        }
    }
    fun storePendingOffer(fromId: String, sdpOffer: String) {
        pendingOffer = Pair(fromId, sdpOffer)
        Log.d(TAG, "Stored pending offer from $fromId for later processing")
    }

    fun stopScreenCapture() {
        try {
            connectionInitialized = false
            pendingOffer = null
            webRTCService.stopScreenCapture()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping screen capture", e)
        }
    }

    fun release() {
        try {
            connectionInitialized = false
            pendingOffer = null
            webRTCService.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WebRTC resources", e)
        }
    }

    fun startObservingRtcMessages(lifecycleOwner: LifecycleOwner) {
        lifecycleOwner.lifecycleScope.launch {
            try {
                webSocketService.observeRtcMessages().collect { rtcMessage ->
                    handleRtcMessage(rtcMessage.type, rtcMessage.fromId, rtcMessage.payload)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error observing RTC messages", e)
            }
        }
    }

    fun confirmSessionAndStartStreaming(fromId: String, sdpOffer: String) {
        Log.d(TAG, "[confirmSessionAndStartStreaming] Handling remote offer from $fromId")

        if (!connectionInitialized) {
            // Store offer for later processing
            Log.d(TAG, "Connection not ready, storing offer for later")
            pendingOffer = Pair(fromId, sdpOffer)
            return
        }

        try {
            // IMPORTANT: This is an offer, not an answer
            webRTCService.handleRemoteSessionDescription("offer", sdpOffer, fromId)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling SDP offer", e)
            throw e
        }
    }

    fun getScreenCaptureIntent(activity: Activity): Intent {
        val mediaProjectionManager = activity.getSystemService(MediaProjectionManager::class.java)
        return mediaProjectionManager.createScreenCaptureIntent()
    }

    fun handleRtcMessage(type: String, fromId: String, payload: Any) {
        try {
            Log.d(TAG, "Handling RTC message of type: $type from: $fromId")

            // Extract payload from potentially nested structure
            val payloadJson = extractPayload(payload)
            Log.d(TAG, "Parsed payload: $payloadJson")

            when (type.lowercase()) {
                "offer" -> {
                    val sdp = extractSdp(payloadJson)
                    if (sdp.isNotEmpty()) {
                        Log.d(TAG, "Received offer from $fromId, processing...")

                        if (!connectionInitialized) {
                            // Store offer for later processing
                            Log.d(TAG, "Connection not ready, storing offer for later")
                            pendingOffer = Pair(fromId, sdp)
                        } else {
                            webRTCService.handleRemoteSessionDescription("offer", sdp, fromId)
                        }
                    } else {
                        Log.e(TAG, "Received offer without SDP: $payloadJson")
                    }
                }
                "answer" -> {
                    val sdp = extractSdp(payloadJson)
                    if (sdp.isNotEmpty()) {
                        webRTCService.handleRemoteSessionDescription("answer", sdp, fromId)
                    } else {
                        Log.e(TAG, "Received answer without SDP: $payloadJson")
                    }
                }
                "ice-candidate" -> {
                    val candidate = extractIceCandidate(payloadJson)
                    val sdpMid = extractIceSdpMid(payloadJson)
                    val sdpMLineIndex = extractIceSdpMLineIndex(payloadJson)

                    if (candidate.isNotEmpty()) {
                        Log.d(TAG, "Processing ICE candidate from $fromId")
                        webRTCService.handleRemoteIceCandidate(candidate, sdpMid, sdpMLineIndex)
                    } else {
                        Log.d(TAG, "Received empty ICE candidate (end of candidates)")
                    }
                }
                else -> {
                    Log.w(TAG, "Unhandled RTC message type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling RTC message: $type", e)
        }
    }

    // Helper functions to extract values from the potentially nested payload
    private fun extractPayload(payload: Any): JSONObject {
        return when {
            payload is JSONObject -> payload
            payload.toString().contains("parsedMessage") -> {
                try {
                    val outerJson = JSONObject(payload.toString())
                    if (outerJson.has("parsedMessage")) {
                        val parsedMessage = outerJson.getJSONObject("parsedMessage")
                        if (parsedMessage.has("payload")) {
                            parsedMessage.getJSONObject("payload")
                        } else {
                            outerJson
                        }
                    } else {
                        outerJson
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error extracting payload: ${e.message}")
                    JSONObject(payload.toString())
                }
            }
            else -> JSONObject(payload.toString())
        }
    }

    private fun extractSdp(json: JSONObject): String {
        return when {
            json.has("sdp") -> json.getString("sdp")
            else -> ""
        }
    }

    private fun extractIceCandidate(json: JSONObject): String {
        return when {
            json.has("candidate") -> json.getString("candidate")
            else -> ""
        }
    }

    private fun extractIceSdpMid(json: JSONObject): String {
        return when {
            json.has("sdpMid") -> json.getString("sdpMid")
            else -> "0"
        }
    }

    private fun extractIceSdpMLineIndex(json: JSONObject): Int {
        return when {
            json.has("sdpMLineIndex") -> json.getInt("sdpMLineIndex")
            else -> 0
        }
    }

    fun createAnswerForPeer(peerId: String) {
        if (!connectionInitialized) {
            Log.e(TAG, "Cannot create answer - connection not initialized")
            return
        }

        try {
            Log.d(TAG, "Manually creating answer for $peerId")
            webRTCService.createAndSendAnswer(peerId)
        } catch (e: Exception) {
            Log.e(TAG, "Error manually creating answer", e)
        }
    }
}