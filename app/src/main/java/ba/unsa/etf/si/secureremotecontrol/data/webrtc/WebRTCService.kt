
package ba.unsa.etf.si.secureremotecontrol.data.webrtc

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
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
import android.provider.Settings
@Singleton
class WebRTCService @Inject constructor(
    private val context: Context,
    private val webSocketService: WebSocketService
) {
    private val TAG = "WebRTCService"
    private var resultCode: Int? = null
    // Make EglBase nullable for safer initialization/release
    private var rootEglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var screenCapturer: VideoCapturer? = null
    private var isCapturing = false

    // --- Buffering variables (Keep this logic) ---
    private var bufferedRemoteOffer: SessionDescription? = null
    private var bufferedOfferFromId: String? = null
    // --- End Buffering variables ---

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

    // **** REVISED startScreenCapture ****
    fun startScreenCapture(resultCode: Int, data: Intent, fromId: String) {
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

            // --- Create VideoSource (BEFORE PeerConnection) ---
            Log.d(TAG,"[startScreenCapture] Creating VideoSource...")
            tempVideoSource = peerConnectionFactory!!.createVideoSource(true) // isScreencast=true
            if (tempVideoSource == null) {
                throw IllegalStateException("VideoSource creation failed.") // Error logged inside catch
            }
            videoSource = tempVideoSource // Assign to member variable *after* success
            Log.d(TAG, "[startScreenCapture] VideoSource created: ID=$, State=${videoSource?.state()}")
            // --- End VideoSource ---

            // --- Create VideoTrack (BEFORE PeerConnection) ---
            Log.d(TAG, "[startScreenCapture] Creating video track. VideoSource state: ${videoSource?.state()}")
            tempLocalVideoTrack = peerConnectionFactory!!.createVideoTrack("video0", videoSource) // Use non-null source
            if (tempLocalVideoTrack == null) {
                throw IllegalStateException("LocalVideoTrack creation failed.") // Error logged inside catch
            }
            localVideoTrack = tempLocalVideoTrack // Assign to member variable *after* success
            Log.d(TAG, "[startScreenCapture] Local video track created: ID=${localVideoTrack?.id()}, State=${localVideoTrack?.state()}")
            // --- End VideoTrack ---

            // --- Now setup the Screen Capturer ---
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
            // --- End Screen Capturer Setup ---


            // --- Create PeerConnection (AFTER media is ready) ---
            // videoSource and localVideoTrack member variables are now guaranteed non-null
            createPeerConnection(fromId)
            Log.d(TAG, "[startScreenCapture] Peer connection creation process finished.") // Was 'initiated'


            // --- Process buffered offer --- (Keep this logic)
            processBufferedOffer()
            // --- End processing buffered offer ---

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

    // **** REVISED createPeerConnection ****
    @SuppressLint("SuspiciousIndentation")
     fun createPeerConnection(remotePeerId: String) {
        if (peerConnectionFactory == null) { Log.e(TAG, "[createPeerConnection] Factory is null."); return }
        if (peerConnection != null) { Log.w(TAG, "[createPeerConnection] Closing existing PC."); peerConnection?.close(); peerConnection = null }

        // --- Ensure Track Exists BEFORE creating PC ---
        if (localVideoTrack == null) {
            Log.e(TAG, "[createPeerConnection] CRITICAL: localVideoTrack is null. Aborting PC creation.")
            // This *shouldn't* happen if startScreenCapture checks are correct, but good safety.
            return
        }
        // --- End Track Check ---

        Log.d(TAG, "[createPeerConnection] Creating PeerConnection object...")
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(signalingState: PeerConnection.SignalingState) {
                Log.d(TAG, "[Observer] onSignalingChange: $signalingState")
                // If state becomes STABLE after setting local/remote descriptions,
                // it might indicate readiness.
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

        // --- Add Track (AFTER PC object created) ---
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
        // --- End Add Track ---
    }

    // **** NEW Helper function ****
    private fun processBufferedOffer() {
        if (peerConnection != null && bufferedRemoteOffer != null && bufferedOfferFromId != null) {
            Log.i(TAG, "[processBufferedOffer] PeerConnection ready. Processing buffered remote offer from $bufferedOfferFromId. PC State: ${peerConnection?.signalingState()}")
            val offerToProcess = bufferedRemoteOffer!! // Capture current value
            val fromIdToProcess = bufferedOfferFromId!!
            // Clear buffer *before* processing to prevent re-entry if processing fails
            bufferedRemoteOffer = null
            bufferedOfferFromId = null
            // Call handleRemoteDescription now that PC and local track are ready
            handleRemoteSessionDescription(
                offerToProcess.type.canonicalForm(),
                offerToProcess.description,
                fromIdToProcess
            )
        } else if (peerConnection == null) {
            Log.w(TAG, "[processBufferedOffer] Cannot process buffered offer, PeerConnection is null.")
        } else {
            // No buffered offer, nothing to do
            // Log.d(TAG, "[processBufferedOffer] No buffered offer to process.")
        }
    }


    fun handleRemoteIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        // ... (Check peerConnection != null) ...
        Log.d(TAG, "[handleRemoteIceCandidate] Adding remote candidate.")
        if (peerConnection == null) { Log.w(TAG, "PC is null, cannot add ICE candidate."); return; }
        val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        peerConnection?.addIceCandidate(iceCandidate)
    }

    /*private fun sendIceCandidate(iceCandidate: IceCandidate, toId: String) {
        // ... (same JSON creation) ...
        Log.d(TAG, "[sendIceCandidate] Sending candidate to $toId")
        coroutineScope.launch { /* ... send message ... */ }
    }*/

    // **** REVISED handleRemoteSessionDescription ****
    fun handleRemoteSessionDescription(type: String, sdp: String, fromId: String) {
        val canonicalType = SessionDescription.Type.fromCanonicalForm(type)
        val sessionDescription = SessionDescription(canonicalType, sdp)
        Log.d(TAG, "[handleRemoteSDP] Received $type from $fromId.")

        // --- Buffering Logic (Check if PC is ready) ---
        if (peerConnection == null || localVideoTrack == null) { // Also check if local track is ready
            Log.w(TAG, "[handleRemoteSDP] PC (${peerConnection != null}) or Track (${localVideoTrack != null}) not ready. Buffering $type from $fromId.")
            if (type.equals("offer", ignoreCase = true)) {
                bufferedRemoteOffer = sessionDescription
                bufferedOfferFromId = fromId
            } else {
                Log.w(TAG, "[handleRemoteSDP] Ignoring non-offer SDP ($type) because setup is not complete.")
            }
            return
        }
        // --- End Buffering Logic ---

        // --- Check Current State BEFORE setRemoteDescription ---
        val currentState = peerConnection?.signalingState()
        Log.i(TAG, "[handleRemoteSDP] PC ready (State: $currentState). Setting remote ${type.toUpperCase()}...")

        // Validate state for setting remote description (especially for offer)
        if (type.equals("offer", ignoreCase = true) && currentState != PeerConnection.SignalingState.STABLE && currentState != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
            // It might be valid to receive an offer in HAVE_LOCAL_OFFER for rollback, but often indicates an issue.
            // Setting offer when in HAVE_REMOTE_OFFER is definitely wrong (glare).
            Log.e(TAG, "[handleRemoteSDP] Invalid state ($currentState) to set remote OFFER. Aborting.")
            // Consider sending an error back or resetting
            return
        }
        if (type.equals("answer", ignoreCase = true) && currentState != PeerConnection.SignalingState.HAVE_LOCAL_OFFER && currentState != PeerConnection.SignalingState.HAVE_REMOTE_PRANSWER) {
            Log.e(TAG, "[handleRemoteSDP] Invalid state ($currentState) to set remote ANSWER. Aborting.")
            // Consider sending an error back or resetting
            return
        }
        // --- End State Check ---


        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                val newState = peerConnection?.signalingState() // Check state AFTER success
                Log.d(TAG, "[handleRemoteSDP] Remote SDP ($type) set successfully. New State: $newState")
                // Only create answer if the remote description was an OFFER and state is correct
                if (type.equals("offer", ignoreCase = true)) {
                    if(newState == PeerConnection.SignalingState.HAVE_REMOTE_OFFER) {
                        Log.d(TAG, "[handleRemoteSDP] State is HAVE_REMOTE_OFFER, creating answer...")
                        createAndSendAnswer(fromId)
                    } else {
                        // THIS is where the previous error likely originated.
                        // onSetSuccess fired, but state wasn't HAVE_REMOTE_OFFER yet.
                        Log.e(TAG, "[handleRemoteSDP] setRemoteDescription(offer) succeeded, but state is $newState (Expected HAVE_REMOTE_OFFER). CANNOT create answer.")
                        // Potentially wait briefly and check state again, or signal an error.
                    }
                } else {
                    Log.d(TAG, "[handleRemoteSDP] Remote description was an ANSWER. Connection should establish.")
                }
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "[handleRemoteSDP] Error setting remote SDP ($type): $error")
            }

            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(error: String) {}
        }, sessionDescription)
    }


    fun createAndSendAnswer(toId: String) {
        if (peerConnection == null) { Log.e(TAG, "[createAnswer] PC is null."); return; }

        // --- Check State BEFORE creating answer ---
        val currentState = peerConnection?.signalingState()
        if (currentState != PeerConnection.SignalingState.HAVE_REMOTE_OFFER && currentState != PeerConnection.SignalingState.HAVE_LOCAL_PRANSWER) {
            Log.e(TAG, "[createAnswer] Cannot create answer. Invalid state: $currentState")
            return // Abort if state is wrong
        }
        // --- End State Check ---

        Log.d(TAG, "[createAnswer] State is valid ($currentState). Creating answer for $toId...")
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                Log.d(TAG, "[createAnswer] Answer created successfully.")
                setLocalDescriptionAndSend(sdp, toId)
            }

            override fun onCreateFailure(error: String) {
                Log.e(TAG, "[createAnswer] Answer creation failed: $error")
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, peerConnectionConstraints)
    }

    private fun setLocalDescriptionAndSend(sdp: SessionDescription, toId: String) {
        // ... (add check for peerConnection != null) ...
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

    /*private fun sendSdp(sdp: SessionDescription, toId: String) {
        // ... (same JSON creation and sending) ...
        Log.i(TAG, "[sendSdp] Sending ${sdp.type} to $toId")
        coroutineScope.launch { webSocketService.sendRawMessage("/* ... message ... */") }
    }*/

    // **** CORRECTED SDP Sending Logic ****
    private fun sendSdp(sdp: SessionDescription, toId: String) {
        // CORRECTED PAYLOAD: Only include the essential SDP description
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val payload = JSONObject().apply {
            put("sdp", sdp.description) // JUST the SDP string
            // REMOVED: put("type", sdp.type.canonicalForm())
        }
        val messageType = sdp.type.canonicalForm() // Top-level type ("offer" or "answer")

        val message = JSONObject().apply {
            put("type", messageType) // Use the correct top-level type
            put("fromId", deviceId) // Use your actual ID mechanism
            put("toId", toId) // Target recipient ID (e.g., "webadmin")
            put("payload", payload) // The corrected payload object
        }
        // Log the structure being sent for verification
        Log.i(TAG, "[sendSdp] Sending $messageType to $toId with payload: ${payload.toString()}")
        coroutineScope.launch {
            try { // Add try-catch around send
                webSocketService.sendRawMessage(message.toString())
                Log.d(TAG, "[sendSdp] Message sent successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "[sendSdp] Error sending message via WebSocket", e)
                // Handle potential send errors (e.g., update UI, attempt reconnect)
            }
        }
    }

    // **** CORRECTED ICE Candidate Sending Logic ****
    private fun sendIceCandidate(iceCandidate: IceCandidate, toId: String) {
        // CORRECTED PAYLOAD: Only include essential ICE details
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

        val payload = JSONObject().apply {
            put("candidate", iceCandidate.sdp)
            put("sdpMid", iceCandidate.sdpMid)
            put("sdpMLineIndex", iceCandidate.sdpMLineIndex)
        }

        val message = JSONObject().apply {
            put("type", "ice-candidate") // Top-level type
            put("fromId", deviceId)
            put("toId", toId)
            put("payload", payload) // Corrected payload
        }
        Log.d(TAG, "[sendIceCandidate] Sending candidate to $toId with payload: ${payload.toString()}")
        coroutineScope.launch {
            try { // Add try-catch around send
                webSocketService.sendRawMessage(message.toString())
                Log.d(TAG, "[sendIceCandidate] Message sent successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "[sendIceCandidate] Error sending message via WebSocket", e)
            }
        }
    }

    fun stopScreenCapture() {
        Log.d(TAG, "[stopScreenCapture] Attempting... isCapturing: $isCapturing")
        if (!isCapturing && screenCapturer == null) { Log.d(TAG, "[stopScreenCapture] Already stopped."); return }

        bufferedRemoteOffer = null // Clear buffer
        bufferedOfferFromId = null

        try { screenCapturer?.stopCapture(); Log.d(TAG, "[stopScreenCapture] Capturer stop issued.") }
        catch (e: Exception) { Log.e(TAG, "[stopScreenCapture] Error stopping capturer", e) }
        finally { screenCapturer = null }

        // Clean up tracks, sources, helpers first
        localVideoTrack?.dispose(); localVideoTrack = null
        videoSource?.dispose(); videoSource = null
        surfaceTextureHelper?.dispose(); surfaceTextureHelper = null

        // Close and nullify peer connection
        if (peerConnection != null) {
            peerConnection?.close()
            peerConnection = null
            Log.d(TAG,"[stopScreenCapture] PeerConnection closed and set to null.")
        }

        isCapturing = false
        resultCode = null
        Log.d(TAG, "[stopScreenCapture] Finished. isCapturing: $isCapturing")
    }

    fun release() {
        Log.i(TAG, "[release] Releasing resources...")
        bufferedRemoteOffer = null // Clear buffer
        bufferedOfferFromId = null

        stopScreenCapture() // Calls cleanup

        if (peerConnectionFactory != null) {
            peerConnectionFactory?.dispose()
            peerConnectionFactory = null
            Log.d(TAG, "[release] PeerConnectionFactory disposed.")
        }
        if (rootEglBase != null) {
            try { rootEglBase!!.release(); Log.d(TAG,"[release] EglBase released.") }
            catch (e: Exception) { Log.e(TAG,"[release] Error releasing EglBase", e) }
            finally { rootEglBase = null }
        }
        Log.i(TAG, "[release] Finished.")
    }

    interface AppIdProvider { fun getDeviceId(): String }
}
