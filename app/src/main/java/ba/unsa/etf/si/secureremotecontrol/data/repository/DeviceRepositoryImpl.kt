package ba.unsa.etf.si.secureremotecontrol.data.repository

import android.util.Log
import ba.unsa.etf.si.secureremotecontrol.data.network.ApiService
import ba.unsa.etf.si.secureremotecontrol.data.api.WebSocketService
import ba.unsa.etf.si.secureremotecontrol.data.models.Device
import ba.unsa.etf.si.secureremotecontrol.data.models.DeviceStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceRepositoryImpl @Inject constructor(
    private val apiService: ApiService,
    private val webSocketService: WebSocketService
) : DeviceRepository {
    override suspend fun registerDevice(device: Device): Response<Void> {
        return try {
            webSocketService.connectWebSocket()
            webSocketService.sendRegistration(device)
            Response.success(null)
        } catch (e: Exception) {
            Response.error(500, okhttp3.ResponseBody.create(null, "Registration failed"))
        } // Simulate a successful response
    }

    override suspend fun updateDeviceStatus(deviceId: String, status: DeviceStatus): Result<Device> {
        // TODO: Implement actual status update logic
        return Result.success(Device(deviceId, "Test Device", DeviceStatus.ONLINE, "Test model", "Test OS"))
    }

    override suspend fun getDevice(deviceId: String): Result<Device> {
        // TODO: Implement actual device fetching logic
        return Result.success(Device(deviceId, "Test Device", DeviceStatus.ONLINE, "Test model", "Test OS"))
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