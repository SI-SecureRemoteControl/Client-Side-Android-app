package ba.unsa.etf.si.secureremotecontrol.data.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Device(
    val id: String,
    val name: String,
    val model: String,
    val osVersion: String,
    val status: DeviceStatus = DeviceStatus.OFFLINE
) : Parcelable

enum class DeviceStatus {
    ONLINE,
    OFFLINE,
    IN_SESSION
} 