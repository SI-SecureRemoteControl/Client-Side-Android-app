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
            deregistrationServerMessage = "Please enter the deregistration key."
            isDeregistrationSuccessful = false
            return
        }

        isDeregistrationLoading = true
        deregistrationServerMessage = null
        isDeregistrationSuccessful = null

        viewModelScope.launch {
            try {
                // Send deregistration request via WebSocket
                webSocketService.sendDeregistrationRequest(deviceId, deregistrationKey)

                // Assume success if no exception is thrown
                isDeregistrationSuccessful = true
                deregistrationServerMessage = "Device successfully deregistered."
                Log.i("DeregistrationVM", "Deregistration request sent successfully.")

                // Perform cleanup on success
                try {
                    Log.i("DeregistrationVM", "Clearing registration preferences...")
                    registrationPrefs.clearRegistration()

                    Log.i("DeregistrationVM", "Stopping WebSocket heartbeat...")
                    webSocketService.stopHeartbeat()

                    Log.i("DeregistrationVM", "Disconnecting WebSocket...")
                    webSocketService.disconnect()

                    tokenDataStore.clearToken()
                } catch (cleanupException: Exception) {
                    Log.e("DeregistrationVM", "Error during post-deregistration cleanup", cleanupException)
                }
            } catch (e: Exception) {
                isDeregistrationSuccessful = false
                deregistrationServerMessage = "Failed to deregister device. Please try again."
                Log.e("DeregistrationVM", "Error during deregistration", e)
            } finally {
                isDeregistrationLoading = false
            }
        }
    }
}