package ba.unsa.etf.si.secureremotecontrol.data.network

import com.google.gson.annotations.SerializedName

// Podaci koje šaljemo serveru
data class VerificationRequest(
    @SerializedName("code") // Naziv mora odgovarati JSON ključu u server.js
    val code: String,
    @SerializedName("deviceId")
    val deviceId: String
)

// Podaci koje primamo od servera
data class VerificationResponse(
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("message")
    val message: String
)