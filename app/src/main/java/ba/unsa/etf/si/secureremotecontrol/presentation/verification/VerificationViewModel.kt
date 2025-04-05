package ba.unsa.etf.si.secureremotecontrol.presentation.verification


import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import ba.unsa.etf.si.secureremotecontrol.data.network.VerificationRequest
import ba.unsa.etf.si.secureremotecontrol.data.network.VerificationResponse
import ba.unsa.etf.si.secureremotecontrol.data.network.RetrofitClient
import kotlinx.coroutines.launch
import java.lang.Exception // Import Exception


// ViewModel za upravljanje stanjem i logikom (bolja praksa)
class VerificationViewModel : ViewModel() {
    // Stanje za uneseni kod
    var code by mutableStateOf("")
        private set // Samo ViewModel može direktno mijenjati

    // Stanje za prikaz poruke od servera ili greške
    var serverMessage by mutableStateOf<String?>(null)
        private set

    // Stanje za praćenje da li je zahtjev u toku (za prikaz loading indikatora)
    var isLoading by mutableStateOf(false)
        private set

    // Funkcija za ažuriranje koda iz TextField-a
    fun updateCode(newCode: String) {
        code = newCode
    }

    // Funkcija za slanje verifikacije na server
    fun sendVerification(deviceId: String) {
        // Ako je zahtjev već u toku, ne radi ništa
        if (isLoading) return
        // Ako kod nije unesen, prikaži poruku
        if (code.isBlank()) {
            serverMessage = "Molimo unesite kod."
            return
        }

        isLoading = true // Počni učitavanje
        serverMessage = null // Resetuj prethodnu poruku

        viewModelScope.launch { // Koristi CoroutineScope iz ViewModel-a
            try {
                val request = VerificationRequest(code = code, deviceId = deviceId)
                val response = RetrofitClient.instance.verifyCode(request)

                if (response.isSuccessful) {
                    // Server je vratio uspješan odgovor (2xx status)
                    serverMessage = response.body()?.message ?: "Nepoznat uspješan odgovor."
                    // Ovdje možete dodati logiku ako je success == true, npr. stvarno zatvoriti dio aplikacije
                    // if(response.body()?.success == true) { /* navigate away or disable UI */ }

                } else {
                    // Server je vratio grešku (4xx, 5xx status)
                    // Pokušavamo pročitati JSON poruku greške ako postoji
                    val errorBody = response.errorBody()?.string()
                    // Ovdje možete parsirati errorBody ako server šalje JSON i za greške
                    // na strukturiran način, ali za sada prikazujemo generičku poruku
                    serverMessage = "Greška ${response.code()}: ${response.message()}. ${errorBody ?: ""}"
                }

            } catch (e: Exception) {
                // Greška u komunikaciji (npr. nema interneta, server nedostupan)
                serverMessage = "Greška u komunikaciji: ${e.message}"
                e.printStackTrace() // Logiraj stack trace za debug
            } finally {
                isLoading = false // Završi učitavanje bez obzira na ishod
            }
        }
    }
}