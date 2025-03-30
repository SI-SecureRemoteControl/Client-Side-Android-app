package ba.unsa.etf.si.secureremotecontrol.data.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.Date

@Parcelize
data class Session(
    val id: String,
    val deviceId: String,
    val startTime: Date,
    val endTime: Date? = null,
    val status: SessionStatus
) : Parcelable

enum class SessionStatus {
    PENDING,
    ACTIVE,
    ENDED,
    ERROR
} 