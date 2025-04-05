package ba.unsa.etf.si.secureremotecontrol.data.network

import ba.unsa.etf.si.secureremotecontrol.data.network.VerificationRequest
import ba.unsa.etf.si.secureremotecontrol.data.network.VerificationResponse
import retrofit2.Response // Koristimo Response<T> za bolju obradu odgovora
import retrofit2.http.Body
import retrofit2.http.POST

interface ApiService {
    @POST("verify") // Putanja definisana u server.js
    suspend fun verifyCode(
        @Body requestBody: VerificationRequest
    ): Response<VerificationResponse> // Koristi suspend za Coroutines
}