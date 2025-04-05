package ba.unsa.etf.si.secureremotecontrol.data.api

import ba.unsa.etf.si.secureremotecontrol.data.models.Device
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
interface ApiService {
    @POST("devices/register") // Ovo je API endpoint za registraciju uređaja
    suspend fun registerDevice(@Body device: Device): Response<Void> // Odgovara samo status kodom
}