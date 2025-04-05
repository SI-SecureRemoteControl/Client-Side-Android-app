package ba.unsa.etf.si.secureremotecontrol

import android.content.Context
import android.util.Log
import androidx.work.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import org.json.JSONObject
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/*
responsible for setting up and managing WebRTC peer-to-peer communication,
using a DataChannel to send heartbeat messages.
*/
@Singleton
class WebRTCClient @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var isChannelOpen = false

    fun initialize() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val encoderFactory = DefaultVideoEncoderFactory(
            EglBase.create().eglBaseContext, true, true
        )
        val decoderFactory = DefaultVideoDecoderFactory(EglBase.create().eglBaseContext)

        val factoryOptions = PeerConnectionFactory.Options()
        peerConnectionFactory = PeerConnectionFactory
            .builder()
            .setOptions(factoryOptions)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    fun connect() {
        val iceServers = listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onDataChannel(dc: DataChannel) {
                dataChannel = dc
                registerDataChannelObserver()
            }

            override fun onIceCandidate(candidate: IceCandidate) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
                TODO("Not yet implemented")
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {}
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(rtpReceiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}
        })

        val init = DataChannel.Init()
        dataChannel = peerConnection?.createDataChannel("heartbeat", init)
        registerDataChannelObserver()
    }

    private fun registerDataChannelObserver() {
        Log.d("WebRTCClient", "DataChannel state: ${dataChannel?.state()}")
        dataChannel?.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(p0: Long) {}
            override fun onStateChange() {
                Log.d("WebRTCClient", "DataChannel new state: ${dataChannel?.state()}")
                isChannelOpen = dataChannel?.state() == DataChannel.State.OPEN
            }

            override fun onMessage(buffer: DataChannel.Buffer) {}
        })
    }

    fun sendHeartbeat(deviceId: String) {
        if (isChannelOpen) {
            val heartbeat = JSONObject().apply {
                put("type", "heartbeat")
                put("deviceId", deviceId)
                put("timestamp", System.currentTimeMillis())
            }
            val buffer = DataChannel.Buffer(
                ByteBuffer.wrap(heartbeat.toString().toByteArray()),
                false
            )
            dataChannel?.send(buffer)
            Log.d("WebRTCClient", "Heartbeat sent: $heartbeat")
        } else {
            Log.w("WebRTCClient", "DataChannel not open, cannot send heartbeat.")
        }
    }

    fun close() {
        dataChannel?.close()
        peerConnection?.close()
        peerConnection = null
        dataChannel = null
        isChannelOpen = false
    }
}
