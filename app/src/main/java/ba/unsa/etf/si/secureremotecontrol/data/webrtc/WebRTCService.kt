package ba.unsa.etf.si.secureremotecontrol.data.webrtc

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjection
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
import android.provider.Settings

@Singleton
class WebRTCService @Inject constructor(
    private val context: Context,
    private val webSocketService: WebSocketService
) {
    private val TAG = "WebRTCService"
    private var resultCode: Int? = null
    private var rootEglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var screenCapturer: VideoCapturer? = null
    private var isCapturing = false

    // Store pending ICE candidates
    private val pendingIceCandidates = mutableListOf<IceCandidate>()

    // Store current peer ID
    private var currentPeerId: String? = null

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
    )

    private val peerConnectionConstraints = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
    }

    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            Log.w(TAG, "MediaProjection stopped externally. Stopping screen capture.")
            CoroutineScope(Dispatchers.Main).launch {
                stopScreenCapture()
            }
        }
    }

    init {
        initializeEglAndFactory()
    }

    private fun initializeEglAndFactory() {
        try {
            Log.d(TAG, "Attempting to create EglBase...")
            rootEglBase = EglBase.create()
            Log.d(TAG, "EglBase created successfully.")
            initPeerConnectionFactory()
        } catch (e: Exception) {
            Log.e(TAG, "FATAL: Failed to create EglBase or PeerConnectionFactory", e)
            rootEglBase = null
            peerConnectionFactory = null
        }
    }

    private fun initPeerConnectionFactory() {
        if (rootEglBase == null) {
            Log.e(TAG, "Cannot init PCF, rootEglBase is null.")
            return
        }

        Log.d(TAG, "Initializing PeerConnectionFactory...")
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        // Keep HW Accel ON for real devices
        val encoderFactory = DefaultVideoEncoderFactory(rootEglBase!!.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(rootEglBase!!.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        if (peerConnectionFactory == null) {
            Log.e(TAG, "PeerConnectionFactory creation returned NULL.")
        } else {
            Log.d(TAG, "PeerConnectionFactory initialized successfully (Non-null).")
        }
    }

    // Main method for screen capture setup
    fun startScreenCapture(resultCode: Int, data: Intent, fromId: String) {
        // Store the peer ID
        currentPeerId = fromId

        // First clean up any existing resources
        if (localVideoTrack != null || videoSource != null || surfaceTextureHelper != null ||
            screenCapturer != null || peerConnection != null) {
            Log.d(TAG, "[startScreenCapture] Cleaning up existing resources first")
            stopScreenCapture()
        }

        Log.d(TAG, "[startScreenCapture] Starting for peer: $fromId")

        if (rootEglBase == null || peerConnectionFactory == null) {
            Log.e(TAG, "[startScreenCapture] Cannot start: EGL/Factory not ready.")
            throw IllegalStateException("WebRTCService EGL or Factory not ready.")
        }

        this.resultCode = resultCode
        isCapturing = true

        try {
            // Set up media components
            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", rootEglBase!!.eglBaseContext)
            videoSource = peerConnectionFactory!!.createVideoSource(true) // isScreencast=true
            localVideoTrack = peerConnectionFactory!!.createVideoTrack("video0", videoSource)

            // Set up screen capturer
            screenCapturer = createScreenCapturer(data)
            screenCapturer?.initialize(surfaceTextureHelper, context, videoSource!!.capturerObserver)

            // Get screen dimensions
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val displayMetrics = DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(displayMetrics)

            // Start capturing
            screenCapturer?.startCapture(displayMetrics.widthPixels, displayMetrics.heightPixels, 30)
            Log.d(TAG, "[startScreenCapture] Screen capture started")

            // Create the peer connection
            createPeerConnection(fromId)
            Log.d(TAG, "[startScreenCapture] Peer connection created")

        } catch (e: Exception) {
            Log.e(TAG, "[startScreenCapture] Error: ${e.message}", e)
            stopScreenCapture()
            throw IllegalStateException("Failed to start screen capture: ${e.message}", e)
        }
    }

    private fun createScreenCapturer(data: Intent): VideoCapturer {
        Log.d(TAG, "[createScreenCapturer] Creating...")
        return ScreenCapturerAndroid(data, mediaProjectionCallback)
    }

    // Create and setup PeerConnection
    @SuppressLint("SuspiciousIndentation")
    fun createPeerConnection(remotePeerId: String) {
        currentPeerId = remotePeerId

        if (peerConnectionFactory == null) {
            Log.e(TAG, "[createPeerConnection] Factory is null.")
            return
        }

        if (peerConnection != null) {
            Log.w(TAG, "[createPeerConnection] Closing existing PC.")
            peerConnection?.close()
            peerConnection = null
        }

        // Ensure video track is ready
        if (localVideoTrack == null) {
            Log.e(TAG, "[createPeerConnection] Video track is not ready, cannot create peer connection")
            return
        }

        Log.d(TAG, "[createPeerConnection] Creating PeerConnection for $remotePeerId")
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            //enableDtlsSrtp = true
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(signalingState: PeerConnection.SignalingState) {
                Log.d(TAG, "[Observer] onSignalingChange: $signalingState")
            }

            override fun onIceConnectionChange(iceConnectionState: PeerConnection.IceConnectionState) {
                Log.d(TAG, "[Observer] onIceConnectionChange: $iceConnectionState")
                if (iceConnectionState == PeerConnection.IceConnectionState.FAILED ||
                    iceConnectionState == PeerConnection.IceConnectionState.DISCONNECTED ||
                    iceConnectionState == PeerConnection.IceConnectionState.CLOSED) {
                    Log.e(TAG, "[Observer] ICE connection failed: $iceConnectionState")
                }
            }

            override fun onIceGatheringChange(iceGatheringState: PeerConnection.IceGatheringState) {
                Log.d(TAG, "[Observer] onIceGatheringChange: $iceGatheringState")
            }

            override fun onIceCandidate(iceCandidate: IceCandidate) {
                Log.d(TAG, "[Observer] ICE candidate generated: ${iceCandidate.sdpMid}:${iceCandidate.sdpMLineIndex}")
                sendIceCandidate(iceCandidate, remotePeerId)
            }

            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
                Log.d(TAG, "[Observer] onAddTrack: ${receiver.track()?.kind()}")
            }

            override fun onDataChannel(dataChannel: DataChannel) {
                Log.d(TAG, "[Observer] onDataChannel: ${dataChannel.label()}")
            }

            override fun onRenegotiationNeeded() {
                Log.d(TAG, "[Observer] onRenegotiationNeeded")
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceCandidatesRemoved(iceCandidates: Array<out IceCandidate>) {}
            override fun onAddStream(mediaStream: MediaStream) {}
            override fun onRemoveStream(mediaStream: MediaStream) {}
        })

        // Add track to the peer connection
        if (peerConnection != null) {
            val sender = peerConnection?.addTrack(localVideoTrack!!, listOf("ARDAMS"))
            if (sender != null) {
                Log.d(TAG, "[createPeerConnection] Video track added successfully")
            } else {
                Log.e(TAG, "[createPeerConnection] Failed to add video track")
                peerConnection?.close()
                peerConnection = null
            }
        } else {
            Log.e(TAG, "[createPeerConnection] Failed to create peer connection")
        }
    }

    // Handle incoming SDP offers/answers - SIMPLIFIED VERSION
    fun handleRemoteSessionDescription(type: String, sdp: String, fromId: String) {
        Log.d(TAG, "[handleRemoteSDP] Received $type from $fromId")

        // Store peer ID
        currentPeerId = fromId

        // Parse the type
        val sdpType = when {
            type.equals("offer", ignoreCase = true) -> SessionDescription.Type.OFFER
            type.equals("answer", ignoreCase = true) -> SessionDescription.Type.ANSWER
            else -> {
                Log.e(TAG, "[handleRemoteSDP] Invalid SDP type: $type")
                return
            }
        }

        // Create session description object
        val sessionDescription = SessionDescription(sdpType, sdp)

        // Check if peer connection exists
        if (peerConnection == null) {
            Log.e(TAG, "[handleRemoteSDP] Peer connection not available, cannot process $type")
            return
        }

        // Get current signaling state
        val currentState = peerConnection?.signalingState()
        Log.d(TAG, "[handleRemoteSDP] Current signaling state: $currentState")

        // Set the remote description
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d(TAG, "[handleRemoteSDP] Remote description set successfully")

                // Process any pending ICE candidates
                if (pendingIceCandidates.isNotEmpty()) {
                    Log.d(TAG, "[handleRemoteSDP] Processing ${pendingIceCandidates.size} pending ICE candidates")
                    pendingIceCandidates.forEach { candidate ->
                        peerConnection?.addIceCandidate(candidate)
                    }
                    pendingIceCandidates.clear()
                }

                // If this was an offer, create an answer
                if (sdpType == SessionDescription.Type.OFFER) {
                    Log.d(TAG, "[handleRemoteSDP] Creating answer in response to offer")
                    createAndSendAnswer(fromId)
                }
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "[handleRemoteSDP] Failed to set remote description: $error")
            }

            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(error: String) {}
        }, sessionDescription)
    }

    // Handle incoming ICE candidates - SIMPLIFIED VERSION
    fun handleRemoteIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        Log.d(TAG, "[handleRemoteIceCandidate] Received ICE candidate: $sdpMid:$sdpMLineIndex")

        val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)

        // If peer connection isn't ready or remote description isn't set, store candidate
        if (peerConnection == null || peerConnection?.remoteDescription == null) {
            Log.d(TAG, "[handleRemoteIceCandidate] Storing ICE candidate for later")
            pendingIceCandidates.add(iceCandidate)
            return
        }

        // Add the candidate
        peerConnection?.addIceCandidate(iceCandidate)
        Log.d(TAG, "[handleRemoteIceCandidate] ICE candidate added")
    }

    // Create and send answer - SIMPLIFIED VERSION
    fun createAndSendAnswer(toId: String) {
        if (peerConnection == null) {
            Log.e(TAG, "[createAndSendAnswer] Peer connection not available")
            return
        }

        val signalingState = peerConnection?.signalingState()
        Log.d(TAG, "[createAndSendAnswer] Current signaling state: $signalingState")

        // Only create answer if we have a remote offer
        if (signalingState != PeerConnection.SignalingState.HAVE_REMOTE_OFFER) {
            Log.e(TAG, "[createAndSendAnswer] Cannot create answer in state: $signalingState")
            return
        }

        Log.d(TAG, "[createAndSendAnswer] Creating answer...")
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                Log.d(TAG, "[createAndSendAnswer] Answer created successfully")

                // Set as local description
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        Log.d(TAG, "[createAndSendAnswer] Local description set, sending answer")
                        sendSdp(sdp, toId)
                    }

                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "[createAndSendAnswer] Failed to set local description: $error")
                    }

                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(error: String) {}
                }, sdp)
            }

            override fun onCreateFailure(error: String) {
                Log.e(TAG, "[createAndSendAnswer] Failed to create answer: $error")
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, peerConnectionConstraints)
    }

    // Send SDP to peer - SIMPLIFIED VERSION
    private fun sendSdp(sdp: SessionDescription, toId: String) {
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

        // Create payload
        val payload = JSONObject().apply {
            put("type", sdp.type.canonicalForm())
            put("sdp", sdp.description)
        }

        val message = JSONObject().apply {
            put("type", sdp.type.canonicalForm())
            put("fromId", deviceId)
            put("toId", toId)
            put("payload", payload)
        }

        Log.d(TAG, "[sendSdp] Sending ${sdp.type.canonicalForm()} to $toId")

        // Send via WebSocket
        coroutineScope.launch {
            try {
                webSocketService.sendRawMessage(message.toString())
                Log.d(TAG, "[sendSdp] Message sent successfully")
            } catch (e: Exception) {
                Log.e(TAG, "[sendSdp] Failed to send message: ${e.message}")
            }
        }
    }

    // Send ICE candidate to peer - SIMPLIFIED VERSION
    private fun sendIceCandidate(iceCandidate: IceCandidate, toId: String) {
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

        val payload = JSONObject().apply {
            put("candidate", iceCandidate.sdp)
            put("sdpMid", iceCandidate.sdpMid)
            put("sdpMLineIndex", iceCandidate.sdpMLineIndex)
        }

        val message = JSONObject().apply {
            put("type", "ice-candidate")
            put("fromId", deviceId)
            put("toId", toId)
            put("payload", payload)
        }

        Log.d(TAG, "[sendIceCandidate] Sending ICE candidate to $toId")

        coroutineScope.launch {
            try {
                webSocketService.sendRawMessage(message.toString())
                Log.d(TAG, "[sendIceCandidate] ICE candidate sent")
            } catch (e: Exception) {
                Log.e(TAG, "[sendIceCandidate] Failed to send ICE candidate: ${e.message}")
            }
        }
    }

    // Get current signaling state
    fun getSignalingState(): PeerConnection.SignalingState? {
        return peerConnection?.signalingState()
    }

    // Stop screen capture and clean up - SIMPLIFIED VERSION
    fun stopScreenCapture() {
        Log.d(TAG, "[stopScreenCapture] Stopping screen capture")

        // Stop capturer
        try {
            screenCapturer?.stopCapture()
        } catch (e: Exception) {
            Log.e(TAG, "[stopScreenCapture] Error stopping capturer: ${e.message}")
        } finally {
            screenCapturer = null
        }

        // Clean up video track
        try {
            localVideoTrack?.dispose()
        } catch (e: Exception) {
            Log.e(TAG, "[stopScreenCapture] Error disposing video track: ${e.message}")
        } finally {
            localVideoTrack = null
        }

        // Clean up video source
        try {
            videoSource?.dispose()
        } catch (e: Exception) {
            Log.e(TAG, "[stopScreenCapture] Error disposing video source: ${e.message}")
        } finally {
            videoSource = null
        }

        // Clean up surface texture helper
        try {
            surfaceTextureHelper?.dispose()
        } catch (e: Exception) {
            Log.e(TAG, "[stopScreenCapture] Error disposing surface texture helper: ${e.message}")
        } finally {
            surfaceTextureHelper = null
        }

        // Close peer connection
        try {
            peerConnection?.close()
        } catch (e: Exception) {
            Log.e(TAG, "[stopScreenCapture] Error closing peer connection: ${e.message}")
        } finally {
            peerConnection = null
        }

        // Clear pending ICE candidates
        pendingIceCandidates.clear()

        // Reset state
        isCapturing = false
        resultCode = null
        Log.d(TAG, "[stopScreenCapture] Screen capture stopped")
    }

    // Release all resources - SIMPLIFIED VERSION
    fun release() {
        Log.d(TAG, "[release] Releasing all resources")

        // Stop screen capture (this also cleans up peer connection)
        stopScreenCapture()

        // Clean up factory
        try {
            peerConnectionFactory?.dispose()
        } catch (e: Exception) {
            Log.e(TAG, "[release] Error disposing factory: ${e.message}")
        } finally {
            peerConnectionFactory = null
        }

        // Release EGL base
        try {
            rootEglBase?.release()
        } catch (e: Exception) {
            Log.e(TAG, "[release] Error releasing EGL: ${e.message}")
        } finally {
            rootEglBase = null
        }

        Log.d(TAG, "[release] All resources released")
    }
}