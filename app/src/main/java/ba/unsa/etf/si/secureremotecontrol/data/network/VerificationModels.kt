package ba.unsa.etf.si.secureremotecontrol.data.network

import com.google.gson.annotations.SerializedName


data class DeregisterRequest(
    @SerializedName("deviceId") // Matches the JSON key
    val deviceId: String,
    @SerializedName("deregistrationKey") // Matches the JSON key
    val deregistrationKey: String
)

data class DeregisterResponse(
    @SerializedName("message") // Matches the JSON key for success message
    val message: String? = null,
    @SerializedName("error") // Matches the JSON key for error message
    val error: String? = null
)