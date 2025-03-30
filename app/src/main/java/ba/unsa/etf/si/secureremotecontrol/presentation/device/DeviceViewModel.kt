package ba.unsa.etf.si.secureremotecontrol.presentation.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ba.unsa.etf.si.secureremotecontrol.data.models.Device
import ba.unsa.etf.si.secureremotecontrol.domain.usecase.device.RegisterDeviceUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DeviceViewModel @Inject constructor(
    private val registerDeviceUseCase: RegisterDeviceUseCase
) : ViewModel() {

    private val _deviceState = MutableStateFlow<DeviceState>(DeviceState.Initial)
    val deviceState: StateFlow<DeviceState> = _deviceState

    fun registerDevice(device: Device) {
        viewModelScope.launch {
            _deviceState.value = DeviceState.Loading
            registerDeviceUseCase(device)
                .onSuccess { _deviceState.value = DeviceState.Registered(it) }
                .onFailure { _deviceState.value = DeviceState.Error(it.message ?: "Unknown error") }
        }
    }
}

sealed class DeviceState {
    object Initial : DeviceState()
    object Loading : DeviceState()
    data class Registered(val device: Device) : DeviceState()
    data class Error(val message: String) : DeviceState()
} 