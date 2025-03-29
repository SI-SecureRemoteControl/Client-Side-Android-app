package ba.unsa.etf.si.secureremotecontrol.data.repository

import ba.unsa.etf.si.secureremotecontrol.data.models.Device
import ba.unsa.etf.si.secureremotecontrol.data.models.DeviceStatus
import kotlinx.coroutines.flow.Flow

interface DeviceRepository {
    suspend fun registerDevice(device: Device): Result<Device>
    suspend fun updateDeviceStatus(deviceId: String, status: DeviceStatus): Result<Device>
    suspend fun getDevice(deviceId: String): Result<Device>
    fun observeDeviceStatus(deviceId: String): Flow<DeviceStatus>
    suspend fun getAllDevices(): Result<List<Device>>
} 