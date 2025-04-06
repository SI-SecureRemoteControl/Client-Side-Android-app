package ba.unsa.etf.si.secureremotecontrol.service

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okio.ByteString
import org.json.JSONObject // Koristimo org.json, može i druga biblioteka (Gson, Moshi)
import java.util.concurrent.TimeUnit

class MyWebSocketClient {

    private var webSocket: WebSocket? = null
    private val client: OkHttpClient
    private val TAG = "WebSocketClient"
    private val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null
    // --- PROMIJENJEN INTERVAL ---
    // Slanje češće od HEARTBEAT_TIMEOUT (60s) i malo češće od HEARTBEAT_CHECK_INTERVAL (30s) radi sigurnosti
    private val HEARTBEAT_SEND_INTERVAL_MS = 25000L // 25 sekundi

    private val MAX_RETRY_ATTEMPTS = 5
    private val RETRY_DELAY_MS = 5000L // 5s

    private var retryCount = 0

    // Listener interfejs ostaje isti
    interface WebSocketListenerCallback {
        fun onWebSocketOpen()
        fun onWebSocketMessage(text: String) // Primljeni tekst (može biti JSON)
        fun onWebSocketClosing(code: Int, reason: String)
        fun onWebSocketFailure(t: Throwable, response: Response?)
    }

    var listenerCallback: WebSocketListenerCallback? = null

    init {
        // OkHttpClient konfiguracija bez automatskog pingInterval-a
        client = OkHttpClient.Builder()
            // .pingInterval(...) // Nije potrebno za naš aplikacijski heartbeat
            .connectTimeout(10, TimeUnit.SECONDS) // Dobro je imati timeout-e
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private fun retryConnection(url: String, deviceId: String) {
        if (retryCount < MAX_RETRY_ATTEMPTS) {
            retryCount++
            Log.d(TAG, "Pokušaj ponovnog povezivanja #$retryCount za $deviceId nakon ${RETRY_DELAY_MS}ms")
            // Čekaj specificirano vrijeme prije ponovnog pokušaja
            clientScope.launch {
                delay(RETRY_DELAY_MS)
                connect(url, deviceId) // Ponovo pozovi connect
            }
        } else {
            Log.e(TAG, "Dostignut maksimalan broj pokušaja ($MAX_RETRY_ATTEMPTS). Povezivanje neuspješno za $deviceId.")
            listenerCallback?.onWebSocketFailure(Throwable("Max retries reached for $deviceId"), null)
            retryCount = 0 // Resetuj brojač za buduće pokušaje ako bude potrebno
        }
    }

    fun connect(url: String, deviceId: String) {
        if (webSocket != null && retryCount == 0) { // Provjeri i retryCount da izbjegneš duple poruke ako već pokušava
            Log.w(TAG, "Već povezan ili u procesu povezivanja/pokušaja za $deviceId.")
            return
        }
        Log.d(TAG, "Pokušaj povezivanja na: $url za uređaj: $deviceId")
        val request = Request.Builder().url(url).build()
        // Kreiraj novu WebSocket konekciju sa SocketListener-om
        webSocket = client.newWebSocket(request, SocketListener(deviceId, url))
    }

    // Generička funkcija za slanje JSON poruka
    private fun sendJsonMessage(jsonObject: JSONObject): Boolean {
        val message = jsonObject.toString()
        return webSocket?.send(message) ?: run {
            Log.e(TAG, "Ne mogu poslati poruku, WebSocket nije povezan: $message")
            false
        }
    }

    // --- PROMIJENJENA FUNKCIJA ---
    // Funkcija za slanje aplikacijskog heartbeat-a (sada kao "status")
    private fun sendStatusHeartbeatMessage(deviceId: String) {
        val statusMsg = JSONObject()
        statusMsg.put("type", "status") // ISPRAVAN TIP PORUKE
        statusMsg.put("deviceId", deviceId)
        statusMsg.put("status", "active") // Eksplicitno slanje statusa

        val sent = sendJsonMessage(statusMsg)
        if (sent) {
            Log.d(TAG, "Poslan status/heartbeat za uređaj: $deviceId")
        } else {
            Log.w(TAG, "Neuspješno slanje statusa/heartbeat-a za uređaj: $deviceId (WebSocket nije spreman?)")
        }
    }

    // --- NOVA FUNKCIJA ---
    // Funkcija za slanje inicijalne registracijske poruke
    private fun sendRegisterMessage(deviceId: String) {
        val registerMsg = JSONObject()
        registerMsg.put("type", "register")
        registerMsg.put("deviceId", deviceId)

        val sent = sendJsonMessage(registerMsg)
        if (sent) {
            Log.i(TAG, "Poslana registracijska poruka za uređaj: $deviceId")
        } else {
            Log.e(TAG, "Neuspješno slanje registracijske poruke za uređaj: $deviceId")
            // Ovdje bi možda trebalo razmotriti zatvaranje konekcije ili ponovni pokušaj registracije
        }
    }


    fun disconnect(code: Int, reason: String) {
        heartbeatJob?.cancel() // Zaustavi slanje heartbeat-a
        heartbeatJob = null
        webSocket?.close(code, reason)
        webSocket = null // Eksplicitno postavi na null nakon zatvaranja
        Log.d(TAG, "Zatraženo diskonektovanje: Kod=$code, Razlog=$reason")
    }

    fun shutdown() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        webSocket?.cancel() // Koristi cancel() za trenutno oslobađanje resursa
        webSocket = null
        clientScope.cancel() // Otkaži sve korutine u ovom scope-u
        // Nije preporučljivo gasiti cijeli executorService ako ga koristi i ostatak aplikacije
        // client.dispatcher.executorService.shutdown()
        Log.i(TAG, "WebSocket klijent ugašen.")
    }


