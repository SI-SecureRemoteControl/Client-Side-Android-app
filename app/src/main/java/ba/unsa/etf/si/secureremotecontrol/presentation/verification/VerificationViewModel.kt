package ba.unsa.etf.si.secureremotecontrol.presentation.verification

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ba.unsa.etf.si.secureremotecontrol.data.network.DeregisterRequest
import ba.unsa.etf.si.secureremotecontrol.data.network.DeregisterResponse // Importujemo ispravan Response model
import ba.unsa.etf.si.secureremotecontrol.data.network.RetrofitClient
import com.google.gson.Gson // Importujemo Gson za parsiranje errorBody
import kotlinx.coroutines.launch
import retrofit2.HttpException // Za hvatanje HTTP grešaka
import java.io.IOException // Za hvatanje mrežnih/IO grešaka

class VerificationViewModel : ViewModel() {

    // --- Stanje za Verifikaciju ---
    var code by mutableStateOf("")
        private set

    var verificationServerMessage by mutableStateOf<String?>(null)
        private set

    var isVerificationLoading by mutableStateOf(false)
        private set

    var isVerificationSuccessful by mutableStateOf<Boolean?>(null)
        private set

    // --- Stanje za Deregistraciju ---
    var deregistrationKey by mutableStateOf("")
        private set

    var deregistrationServerMessage by mutableStateOf<String?>(null)
        private set

    var isDeregistrationLoading by mutableStateOf(false)
        private set

    var isDeregistrationSuccessful by mutableStateOf<Boolean?>(null)
        private set

    // Gson instanca za parsiranje error body-ja
    private val gson = Gson()

    // --- Funkcije za Verifikaciju ---
    fun updateCode(newCode: String) {
        code = newCode
    }


    // --- Funkcije za Deregistraciju ---
    fun updateDeregistrationKey(newKey: String) {
        deregistrationKey = newKey
    }

    fun deregisterDevice(deviceId: String) {
        if (isDeregistrationLoading) return // Ne pokreći ako već traje
        if (deregistrationKey.isBlank()) {
            deregistrationServerMessage = "Molimo unesite ključ za deregistraciju."
            isDeregistrationSuccessful = false
            return
        }

        // Resetovanje stanja prije poziva
        isDeregistrationLoading = true
        deregistrationServerMessage = null
        isDeregistrationSuccessful = null

        viewModelScope.launch {
            try {
                // Kreiranje request objekta sa ispravnim podacima
                val request = DeregisterRequest(deviceId = deviceId, deregistrationKey = deregistrationKey)
                // Poziv API metode koristeći Retrofit instancu
                val response = RetrofitClient.instance.deregisterDevice(request)

                if (response.isSuccessful) {
                    // Server vratio uspješan status kod (2xx)
                    val body = response.body() // Dobijanje DeregisterResponse objekta
                    isDeregistrationSuccessful = body?.message != null // Uspjeh ako postoji poruka
                    deregistrationServerMessage = body?.message ?: "Uređaj uspješno deregistriran (nema poruke)."
                    println("Deregistracija uspješna: ${deregistrationServerMessage}")

                } else {
                    // Server vratio grešku (4xx, 5xx)
                    isDeregistrationSuccessful = false
                    val errorBody = response.errorBody()?.string() // Čitanje tijela greške kao String
                    val errorResponse = try {
                        // Pokušaj parsiranja tijela greške u naš DeregisterResponse model da izvučemo 'error' polje
                        gson.fromJson(errorBody, DeregisterResponse::class.java)
                    } catch (e: Exception) {
                        // Deserializacija nije uspjela
                        println("Greška pri parsiranju error body-ja: ${e.message}")
                        null
                    }
                    // Postavi poruku greške iz parsiranog objekta, ili generičku poruku ako parsiranje nije uspjelo
                    deregistrationServerMessage = errorResponse?.error ?: "Greška ${response.code()}: Deregistracija neuspješna."
                    println("Deregistracija neuspješna: Kod ${response.code()}, Poruka: ${deregistrationServerMessage}, Tijelo greške: $errorBody")
                }

            } catch (e: IOException) {
                // Greška u konekciji, timeout, prekinuta konekcija...
                isDeregistrationSuccessful = false
                deregistrationServerMessage = "Mrežna greška prilikom deregistracije. Provjerite internet konekciju."
                println("IOException tokom deregistracije: ${e.message}")
            } catch (e: HttpException) {
                // Greška u HTTP protokolu koja nije uhvaćena kao !isSuccessful (rijetko sa Response<T>)
                isDeregistrationSuccessful = false
                deregistrationServerMessage = "HTTP Greška ${e.code()}: Deregistracija neuspješna."
                println("HttpException tokom deregistracije: ${e.message}")
            } catch (e: Exception) {
                // Sve ostale greške (npr., greška u parsiranju JSON-a ako je uspješan odgovor neispravan)
                isDeregistrationSuccessful = false
                deregistrationServerMessage = "Neočekivana greška prilikom deregistracije."
                println("Neočekivana greška tokom deregistracije: ${e.message}")
                // e.printStackTrace() // Korisno za debug, ukloniti ili zamijeniti loggerom za production
            } finally {
                // Osiguraj da se loading indikator ugasi bez obzira na ishod
                isDeregistrationLoading = false
            }
        }
    }
}