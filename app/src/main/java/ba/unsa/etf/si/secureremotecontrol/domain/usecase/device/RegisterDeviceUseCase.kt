package ba.unsa.etf.si.secureremotecontrol.domain.usecase.device

import ba.unsa.etf.si.secureremotecontrol.data.models.Device
import ba.unsa.etf.si.secureremotecontrol.data.repository.DeviceRepository
import javax.inject.Inject

class RegisterDeviceUseCase @Inject constructor(
    private val deviceRepository: DeviceRepository
) {
    suspend operator fun invoke(device: Device): Result<Device> {
        return deviceRepository.registerDevice(device)
    }
} 