package ba.unsa.etf.si.secureremotecontrol.data.webrtc

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private var resultCode: Int? = null
    private var rootEglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var screenCapturer: VideoCapturer? = null
    private var isCapturing = false
    private var candidatesGenerated = 0

    // Store pending ICE candidates
    private val pendingIceCandidates = mutableListOf<IceCandidate>()

    // Store current peer ID
    private var currentPeerId: String? = null

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        // Add TURN servers if available
        // PeerConnection.IceServer.builder("turn:your-turn-server.example:3478")
        //     .setUsername("username")
        //     .setPassword("password")
        //     .createIceServer(),
        // PeerConnection.IceServer.builder("turn:your-turn-server.example:3478?transport=tcp")
        //     .setUsername("username")
        //     .setPassword("password")
        //     .createIceServer()
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
            mainHandler.post {
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
        candidatesGenerated = 0

        // First clean up any existing resources
        if (localVideoTrack != null || videoSource != null || surfaceTextureHelper != null ||
            screenCapturer != null || peerConnection != null) {
            Log.d(TAG, "[startScreenCapture] Cleaning up existing resources first")
            stopScreenCapture()
        }

        Log.d(TAG, "[startScreenCapture] Starting for peer: $fromId")

        if (rootEglBase == null || peerConnectionFactory == null) {
            Log.e(TAG, "[startScreenCapture] Cannot start: EGL/Factory not ready.")
            // Try to re-initialize
            initializeEglAndFactory()

            if (rootEglBase == null || peerConnectionFactory == null) {
                throw IllegalStateException("WebRTCService EGL or Factory not ready.")
            }
        }

        this.resultCode = resultCode
        isCapturing = true

        try {
            // Set up media components
            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", rootEglBase!!.eglBaseContext)
            Log.d(TAG, "[startScreenCapture] SurfaceTextureHelper created successfully")

            // Create VideoSource with proper settings
            videoSource = peerConnectionFactory!!.createVideoSource(/* isScreencast= */ true)
            if (videoSource == null) {
                throw IllegalStateException("Failed to create video source")
            }
            Log.d(TAG, "[startScreenCapture] VideoSource created successfully")

            // Create VideoTrack
            localVideoTrack = peerConnectionFactory!!.createVideoTrack("video0", videoSource)
            if (localVideoTrack == null) {
                throw IllegalStateException("Failed to create video track")
            }
            Log.d(TAG, "[startScreenCapture] Local video track created successfully: ID=${localVideoTrack?.id()}")

            // Enable the track to make sure it's active
            localVideoTrack?.setEnabled(true)

            // Set up screen capturer
            screenCapturer = createScreenCapturer(data)
            screenCapturer?.initialize(surfaceTextureHelper, context, videoSource!!.capturerObserver)
            Log.d(TAG, "[startScreenCapture] Screen capturer initialized successfully")

            // Get screen dimensions
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val displayMetrics = DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(displayMetrics)
            val originalWidth = displayMetrics.widthPixels
            val originalHeight = displayMetrics.heightPixels
            val fps = 15

            val scaler = 3
            val width = originalWidth/scaler
            val height = originalHeight/scaler

            // Start capturing
            Log.d(TAG, "[startScreenCapture] Starting capture with resolution: ${width}x${height} @ $fps fps")
            screenCapturer?.startCapture(width, height, fps)


            // Create the peer connection immediately
            createPeerConnection(fromId)
            Log.d(TAG, "[startScreenCapture] Peer connection created")

            // Process any pending ICE candidates
            if (pendingIceCandidates.isNotEmpty() && peerConnection?.remoteDescription != null) {
                Log.d(TAG, "[startScreenCapture] Processing ${pendingIceCandidates.size} pending ICE candidates")
                processPendingIceCandidates()
            }

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
        candidatesGenerated = 0

        if (peerConnectionFactory == null) {
            Log.e(TAG, "[createPeerConnection] Factory is null.")
            return
        }

        if (peerConnection != null) {
            Log.w(TAG, "[createPeerConnection] Peer connection already exists, reusing it.")
            return
        }

        // Ensure video track is ready
        if (localVideoTrack == null) {
            Log.e(TAG, "[createPeerConnection] Video track is not ready, cannot create peer connection")
            return
        }

        Log.d(TAG, "[createPeerConnection] Creating PeerConnection for $remotePeerId")
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // Add ICE restart configuration
            iceTransportsType = PeerConnection.IceTransportsType.ALL

            // If your WebRTC version supports these properties, uncomment them:
            // continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            // iceCheckMinInterval = 3000
            // keyType = PeerConnection.KeyType.ECDSA
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(signalingState: PeerConnection.SignalingState) {
                Log.d(TAG, "[Observer] onSignalingChange: $signalingState")
            }

            override fun onIceConnectionChange(iceConnectionState: PeerConnection.IceConnectionState) {
                Log.d(TAG, "[Observer] onIceConnectionChange: $iceConnectionState")
                when (iceConnectionState) {
                    PeerConnection.IceConnectionState.CONNECTED -> {
                        Log.d(TAG, "[Observer] ICE Connected - Connection established successfully!")
                    }
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        Log.d(TAG, "[Observer] ICE Completed - All candidates exchanged")
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        Log.e(TAG, "[Observer] ICE Failed - Connection failed. Total ICE candidates generated: $candidatesGenerated")
                        // Consider restarting ICE, if your WebRTC version supports it
                        // peerConnection?.restartIce()
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        Log.e(TAG, "[Observer] ICE Disconnected - Connection temporarily lost")
                    }
                    PeerConnection.IceConnectionState.CLOSED -> {
                        Log.e(TAG, "[Observer] ICE Closed - Connection closed")
                    }
                    else -> {}
                }
            }

            override fun onIceGatheringChange(iceGatheringState: PeerConnection.IceGatheringState) {
                Log.d(TAG, "[Observer] onIceGatheringChange: $iceGatheringState")
                when (iceGatheringState) {
                    PeerConnection.IceGatheringState.GATHERING -> {
                        Log.d(TAG, "[Observer] ICE Gathering in progress")
                    }
                    PeerConnection.IceGatheringState.COMPLETE -> {
                        Log.d(TAG, "[Observer] ICE Gathering completed. Total candidates: $candidatesGenerated")
                    }
                    else -> {}
                }
            }

            override fun onIceCandidate(iceCandidate: IceCandidate) {
                candidatesGenerated++
                Log.d(TAG, "[Observer] ICE candidate generated #$candidatesGenerated: ${iceCandidate.sdpMid}:${iceCandidate.sdpMLineIndex}")
                Log.d(TAG, "[Observer] ICE candidate SDP: ${iceCandidate.sdp}")
                if (currentPeerId != null) {
                    sendIceCandidate(iceCandidate, currentPeerId!!)
                } else {
                    Log.e(TAG, "[Observer] Cannot send ICE candidate - no peer ID set")
                }
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

            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                Log.d(TAG, "[Observer] onIceConnectionReceivingChange: $receiving")
            }

            override fun onIceCandidatesRemoved(iceCandidates: Array<out IceCandidate>) {
                Log.d(TAG, "[Observer] onIceCandidatesRemoved: ${iceCandidates.size}")
            }

            override fun onAddStream(mediaStream: MediaStream) {
                Log.d(TAG, "[Observer] onAddStream: ${mediaStream.id}")
            }

            override fun onRemoveStream(mediaStream: MediaStream) {
                Log.d(TAG, "[Observer] onRemoveStream: ${mediaStream.id}")
            }
        })

        // Add track to the peer connection
        if (peerConnection != null) {
            Log.d(TAG, "[createPeerConnection] Adding video track to peer connection")

            // Make sure track is enabled
            localVideoTrack?.setEnabled(true)

            // Add track with a stream ID
            val sender = peerConnection?.addTrack(localVideoTrack!!, listOf("ARDAMS"))

            if (sender != null) {
                Log.d(TAG, "[createPeerConnection] Video track added successfully. Track ID: ${localVideoTrack?.id()}")

                // Configure the RTP sender if needed
                try {
                    // If your WebRTC version supports these methods, uncomment them:
                    // val parameters = sender.parameters
                    // parameters.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
                    // sender.parameters = parameters

                    Log.d(TAG, "[createPeerConnection] RTP sender configured")
                } catch (e: Exception) {
                    Log.w(TAG, "[createPeerConnection] Could not configure RTP sender: ${e.message}")
                }

                // Try to configure transceiver for newer WebRTC versions
                try {
                    peerConnection?.transceivers?.forEach { transceiver ->
                        if (transceiver.sender.track()?.kind() == MediaStreamTrack.VIDEO_TRACK_KIND) {
                            Log.d(TAG, "[createPeerConnection] Configuring video transceiver: ${transceiver.mid}")
                            // If your WebRTC version supports this method, uncomment it:
                            // transceiver.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[createPeerConnection] Transceivers not supported or error: ${e.message}")
                }
            } else {
                Log.e(TAG, "[createPeerConnection] Failed to add video track - addTrack returned null")
                peerConnection?.close()
                peerConnection = null
            }
        } else {
            Log.e(TAG, "[createPeerConnection] Failed to create peer connection - factory returned null")
        }
    }

    // Handle incoming SDP offers/answers
    fun handleRemoteSessionDescription(type: String, sdp: String, fromId: String) {
        Log.d(TAG, "[handleRemoteSDP] Received $type from $fromId")
        Log.d(TAG, "[handleRemoteSDP] SDP content: $sdp")

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

        // If peer connection isn't ready but we receive an offer, create PeerConnection first
        if (peerConnection == null) {
            if (sdpType == SessionDescription.Type.OFFER) {
                Log.w(TAG, "[handleRemoteSDP] PeerConnection not ready, creating it first")
                // If media components are ready but PeerConnection isn't, create it
                if (localVideoTrack != null) {
                    createPeerConnection(fromId)

                    if (peerConnection == null) {
                        Log.e(TAG, "[handleRemoteSDP] Failed to create PeerConnection")
                        return
                    }
                } else {
                    Log.e(TAG, "[handleRemoteSDP] Cannot create PeerConnection - media components not ready")
                    return
                }
            } else {
                Log.e(TAG, "[handleRemoteSDP] Peer connection not available, cannot process $type")
                return
            }
        }

        // Get current signaling state
        val currentState = peerConnection?.signalingState()
        Log.d(TAG, "[handleRemoteSDP] Current signaling state: $currentState")

        // Set the remote description
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d(TAG, "[handleRemoteSDP] Remote description set successfully")

                // Process any pending ICE candidates
                processPendingIceCandidates()

                // If this was an offer, create an answer
                if (sdpType == SessionDescription.Type.OFFER) {
                    Log.d(TAG, "[handleRemoteSDP] Creating answer in response to offer")
                    // Add a slight delay to ensure stable state
                    mainHandler.postDelayed({
                        createAndSendAnswer(fromId)
                    }, 500)
                }
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "[handleRemoteSDP] Failed to set remote description: $error")

                // Try again with a delay if it's a temporary error
                mainHandler.postDelayed({
                    Log.d(TAG, "[handleRemoteSDP] Retrying setRemoteDescription")
                    peerConnection?.setRemoteDescription(this, sessionDescription)
                }, 1000)
            }

            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(error: String) {}
        }, sessionDescription)
    }

    // Handle incoming ICE candidates
    fun handleRemoteIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        Log.d(TAG, "[handleRemoteIceCandidate] Received ICE candidate: $sdpMid:$sdpMLineIndex")
        Log.d(TAG, "[handleRemoteIceCandidate] Candidate SDP: $candidate")

        val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)

        // If peer connection isn't ready or remote description isn't set, store candidate
        if (peerConnection == null || peerConnection?.remoteDescription == null) {
            Log.d(TAG, "[handleRemoteIceCandidate] Storing ICE candidate for later")
            pendingIceCandidates.add(iceCandidate)
            return
        }

        // Add the candidate
        try {
            peerConnection?.addIceCandidate(iceCandidate)
            Log.d(TAG, "[handleRemoteIceCandidate] ICE candidate added successfully")
        } catch (e: Exception) {
            Log.e(TAG, "[handleRemoteIceCandidate] Failed to add ICE candidate: ${e.message}")
            // Store it anyway in case we need it later
            pendingIceCandidates.add(iceCandidate)
        }
    }

    // Process pending ICE candidates
    private fun processPendingIceCandidates() {
        if (peerConnection != null && peerConnection?.remoteDescription != null && pendingIceCandidates.isNotEmpty()) {
            Log.d(TAG, "[processPendingIceCandidates] Processing ${pendingIceCandidates.size} ICE candidates")

            val successCount = pendingIceCandidates.count { candidate ->
                try {
                    peerConnection?.addIceCandidate(candidate)
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "[processPendingIceCandidates] Failed to add ICE candidate: ${e.message}")
                    false
                }
            }

            Log.d(TAG, "[processPendingIceCandidates] Successfully added $successCount/${pendingIceCandidates.size} candidates")
            pendingIceCandidates.clear()
        }
    }

    // Create and send answer
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
                Log.d(TAG, "[createAndSendAnswer] Answer SDP: ${sdp.description}")

                // Set as local description
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        Log.d(TAG, "[createAndSendAnswer] Local description set, sending answer")
                        sendSdp(sdp, toId)
                    }

                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "[createAndSendAnswer] Failed to set local description: $error")

                        // Try again after a short delay
                        mainHandler.postDelayed({
                            Log.d(TAG, "[createAndSendAnswer] Retrying setting local description")
                            peerConnection?.setLocalDescription(this, sdp)
                        }, 1000)
                    }

                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(error: String) {}
                }, sdp)
            }

            override fun onCreateFailure(error: String) {
                Log.e(TAG, "[createAndSendAnswer] Failed to create answer: $error")

                // Try again after a short delay
                mainHandler.postDelayed({
                    Log.d(TAG, "[createAndSendAnswer] Retrying answer creation")
                    createAndSendAnswer(toId)
                }, 1000)
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, peerConnectionConstraints)
    }

    // Send SDP to peer
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
        Log.d(TAG, "[sendSdp] EXACT MESSAGE: ${message.toString()}")

        // Send via WebSocket
        coroutineScope.launch {
            try {
                val result = webSocketService.sendRawMessage(message.toString())
                Log.d(TAG, "[sendSdp] Message sent successfully: $result")
            } catch (e: Exception) {
                Log.e(TAG, "[sendSdp] Failed to send message: ${e.message}")

                // Try again after a short delay
                mainHandler.postDelayed({
                    coroutineScope.launch {
                        try {
                            Log.d(TAG, "[sendSdp] Retrying sending SDP")
                            webSocketService.sendRawMessage(message.toString())
                        } catch (e: Exception) {
                            Log.e(TAG, "[sendSdp] Retry failed: ${e.message}")
                        }
                    }
                }, 1000)
            }
        }
    }

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

        Log.d(TAG, "[sendIceCandidate] Sending ICE candidate #$candidatesGenerated to $toId")
        Log.d(TAG, "[sendIceCandidate] EXACT MESSAGE: ${message.toString()}")

        coroutineScope.launch {
            try {
                val result = webSocketService.sendRawMessage(message.toString())
                Log.d(TAG, "[sendIceCandidate] ICE candidate sent successfully: $result")
            } catch (e: Exception) {
                Log.e(TAG, "[sendIceCandidate] Failed to send ICE candidate: ${e.message}")

                // Try again after a short delay
                mainHandler.postDelayed({
                    coroutineScope.launch {
                        try {
                            Log.d(TAG, "[sendIceCandidate] Retrying sending ICE candidate")
                            webSocketService.sendRawMessage(message.toString())
                        } catch (e: Exception) {
                            Log.e(TAG, "[sendIceCandidate] Retry failed: ${e.message}")
                        }
                    }
                }, 500)
            }
        }
    }

    // Get current signaling state
    fun getSignalingState(): PeerConnection.SignalingState? {
        return peerConnection?.signalingState()
    }

    // Stop screen capture and clean up
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
        candidatesGenerated = 0
        Log.d(TAG, "[stopScreenCapture] Screen capture stopped")
    }

    // Release all resources
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