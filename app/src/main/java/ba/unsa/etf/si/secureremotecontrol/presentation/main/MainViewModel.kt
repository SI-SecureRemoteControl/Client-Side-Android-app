package ba.unsa.etf.si.secureremotecontrol.presentation.main

import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ba.unsa.etf.si.secureremotecontrol.data.api.WebSocketService
import ba.unsa.etf.si.secureremotecontrol.data.datastore.TokenDataStore
import ba.unsa.etf.si.secureremotecontrol.data.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject
import ba.unsa.etf.si.secureremotecontrol.data.webrtc.WebRTCManager
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.LifecycleOwner
import ba.unsa.etf.si.secureremotecontrol.service.RemoteControlAccessibilityService
import ba.unsa.etf.si.secureremotecontrol.service.ScreenSharingService
import org.json.JSONException

@HiltViewModel
class MainViewModel @Inject constructor(
    private val webSocketService: WebSocketService,
    private val apiService: ApiService,
    @ApplicationContext private val context: Context,
    private val tokenDataStore: TokenDataStore,
    private val webRTCManager: WebRTCManager
) : ViewModel() {

    private val TAG = "MainViewModel"

    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Idle)
    val sessionState: StateFlow<SessionState> = _sessionState

    private var messageObservationJob: Job? = null
    private var timeoutJob: Job? = null

    init {
        connectAndObserveMessages()
    }

    private fun connectAndObserveMessages() {
        viewModelScope.launch {
            try {
                webSocketService.connectWebSocket()
                observeMessages()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect WebSocket: ${e.localizedMessage}")
                _sessionState.value = SessionState.Error("Failed to connect WebSocket")
            }
        }
    }

    fun requestSession() {
        viewModelScope.launch {
            val token = tokenDataStore.token.firstOrNull()
            if (token.isNullOrEmpty()) {
                _sessionState.value = SessionState.Error("Token not found")
                return@launch
            }

            _sessionState.value = SessionState.Requesting

            // Start a timeout job
            timeoutJob = viewModelScope.launch {
                delay(30000L) // 30 seconds timeout
                if (_sessionState.value == SessionState.Requesting || _sessionState.value == SessionState.Waiting) {
                    _sessionState.value = SessionState.Timeout
                }
            }

            try {
                val from = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                webSocketService.sendSessionRequest(from, token)
            } catch (e: Exception) {
                _sessionState.value = SessionState.Error("Error: ${e.localizedMessage}")
                timeoutJob?.cancel() // Cancel timeout if an error occurs
            }
        }
    }

    fun sendSessionFinalConfirmation(decision: Boolean) {
        viewModelScope.launch {
            val token = tokenDataStore.token.firstOrNull()
            if (token.isNullOrEmpty()) {
                _sessionState.value = SessionState.Error("Token not found")
                return@launch
            }

            val from = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            try {
                webSocketService.sendFinalConformation(from, token, decision)
                _sessionState.value = if(decision) SessionState.Connected else SessionState.Idle
            } catch (e: Exception) {
                _sessionState.value = SessionState.Error("Failed to send confirmation: ${e.localizedMessage}")
            }
        }
    }

    fun disconnectSession() {
        viewModelScope.launch {
            val token = tokenDataStore.token.firstOrNull()
            if (token.isNullOrEmpty()) {
                _sessionState.value = SessionState.Error("Token not found")
                return@launch
            }

            val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

            try {
                val response = apiService.removeSession(
                    mapOf("token" to token, "deviceId" to deviceId)
                )

                if (response.code() == 200) {
                    resetSessionState()
                } else {
                    val errorMessage = response.body()?.get("message") as? String
                        ?: "Failed to disconnect session"
                    _sessionState.value = SessionState.Error(errorMessage)
                }
            } catch (e: Exception) {
                _sessionState.value = SessionState.Error("Error: ${e.localizedMessage}")
            }
        }
    }

    private fun observeMessages() {
        messageObservationJob = viewModelScope.launch {
            webSocketService.observeMessages().collect { message ->
                Log.d(TAG, "Raw message received: $message")
                try {
                    val response = JSONObject(message)
                    val messageType = response.optString("type", "")

                    when (messageType) {
                        "click" -> {
                            val payload = response.optJSONObject("payload")
                            val x = payload.getDouble("x").toFloat()
                            val y = payload.getDouble("y").toFloat()
                            //redo coordinates cuz relative
                            val context = RemoteControlAccessibilityService.instance

                            val displayMetrics = context?.resources?.displayMetrics
                            //val displayMetrics = context?.resources?.displayMetrics
                            val screenWidth = displayMetrics?.widthPixels ?: 0
                            val screenHeight = displayMetrics?.heightPixels?.plus(
                                getNavigationBarHeight(context)
                            )
                            //val screenHeight = displayMetrics?.heightPixels ?: 0
                            Log.d("Dims", "Phone dimens ($screenWidth) ($screenHeight")
                            val absoluteX = x * screenWidth
                            val absoluteY = y * screenHeight!! //2376 //screenHeight for OPPO as heightPixels cut off the NavBar

                            context.performClick(absoluteX, absoluteY)

                            Log.d("WebRTCManager", "Received case click at relative ($x, $y), ($absoluteX, $absoluteY)")
                        }

                        "swipe" -> {
                            val payload = response.optJSONObject("payload")
                            val startX = payload.getDouble("startX").toFloat()
                            val startY = payload.getDouble("startY").toFloat()
                            val endX = payload.getDouble("endX").toFloat()
                            val endY = payload.getDouble("endY").toFloat()
                            val velocity = payload.optDouble("velocity", 1.0)

                            val context = RemoteControlAccessibilityService.instance

                            val displayMetrics = context?.resources?.displayMetrics
                            val screenWidth = displayMetrics?.widthPixels ?: 0
                            val screenHeight = displayMetrics?.heightPixels?.plus(
                                getNavigationBarHeight(context)
                            ) ?: 0

                            // Convert relative coordinates to absolute screen coordinates
                            val absoluteStartX = startX * screenWidth
                            val absoluteStartY = startY * screenHeight
                            val absoluteEndX = endX * screenWidth
                            val absoluteEndY = endY * screenHeight

                            // Calculate the distance of the swipe
                            val distance = Math.sqrt(
                                Math.pow((absoluteEndX - absoluteStartX).toDouble(), 2.0) +
                                        Math.pow((absoluteEndY - absoluteStartY).toDouble(), 2.0)
                            ).toFloat()

                            // Calculate duration based on velocity and distance
                            // Lower velocity means longer duration (slower swipe)
                            // Higher velocity means shorter duration (faster swipe)
                            // Base duration is scaled inversely by velocity with reasonable limits
                            val baseDuration = (distance / velocity).toLong()
                            val durationMs = Math.max(100, Math.min(baseDuration, 800))

                            Log.d(TAG, "Performing swipe from ($absoluteStartX, $absoluteStartY) to " +
                                    "($absoluteEndX, $absoluteEndY) with duration $durationMs ms")

                            context?.performSwipe(
                                absoluteStartX,
                                absoluteStartY,
                                absoluteEndX,
                                absoluteEndY,
                                durationMs
                            )
                        }

                        "info" -> {
                            Log.d(TAG, "Received info message")
                            _sessionState.value = SessionState.Waiting
                        }
                        "error" -> {
                            val errorMessage = response.optString("message", "Unknown error")
                            Log.e(TAG, "Received error message: $errorMessage")
                            _sessionState.value = SessionState.Error(errorMessage)
                            timeoutJob?.cancel()
                        }
                        "approved" -> {
                            Log.d(TAG, "Received approved message")
                            _sessionState.value = SessionState.Accepted
                        }
                        "rejected" -> {
                            val reason = response.optString("message", "Session rejected")
                            Log.w(TAG, "Received rejected message: $reason")
                            _sessionState.value = SessionState.Rejected
                        }
                        "session_confirmed" -> {
                            Log.d(TAG, "Server confirmed session start.")
                        }
                        "offer" -> {
                            // Critical part - need to distinguish between SDP offers and ICE candidates
                            val payload = response.optJSONObject("payload")
                            if (payload != null) {
                                // Check if this is actually an ICE candidate message
                                val parsedMessage = payload.optJSONObject("parsedMessage")
                                if (parsedMessage != null) {
                                    val innerType = parsedMessage.optString("type", "")

                                    if (innerType == "ice-candidate") {
                                        // This is actually an ICE candidate
                                        handleIceCandidate(response)
                                    } else if (innerType == "offer") {
                                        // This is a genuine SDP offer
                                        handleSdpOffer(response)
                                    } else {
                                        Log.w(TAG, "Unknown inner message type: $innerType")
                                    }
                                } else {
                                    // Direct structure without parsedMessage
                                    handleSdpOffer(response)
                                }
                            }
                        }
                        "ice-candidate" -> {
                            handleIceCandidate(response)
                        }
                        else -> {
                            Log.d(TAG, "Unhandled message type: $messageType")
                        }
                    }
                } catch (e: JSONException) {
                    Log.e(TAG, "Failed to parse WebSocket message: $message", e)
                    _sessionState.value = SessionState.Error("Failed to parse server message")
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing message: ${e.message}", e)
                }
            }
        }
    }

    fun getNavigationBarHeight(context: Context): Int {
        val resources = context.resources
        val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            resources.getDimensionPixelSize(resourceId)
        } else {
            0
        }
    }


    private fun handleSdpOffer(response: JSONObject) {
        try {
            val fromId = response.optString("fromId", "unknown_sender")
            val payload = response.optJSONObject("payload")

            if (payload != null) {
                val parsedMessage = payload.optJSONObject("parsedMessage")

                if (parsedMessage != null) {
                    val innerPayload = parsedMessage.optJSONObject("payload")

                    if (innerPayload != null && innerPayload.has("sdp")) {
                        val sdp = innerPayload.getString("sdp")
                        Log.d(TAG, "Found SDP in nested payload structure")
                        webRTCManager.confirmSessionAndStartStreaming(fromId, sdp)
                        return
                    }
                }

                // Check direct structure just in case
                if (payload.has("sdp")) {
                    val sdp = payload.getString("sdp")
                    Log.d(TAG, "Found SDP directly in payload")
                    webRTCManager.confirmSessionAndStartStreaming(fromId, sdp)
                    return
                }
            }

            Log.e(TAG, "SDP not found in offer message: $payload")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling SDP offer: ${e.message}", e)
            _sessionState.value = SessionState.Error("Error processing offer: ${e.message}")
        }
    }

    private fun handleIceCandidate(response: JSONObject) {
        try {
            val fromId = response.optString("fromId", "unknown_sender")
            val payload = response.optJSONObject("payload")

            if (payload != null) {
                val parsedMessage = payload.optJSONObject("parsedMessage")

                if (parsedMessage != null) {
                    val innerPayload = parsedMessage.optJSONObject("payload")

                    if (innerPayload != null) {
                        val candidate = innerPayload.optString("candidate", "")
                        val sdpMid = innerPayload.optString("sdpMid", "")
                        val sdpMLineIndex = innerPayload.optInt("sdpMLineIndex", 0)

                        // Only forward if not an empty candidate
                        if (candidate.isNotEmpty()) {
                            Log.d(TAG, "Processing ICE candidate")
                            try {
                                webRTCManager.handleRtcMessage("ice-candidate", fromId, innerPayload)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error processing ICE candidate", e)
                            }
                        } else {
                            Log.d(TAG, "Received empty ICE candidate (end of candidates)")
                        }
                        return
                    }
                }

                // Check direct structure as well
                val candidate = payload.optString("candidate", "")
                if (candidate.isNotEmpty()) {
                    Log.d(TAG, "Processing ICE candidate from direct payload")
                    try {
                        webRTCManager.handleRtcMessage("ice-candidate", fromId, payload)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing direct ICE candidate", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling ICE candidate: ${e.message}", e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun startStreaming(resultCode: Int, data: Intent, fromId: String) {
        viewModelScope.launch {
            try {
                val intent = ScreenSharingService.getStartIntent(context, resultCode, data, fromId)
                context.startForegroundService(intent)
                _sessionState.value = SessionState.Streaming
            } catch (e: Exception) {
                _sessionState.value = SessionState.Error("Failed to start streaming: ${e.localizedMessage}")
            }
        }
    }

    fun startObservingRtcMessages(lifecycleOwner: LifecycleOwner) {
        viewModelScope.launch {
            webRTCManager.startObservingRtcMessages(lifecycleOwner)
        }
    }

    fun resetSessionState() {
        _sessionState.value = SessionState.Idle
        timeoutJob?.cancel()
    }

    fun stopObservingMessages() {
        messageObservationJob?.cancel()
        messageObservationJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stopObservingMessages()
        timeoutJob?.cancel()
    }
}

sealed class SessionState {
    object Idle : SessionState()
    object Requesting : SessionState()
    object Timeout : SessionState()
    object Waiting : SessionState()
    object Accepted : SessionState()
    object Rejected : SessionState()
    object Connected : SessionState()
    data class Error(val message: String) : SessionState()
    object Streaming : SessionState()
}