    private inner class SocketListener(
        private val deviceId: String,
        private val url: String // Čuvamo URL za ponovne pokušaje
    ) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WebSocket konekcija otvorena za uređaj: $deviceId")
            this@MyWebSocketClient.webSocket = webSocket // Ažuriraj referencu
            retryCount = 0 // Resetuj brojač pokušaja nakon uspješnog otvaranja

            // --- PROMIJENJEN REDOSLIJED I LOGIKA ---
            // 1. PRVO POŠALJI REGISTRACIJSKU PORUKU
            sendRegisterMessage(deviceId)

            // 2. ONDA POKRENI PERIODIČNO SLANJE HEARTBEAT-A ("status")
            heartbeatJob?.cancel() // Otkaži stari posao ako postoji
            heartbeatJob = clientScope.launch {
                // Možda mali delay prije prvog heartbeat-a nakon registracije
                delay(500)
                while (isActive) {
                    sendStatusHeartbeatMessage(deviceId) // Šalji status/heartbeat
                    delay(HEARTBEAT_SEND_INTERVAL_MS) // Koristi novi interval
                }
            }
            // Obavijesti listener da je konekcija otvorena (nakon inicijalnih koraka)
            listenerCallback?.onWebSocketOpen()
        }

        // onMessage ostaje uglavnom isti, obrađuje poruke primljene OD SERVERA
        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "Primljena tekstualna poruka: $text")
            listenerCallback?.onWebSocketMessage(text) // Proslijedi sirovu poruku listeneru

            // Opcionalno: Osnovno parsiranje za logiranje ili specifične akcije unutar klijenta
            try {
                val data = JSONObject(text)
                val type = data.optString("type", "unknown") // Koristi optString za sigurnost

                when (type) {
                    "signal" -> {
                        val from = data.optString("from")
                        val payload = data.optJSONObject("payload")
                        Log.d(TAG, "Primljen signal od $from")
                        // Dalja obrada signala se vjerovatno dešava u listenerCallback-u
                    }
                    "status" -> {
                        // Server ne bi trebao slati 'status' poruke nazad klijentu po trenutnoj logici
                        Log.w(TAG, "Primljena 'status' poruka od servera? Neočekivano.")
                    }
                    "disconnect" -> {
                        val reason = data.optString("reason", "nepoznat")
                        Log.w(TAG, "Server je zatražio diskonekciju: $reason")
                        // Možda pokrenuti logiku čišćenja ili obavijestiti korisnika
                    }
                    // Ovdje možete dodati obradu drugih tipova poruka koje server može slati
                    else -> {
                        Log.w(TAG, "Nepoznat tip poruke primljen od servera: $type")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Greška pri parsiranju primljene poruke: $text", e)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            Log.d(TAG, "Primljena binarna poruka: ${bytes.hex()}")
            // Ovdje obraditi binarne poruke ako je potrebno
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.w(TAG, "WebSocket se zatvara: Kod=$code, Razlog=$reason")
            heartbeatJob?.cancel() // Zaustavi slanje heartbeat-a
            heartbeatJob = null
            // Ne treba zvati webSocket.close() ovdje, jer je već u procesu zatvaranja
            this@MyWebSocketClient.webSocket = null // Očisti referencu
            listenerCallback?.onWebSocketClosing(code, reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // Provjeri je li scope još aktivan prije pokretanja retry-a
            if (!clientScope.isActive) {
                Log.e(TAG,"WebSocket greška, ali scope nije aktivan. Ne pokušavam ponovo. Greška: ${t.message}")
                return
            }

            Log.e(TAG, "WebSocket greška konekcije: ${t.message}", t)
            heartbeatJob?.cancel() // Zaustavi slanje heartbeat-a
            heartbeatJob = null
            this@MyWebSocketClient.webSocket = null // Očisti referencu
            listenerCallback?.onWebSocketFailure(t, response)

            // Pokreni logiku ponovnog pokušaja povezivanja
            retryConnection(url, deviceId)
        }
    }
}