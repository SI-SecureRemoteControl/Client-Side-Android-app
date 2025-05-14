package ba.unsa.etf.si.secureremotecontrol.data.network

import dagger.Provides
import retrofit2.Response // Koristimo Response<T> za bolju obradu odgovora
import retrofit2.http.Body
import retrofit2.http.POST


interface ApiService {

    @POST("devices/deregister") // New endpoint for device deregistration
    suspend fun deregisterDevice(
        @Body requestBody: DeregisterRequest
    ): Response<DeregisterResponse>

    @POST("removeSessions")
    suspend fun removeSession(@Body requestBody: Map<String, String>): Response<Map<String, Any>>
}