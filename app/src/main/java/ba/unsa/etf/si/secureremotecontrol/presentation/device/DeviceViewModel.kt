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

@HiltViewModel
class DeviceViewModel @Inject constructor(
    private val webSocketService: WebSocketService,
    @ApplicationContext private val context: Context,
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
                            name = "a",
                            registrationKey = "a",
                            model = "a",
                            osVersion = "a",
                            networkType = "a",
                            ipAddress = "a",
                            deregistrationKey = "a"
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

    fun registerDevice(name: String, registrationKey: String, deregistrationKey: String) {
        viewModelScope.launch {
            _deviceState.value = DeviceState.Loading

            try {
                val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                val model = Build.MODEL
                val osVersion = Build.VERSION.RELEASE
                val networkType = getNetworkType()
                val ipAddress = getIpAddress()

                val device = Device(
                    deviceId = deviceId,
                    name = name,
                    registrationKey = registrationKey,
                    model = model,
                    osVersion = osVersion,
                    networkType = networkType,
                    ipAddress = ipAddress,
                    deregistrationKey = deregistrationKey
                )

                webSocketService.sendRegistration(device)
            } catch (e: Exception) {
                _deviceState.value = DeviceState.Error("Error: ${e.localizedMessage}")
            }
        }
    }

    private fun getNetworkType(): String {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return ""
        val networkCapabilities = connectivityManager.getNetworkCapabilities(network) ?: return ""
        return when {
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobileData"
            else -> ""
        }
    }

    private fun getIpAddress(): String {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipAddress = wifiManager.connectionInfo.ipAddress
        return String.format(
            Locale.getDefault(),
            "%d.%d.%d.%d",
            (ipAddress and 0xff),
            (ipAddress shr 8 and 0xff),
            (ipAddress shr 16 and 0xff),
            (ipAddress shr 24 and 0xff)
        )
    }
}
sealed class DeviceState {
    object Initial : DeviceState()
    object Loading : DeviceState()
    data class Registered(val device: Device) : DeviceState()
    data class Error(val message: String) : DeviceState()
}