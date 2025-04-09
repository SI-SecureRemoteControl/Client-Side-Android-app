package ba.unsa.etf.si.secureremotecontrol.presentation.device

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ba.unsa.etf.si.secureremotecontrol.data.models.Device
import ba.unsa.etf.si.secureremotecontrol.data.api.WebSocketService
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject
import android.util.Log
import ba.unsa.etf.si.secureremotecontrol.data.util.RegistrationPreferences

@HiltViewModel
class DeviceViewModel @Inject constructor(
    private val webSocketService: WebSocketService,
    @ApplicationContext private val context: Context,
    private val registrationPrefs: RegistrationPreferences,
    private val gson: Gson
) : ViewModel() {

    private val _deviceState = MutableStateFlow<DeviceState>(DeviceState.Initial)
    val deviceState: StateFlow<DeviceState> = _deviceState

    init {
        connectAndObserveMessages()
    }

    private fun connectAndObserveMessages() {
        viewModelScope.launch {
            try {
                webSocketService.connectWebSocket()
                observeMessages()
            } catch (e: Exception) {
                Log.e("DeviceViewModel", "Failed to connect WebSocket: ${e.localizedMessage}")
                _deviceState.value = DeviceState.Error("Failed to connect WebSocket")
            }
        }
    }

    private fun observeMessages() {
        viewModelScope.launch {
            webSocketService.observeMessages().collect { message ->
                Log.d("DeviceViewModel", "Message received: $message")
                val response = gson.fromJson(message, Map::class.java)
                when (response["type"]) {
                    "success" -> {
                        Log.d("DeviceViewModel", "Device registered successfully")
                        _deviceState.value = DeviceState.Registered(Device(
                            deviceId = "a",
                            registrationKey = "a",
                            model = "a",
                            osVersion = "a",
                        ))
                    }
                    "error" -> {
                        Log.d("DeviceViewModel", "Error: ${response["message"]}")
                        _deviceState.value = DeviceState.Error(response["message"] as String)
                    }
                }
            }
        }
    }

    fun registerDevice(registrationKey: String) {
        viewModelScope.launch {
            _deviceState.value = DeviceState.Loading

            try {
                val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                val model = Build.MODEL
                val osVersion = Build.VERSION.RELEASE

                val device = Device(
                    deviceId = deviceId,
                    registrationKey = registrationKey,
                    model = model,
                    osVersion = osVersion
                )

                webSocketService.sendRegistration(device)

                try {
                    Log.i("RegistrationVM", "Creating registration data")
                    registrationPrefs.saveRegistrationDetails(deviceId) // <<< Clears SharedPreferences

                    Log.i("RegistrationVM", "Starting WebSocket heartbeat...")
                    webSocketService.startHeartbeat(deviceId)// <<< Stops WebSocket pings

                    Log.i("DeregistrationVM", "Disconnecting WebSocket...")
                     // <<< Disconnects WebSocket

                } catch (cleanupException: Exception) {
                    // Log error during cleanup but don't fail the overall success state
                    Log.e("DeregistrationVM", "Error during post-registration settings", cleanupException)
                }

            } catch (e: Exception) {
                _deviceState.value = DeviceState.Error("Error: ${e.localizedMessage}")
            }
        }
    }
}
sealed class DeviceState {
    object Initial : DeviceState()
    object Loading : DeviceState()
    data class Registered(val device: Device) : DeviceState()
    data class Error(val message: String) : DeviceState()
}