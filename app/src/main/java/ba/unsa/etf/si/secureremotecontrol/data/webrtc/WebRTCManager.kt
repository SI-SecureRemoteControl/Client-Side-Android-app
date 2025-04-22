package ba.unsa.etf.si.secureremotecontrol.data.webrtc

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
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
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    // Key method: starts screen capture for an incoming connection
    fun startScreenCapture(resultCode: Int, data: Intent, fromId: String) {
        if (data == null) {
            Log.e(TAG, "Invalid resultData. Cannot start screen capture.")
            return
        }

        try {
            // Ensure any existing capture is stopped first
            webRTCService.stopScreenCapture()

            // Start new capture (this will set up the peer connection)
            webRTCService.startScreenCapture(resultCode, data, fromId)
            Log.d(TAG, "Screen sharing initiated for user: $fromId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start screen capture", e)
            throw e  // Re-throw so caller knows about the failure
        }
    }

    fun stopScreenCapture() {
        try {
            webRTCService.stopScreenCapture()
            Log.d(TAG, "Screen capture stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping screen capture", e)
        }
    }

    fun release() {
        try {
            webRTCService.release()
            Log.d(TAG, "WebRTC resources released")
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

    // FIXED: Changed parameter to correctly identify this as an offer
    fun confirmSessionAndStartStreaming(fromId: String, sdpOffer: String) {
        Log.d(TAG, "[confirmSessionAndStartStreaming] Handling remote offer from $fromId")
        try {
            // IMPORTANT FIX: Properly identify this as an "offer" not an "answer"
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

            // Check if payload is nested (common in some WebSocket frameworks)
            val payloadJson = when {
                payload is JSONObject -> payload
                payload.toString().contains("parsedMessage") -> {
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
                }
                else -> JSONObject(payload.toString())
            }

            // Log the final payload for debugging
            Log.d(TAG, "Parsed payload: $payloadJson")

            when (type.lowercase()) {
                "offer" -> {
                    val sdp = if (payloadJson.has("sdp")) {
                        payloadJson.getString("sdp")
                    } else if (payloadJson.has("parsedMessage")) {
                        val parsedMsg = payloadJson.getJSONObject("parsedMessage")
                        val innerPayload = parsedMsg.getJSONObject("payload")
                        innerPayload.getString("sdp")
                    } else {
                        Log.e(TAG, "Could not find SDP in payload: $payloadJson")
                        return
                    }

                    if (sdp.isNotEmpty()) {
                        Log.d(TAG, "Received offer from $fromId, processing...")
                        webRTCService.handleRemoteSessionDescription("offer", sdp, fromId)
                    } else {
                        Log.e(TAG, "Received offer without SDP: $payloadJson")
                    }
                }
                "answer" -> {
                    val sdp = payloadJson.optString("sdp", "")
                    if (sdp.isNotEmpty()) {
                        webRTCService.handleRemoteSessionDescription("answer", sdp, fromId)
                    } else {
                        Log.e(TAG, "Received answer without SDP: $payloadJson")
                    }
                }
                "ice-candidate" -> {
                    // Extract ICE candidate info
                    val candidate = if (payloadJson.has("candidate")) {
                        payloadJson.getString("candidate")
                    } else if (payloadJson.has("parsedMessage")) {
                        val parsedMsg = payloadJson.getJSONObject("parsedMessage")
                        val innerPayload = parsedMsg.getJSONObject("payload")
                        innerPayload.getString("candidate")
                    } else {
                        Log.e(TAG, "Could not find candidate in payload: $payloadJson")
                        return
                    }

                    val sdpMid = payloadJson.optString("sdpMid", "0")
                    val sdpMLineIndex = payloadJson.optInt("sdpMLineIndex", 0)

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

    fun createAnswerForPeer(peerId: String) {
        try {
            Log.d(TAG, "Manually creating answer for $peerId")
            webRTCService.createAndSendAnswer(peerId)
        } catch (e: Exception) {
            Log.e(TAG, "Error manually creating answer", e)
        }
    }
}