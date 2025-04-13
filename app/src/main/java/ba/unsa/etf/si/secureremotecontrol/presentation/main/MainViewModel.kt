package ba.unsa.etf.si.secureremotecontrol.presentation.main

import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ba.unsa.etf.si.secureremotecontrol.data.api.WebSocketService
import ba.unsa.etf.si.secureremotecontrol.data.datastore.TokenDataStore
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

@HiltViewModel
class MainViewModel @Inject constructor(
    private val webSocketService: WebSocketService,
    @ApplicationContext private val context: Context,
    private val tokenDataStore: TokenDataStore
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
            } catch (e: Exception) {
                _sessionState.value = SessionState.Error("Failed to send confirmation: ${e.localizedMessage}")
            }
        }
    }

    private fun observeMessages() {
        messageObservationJob = viewModelScope.launch {
            webSocketService.observeMessages().collect { message ->
                val response = JSONObject(message)
                when (response.getString("type")) {
                    "info" -> {
                        _sessionState.value = SessionState.Waiting
                    }
                    "error" -> {
                        _sessionState.value = SessionState.Error(response.getString("message"))
                        timeoutJob?.cancel() // Cancel timeout on error
                    }
                    "approved" -> {
                        _sessionState.value = SessionState.Accepted
                    }
                    "rejected" -> {
                        _sessionState.value = SessionState.Rejected
                    }
                    else -> {
                        _sessionState.value = SessionState.Idle
                    }
                }
            }
        }
    }

    fun resetSessionState() {
        _sessionState.value = SessionState.Idle
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
    data class Error(val message: String) : SessionState()
}