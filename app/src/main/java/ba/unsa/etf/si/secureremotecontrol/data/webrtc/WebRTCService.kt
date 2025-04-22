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

    // Buffering variables for SDP offers
    private var bufferedRemoteOffer: SessionDescription? = null
    private var bufferedOfferFromId: String? = null

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

    fun processBufferedOffers() {
        Log.d(TAG, "processBufferedOffers called externally")
        processBufferedOffer()
    }

    private fun initPeerConnectionFactory() {
        if (rootEglBase == null) { Log.e(TAG, "Cannot init PCF, rootEglBase is null."); return; }
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

        if (peerConnectionFactory == null) { Log.e(TAG, "PeerConnectionFactory creation returned NULL.") }
        else { Log.d(TAG, "PeerConnectionFactory initialized successfully (Non-null).") }
    }

    // Main method for screen capture setup
    fun startScreenCapture(resultCode: Int, data: Intent, fromId: String) {
        if (localVideoTrack != null || videoSource != null || surfaceTextureHelper != null || screenCapturer != null || peerConnection != null) {
            Log.e(TAG, "[startScreenCapture] PRE-CHECK FAILED: Old resources still exist! Track: ${localVideoTrack != null}, Source: ${videoSource != null}, Helper: ${surfaceTextureHelper != null}, Capturer: ${screenCapturer != null}, PC: ${peerConnection != null}. Forcing stop again.")
            stopScreenCapture() // Try stopping again just in case
        }
        Log.d(TAG, "[startScreenCapture] Attempting... isCapturing: $isCapturing")
        if (isCapturing) {
            Log.w(TAG, "[startScreenCapture] Already capturing. Stopping previous.")
            stopScreenCapture()
        }

        if (rootEglBase == null || peerConnectionFactory == null) {
            Log.e(TAG, "[startScreenCapture] Cannot start: EGL/Factory not ready.")
            throw IllegalStateException("WebRTCService EGL or Factory not ready.")
        }

        this.resultCode = resultCode
        isCapturing = true
        Log.d(TAG, "[startScreenCapture] isCapturing set true.")

        var tempVideoSource: VideoSource? = null      // Use local vars for safety
        var tempLocalVideoTrack: VideoTrack? = null   // Use local vars for safety

        try {
            Log.d(TAG, "[startScreenCapture] Setting up resources...")
            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", rootEglBase!!.eglBaseContext)
            Log.d(TAG,"[startScreenCapture] SurfaceTextureHelper created.")

            // Create VideoSource (BEFORE PeerConnection)
            Log.d(TAG,"[startScreenCapture] Creating VideoSource...")
            tempVideoSource = peerConnectionFactory!!.createVideoSource(true) // isScreencast=true
            if (tempVideoSource == null) {
                throw IllegalStateException("VideoSource creation failed.")
            }
            videoSource = tempVideoSource // Assign to member variable *after* success

            Log.d(TAG, "[startScreenCapture] VideoSource created successfully")

            // Create VideoTrack (BEFORE PeerConnection)
            Log.d(TAG, "[startScreenCapture] Creating video track...")
            tempLocalVideoTrack = peerConnectionFactory!!.createVideoTrack("video0", videoSource) // Use non-null source
            if (tempLocalVideoTrack == null) {
                throw IllegalStateException("LocalVideoTrack creation failed.")
            }
            localVideoTrack = tempLocalVideoTrack // Assign to member variable *after* success
            Log.d(TAG, "[startScreenCapture] Local video track created: ID=${localVideoTrack?.id()}")

            // Setup the Screen Capturer
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val displayMetrics = DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(displayMetrics)
            val fps = 30

            screenCapturer = createScreenCapturer(data)
            Log.d(TAG, "[startScreenCapture] ScreenCapturerAndroid created.")

            screenCapturer?.initialize(surfaceTextureHelper, context, videoSource!!.capturerObserver)
            Log.d(TAG, "[startScreenCapture] ScreenCapturerAndroid initialized.")

            screenCapturer?.startCapture(displayMetrics.widthPixels, displayMetrics.heightPixels, fps)
            Log.d(TAG, "[startScreenCapture] ScreenCapturerAndroid capture started.")

            // Create PeerConnection (AFTER media is ready)
            // videoSource and localVideoTrack member variables are now guaranteed non-null
            createPeerConnection(fromId)
            Log.d(TAG, "[startScreenCapture] Peer connection creation process finished.")

            // Check for buffered offers
            peerConnection?.let {
                Log.d(TAG, "[startScreenCapture] PeerConnection successfully created. Checking for buffered offer...")
                if (bufferedRemoteOffer != null && bufferedOfferFromId != null) {
                    Log.i(TAG, "[startScreenCapture] Found buffered offer, scheduling processing...")

                    // Important delay to give PeerConnection time to initialize internal state
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        processBufferedOffer()
                    }, 500)
                } else {
                    Log.d(TAG, "[startScreenCapture] No buffered offer found.")
                }
            }

            // Process any buffered offers
            processBufferedOffer()

        } catch (e: Exception) {
            Log.e(TAG, "[startScreenCapture] Error during setup: ${e.message}", e)
            // Cleanup partially created resources if exception occurred
            if (tempLocalVideoTrack == null) videoSource?.dispose() // Dispose source if track failed
            videoSource = null
            localVideoTrack = null // Ensure members are null on failure
            surfaceTextureHelper?.dispose(); surfaceTextureHelper = null
            screenCapturer?.stopCapture(); screenCapturer = null // Stop capturer if started
            stopScreenCapture() // Call general cleanup just in case
            isCapturing = false
            // Re-throw specific exception or a general one
            throw IllegalStateException("Failed to initialize screen capture components: ${e.message}", e)
        }
    }

    private fun createScreenCapturer(data: Intent): VideoCapturer {
        Log.d(TAG, "[createScreenCapturer] Creating...")
        return ScreenCapturerAndroid(data, mediaProjectionCallback)
    }

    // Create and setup PeerConnection
    @SuppressLint("SuspiciousIndentation")
    fun createPeerConnection(remotePeerId: String) {
        if (peerConnectionFactory == null) { Log.e(TAG, "[createPeerConnection] Factory is null."); return }
        if (peerConnection != null) { Log.w(TAG, "[createPeerConnection] Closing existing PC."); peerConnection?.close(); peerConnection = null }

        // Ensure Track Exists BEFORE creating PC
        if (localVideoTrack == null) {
            Log.e(TAG, "[createPeerConnection] CRITICAL: localVideoTrack is null. Aborting PC creation.")
            return
        }

        Log.d(TAG, "[createPeerConnection] Creating PeerConnection object...")
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(signalingState: PeerConnection.SignalingState) {
                Log.d(TAG, "[Observer] onSignalingChange: $signalingState")
            }
            override fun onIceConnectionChange(iceConnectionState: PeerConnection.IceConnectionState) {
                Log.d(TAG, "[Observer] onIceConnectionChange: $iceConnectionState")
                if (iceConnectionState == PeerConnection.IceConnectionState.FAILED ||
                    iceConnectionState == PeerConnection.IceConnectionState.DISCONNECTED ||
                    iceConnectionState == PeerConnection.IceConnectionState.CLOSED ) {
                    Log.e(TAG, "[Observer] Peer connection state changed to $iceConnectionState.")
                }
            }
            override fun onIceGatheringChange(iceGatheringState: PeerConnection.IceGatheringState) {
                Log.d(TAG, "[Observer] onIceGatheringChange: $iceGatheringState")
            }
            override fun onIceCandidate(iceCandidate: IceCandidate) {
                Log.d(TAG, "[Observer] Local ICE Candidate generated.")
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

        // Add Track (AFTER PC object created)
        if (peerConnection != null) {
            Log.d(TAG, "[createPeerConnection] PeerConnection created successfully. Adding track...")
            // localVideoTrack is known to be non-null here from the check above
            val sender = peerConnection?.addTrack(localVideoTrack!!, listOf("ARDAMS")) // Use non-null assertion

            if (sender != null) {
                Log.d(TAG, "[createPeerConnection] Local video track added successfully (Sender ID: ${sender.id()}). PC State: ${peerConnection?.signalingState()}")
            } else {
                Log.e(TAG, "[createPeerConnection] Failed to add local video track (addTrack returned null).")
                peerConnection?.close() // Close unusable PC
                peerConnection = null
            }
        } else {
            Log.e(TAG, "[createPeerConnection] Failed to create PeerConnection object (createPeerConnection returned null).")
        }
    }

    // Process any buffered SDP offers
    private fun processBufferedOffer() {
        Log.d(TAG, "[processBufferedOffer] BUFFERING DEBUG:")
        Log.d(TAG, "   PeerConnection: ${if (peerConnection != null) "EXISTS" else "NULL"}")
        Log.d(TAG, "   BufferedRemoteOffer: ${if (bufferedRemoteOffer != null) "EXISTS" else "NULL"}")
        Log.d(TAG, "   BufferedOfferFromId: $bufferedOfferFromId")
        if (peerConnection != null) {
            Log.d(TAG, "   PeerConnection state: ${peerConnection?.signalingState()}")
        }

        if (peerConnection != null && bufferedRemoteOffer != null && bufferedOfferFromId != null) {
            Log.i(TAG, "[processBufferedOffer] Processing buffered offer from ${bufferedOfferFromId} (State: ${peerConnection?.signalingState()})")

            // IMPORTANT - save copies before resetting
            val offerToProcess = bufferedRemoteOffer
            val fromIdToProcess = bufferedOfferFromId

            // Reset buffers
            bufferedRemoteOffer = null
            bufferedOfferFromId = null

            // Call handler with copies
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Log.d(TAG, "[processBufferedOffer] Now calling handleRemoteSessionDescription...")
                handleRemoteSessionDescription(
                    offerToProcess!!.type.canonicalForm(),
                    offerToProcess.description,
                    fromIdToProcess!!
                )
            }
        } else {
            if (peerConnection == null) {
                Log.w(TAG, "[processBufferedOffer] Cannot process buffered offer, PeerConnection is null.")
            } else if (bufferedRemoteOffer == null) {
                Log.d(TAG, "[processBufferedOffer] No buffered offer to process.")
            }
        }
    }

    // Handle incoming ICE candidates
    /*fun handleRemoteIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        Log.d(TAG, "[handleRemoteIceCandidate] Adding remote candidate.")
        if (peerConnection == null) { Log.w(TAG, "PC is null, cannot add ICE candidate."); return; }
        val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        peerConnection?.addIceCandidate(iceCandidate)
    }*/
    // Add this as a class variable
    private val bufferedIceCandidates = mutableListOf<IceCandidate>()

    fun handleRemoteIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        Log.d(TAG, "[handleRemoteIceCandidate] Adding remote candidate.")
        val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)

        if (peerConnection == null) {
            Log.d(TAG, "PC is null, buffering ICE candidate for later.")
            bufferedIceCandidates.add(iceCandidate)
            return
        }

        peerConnection?.addIceCandidate(iceCandidate)
    }

    // Add this function to process buffered candidates after the PeerConnection is created
    fun processPendingIceCandidates() {
        if (peerConnection != null && bufferedIceCandidates.isNotEmpty()) {
            Log.d(TAG, "Processing ${bufferedIceCandidates.size} buffered ICE candidates")
            bufferedIceCandidates.forEach { candidate ->
                peerConnection?.addIceCandidate(candidate)
            }
            bufferedIceCandidates.clear()
        }
    }

    // Handle incoming SDP offers/answers
    fun handleRemoteSessionDescription(type: String, sdp: String, fromId: String) {
        Log.d(TAG, "[handleRemoteSDP] Processing $type from $fromId")

        // Get type from payload if it exists
        val effectiveType = if (type.equals("offer", ignoreCase = true) ||
            type.equals("answer", ignoreCase = true)) {
            type
        } else {
            "offer" // Default if not specified
        }

        val canonicalType = SessionDescription.Type.fromCanonicalForm(effectiveType)
        val sessionDescription = SessionDescription(canonicalType, sdp)

        // Buffering logic
        if (peerConnection == null) {
            Log.w(TAG, "[handleRemoteSDP] PeerConnection not ready. Buffering $type from $fromId")
            if (type.equals("offer", ignoreCase = true)) {
                bufferedRemoteOffer = sessionDescription
                bufferedOfferFromId = fromId
                Log.d(TAG, "[handleRemoteSDP] SDP offer buffered for later processing!")
            }
            return
        }

        val currentState = peerConnection?.signalingState()
        Log.d(TAG, "[handleRemoteSDP] Setting remote $type (current state: $currentState)")

        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                val newState = peerConnection?.signalingState()
                Log.d(TAG, "[handleRemoteSDP] Remote SDP set successfully! New state: $newState")

                if (type.equals("offer", ignoreCase = true)) {
                    // IMPORTANT: Immediately respond to offer after successfully setting remote description
                    Log.d(TAG, "[handleRemoteSDP] Creating answer immediately...")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        createAndSendAnswer(fromId)
                    }, 300)  // Small delay for stability
                }
                processPendingIceCandidates()
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "[handleRemoteSDP] Failed to set remote description: $error")
            }

            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(error: String) {}
        }, sessionDescription)
    }

    // Create and send SDP offer to web client
    fun createAndSendOffer(toId: String) {
        if (peerConnection == null) {
            Log.e(TAG, "[createOffer] PC is null.")
            return
        }

        val currentState = peerConnection?.signalingState()
        if (currentState != PeerConnection.SignalingState.STABLE) {
            Log.e(TAG, "[createOffer] Cannot create offer. Invalid state: $currentState")
            return
        }

        Log.d(TAG, "[createOffer] Creating offer for $toId...")
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                Log.d(TAG, "[createOffer] Offer created successfully.")
                setLocalDescriptionAndSend(sdp, toId)
            }

            override fun onCreateFailure(error: String) {
                Log.e(TAG, "[createOffer] Offer creation failed: $error")
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, peerConnectionConstraints)
    }

    // Create and send SDP answer
    fun createAndSendAnswer(toId: String) {
        if (peerConnection == null) {
            Log.e(TAG, "[createAndSendAnswer] PeerConnection is null, cannot create answer!")
            return
        }

        val currentState = peerConnection?.signalingState()
        Log.d(TAG, "[createAndSendAnswer] Creating answer, current state: $currentState")

        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                Log.d(TAG, "[createAndSendAnswer] Answer created successfully!")

                // Immediately set local description
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        Log.d(TAG, "[createAndSendAnswer] Local SDP set, sending answer to $toId")
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

    private fun setLocalDescriptionAndSend(sdp: SessionDescription, toId: String) {
        if (peerConnection == null) { Log.e(TAG, "[setLocalSDP] PC is null."); return; }
        Log.d(TAG, "[setLocalSDP] Setting local ${sdp.type}...")
        peerConnection?.setLocalDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d(TAG, "[setLocalSDP] Local SDP (${sdp.type}) set successfully. PC State: ${peerConnection?.signalingState()}")
                sendSdp(sdp, toId)
            }
            override fun onSetFailure(error: String?) { Log.e(TAG, "[setLocalSDP] Error setting local SDP (${sdp.type}): $error") }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, sdp)
    }

    // Send SDP offer/answer to peer
    private fun sendSdp(sdp: SessionDescription, toId: String) {
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

        // Proper SDP message format
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

        Log.i(TAG, "[sendSdp] Sending ${sdp.type.canonicalForm()} to $toId")
        coroutineScope.launch {
            try {
                webSocketService.sendRawMessage(message.toString())
                Log.d(TAG, "[sendSdp] Message sent successfully!")
            } catch (e: Exception) {
                Log.e(TAG, "[sendSdp] Error sending message", e)
            }
        }
    }

    // Send ICE candidate to peer
    private fun sendIceCandidate(iceCandidate: IceCandidate, toId: String) {
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

        val payload = JSONObject().apply {
            put("candidate", iceCandidate.sdp)
            put("sdpMid", iceCandidate.sdpMid)
            put("sdpMLineIndex", iceCandidate.sdpMLineIndex)
        }

        val message = JSONObject().apply {
            put("type", "ice-candidate") // Use correct message type
            put("fromId", deviceId)
            put("toId", toId)
            put("payload", payload)
        }

        Log.d(TAG, "[sendIceCandidate] Sending candidate to $toId")
        coroutineScope.launch {
            try {
                webSocketService.sendRawMessage(message.toString())
                Log.d(TAG, "[sendIceCandidate] Message sent successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "[sendIceCandidate] Error sending message via WebSocket", e)
            }
        }
    }

    // Stop screen capture and clean up resources
    fun stopScreenCapture() {
        Log.d(TAG, "[stopScreenCapture] Attempting... isCapturing: $isCapturing")

        // Clear any pending offer immediately
        bufferedRemoteOffer = null
        bufferedOfferFromId = null

        // Stop Capturer
        try {
            screenCapturer?.stopCapture()
            Log.d(TAG, "[stopScreenCapture] Capturer stopCapture() called.")
        } catch (e: Exception) {
            Log.e(TAG, "[stopScreenCapture] Error stopping screen capturer", e)
        } finally {
            screenCapturer = null
        }

        // Dispose Video Track
        try {
            localVideoTrack?.dispose()
            Log.d(TAG, "[stopScreenCapture] Local video track disposed.")
        } catch (e: Exception) {
            Log.e(TAG, "[stopScreenCapture] Error disposing local video track", e)
        } finally {
            localVideoTrack = null
        }

        // Dispose Video Source
        try {
            videoSource?.dispose()
            Log.d(TAG, "[stopScreenCapture] Video source disposed.")
        } catch (e: Exception) {
            Log.e(TAG, "[stopScreenCapture] Error disposing video source", e)
        } finally {
            videoSource = null
        }

        // Dispose Surface Texture Helper
        try {
            surfaceTextureHelper?.dispose()
            Log.d(TAG, "[stopScreenCapture] SurfaceTextureHelper disposed.")
        } catch (e: Exception) {
            Log.e(TAG, "[stopScreenCapture] Error disposing SurfaceTextureHelper", e)
        } finally {
            surfaceTextureHelper = null
        }

        // Close Peer Connection
        try {
            peerConnection?.close()
            Log.d(TAG,"[stopScreenCapture] PeerConnection closed.")
        } catch (e: Exception) {
            Log.e(TAG,"[stopScreenCapture] Error closing PeerConnection", e)
        } finally {
            peerConnection = null
        }

        // Reset State
        isCapturing = false
        resultCode = null
        Log.d(TAG, "[stopScreenCapture] Cleanup finished. isCapturing: $isCapturing")
    }

    // Release all resources
    fun release() {
        Log.i(TAG, "[release] Releasing resources...")
        bufferedRemoteOffer = null
        bufferedOfferFromId = null

        stopScreenCapture() // Calls cleanup

        if (peerConnectionFactory != null) {
            try {
                peerConnectionFactory?.dispose()
                Log.d(TAG, "[release] PeerConnectionFactory disposed.")
            } catch (e: Exception) {
                Log.e(TAG, "[release] Error disposing PeerConnectionFactory", e)
            } finally {
                peerConnectionFactory = null
            }
        }

        if (rootEglBase != null) {
            try {
                rootEglBase!!.release()
                Log.d(TAG,"[release] EglBase released.")
            } catch (e: Exception) {
                Log.e(TAG,"[release] Error releasing EglBase", e)
            } finally {
                rootEglBase = null
            }
        }

        Log.i(TAG, "[release] Finished.")
    }

    interface AppIdProvider { fun getDeviceId(): String }
}