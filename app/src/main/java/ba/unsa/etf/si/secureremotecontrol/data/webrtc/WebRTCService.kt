package ba.unsa.etf.si.secureremotecontrol.data.webrtc

import android.content.Context
import android.content.Intent
// import android.media.projection.MediaProjection // REMOVE THIS
import android.media.projection.MediaProjectionManager // Keep for context.getSystemService
import android.media.projection.MediaProjection // Keep for Callback type
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import ba.unsa.etf.si.secureremotecontrol.data.api.WebSocketService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebRTCService @Inject constructor(
    private val context: Context,
    private val webSocketService: WebSocketService
) {
    private val TAG = "WebRTCService"
    private var resultCode: Int? = null // Keep if needed for logging/state
    private val rootEglBase: EglBase = EglBase.create()
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    // --- REMOVED ---
    // private var mediaProjection: MediaProjection? = null
    // --- END REMOVED ---
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var screenCapturer: VideoCapturer? = null // This will be ScreenCapturerAndroid
    private var isCapturing = false

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
    )

    private val peerConnectionConstraints = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false")) // Adjust if audio needed
    }

    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    // MediaProjection callback defined as a member
    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            Log.w(TAG, "MediaProjection stopped externally (via Callback). Stopping screen capture.")
            // Ensure this runs on a valid thread if UI interaction is needed, but stopping capture should be safe
            CoroutineScope(Dispatchers.Main).launch { // Or Dispatchers.IO if no UI needed
                stopScreenCapture()
            }
        }
    }

    init {
        initPeerConnectionFactory()
    }

    private fun initPeerConnectionFactory() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val encoderFactory = DefaultVideoEncoderFactory(
            rootEglBase.eglBaseContext, true, true
        )
        val decoderFactory = DefaultVideoDecoderFactory(rootEglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    fun startScreenCapture(resultCode: Int, data: Intent, fromId: String) {
        Log.d(TAG, "Attempting to start screen capture. Current state (isCapturing): $isCapturing")
        if (isCapturing) {
            Log.w(TAG, "Screen capture already in progress. Forcing stop before restart.")
            stopScreenCapture() // Ensure any previous capture is fully stopped
        }

        this.resultCode = resultCode // Store if needed

        // --- Start Setup ---
        isCapturing = true // Set state tentatively (will be reset on failure)
        Log.d(TAG, "isCapturing set to true.")

        try {
            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", rootEglBase.eglBaseContext)
            // Check if VideoSource should be isScreencast = true
            videoSource = peerConnectionFactory?.createVideoSource(true) // Often true for screen capture

            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val displayMetrics = DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(displayMetrics)

            val fps = 30 // Or configure as needed

            // Create ScreenCapturerAndroid, passing the RESULT INTENT and the CALLBACK
            // It will internally use the intent to get the MediaProjection.
            screenCapturer = createScreenCapturer(data)
            Log.d(TAG, "ScreenCapturerAndroid created.")

            screenCapturer?.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
            Log.d(TAG, "ScreenCapturerAndroid initialized.")

            // Start capturing - This is where createVirtualDisplay will be called internally
            screenCapturer?.startCapture(displayMetrics.widthPixels, displayMetrics.heightPixels, fps)
            Log.d(TAG, "ScreenCapturerAndroid capture started.")

            // Proceed with WebRTC setup only after successful capture start

            createPeerConnection(fromId) // Setup WebRTC connection
            Log.d(TAG, "Peer connection creation initiated.")

            /*localVideoTrack = peerConnectionFactory?.createVideoTrack("video0", videoSource)
            Log.d(TAG, "Local video track created.")*/


        } catch (e: Exception) {
            Log.e(TAG, "Error during screen capture setup", e)
            // Cleanup on failure
            stopScreenCapture() // This will now handle capturer cleanup
            isCapturing = false // Ensure state is reset
            // Re-throw or handle the error appropriately in the caller (ScreenSharingService)
            throw IllegalStateException("Failed to initialize screen capture components: ${e.message}", e)
        }
    }

    // Pass the actual callback here
    private fun createScreenCapturer(data: Intent): VideoCapturer {
        Log.d(TAG, "Creating ScreenCapturerAndroid with data intent and media projection callback.")
        // Pass the data Intent and the callback. ScreenCapturerAndroid uses the Intent
        // to get the MediaProjection and manages its lifecycle, using the callback.
        return ScreenCapturerAndroid(data, mediaProjectionCallback)
    }

    // createPeerConnection and other WebRTC signalling methods remain the same
    // ... (onSignalingChange, onIceCandidate, handleRemoteSessionDescription, etc.) ...
     fun createPeerConnection(fromId: String) {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(signalingState: PeerConnection.SignalingState) {
                Log.d(TAG, "onSignalingChange: $signalingState")
            }

            override fun onIceConnectionChange(iceConnectionState: PeerConnection.IceConnectionState) {
                Log.d(TAG, "onIceConnectionChange: $iceConnectionState")
                if (iceConnectionState == PeerConnection.IceConnectionState.FAILED ||
                    iceConnectionState == PeerConnection.IceConnectionState.DISCONNECTED) {
                    Log.e(TAG, "Peer connection failed or disconnected.")
                    // Consider stopping capture or attempting reconnect based on state
                }
            }

            override fun onIceGatheringChange(iceGatheringState: PeerConnection.IceGatheringState) {
                Log.d(TAG, "onIceGatheringChange: $iceGatheringState")
            }

            override fun onIceCandidate(iceCandidate: IceCandidate) {
                Log.d(TAG, "onIceCandidate: $iceCandidate")
                sendIceCandidate(iceCandidate, fromId)
            }

            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
                Log.d(TAG, "onAddTrack: $receiver")
            }

            override fun onDataChannel(dataChannel: DataChannel) {
                Log.d(TAG, "onDataChannel: $dataChannel")
            }

            override fun onRenegotiationNeeded() {
                Log.d(TAG, "onRenegotiationNeeded")
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceCandidatesRemoved(iceCandidates: Array<out IceCandidate>) {}
            override fun onAddStream(mediaStream: MediaStream) {}
            override fun onRemoveStream(mediaStream: MediaStream) {}
        })

        localVideoTrack = peerConnectionFactory?.createVideoTrack("video0", videoSource)
            Log.d(TAG, "Local video track created.")
        // Add track only if it was successfully created
        localVideoTrack?.let {
            peerConnection?.addTrack(it, listOf("ARDAMS"))
            Log.d(TAG, "Local video track added to peer connection.")
        } ?: Log.e(TAG, "Cannot add null local video track to peer connection.")

    }
    fun handleRemoteIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        peerConnection?.addIceCandidate(iceCandidate)
        Log.d(TAG, "Remote ICE candidate added: $iceCandidate")
    }
    private fun sendIceCandidate(iceCandidate: IceCandidate, toId: String) {
        val payload = JSONObject().apply {
            put("candidate", iceCandidate.sdp)
            put("sdpMid", iceCandidate.sdpMid)
            put("sdpMLineIndex", iceCandidate.sdpMLineIndex)
        }

        val message = JSONObject().apply {
            put("type", "ice-candidate")
            put("fromId", (context.applicationContext as? AppIdProvider)?.getDeviceId() ?: "unknown_device") // Provide default
            put("toId", toId)
            put("payload", payload)
        }

        coroutineScope.launch {
            webSocketService.sendRawMessage(message.toString())
        }
    }

    fun handleRemoteSessionDescription(type: String, sdp: String, fromId: String) {
        val sessionDescription = SessionDescription(
            SessionDescription.Type.fromCanonicalForm(type),
            sdp
        )
        Log.d(TAG, "Setting remote description: Type=$type")

        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d(TAG, "Remote SDP ($type) set successfully")
                // Only create answer if the remote description was an OFFER
                if (type.equals("offer", ignoreCase = true)) {
                    Log.d(TAG, "Remote description was an offer, creating answer...")
                    createAndSendAnswer(fromId)
                } else {
                    Log.d(TAG, "Remote description was an answer, no action needed here.")
                }
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Error setting remote SDP ($type): $error")
            }

            // Unused SdpObserver methods
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(error: String) {} // Should not be called for setRemoteDescription
        }, sessionDescription)
    }

    fun createAndSendAnswer(toId: String) {
        if (peerConnection == null) {
            Log.e(TAG, "Cannot create answer, PeerConnection is null.")
            return
        }
        Log.d(TAG, "Creating answer...")
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                Log.d(TAG, "Answer created successfully.")
                setLocalDescriptionAndSend(sdp, toId) // Helper function
            }

            override fun onCreateFailure(error: String) {
                Log.e(TAG, "Answer creation failed: $error")
            }
            // Unused SdpObserver methods
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, peerConnectionConstraints) // Pass constraints if needed for answer
    }

    // Helper to reduce nesting in createAndSendAnswer/Offer
    private fun setLocalDescriptionAndSend(sdp: SessionDescription, toId: String) {
        peerConnection?.setLocalDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d(TAG, "Local SDP (${sdp.type}) set successfully")
                // Send the SDP (offer or answer)
                sendSdp(sdp, toId)
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Error setting local SDP (${sdp.type}): $error")
            }
            // Unused SdpObserver methods
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, sdp)
    }

    // Consolidated SDP sending logic
    private fun sendSdp(sdp: SessionDescription, toId: String) {
        val payload = JSONObject().apply {
            put("type", sdp.type.canonicalForm())
            put("sdp", sdp.description)
        }
        val messageType = sdp.type.canonicalForm() // "offer" or "answer"

        val message = JSONObject().apply {
            put("type", messageType)
            put("fromId", (context.applicationContext as? AppIdProvider)?.getDeviceId() ?: "unknown_device")
            put("toId", toId)
            put("payload", payload)
        }
        Log.d(TAG, "Sending $messageType to $toId")
        coroutineScope.launch {
            webSocketService.sendRawMessage(message.toString())
        }
    }
    // This function is now simplified
    fun stopScreenCapture() {
        Log.d(TAG, "Attempting to stop screen capture. Current state (isCapturing): $isCapturing")
        if (!isCapturing && screenCapturer == null) {
            Log.d(TAG, "Screen capture already stopped or not started.")
            return // Already stopped
        }
        try {
            // Stop the capturer first. This should trigger the MediaProjection.Callback's onStop
            // if ScreenCapturerAndroid is implemented correctly, or handle stopping internally.
            screenCapturer?.stopCapture()
            Log.d(TAG, "Screen capturer stop command issued.")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping screen capturer", e)
        } finally {
            // screenCapturer?.dispose() // Dispose if necessary, check WebRTC docs
            screenCapturer = null // Release reference
        }

        // --- REMOVED ---
        // MediaProjection lifecycle is now managed internally by ScreenCapturerAndroid
        // try {
        //     mediaProjection?.unregisterCallback(mediaProjectionCallback)
        //     mediaProjection?.stop()
        //     Log.d(TAG, "MediaProjection stopped.")
        // } catch (e: Exception) {
        //     Log.e(TAG, "Error stopping media projection", e)
        // } finally {
        //    mediaProjection = null
        // }
        // --- END REMOVED ---


        // Dispose WebRTC video source and related helpers
        localVideoTrack?.dispose()
        localVideoTrack = null
        videoSource?.dispose()
        videoSource = null

        // Dispose SurfaceTextureHelper only if it's fully owned by this capture session
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null


        // Reset state *after* cleanup attempts
        isCapturing = false
        resultCode = null // Clear result code

        Log.d(TAG, "Screen capture stop process finished. isCapturing set to false.")
    }


    fun release() {
        Log.d(TAG, "Releasing WebRTCService resources.")
        // Stop capture first
        stopScreenCapture()

        // Close peer connection
        peerConnection?.close() // Use close for graceful shutdown before dispose
        peerConnection?.dispose()
        peerConnection = null

        // videoSource, surfaceTextureHelper already handled in stopScreenCapture

        // Dispose factory last
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null

        // Release EGL context if appropriate for your app structure
        // EglBase.Context eglContext = rootEglBase.getEglBaseContext(); // Get context before release if needed elsewhere
        try {
            rootEglBase.release()
            Log.d(TAG,"EglBase released.")
        } catch (e: Exception) {
            Log.e(TAG,"Error releasing EglBase", e)
        }


        Log.d(TAG, "WebRTCService resources released.")
    }

    interface AppIdProvider {
        fun getDeviceId(): String
    }
}

