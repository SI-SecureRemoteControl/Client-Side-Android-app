package ba.unsa.etf.si.secureremotecontrol.data.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Device(
    val deviceId: String,
    val registrationKey: String,
    val status: DeviceStatus = DeviceStatus.OFFLINE,
    val model: String,
    val osVersion: String
) : Parcelable

enum class DeviceStatus {
    ONLINE,
    OFFLINE,
    IN_SESSION
} 