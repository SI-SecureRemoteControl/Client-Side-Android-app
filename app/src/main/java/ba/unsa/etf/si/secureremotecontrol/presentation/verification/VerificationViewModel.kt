package ba.unsa.etf.si.secureremotecontrol.presentation.verification

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ba.unsa.etf.si.secureremotecontrol.data.network.VerificationRequest
import ba.unsa.etf.si.secureremotecontrol.data.network.RetrofitClient
import kotlinx.coroutines.launch

class VerificationViewModel : ViewModel() {
    var code by mutableStateOf("")
        private set

    var serverMessage by mutableStateOf<String?>(null)
        private set

    var isLoading by mutableStateOf(false)
        private set

    var isVerificationSuccessful by mutableStateOf<Boolean?>(null)
        private set

    fun updateCode(newCode: String) {
        code = newCode
    }

    fun sendVerification(deviceId: String) {
        if (isLoading) return
        if (code.isBlank()) {
            serverMessage = "Molimo unesite kod."
            isVerificationSuccessful = false
            return
        }

        isLoading = true
        serverMessage = null
        isVerificationSuccessful = null

        viewModelScope.launch {
            try {
                val request = VerificationRequest(code = code, deviceId = deviceId)
                val response = RetrofitClient.instance.verifyCode(request)

                if (response.isSuccessful) {
                    val body = response.body()
                    isVerificationSuccessful = body?.success == true
                    serverMessage = if (body?.success == true)
                        body.message ?: "Uspješno ste se odjavili!"
                    else
                        "Vaš kod nije validan!"
                } else {
                    serverMessage = "Vaš kod nije validan!"
                    isVerificationSuccessful = false
                }

            } catch (e: Exception) {
                serverMessage = "Greška u komunikaciji: ${e.message}"
                isVerificationSuccessful = false
                e.printStackTrace()
            } finally {
                isLoading = false
            }
        }
    }
}
