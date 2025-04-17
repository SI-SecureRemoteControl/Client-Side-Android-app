// Add necessary imports
package ba.unsa.etf.si.secureremotecontrol.presentation.verification

import android.util.Log // Import Log for better debugging
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ba.unsa.etf.si.secureremotecontrol.data.network.DeregisterRequest
import ba.unsa.etf.si.secureremotecontrol.data.network.DeregisterResponse
import ba.unsa.etf.si.secureremotecontrol.data.network.RetrofitClient // Keep for now, but ideally inject ApiService
import ba.unsa.etf.si.secureremotecontrol.data.util.RegistrationPreferences // Import your SharedPreferences wrapper
import ba.unsa.etf.si.secureremotecontrol.data.api.WebSocketService // Import WebSocket service interface
import ba.unsa.etf.si.secureremotecontrol.data.datastore.TokenDataStore
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel // Import for Hilt ViewModel
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject // Import for Hilt injection

@HiltViewModel // *** Mark ViewModel for Hilt injection ***
class VerificationViewModel @Inject constructor( // *** Inject dependencies via constructor ***
    private val registrationPrefs: RegistrationPreferences,
    private val webSocketService: WebSocketService,
    private val tokenDataStore: TokenDataStore
    // Consider injecting your Retrofit API service interface instead of using RetrofitClient.instance directly in the future
) : ViewModel() {

    // --- Stanje za Verifikaciju --- (Existing code)
    var code by mutableStateOf("")
        private set
    var verificationServerMessage by mutableStateOf<String?>(null)
        private set
    var isVerificationLoading by mutableStateOf(false)
        private set
    var isVerificationSuccessful by mutableStateOf<Boolean?>(null)
        private set

    // --- Stanje za Deregistraciju --- (Existing code)
    var deregistrationKey by mutableStateOf("")
        private set
    var deregistrationServerMessage by mutableStateOf<String?>(null)
        private set
    var isDeregistrationLoading by mutableStateOf(false)
        private set
    var isDeregistrationSuccessful by mutableStateOf<Boolean?>(null)
        private set

    // Gson instance (Existing code)
    private val gson = Gson()

    // --- Funkcije za Verifikaciju --- (Existing code)
    fun updateCode(newCode: String) {
        code = newCode
    }

    // --- Funkcije za Deregistraciju --- (Existing code)
    fun updateDeregistrationKey(newKey: String) {
        deregistrationKey = newKey
    }

    fun deregisterDevice(deviceId: String) {
        if (isDeregistrationLoading) return
        if (deregistrationKey.isBlank()) {
            deregistrationServerMessage = "Molimo unesite ključ za deregistraciju."
            isDeregistrationSuccessful = false
            return
        }

        isDeregistrationLoading = true
        deregistrationServerMessage = null
        isDeregistrationSuccessful = null

        viewModelScope.launch {
            try {
                val request = DeregisterRequest(deviceId = deviceId, deregistrationKey = deregistrationKey)
                // Ideally, inject ApiService: val response = apiService.deregisterDevice(request)
                val response = RetrofitClient.instance.deregisterDevice(request)

                if (response.isSuccessful) {
                    val body = response.body()
                    // Check if the server *really* confirmed success (body might be null or contain status)
                    val wasServerSuccess = body != null // Adjust this condition based on your actual API response if needed

                    if (wasServerSuccess) {
                        isDeregistrationSuccessful = true
                        deregistrationServerMessage = body?.message ?: "Uređaj uspješno deregistriran."
                        Log.i("DeregistrationVM", "Server confirmed successful deregistration: ${deregistrationServerMessage}")

                        // --- *** START: CORE CHANGE - Cleanup on Success *** ---
                        try {
                            Log.i("DeregistrationVM", "Clearing registration preferences...")
                            registrationPrefs.clearRegistration() // <<< Clears SharedPreferences

                            Log.i("DeregistrationVM", "Stopping WebSocket heartbeat...")
                            webSocketService.stopHeartbeat() // <<< Stops WebSocket pings

                            Log.i("DeregistrationVM", "Disconnecting WebSocket...") // <<< Disconnects WebSocket

                            tokenDataStore.clearToken()
                        } catch (cleanupException: Exception) {
                            // Log error during cleanup but don't fail the overall success state
                            Log.e("DeregistrationVM", "Error during post-deregistration cleanup", cleanupException)
                        }
                        // --- *** END: CORE CHANGE *** ---

                    } else {
                        // Handle cases where server returns 2xx but indicates failure in the body
                        isDeregistrationSuccessful = false
                        deregistrationServerMessage = body?.error ?: "Greška: Server je vratio uspjeh ali tijelo odgovora ukazuje na problem."
                        Log.w("DeregistrationVM", "Deregistration failed according to response body: ${deregistrationServerMessage}")
                    }

                } else {
                    // Server returned error (4xx, 5xx)
                    isDeregistrationSuccessful = false
                    val errorBody = response.errorBody()?.string()
                    val errorResponse = try {
                        gson.fromJson(errorBody, DeregisterResponse::class.java)
                    } catch (e: Exception) {
                        Log.e("DeregistrationVM", "Failed to parse error body: $errorBody", e)
                        null
                    }
                    deregistrationServerMessage = errorResponse?.error ?: "Greška ${response.code()}: Deregistracija neuspješna."
                    Log.e("DeregistrationVM", "Deregistration failed: Code ${response.code()}, Message: ${deregistrationServerMessage}, RawBody: $errorBody")
                }

            } catch (e: IOException) {
                isDeregistrationSuccessful = false
                deregistrationServerMessage = "Mrežna greška prilikom deregistracije. Provjerite internet konekciju."
                Log.e("DeregistrationVM", "IOException during deregistration", e)
            } catch (e: HttpException) {
                isDeregistrationSuccessful = false
                deregistrationServerMessage = "HTTP Greška ${e.code()}: Deregistracija neuspješna."
                Log.e("DeregistrationVM", "HttpException during deregistration", e)
            } catch (e: Exception) {
                isDeregistrationSuccessful = false
                deregistrationServerMessage = "Neočekivana greška prilikom deregistracije."
                Log.e("DeregistrationVM", "Unexpected error during deregistration", e)
                // e.printStackTrace() // Consider using logging framework
            } finally {
                isDeregistrationLoading = false
            }
        }
    }
}