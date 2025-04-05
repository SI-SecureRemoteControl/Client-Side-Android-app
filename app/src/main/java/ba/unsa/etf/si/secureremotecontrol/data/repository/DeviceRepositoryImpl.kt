package ba.unsa.etf.si.secureremotecontrol.data.repository

import ba.unsa.etf.si.secureremotecontrol.data.api.ApiService
import ba.unsa.etf.si.secureremotecontrol.data.models.Device
import ba.unsa.etf.si.secureremotecontrol.data.models.DeviceStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceRepositoryImpl @Inject constructor(
    private val apiService: ApiService
) : DeviceRepository {
    override suspend fun registerDevice(device: Device): Response<Void> {
        return apiService.registerDevice(device)
    }

    override suspend fun updateDeviceStatus(deviceId: String, status: DeviceStatus): Result<Device> {
        // TODO: Implement actual status update logic
        return Result.success(Device(deviceId, "Test Device", "Test Model", "Android", status))
    }

    override suspend fun getDevice(deviceId: String): Result<Device> {
        // TODO: Implement actual device fetching logic
        return Result.success(Device(deviceId, "Test Device", "Test Model", "Android", DeviceStatus.OFFLINE))
    }

    override fun observeDeviceStatus(deviceId: String): Flow<DeviceStatus> = flow {
        // TODO: Implement actual status observation logic
        emit(DeviceStatus.OFFLINE)
    }

    override suspend fun getAllDevices(): Result<List<Device>> {
        // TODO: Implement actual device list fetching logic
        return Result.success(emptyList())
    }
} 