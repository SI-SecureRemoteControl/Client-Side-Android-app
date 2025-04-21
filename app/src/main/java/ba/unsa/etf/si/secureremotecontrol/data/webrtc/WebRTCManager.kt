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

@Singleton
class WebRTCManager @Inject constructor(
    private val webRTCService: WebRTCService,
    private val webSocketService: WebSocketService
) {
    private val TAG = "WebRTCManager"
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    // Key method: starts screen capture and creates offer proactively
    fun startScreenCapture(resultCode: Int, data: Intent, fromId: String) {
        if (data == null) {
            Log.e(TAG, "Invalid resultData. Cannot start screen capture.")
            return
        }

        try {
            // Ensure any existing capture is stopped first
            webRTCService.stopScreenCapture()

            // Wait a moment to ensure resources are released
            Thread.sleep(100)

            // Start new capture
            webRTCService.startScreenCapture(resultCode, data, fromId)

            // IMPORTANT: Proactively create SDP offer after initialization
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                Log.d(TAG, "Proactively creating offer to initiate connection")
                createOffer("webadmin")
            }, 1000)  // 1 second delay to ensure PeerConnection is fully initialized

            // Also check buffered offers as a backup
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                checkBufferedOffers()
            }, 1500)

            Log.d(TAG, "Screen sharing initiated for user: $fromId")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start screen capture", e)
            throw e  // Re-throw so caller knows about the failure
        }
    }

    fun stopScreenCapture() {
        try {
            webRTCService.stopScreenCapture()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping screen capture", e)
        }
    }

    fun release() {
        try {
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
        try {
            webRTCService.handleRemoteSessionDescription("offer", sdpOffer, fromId)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling SDP offer", e)
            throw e  // Re-throw to allow caller to handle the error
        }
    }

    fun getScreenCaptureIntent(activity: Activity): Intent {
        val mediaProjectionManager = activity.getSystemService(MediaProjectionManager::class.java)
        return mediaProjectionManager.createScreenCaptureIntent()
    }

    fun handleRtcMessage(type: String, fromId: String, payload: Any) {
        try {
            val payloadJson = payload as? JSONObject ?: JSONObject(payload.toString())

            Log.d(TAG, "Handling RTC message of type: $type from: $fromId")

            when (type) {
                "offer" -> {
                    val sdpType = payloadJson.optString("type", "offer")
                    val sdp = payloadJson.optString("sdp")
                    if (sdp.isNotEmpty()) {
                        webRTCService.handleRemoteSessionDescription(sdpType, sdp, fromId)
                    } else {
                        Log.e(TAG, "Received offer without SDP: $payloadJson")
                    }
                }
                "answer" -> {
                    val sdpType = payloadJson.optString("type", "answer")
                    val sdp = payloadJson.optString("sdp")
                    if (sdp.isNotEmpty()) {
                        webRTCService.handleRemoteSessionDescription(sdpType, sdp, fromId)
                    } else {
                        Log.e(TAG, "Received answer without SDP: $payloadJson")
                    }
                }
                "ice-candidate" -> {
                    val candidate = payloadJson.optString("candidate", "")
                    val sdpMid = payloadJson.optString("sdpMid", "")
                    val sdpMLineIndex = payloadJson.optInt("sdpMLineIndex", 0)

                    // Only process if candidate is not empty
                    if (candidate.isNotEmpty()) {
                        webRTCService.handleRemoteIceCandidate(candidate, sdpMid, sdpMLineIndex)
                    } else {
                        Log.d(TAG, "Received empty ICE candidate (end of candidates)")
                        // Empty candidate is normal at end of ICE gathering
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

    fun checkBufferedOffers() {
        Log.d(TAG, "Checking for buffered offers...")
        try {
            // Calls processBufferedOffer method in WebRTCService
            webRTCService.processBufferedOffers()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking buffered offers", e)
        }
    }

    // Key method: creates an SDP offer to send to web client
    fun createOffer(peerId: String) {
        try {
            Log.d(TAG, "Proactively creating offer for $peerId")
            webRTCService.createAndSendOffer(peerId)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating offer", e)
        }
    }
}