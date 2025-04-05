package ba.unsa.etf.si.secureremotecontrol.presentation.device

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ba.unsa.etf.si.secureremotecontrol.data.models.Device
import ba.unsa.etf.si.secureremotecontrol.domain.usecase.device.RegisterDeviceUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import retrofit2.Response
import javax.inject.Inject
import android.provider.Settings

@HiltViewModel
class DeviceViewModel @Inject constructor(
    private val registerDeviceUseCase: RegisterDeviceUseCase,
    @ApplicationContext private val context: Context // Dodajemo Context za generisanje Android ID
) : ViewModel() {

    private val _deviceState = MutableStateFlow<DeviceState>(DeviceState.Initial)
    val deviceState: StateFlow<DeviceState> = _deviceState

    fun registerDevice(name: String, model: String, osVersion: String) {
        viewModelScope.launch {
            _deviceState.value = DeviceState.Loading

            try {
                // Generisanje unique ID-a pomoću Android ID
                val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

                // Kreiranje objekta Device
                val device = Device(
                    id = deviceId,
                    name = name,
                    model = model,
                    osVersion = osVersion
                )

                // Pozivamo UseCase za registraciju uređaja
                val response: Response<Void> = registerDeviceUseCase(device)
                if (response.isSuccessful) {
                    _deviceState.value = DeviceState.Registered(device)
                } else {
                    _deviceState.value = DeviceState.Error("Registration failed: ${response.message()}")
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