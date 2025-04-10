package ba.unsa.etf.si.secureremotecontrol.data.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit // KTX extension for simpler editing

// Define constants for keys
private const val PREFS_NAME = "AppRegistrationPrefs"
private const val KEY_IS_REGISTERED = "is_registered"
private const val KEY_DEVICE_ID = "device_id"
// Add other keys if needed (e.g., KEY_REGISTRATION_KEY)

class RegistrationPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isRegistered: Boolean
        get() = prefs.getBoolean(KEY_IS_REGISTERED, false) // Default to false
        set(value) = prefs.edit { putBoolean(KEY_IS_REGISTERED, value) }

    var deviceId: String?
        get() = prefs.getString(KEY_DEVICE_ID, null) // Default to null
        set(value) = prefs.edit { putString(KEY_DEVICE_ID, value) }

    // Function to save registration details together
    fun saveRegistrationDetails(deviceId: String /*, registrationKey: String? = null */) {
        prefs.edit {
            putBoolean(KEY_IS_REGISTERED, true)
            putString(KEY_DEVICE_ID, deviceId)
            // putString(KEY_REGISTRATION_KEY, registrationKey) // If you need to store this too
            apply() // Or commit() if immediate write is critical
        }
    }

    // Function to clear registration details
    fun clearRegistration() {
        prefs.edit {
            remove(KEY_IS_REGISTERED) // or putBoolean(KEY_IS_REGISTERED, false)
            remove(KEY_DEVICE_ID)
            // remove(KEY_REGISTRATION_KEY)
            apply()
        }
    }
}