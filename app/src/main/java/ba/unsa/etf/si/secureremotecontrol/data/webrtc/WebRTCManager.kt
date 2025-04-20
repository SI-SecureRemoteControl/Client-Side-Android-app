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

    fun startScreenCapture(resultCode: Int, data: Intent, fromId: String) {
       //webRTCService.stopScreenCapture()
        if (data == null) {
            Log.e(TAG, "Invalid resultData. Cannot start screen capture.")
            return
        }

        webRTCService.startScreenCapture(resultCode, data, fromId)
    }

    fun stopScreenCapture() {
        webRTCService.stopScreenCapture()
    }

    fun release() {
        webRTCService.release()
    }

    fun startObservingRtcMessages(lifecycleOwner: LifecycleOwner) {
        lifecycleOwner.lifecycleScope.launch {
            webSocketService.observeRtcMessages().collect { rtcMessage ->
                handleRtcMessage(rtcMessage.type, rtcMessage.fromId, rtcMessage.payload)
            }
        }
    }

    fun confirmSessionAndStartStreaming(fromId: String, sdpOffer: String) {
        webRTCService.createPeerConnection(fromId)
        webRTCService.handleRemoteSessionDescription("offer", sdpOffer, fromId)
        webRTCService.createAndSendAnswer(fromId)
    }

    fun getScreenCaptureIntent(activity: Activity): Intent {
        val mediaProjectionManager = activity.getSystemService(MediaProjectionManager::class.java)
        return mediaProjectionManager.createScreenCaptureIntent()
    }

    private fun handleRtcMessage(type: String, fromId: String, payload: Any) {
        try {
            val payloadJson = payload as? JSONObject ?: JSONObject(payload.toString())

            when (type) {
                "offer" -> {
                    val sdpType = payloadJson.getString("type")
                    val sdp = payloadJson.getString("sdp")
                    webRTCService.handleRemoteSessionDescription(sdpType, sdp, fromId)
                }
                "answer" -> {
                    val sdpType = payloadJson.getString("type")
                    val sdp = payloadJson.getString("sdp")
                    webRTCService.handleRemoteSessionDescription(sdpType, sdp, fromId)
                }
                "ice-candidate" -> {
                    val candidate = payloadJson.getString("candidate")
                    val sdpMid = payloadJson.getString("sdpMid")
                    val sdpMLineIndex = payloadJson.getInt("sdpMLineIndex")
                    webRTCService.handleRemoteIceCandidate(candidate, sdpMid, sdpMLineIndex)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling RTC message", e)
        }
    }
}