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
import androidx.lifecycle.LifecycleOwner
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

    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Idle)
    val sessionState: StateFlow<SessionState> = _sessionState

    private var messageObservationJob: Job? = null

    init {
        connectAndObserveMessages()
    }

    private fun connectAndObserveMessages() {
        viewModelScope.launch {
            try {
                webSocketService.connectWebSocket()
                observeMessages()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to connect WebSocket: ${e.localizedMessage}")
                _sessionState.value = SessionState.Error("Failed to connect WebSocket")
            }
        }
    }

    private var timeoutJob: Job? = null

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
                delay(30000L) // 10 seconds timeout
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
                Log.d("MainViewModel", "Raw message received: $message") // Log the raw message for debugging
                try { // Add try-catch around JSON parsing
                    val response = JSONObject(message)
                    val messageType = response.optString("type") // Use optString for safety

                    when (messageType) {
                        "info" -> {
                            Log.d("MainViewModel", "Received info message")
                            _sessionState.value = SessionState.Waiting
                        }
                        "error" -> {
                            val errorMessage = response.optString("message", "Unknown error")
                            Log.e("MainViewModel", "Received error message: $errorMessage")
                            _sessionState.value = SessionState.Error(errorMessage)
                            timeoutJob?.cancel() // Cancel timeout on error
                        }
                        "approved" -> {
                            Log.d("MainViewModel", "Received approved message")
                            _sessionState.value = SessionState.Accepted
                            // Optionally, you might receive the sessionId here if needed later
                            // val sessionId = response.optString("sessionId")
                        }
                        "rejected" -> {
                            val reason = response.optString("message", "Session rejected")
                            Log.w("MainViewModel", "Received rejected message: $reason")
                            _sessionState.value = SessionState.Rejected
                            // Optionally, handle session ID if provided
                            // val sessionId = response.optString("sessionId")
                        }
                        "offer" -> { // Handle incoming SDP offer
                            Log.d("MainViewModel", "Received offer message")
                            val fromId = response.optString("fromId", "unknown_sender") // Get fromId safely

                            // Safely get the top-level payload object
                            val topLevelPayload = response.optJSONObject("payload")
                            var sdp: String? = null // Use nullable String to store found SDP

                            if (topLevelPayload != null) {
                                Log.d("MainViewModel", "Found topLevelPayload: $topLevelPayload")

                                // *** START: Handle Nested Structure ***
                                // Try to get the 'parsedMessage' object within the top-level payload
                                val parsedMessageObject = topLevelPayload.optJSONObject("parsedMessage")

                                if (parsedMessageObject != null) {
                                    Log.d("MainViewModel", "Found parsedMessageObject: $parsedMessageObject")
                                    // Try to get the *inner* 'payload' object within 'parsedMessage'
                                    val innerPayloadObject = parsedMessageObject.optJSONObject("payload")

                                    if (innerPayloadObject != null) {
                                        Log.d("MainViewModel", "Found innerPayloadObject: $innerPayloadObject")
                                        // Try to get the 'sdp' string from the *inner* payload
                                        sdp = innerPayloadObject.optString("sdp").takeIf { it.isNotEmpty() } // Get SDP only if it's not empty
                                        if (sdp != null) {
                                            Log.d("MainViewModel", "Successfully extracted SDP from nested structure.")
                                        } else {
                                            Log.w("MainViewModel", "Found inner payload, but 'sdp' key was missing or empty.")
                                        }
                                    } else {
                                        Log.w("MainViewModel", "'parsedMessage' object did not contain an 'payload' object.")
                                    }
                                } else {
                                    // *** END: Handle Nested Structure ***

                                    // *** FALLBACK: Check for direct SDP (optional, but good practice) ***
                                    // If 'parsedMessage' wasn't found, maybe the server fixed itself?
                                    // Check if 'sdp' exists directly in the top-level payload.
                                    Log.d("MainViewModel", "Did not find 'parsedMessage' object. Checking for direct 'sdp' in topLevelPayload.")
                                    sdp = topLevelPayload.optString("sdp").takeIf { it.isNotEmpty() }
                                    if (sdp != null) {
                                        Log.d("MainViewModel", "Found SDP directly in topLevelPayload (fallback).")
                                    }
                                    // *** END FALLBACK ***
                                }

                            } else {
                                // Top-level payload object is missing entirely
                                Log.e("MainViewModel", "Received 'offer' message from $fromId, but 'payload' object is missing or not a JSON object. Full message: $message")
                                _sessionState.value = SessionState.Error("Invalid offer received (missing payload)")
                            }

                            // --- Process the result ---
                            if (sdp != null) {
                                // We successfully extracted the SDP from *somewhere* (nested or direct)
                                Log.d("MainViewModel", "Processing offer with SDP from $fromId")
                                try {
                                    webRTCManager.confirmSessionAndStartStreaming(fromId, sdp)
                                } catch (e: Exception) {
                                    Log.e("MainViewModel", "Error calling confirmSessionAndStartStreaming", e)
                                    _sessionState.value = SessionState.Error("Failed to process offer: ${e.message}")
                                }
                            } else {
                                // Failure: SDP was not found in either the nested or direct location
                                Log.e("MainViewModel", "Received 'offer' message from $fromId, but failed to find 'sdp' in any expected location within the payload. Payload received: ${topLevelPayload}")
                                _sessionState.value = SessionState.Error("Invalid offer structure received (SDP not found)")
                            }
                        } // End of "offer" case

                        // Handle other potential message types from the server if necessary
                        "session_confirmed" -> {
                            Log.d("MainViewModel", "Server confirmed session start.")
                            // Update UI or state if needed
                        }
                        "" -> {
                            Log.w("MainViewModel", "Received message with empty type.")
                        }
                        else -> {
                            Log.w("MainViewModel", "Received unhandled message type: $messageType")
                        }
                    }
                } catch (e: JSONException) {
                    Log.e("MainViewModel", "Failed to parse incoming WebSocket message: $message", e)
                    // Optionally update UI to show a generic parsing error
                    _sessionState.value = SessionState.Error("Failed to understand server message")
                }
            }
        }
    }
   /* fun startStreaming(resultCode: Int, data: Intent, fromId: String) {
        viewModelScope.launch {
            try {
                //webRTCManager.startScreenCapture(resultCode, data, fromId)
                val intent = ScreenSharingService.getStartIntent(context, resultCode, data, fromId)
                context.startForegroundService(intent)
                _sessionState.value = SessionState.Streaming
            } catch (e: Exception) {
                _sessionState.value = SessionState.Error("Failed to start streaming: ${e.localizedMessage}")
            }
        }
    }*/

    fun startStreaming(resultCode: Int, data: Intent, fromId: String) {
        viewModelScope.launch {
            try {
                //
                // Start the foreground service for screen sharing first
                val intent = ScreenSharingService.getStartIntent(context, resultCode, data, fromId)
                context.startForegroundService(intent)

                // Ensure the screen capture is stopped before starting a new one
                //webRTCManager.stopScreenCapture()



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

