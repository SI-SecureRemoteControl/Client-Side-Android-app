package ba.unsa.etf.si.secureremotecontrol.data.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
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

    private val rootEglBase: EglBase = EglBase.create()
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var mediaProjection: MediaProjection? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var screenCapturer: VideoCapturer? = null

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
    )

    private val peerConnectionConstraints = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
    }

    private val coroutineScope = CoroutineScope(Dispatchers.IO)

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
        val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)

        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection is null. Screen capture cannot start.")
            throw IllegalStateException("MediaProjection is null. Ensure valid resultCode and data are passed.")
        }

        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", rootEglBase.eglBaseContext)
        videoSource = peerConnectionFactory?.createVideoSource(false)

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)

        val fps = 30
        screenCapturer = createScreenCapturer(data)
        screenCapturer?.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
        screenCapturer?.startCapture(displayMetrics.widthPixels / 2, displayMetrics.heightPixels / 2, fps)

        localVideoTrack = peerConnectionFactory?.createVideoTrack("video0", videoSource)

        createPeerConnection(fromId)
    }

    private fun createScreenCapturer(data: Intent): VideoCapturer {
        return ScreenCapturerAndroid(data, null)
    }

    private fun createPeerConnection(fromId: String) {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(signalingState: PeerConnection.SignalingState) {
                Log.d(TAG, "onSignalingChange: $signalingState")
            }

            override fun onIceConnectionChange(iceConnectionState: PeerConnection.IceConnectionState) {
                Log.d(TAG, "onIceConnectionChange: $iceConnectionState")
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

        peerConnection?.addTrack(localVideoTrack, listOf("ARDAMS"))
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
            put("fromId", (context.applicationContext as? AppIdProvider)?.getDeviceId() ?: "")
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

        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d(TAG, "Remote SDP set successfully")
                if (type == "offer") {
                    createAndSendAnswer(fromId)
                }
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Error setting remote SDP: $error")
            }

            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, sessionDescription)
    }

    fun createAndSendAnswer(toId: String) {
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        Log.d(TAG, "Local answer SDP set successfully")
                        sendAnswer(sdp, toId)
                    }

                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "Error setting local SDP: $error")
                    }

                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sdp)
            }

            override fun onCreateFailure(error: String) {
                Log.e(TAG, "Answer creation failed: $error")
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, peerConnectionConstraints)
    }

    private fun sendAnswer(sdp: SessionDescription, toId: String) {
        val payload = JSONObject().apply {
            put("type", sdp.type.canonicalForm())
            put("sdp", sdp.description)
        }

        val message = JSONObject().apply {
            put("type", "answer")
            put("fromId", (context.applicationContext as? AppIdProvider)?.getDeviceId() ?: "")
            put("toId", toId)
            put("payload", payload)
        }

        coroutineScope.launch {
            webSocketService.sendRawMessage(message.toString())
        }
    }

    fun stopScreenCapture() {
        try {
            screenCapturer?.stopCapture()
            screenCapturer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping screen capture", e)
        }
    }

    fun release() {
        try {
            stopScreenCapture()
            peerConnection?.dispose()
            peerConnection = null
            videoSource?.dispose()
            videoSource = null
            surfaceTextureHelper?.dispose()
            surfaceTextureHelper = null
            peerConnectionFactory?.dispose()
            peerConnectionFactory = null

            // Check if mediaProjection is not null before stopping it
           /* mediaProjection?.stop()
            mediaProjection = null*/
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing resources", e)
        }
    }

    interface AppIdProvider {
        fun getDeviceId(): String
    }
}