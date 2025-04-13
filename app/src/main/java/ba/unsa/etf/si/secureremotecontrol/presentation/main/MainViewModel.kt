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

    private fun observeMessages() {
        viewModelScope.launch {
            webSocketService.observeMessages().collect { message ->
                Log.d("MainViewModel", "Message received: $message")
                val response = JSONObject(message)
                when (response.getString("type")) {
                    "success" -> {
                        _sessionState.value = SessionState.Accepted
                    }
                    "error" -> {
                        _sessionState.value = SessionState.Error(response.getString("message"))
                    }
                    else -> {
                        _sessionState.value = SessionState.Idle
                    }
                }
            }
        }
    }

    fun requestSession(to: String) {
        viewModelScope.launch {
            val token = tokenDataStore.token.firstOrNull()
            if (token.isNullOrEmpty()) {
                _sessionState.value = SessionState.Error("Token not found")
                return@launch
            }

            _sessionState.value = SessionState.Requesting
            try {
                val from = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                webSocketService.sendSessionRequest(from, token)
            } catch (e: Exception) {
                _sessionState.value = SessionState.Error("Error: ${e.localizedMessage}")
            }
        }
    }

    fun resetSessionState() {
        _sessionState.value = SessionState.Idle
    }
}

sealed class SessionState {
    object Idle : SessionState()
    object Requesting : SessionState()
    object Timeout : SessionState()
    object Accepted : SessionState()
    object Rejected : SessionState()
    data class Error(val message: String) : SessionState()
}